# MuHan Intelligence 账号系统与云同步方案（Cloudflare 版）

> 版本：v2.0（2026-10-17）
> 变更：v1.0 选用 Supabase；因作者自有域名且已托管 Cloudflare，改为 **Cloudflare Workers + D1** 自建轻量后端，国内可达性更可控。

---

## 1. 目标与约束

### 1.1 目标

1. 邮箱 + 密码注册/登录，支持退出。
2. 对话与消息跨设备同步，本地离线可用。
3. 月度 Token 额度上云，多设备共享。
4. 零服务器运维（Serverless）。

### 1.2 约束

| 约束 | 说明 |
|---|---|
| 无服务器 | 使用 Cloudflare Workers（Serverless） |
| 未满 18 | Cloudflare 仅需邮箱注册，无需实名/信用卡 |
| 有自有域名 | 绑定 `api.<你的域名>`，规避 `workers.dev` 国内不稳定 |
| 一期不做邮箱验证 | 注册即用；找回密码二期接入 Resend/MailChannels |
| 普通加密 | TLS 传输，服务端可读（不做端到端加密） |

---

## 2. 架构

```
Android App
  ├─ AuthRepository  ──┐
  ├─ SyncRepository  ──┼── HTTPS ──→ api.<你的域名>（Cloudflare Worker）
  └─ QuotaRepository ──┘                  │
                                          ├─ D1（SQLite：用户/会话/消息/额度）
                                          └─ R2（二期：附件）
```

- Worker 无状态，所有状态在 D1。
- 密码用 PBKDF2-SHA256（10 万次迭代（Workers 上限））加盐哈希。
- 登录签发 JWT（access 15 分钟）+ 随机 refresh token（30 天，存 D1 可撤销）。

---

## 3. 数据模型（D1）

见 `server/schema.sql`，核心表：

| 表 | 用途 | 关键字段 |
|---|---|---|
| `users` | 账号 | `id`, `email` (unique), `password_hash`, `password_salt` |
| `refresh_tokens` | 会话续期 | `token_hash`, `user_id`, `expires_at` |
| `conversations` | 会话 | `id`, `user_id`, `mode`, `title`, `updated_at`, `deleted` |
| `messages` | 消息 | `id`, `user_id`, `conversation_id`, `content`, `agent_trace`, `updated_at`, `deleted` |
| `usage_quota` | 额度 | `user_id`, `period` (YYYY-MM), `used`, `carry` |

说明：

- 主键都是客户端生成的 UUID 字符串，离线创建不冲突。
- `updated_at` 为 epoch 毫秒，作为同步游标。
- 删除用软删除（`deleted = 1`），避免多端删除冲突。
- 所有查询在 Worker 内强制 `user_id = 当前登录用户`，D1 无 RLS。

---

## 4. API 设计

Base URL：`https://api.<你的域名>`

### 4.1 认证

| 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|
| POST | `/auth/register` | `{email, password}` | `{user, accessToken, refreshToken, expiresIn}` |
| POST | `/auth/login` | `{email, password}` | 同上 |
| POST | `/auth/refresh` | `{refreshToken}` | 同上（刷新令牌轮换） |
| POST | `/auth/logout` | `{refreshToken}` | `{ok: true}` |
| GET | `/auth/me` | Header: Bearer | `{user}` |

鉴权头：`Authorization: Bearer <accessToken>`。

### 4.2 同步

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/sync?since=<ms>&limit=500` | 返回 `updated_at > since` 的会话与消息（升序） |
| POST | `/sync` | 批量 upsert：`{conversations: [], messages: []}` |
| DELETE | `/sync/conversations/:id` | 软删除会话（连带消息） |

响应：

```json
{
  "cursor": 1792200000123,
  "hasMore": true,
  "conversations": [ ... ],
  "messages": [ ... ]
}
```

### 4.3 额度

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/quota` | 当前周期 `{period, used, carry, limit, available}` |
| POST | `/quota/consume` | `{tokens: 1234}` → 原子累加并返回最新额度 |

---

## 5. 客户端改造点

### 5.1 Token 存储

复用 `SecureKeyStore`（EncryptedSharedPreferences）：

- `cf_access` / `cf_refresh` / `cf_expires_at` / `cf_email`
- 请求前若过期（< 60s），先 `/auth/refresh`。

### 5.2 同步状态

本地 Room 增加字段（版本 4 → 5）：

```sql
ALTER TABLE conversations ADD COLUMN sync_state TEXT NOT NULL DEFAULT 'PENDING';
ALTER TABLE messages ADD COLUMN sync_state TEXT NOT NULL DEFAULT 'PENDING';
```

- `PENDING`：本地有改动待推送
- `SYNCED`：已与云端一致
- `DELETED`：本地已删待推送

### 5.3 同步流程

```
启动/回前台 → GET /sync?since=cursor → upsert 到 Room → 更新 cursor
本地写入   → sync_state=PENDING → POST /sync（100 条/批）→ SYNCED
失败       → 指数退避重试（1s/2s/4s），网络恢复时重跑队列
```

冲突：消息 append-only；会话标题/置顶按 `updated_at` 大者胜（LWW）。

### 5.4 游客与登录合并

- 未登录：完全本地（现状）。
- 登录：先把本地 `PENDING` 全量推送，再按游标拉取。
- 退出：清 token，保留本地数据；换账号时提示是否合并。

### 5.5 额度

- 已登录：`POST /quota/consume`，以服务端返回为准；发送前 `GET /quota` 校验。
- 未登录：沿用本地伪装日志记账（2.0.2 已有）。

---

### 5.6 实现状态（3.1.0-dev）

