/**
 * MuHan Intelligence 账号与同步后端
 * Cloudflare Workers + D1
 *
 * 端点：
 *   GET  /health
 *   POST /auth/register    {email, password}
 *   POST /auth/login       {email, password}
 *   POST /auth/refresh     {refreshToken}
 *   POST /auth/logout      {refreshToken}
 *   GET  /auth/me
 *   GET  /sync?since=<ms>&limit=500
 *   POST /sync             {conversations: [], messages: []}
 *   DELETE /sync/conversations/:id
 *   GET  /quota
 *   POST /quota/consume    {tokens}
 */

export interface Env {
  DB: D1Database;
  JWT_SECRET: string;
}

const ACCESS_TTL_SECONDS = 15 * 60;
const REFRESH_TTL_SECONDS = 30 * 24 * 3600;
const MONTHLY_LIMIT = 100_000;
const MAX_BATCH = 100;
const enc = new TextEncoder();

class HttpError extends Error {
  status: number;
  constructor(message: string, status = 400) {
    super(message);
    this.status = status;
  }
}

// ------------------------------------------------------------------ 基础工具

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

function corsHeaders(): Record<string, string> {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-headers": "authorization, content-type",
    "access-control-allow-methods": "GET, POST, DELETE, OPTIONS",
    "access-control-max-age": "86400",
  };
}

function withCors(res: Response): Response {
  for (const [k, v] of Object.entries(corsHeaders())) res.headers.set(k, v);
  return res;
}

async function readJson(req: Request): Promise<any> {
  try {
    return await req.json();
  } catch {
    throw new HttpError("请求体不是合法 JSON");
  }
}

