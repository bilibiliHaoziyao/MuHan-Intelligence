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

import { sendMail } from "./smtp";

export interface Env {
  DB: D1Database;
  JWT_SECRET: string;
  /** 腾讯企业邮 SMTP（smtp.exmail.qq.com:465）。 */
  SMTP_HOST?: string;
  SMTP_PORT?: string;
  SMTP_USER?: string;
  SMTP_PASS?: string;
  SMTP_FROM?: string;
  /** 设为 "1" 时在发信失败响应里返回具体原因，仅用于部署排障。 */
  SMTP_DEBUG?: string;
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

// ------------------------------------------------------------------ 邮箱验证码

const CODE_TTL_MS = 10 * 60 * 1000; // 10 分钟有效
const CODE_RESEND_MS = 60 * 1000; // 同邮箱 60 秒一条
const CODE_HOURLY_LIMIT = 5; // 每小时最多 5 条
const CODE_DAILY_LIMIT = 10; // 每天最多 10 条
const CODE_MAX_ATTEMPTS = 5; // 校验失败 5 次作废

function normalizeEmail(raw: unknown): string {
  return String(raw || "").trim().toLowerCase();
}

function isEmail(email: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
}

function codeHash(email: string, code: string, secret: string): Promise<string> {
  // 绑定邮箱与密钥，避免库被读走后直接使用。
  return sha256Hex(`${email}:${code}:${secret}`);
}

function verificationMail(code: string): string {
  const digits = code.split("").join(" ");
  return `<!doctype html>
<html><body style="margin:0;padding:0;background:#f5f6fa;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,'PingFang SC','Microsoft YaHei',sans-serif;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f5f6fa;padding:32px 16px;">
    <tr><td align="center">
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:480px;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 6px 24px rgba(20,24,40,0.08);">
        <tr><td style="padding:28px 32px 8px 32px;">
          <div style="font-size:19px;font-weight:600;color:#151a2d;letter-spacing:0.2px;">MuHan Intelligence</div>
          <div style="font-size:12px;color:#8a90a6;margin-top:4px;">听见你，更懂你</div>
        </td></tr>
        <tr><td style="padding:20px 32px 0 32px;">
          <div style="font-size:15px;color:#2b3149;line-height:1.6;">你正在注册 MuHan Intelligence，本次验证码为：</div>
        </td></tr>
        <tr><td align="center" style="padding:22px 32px;">
          <div style="display:inline-block;padding:16px 26px;background:#f1f3ff;border-radius:12px;font-size:30px;font-weight:700;letter-spacing:4px;color:#4b5bd6;font-family:'SF Mono',Menlo,Consolas,monospace;">${digits}</div>
        </td></tr>
        <tr><td style="padding:0 32px 8px 32px;">
          <div style="font-size:13px;color:#6b7285;line-height:1.7;">验证码 10 分钟内有效，请勿转发给他人。<br>如果这不是你本人的操作，忽略此邮件即可。</div>
        </td></tr>
        <tr><td style="padding:22px 32px 26px 32px;border-top:1px solid #eef0f6;">
          <div style="font-size:11px;color:#9aa0b4;line-height:1.6;">此邮件由系统自动发送，请勿直接回复。<br>MuHan Intelligence · 慕寒实验室</div>
        </td></tr>
      </table>
    </td></tr>
  </table>
</body></html>`;
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

      // ---- 检查更新（无需登录，启动即调用）
      if (path === "/app/version" && request.method === "GET") {
        const latest = await env.DB.prepare(
          "SELECT version_code, version_name, download_url, released_at FROM app_versions ORDER BY version_code DESC LIMIT 1",
        ).first<any>();
        if (!latest) throw new HttpError("暂未发布版本信息", 404);

        const cfg = await env.DB.prepare(
          "SELECT value FROM app_config WHERE key='min_supported_code'",
        ).first<any>();
        const minCode = Number(cfg?.value || latest.version_code);
        const minRow = await env.DB.prepare(
          "SELECT version_code, version_name, download_url FROM app_versions WHERE version_code=?",
        )
          .bind(minCode)
          .first<any>();

        return withCors(
          json({
            latest: {
              code: latest.version_code,
              name: latest.version_name,
              url: latest.download_url,
            },
            minSupported: minRow
              ? {
                  code: minRow.version_code,
                  name: minRow.version_name,
                  url: minRow.download_url,
                }
              : {
                  code: latest.version_code,
                  name: latest.version_name,
                  url: latest.download_url,
                },
            serverTime: Date.now(),
          }),
        );
      }

      // ---- 发送注册验证码
      if (path === "/auth/send-code" && request.method === "POST") {
        const body = await readJson(request);
        const email = normalizeEmail(body.email);
        if (!isEmail(email)) throw new HttpError("邮箱格式不正确");

        const exists = await env.DB.prepare("SELECT id FROM users WHERE email=?")
          .bind(email)
          .first();
        if (exists) throw new HttpError("该邮箱已注册，请直接登录", 409);

        const now = Date.now();
        const recent = await env.DB.prepare(
          "SELECT created_at FROM email_codes WHERE email=? AND created_at > ? ORDER BY created_at DESC LIMIT 50",
        )
          .bind(email, now - 24 * 3600 * 1000)
          .all<any>();
        const rows = (recent.results || []) as Array<{ created_at: number }>;

        if (rows.length && now - rows[0].created_at < CODE_RESEND_MS) {
          const wait = Math.ceil((CODE_RESEND_MS - (now - rows[0].created_at)) / 1000);
          throw new HttpError(`发送过于频繁，请 ${wait} 秒后再试`, 429);
        }
        const hourly = rows.filter((r) => now - r.created_at < 3600 * 1000).length;
        if (hourly >= CODE_HOURLY_LIMIT) {
          throw new HttpError("该邮箱验证码发送次数过多，请 1 小时后再试", 429);
        }
        if (rows.length >= CODE_DAILY_LIMIT) {
          throw new HttpError("该邮箱今日发送次数已达上限，请明天再试", 429);
        }

        if (!env.SMTP_USER || !env.SMTP_PASS) {
          throw new HttpError("邮件服务尚未配置，请联系管理员", 503);
        }

        const code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1000000).padStart(6, "0");
        const id = crypto.randomUUID();
        const hash = await codeHash(email, code, env.JWT_SECRET);

        // 旧码立即作废，保证同一时刻只有一个有效码。
        await env.DB.prepare(
          "UPDATE email_codes SET consumed=1 WHERE email=? AND purpose='register' AND consumed=0",
        )
          .bind(email)
          .run();
        await env.DB.prepare(
          "INSERT INTO email_codes(id, email, code_hash, purpose, attempts, consumed, expires_at, created_at) VALUES (?,?,?,?,0,0,?,?)",
        )
          .bind(id, email, hash, "register", now + CODE_TTL_MS, now)
          .run();

        try {
          await sendMail(
            {
              host: env.SMTP_HOST || "smtp.exmail.qq.com",
              port: Number(env.SMTP_PORT || 465),
              user: env.SMTP_USER,
              pass: env.SMTP_PASS,
              from: env.SMTP_FROM || env.SMTP_USER,
              fromName: "MuHan Intelligence",
            },
            {
              to: email,
              subject: "MuHan Intelligence 注册验证码",
              html: verificationMail(code),
            },
          );
        } catch (e) {
          // 发信失败则删除这条码，避免"码已存在但用户没收到"。
          await env.DB.prepare("DELETE FROM email_codes WHERE id=?").bind(id).run();
          console.error("send-code failed", e);
          const detail =
            env.SMTP_DEBUG === "1"
              ? `：${e instanceof Error ? e.message : String(e)}`
              : "";
          throw new HttpError(`验证码邮件发送失败，请稍后重试${detail}`, 502);
        }

        return withCors(
          json({ ok: true, expiresIn: CODE_TTL_MS / 1000, resendAfter: CODE_RESEND_MS / 1000 }),
        );
      }