- **强制登录**：未登录只显示登录/注册页，不能进入主界面；token 存 EncryptedSharedPreferences，过期自动刷新，刷新失败回到登录页。
- **旧数据迁移**：Room v4 → v5，历史会话/消息默认 `sync_state = PENDING`；首次登录成功后整体分批 push（每批 100 会话 / 200 消息，循环直到清空），再增量 pull。
- **每次操作即同步**：发送、生成完成、编辑、重发、标题、置顶、删除、清空等写操作后 300ms 合并触发一次 push + pull；流式 token 不触发。
- **额度全量上云**：删除本地额度文件逻辑，Chat / Agent / 设置页全部走 `/quota` 与 `/quota/consume`。
- **设置上云**：主题、动态取色、语音开关、音色、体验计划随账号同步；登录时以云端为准（新账号则上传本地值）。
- **退出登录**：尽力同步一次后清除本地缓存，云端数据保留。

---

## 6. 部署（本地 wrangler）

### 6.1 一次性准备

```bash
# Node.js 18+
npm i -g wrangler
wrangler login          # 浏览器授权 Cloudflare 账号
```

### 6.2 创建数据库并建表

```bash
cd server
wrangler d1 create muhan-db
# 把输出的 database_id 填入 wrangler.toml

wrangler d1 execute muhan-db --file=./schema.sql --remote
```

### 6.3 配置密钥

```bash
# 生成一个 32 字节随机串
openssl rand -hex 32

wrangler secret put JWT_SECRET   # 粘贴上面的随机串
```

### 6.4 部署并绑定域名

```bash
wrangler deploy
```

在 `wrangler.toml` 中已配置：

```toml
routes = [
  { pattern = "api.example.com", custom_domain = true }
]
```

把 `api.example.com` 换成你的真实子域，DNS 会自动创建、SSL 自动签发。

### 6.5 验证

```bash
curl https://api.<你的域名>/health
# {"ok":true}

curl -X POST https://api.<你的域名>/auth/register \
  -H 'content-type: application/json' \
  -d '{"email":"you@example.com","password":"password123"}'
```

### 6.6 当前部署记录（2026-10-17）

| 项 | 值 |
|---|---|
| API 地址 | `https://api.novasync.cn` |
| Worker | `muhan-api` |
| D1 | `muhan-db`（`5ab18bd7-9002-48aa-999c-f7a2c0d5bf82`） |
| 健康检查 | 已通过 |
| 端到端 | 注册 / 登录 / 刷新 / 同步 / 额度 全部通过 |
| 注意 | Cloudflare 会拦截 `Python-urllib` 等 UA（错误 1010）；App 使用 OkHttp 不受影响 |

---

## 7. 成本与限制

| 项 | 免费额度 | 说明 |
|---|---|---|
| Workers 请求 | 10 万/天 | 个人项目足够 |
| D1 存储 | 5 GB | 纯文本可存百万级消息 |
| D1 读行数 | 500 万/天 | 增量同步远低于此 |
| R2 存储 | 10 GB（二期） | 附件 |
| 自定义域名 | 免费 | 需域名托管在 Cloudflare |

注意：Cloudflare 在国内的可达性优于 `workers.dev`，但仍可能受运营商 QoS 影响；自定义域名 + 代理已是最优解。

---

## 8. 安全

| 项 | 措施 |
|---|---|
| 密码 | PBKDF2-SHA256，10 万次迭代（Workers 上限），16 字节随机盐 |
| 传输 | HTTPS（Cloudflare 自动证书） |
| Access Token | JWT HS256，15 分钟 |
| Refresh Token | 32 字节随机，仅存 SHA-256 哈希，30 天，轮换 |
| 注入 | 全部使用 D1 参数绑定 |
| 枚举 | 登录失败统一返回「邮箱或密码错误」 |
| 限流 | 建议在 Cloudflare 控制台为 `/auth/*` 配置 Rate Limiting 规则 |
| 密钥 | `JWT_SECRET` 只存 Worker Secret，不进仓库 |
| 日志 | 不记录密码、token、消息正文 |

---

## 9. 实施计划

| 阶段 | 内容 | 预估 |
|---|---|---|
| P0 | Worker + D1 + schema 部署，`/health` 通 | 0.5 天 |
| P1 | 注册/登录/刷新/退出 + 客户端账号页 | 1.5 天 |
| P2 | Room 增加 sync_state + 迁移 | 0.5 天 |
| P3 | 同步引擎（pull/push/重试） | 2 天 |
| P4 | 额度上云 + 双端校验 | 1 天 |
| P5 | 设置页账号入口、同步状态展示 | 0.5 天 |

---

## 10. 风险与备选

| 风险 | 应对 |
|---|---|
| 国内访问波动 | 自定义域名 + Cloudflare 代理；必要时套一层国内可达的反代 |
| D1 单区域写入延迟 | 数据量小可忽略；热点写可加 KV 缓存 |
| 免费额度超限 | 升级 Workers Paid（$5/月） |
| 忘记密码 | 一期不支持；二期接 Resend 免费额度做重置邮件 |
| LLM Key 被提取 | 二期用 Worker 代理 LLM 请求，Key 只存 Secret |

---

## 11. 决策记录

| 日期 | 决策 | 结论 |
|---|---|---|
| 2026-10-17 | 后端 | 由 Supabase 改为 Cloudflare Workers + D1 |
| 2026-10-17 | 原因 | 自有域名已托管 Cloudflare，国内可达性更可控，无实名要求 |
| 2026-10-17 | 登录 | 邮箱 + 密码（一期不做邮箱验证） |
| 2026-10-17 | 加密 | 普通加密（TLS，服务端可读） |
| 2026-10-17 | 部署 | 本地 wrangler |