function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function hexToBytes(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

function b64url(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function b64urlDecode(text: string): Uint8Array {
  const p = text.replace(/-/g, "+").replace(/_/g, "/");
  const s = atob(p + "=".repeat((4 - (p.length % 4)) % 4));
  return Uint8Array.from(s, (c) => c.charCodeAt(0));
}

async function sha256Hex(input: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", enc.encode(input));
  return bytesToHex(new Uint8Array(buf));
}

/** PBKDF2-HMAC-SHA256，21 万次迭代。 */
async function pbkdf2(password: string, saltHex: string): Promise<string> {
  const keyMaterial = await crypto.subtle.importKey(
    "raw",
    enc.encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt: hexToBytes(saltHex), iterations: 100_000, hash: "SHA-256" },
    keyMaterial,
    256,
  );
  return bytesToHex(new Uint8Array(bits));
}

// ------------------------------------------------------------------ JWT（HS256）

async function signJwt(payload: Record<string, unknown>, secret: string): Promise<string> {
  const header = { alg: "HS256", typ: "JWT" };
  const body =
    b64url(enc.encode(JSON.stringify(header))) + "." + b64url(enc.encode(JSON.stringify(payload)));
  const key = await crypto.subtle.importKey(
    "raw",
    enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, enc.encode(body));
  return body + "." + b64url(new Uint8Array(sig));
}

async function verifyJwt(token: string, secret: string): Promise<any> {
  const parts = token.split(".");
  if (parts.length !== 3) throw new HttpError("无效的登录凭证", 401);
  const [h, p, s] = parts;
  const key = await crypto.subtle.importKey(
    "raw",
    enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["verify"],
  );
  const ok = await crypto.subtle.verify("HMAC", key, b64urlDecode(s), enc.encode(`${h}.${p}`));
  if (!ok) throw new HttpError("无效的登录凭证", 401);
  const payload = JSON.parse(new TextDecoder().decode(b64urlDecode(p)));
  if (typeof payload.exp !== "number" || payload.exp * 1000 < Date.now()) {
    throw new HttpError("登录已过期", 401);
  }
  return payload;
}

async function requireUser(req: Request, env: Env): Promise<string> {
  const header = req.headers.get("authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (!token) throw new HttpError("未登录", 401);
  const payload = await verifyJwt(token, env.JWT_SECRET);
  if (!payload.sub) throw new HttpError("未登录", 401);
  return String(payload.sub);
}

async function issueTokens(env: Env, userId: string) {
  const now = Math.floor(Date.now() / 1000);
  const accessToken = await signJwt(
    { sub: userId, iat: now, exp: now + ACCESS_TTL_SECONDS },
    env.JWT_SECRET,
  );
  const refreshToken = b64url(crypto.getRandomValues(new Uint8Array(32)));
  const tokenHash = await sha256Hex(refreshToken);
  await env.DB.prepare(
    "INSERT INTO refresh_tokens(token_hash, user_id, expires_at, created_at) VALUES (?,?,?,?)",
  )
    .bind(tokenHash, userId, (now + REFRESH_TTL_SECONDS) * 1000, Date.now())
    .run();
  return { accessToken, refreshToken, expiresIn: ACCESS_TTL_SECONDS };
}

// ------------------------------------------------------------------ 额度

function currentPeriod(): string {
  // Asia/Shanghai（UTC+8）
  return new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 7);
}

async function ensureQuota(env: Env, userId: string) {
  const period = currentPeriod();
  let row = await env.DB.prepare(
    "SELECT period, used, carry FROM usage_quota WHERE user_id=? AND period=?",
  )
    .bind(userId, period)
    .first<any>();

  if (!row) {
    const prev = await env.DB.prepare(
      "SELECT period, used, carry FROM usage_quota WHERE user_id=? AND period<? ORDER BY period DESC LIMIT 1",
    )
      .bind(userId, period)
      .first<any>();
    // 上月超出额度的部分结转为本月欠账。
    const carry = prev
      ? Math.max(0, Number(prev.used) - Math.max(0, MONTHLY_LIMIT - Number(prev.carry)))
      : 0;
    await env.DB.prepare(
      "INSERT OR IGNORE INTO usage_quota(user_id, period, used, carry, updated_at) VALUES (?,?,0,?,?)",
    )
      .bind(userId, period, carry, Date.now())
      .run();
    row = { period, used: 0, carry };
  }
  return row as { period: string; used: number; carry: number };
}

// ------------------------------------------------------------------ 路由

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";

    try {
      // ---- 健康检查
      if (path === "/health" && request.method === "GET") {
        return withCors(json({ ok: true, time: Date.now() }));
      }

      // ---- 注册
      if (path === "/auth/register" && request.method === "POST") {
        const body = await readJson(request);
        const email = String(body.email || "").trim().toLowerCase();
        const password = String(body.password || "");
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new HttpError("邮箱格式不正确");
        if (password.length < 8) throw new HttpError("密码至少 8 位");

        const exists = await env.DB.prepare("SELECT id FROM users WHERE email=?").bind(email).first();
        if (exists) throw new HttpError("该邮箱已注册", 409);

        const salt = bytesToHex(crypto.getRandomValues(new Uint8Array(16)));
        const hash = await pbkdf2(password, salt);
        const id = crypto.randomUUID();
        await env.DB.prepare(
          "INSERT INTO users(id, email, password_hash, password_salt, created_at) VALUES (?,?,?,?,?)",
        )
          .bind(id, email, hash, salt, Date.now())
          .run();

        const tokens = await issueTokens(env, id);
        return withCors(json({ user: { id, email }, ...tokens }, 201));
      }

      // ---- 登录
      if (path === "/auth/login" && request.method === "POST") {
        const body = await readJson(request);
        const email = String(body.email || "").trim().toLowerCase();
        const password = String(body.password || "");
        if (!email || !password) throw new HttpError("请输入邮箱与密码");

        const user = await env.DB.prepare(
          "SELECT id, email, password_hash, password_salt FROM users WHERE email=?",
        )
          .bind(email)
          .first<any>();
        const fail = new HttpError("邮箱或密码错误", 401);
        if (!user) throw fail;
        const hash = await pbkdf2(password, user.password_salt);
        if (hash !== user.password_hash) throw fail;

        const tokens = await issueTokens(env, user.id);
        return withCors(json({ user: { id: user.id, email: user.email }, ...tokens }));
      }

      // ---- 刷新令牌（轮换）
      if (path === "/auth/refresh" && request.method === "POST") {
        const body = await readJson(request);
        const refreshToken = String(body.refreshToken || "");
        if (!refreshToken) throw new HttpError("缺少 refreshToken");
        const tokenHash = await sha256Hex(refreshToken);
        const row = await env.DB.prepare(
          "SELECT user_id, expires_at FROM refresh_tokens WHERE token_hash=?",
        )
          .bind(tokenHash)
          .first<any>();
        if (!row || Number(row.expires_at) < Date.now()) {
          throw new HttpError("登录已过期，请重新登录", 401);
        }
        await env.DB.prepare("DELETE FROM refresh_tokens WHERE token_hash=?").bind(tokenHash).run();
        const tokens = await issueTokens(env, row.user_id);
        return withCors(json(tokens));
      }

      // ---- 退出
      if (path === "/auth/logout" && request.method === "POST") {
        const body = await readJson(request);
        const refreshToken = String(body.refreshToken || "");
        if (refreshToken) {
          const tokenHash = await sha256Hex(refreshToken);
          await env.DB.prepare("DELETE FROM refresh_tokens WHERE token_hash=?").bind(tokenHash).run();
        }
        return withCors(json({ ok: true }));
      }

      // ---- 当前用户
      if (path === "/auth/me" && request.method === "GET") {
        const userId = await requireUser(request, env);
        const user = await env.DB.prepare("SELECT id, email FROM users WHERE id=?")
          .bind(userId)
          .first<any>();
        if (!user) throw new HttpError("账号不存在", 404);
        return withCors(json({ user }));
      }

      // ---- 增量拉取
      if (path === "/sync" && request.method === "GET") {
        const userId = await requireUser(request, env);
        const since = Math.max(0, Number(url.searchParams.get("since") || "0") || 0);
        const limit = Math.min(Math.max(Number(url.searchParams.get("limit") || "500") || 500, 1), 500);

        const convs = await env.DB.prepare(
          `SELECT id, title, mode, model_name AS modelName, pinned,
                  message_count AS messageCount, created_at AS createdAt,
                  updated_at AS updatedAt, deleted
             FROM conversations
            WHERE user_id=? AND updated_at>?
            ORDER BY updated_at ASC LIMIT ?`,
        )
          .bind(userId, since, limit)
          .all<any>();

        const msgs = await env.DB.prepare(
          `SELECT id, conversation_id AS conversationId, role, content,
                  reasoning_content AS reasoningContent, status,
                  model_name AS modelName, token_count AS tokenCount,
                  attachments, image_url AS imageUrl, agent_trace AS agentTrace,
                  created_at AS createdAt, updated_at AS updatedAt, deleted
             FROM messages
            WHERE user_id=? AND updated_at>?
            ORDER BY updated_at ASC LIMIT ?`,
        )
          .bind(userId, since, limit)
          .all<any>();

        let cursor = since;
        for (const r of convs.results || []) cursor = Math.max(cursor, Number(r.updatedAt) || 0);
        for (const r of msgs.results || []) cursor = Math.max(cursor, Number(r.updatedAt) || 0);

        return withCors(json({
          cursor,
          hasMore: (convs.results?.length || 0) === limit || (msgs.results?.length || 0) === limit,
          conversations: convs.results || [],
          messages: msgs.results || [],
        }));
      }

      // ---- 批量推送
      if (path === "/sync" && request.method === "POST") {
        const userId = await requireUser(request, env);
        const body = await readJson(request);
        const conversations = Array.isArray(body.conversations)
          ? body.conversations.slice(0, MAX_BATCH)
          : [];
        const messages = Array.isArray(body.messages) ? body.messages.slice(0, MAX_BATCH) : [];

        const stmts: D1PreparedStatement[] = [];

        for (const c of conversations) {
          if (!c?.id) continue;
          stmts.push(
            env.DB.prepare(
              `INSERT INTO conversations
                 (id, user_id, title, mode, model_name, pinned, message_count,
                  created_at, updated_at, deleted)
               VALUES (?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET
                 title=excluded.title, mode=excluded.mode, model_name=excluded.model_name,
                 pinned=excluded.pinned, message_count=excluded.message_count,
                 updated_at=excluded.updated_at, deleted=excluded.deleted
               WHERE conversations.user_id = excluded.user_id
                 AND excluded.updated_at >= conversations.updated_at`,
            ).bind(
              c.id,
              userId,
              c.title ?? "新对话",
              c.mode ?? "chat",
              c.modelName ?? null,
              c.pinned ? 1 : 0,
              Number(c.messageCount ?? 0),
              Number(c.createdAt ?? Date.now()),
              Number(c.updatedAt ?? Date.now()),
              c.deleted ? 1 : 0,
            ),
          );
        }

        for (const m of messages) {
          if (!m?.id || !m?.conversationId) continue;
          stmts.push(
            env.DB.prepare(
              `INSERT INTO messages
                 (id, user_id, conversation_id, role, content, reasoning_content, status,
                  model_name, token_count, attachments, image_url, agent_trace,
                  created_at, updated_at, deleted)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET
                 content=excluded.content, reasoning_content=excluded.reasoning_content,
                 status=excluded.status, model_name=excluded.model_name,
                 token_count=excluded.token_count, attachments=excluded.attachments,
                 image_url=excluded.image_url, agent_trace=excluded.agent_trace,
                 updated_at=excluded.updated_at, deleted=excluded.deleted
               WHERE messages.user_id = excluded.user_id
                 AND excluded.updated_at >= messages.updated_at`,
            ).bind(
              m.id,
              userId,
              m.conversationId,
              m.role ?? "user",
              m.content ?? "",
              m.reasoningContent ?? "",
              m.status ?? "COMPLETE",
              m.modelName ?? null,
              m.tokenCount ?? null,
              typeof m.attachments === "string" ? m.attachments : JSON.stringify(m.attachments ?? []),
              m.imageUrl ?? null,
              typeof m.agentTrace === "string" ? m.agentTrace : JSON.stringify(m.agentTrace ?? []),
              Number(m.createdAt ?? Date.now()),
              Number(m.updatedAt ?? Date.now()),
              m.deleted ? 1 : 0,
            ),
          );
        }

        if (stmts.length > 0) await env.DB.batch(stmts);
        return withCors(json({
          ok: true,
          conversations: conversations.length,
          messages: messages.length,
        }));
      }

      // ---- 删除会话（软删除，连带消息）
      if (path.startsWith("/sync/conversations/") && request.method === "DELETE") {
        const userId = await requireUser(request, env);
        const id = path.slice("/sync/conversations/".length);
        if (!id) throw new HttpError("缺少会话 id");
        const now = Date.now();
        await env.DB.batch([
          env.DB.prepare(
            "UPDATE conversations SET deleted=1, updated_at=? WHERE id=? AND user_id=?",
          ).bind(now, id, userId),
          env.DB.prepare(
            "UPDATE messages SET deleted=1, updated_at=? WHERE conversation_id=? AND user_id=?",
          ).bind(now, id, userId),
        ]);
        return withCors(json({ ok: true }));
      }

      // ---- 查询额度
      if (path === "/quota" && request.method === "GET") {
        const userId = await requireUser(request, env);
        const q = await ensureQuota(env, userId);
        return withCors(json({
          period: q.period,
          used: q.used,
          carry: q.carry,
          limit: MONTHLY_LIMIT,
          available: Math.max(0, MONTHLY_LIMIT - Number(q.carry) - Number(q.used)),
        }));
      }

      // ---- 扣减额度
      if (path === "/quota/consume" && request.method === "POST") {
        const userId = await requireUser(request, env);
        const body = await readJson(request);
        const tokens = Math.floor(Number(body.tokens || 0));
        if (!Number.isFinite(tokens) || tokens <= 0) throw new HttpError("tokens 必须为正整数");

        await ensureQuota(env, userId);
        const period = currentPeriod();
        const res = await env.DB.prepare(
          `UPDATE usage_quota SET used = used + ?, updated_at = ?
            WHERE user_id=? AND period=?
            RETURNING period, used, carry`,
        )
          .bind(tokens, Date.now(), userId, period)
          .all<any>();

        const row = (res.results || [])[0];
        if (!row) throw new HttpError("额度记录不存在", 500);
        return withCors(json({
          period: row.period,
          used: Number(row.used),
          carry: Number(row.carry),
          limit: MONTHLY_LIMIT,
          available: Math.max(0, MONTHLY_LIMIT - Number(row.carry) - Number(row.used)),
        }));
      }

      throw new HttpError("接口不存在", 404);
    } catch (e: any) {
      if (e instanceof HttpError) return withCors(json({ error: e.message }, e.status));
      console.error("unhandled", e);
      return withCors(json({ error: "服务器内部错误" }, 500));
    }
  },
};