      // ---- 注册
      if (path === "/auth/register" && request.method === "POST") {
        const body = await readJson(request);
        const email = normalizeEmail(body.email);
        const password = String(body.password || "");
        const code = String(body.code || "").trim();
        if (!isEmail(email)) throw new HttpError("邮箱格式不正确");
        if (password.length < 8) throw new HttpError("密码至少 8 位");
        if (!/^\d{6}$/.test(code)) throw new HttpError("请输入 6 位邮箱验证码");

        const exists = await env.DB.prepare("SELECT id FROM users WHERE email=?").bind(email).first();
        if (exists) throw new HttpError("该邮箱已注册", 409);

        // 校验验证码（防机器人批量注册）。
        const record = await env.DB.prepare(
          "SELECT id, code_hash, attempts, expires_at FROM email_codes WHERE email=? AND purpose='register' AND consumed=0 ORDER BY created_at DESC LIMIT 1",
        )
          .bind(email)
          .first<any>();
        if (!record) throw new HttpError("请先获取邮箱验证码", 400);
        if (record.expires_at < Date.now()) throw new HttpError("验证码已过期，请重新获取", 400);
        if (record.attempts >= CODE_MAX_ATTEMPTS) {
          throw new HttpError("验证码错误次数过多，请重新获取", 429);
        }

        const expected = await codeHash(email, code, env.JWT_SECRET);
        if (expected !== record.code_hash) {
          await env.DB.prepare("UPDATE email_codes SET attempts = attempts + 1 WHERE id=?")
            .bind(record.id)
            .run();
          throw new HttpError("验证码不正确", 400);
        }
        await env.DB.prepare("UPDATE email_codes SET consumed = 1 WHERE id=?").bind(record.id).run();

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

      // ---- 读取用户偏好
      if (path === "/settings" && request.method === "GET") {
        const userId = await requireUser(request, env);
        const row = await env.DB.prepare(
          `SELECT theme_mode AS themeMode, dynamic_color AS dynamicColor,
                  tts_enabled AS ttsEnabled, tts_voice AS ttsVoice,
                  ux_improvement AS uxImprovement, updated_at AS updatedAt
             FROM user_settings WHERE user_id=?`,
        )
          .bind(userId)
          .first<any>();
        if (!row) {
          return withCors(json({
            themeMode: "SYSTEM",
            dynamicColor: true,
            ttsEnabled: true,
            ttsVoice: "冰糖",
            uxImprovement: true,
            updatedAt: 0,
          }));
        }
        return withCors(json({
          themeMode: row.themeMode,
          dynamicColor: !!row.dynamicColor,
          ttsEnabled: !!row.ttsEnabled,
          ttsVoice: row.ttsVoice,
          uxImprovement: !!row.uxImprovement,
          updatedAt: Number(row.updatedAt),
        }));
      }

      // ---- 写入用户偏好（LWW）
      if (path === "/settings" && request.method === "PUT") {
        const userId = await requireUser(request, env);
        const body = await readJson(request);
        const updatedAt = Number(body.updatedAt || Date.now());
        await env.DB.prepare(
          `INSERT INTO user_settings
             (user_id, theme_mode, dynamic_color, tts_enabled, tts_voice,
              ux_improvement, updated_at)
           VALUES (?,?,?,?,?,?,?)
           ON CONFLICT(user_id) DO UPDATE SET
             theme_mode=excluded.theme_mode, dynamic_color=excluded.dynamic_color,
             tts_enabled=excluded.tts_enabled, tts_voice=excluded.tts_voice,
             ux_improvement=excluded.ux_improvement, updated_at=excluded.updated_at
           WHERE excluded.updated_at >= user_settings.updated_at`,
        )
          .bind(
            userId,
            String(body.themeMode || "SYSTEM"),
            body.dynamicColor ? 1 : 0,
            body.ttsEnabled ? 1 : 0,
            String(body.ttsVoice || "冰糖"),
            body.uxImprovement ? 1 : 0,
            updatedAt,
          )
          .run();
        return withCors(json({ ok: true, updatedAt }));
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
