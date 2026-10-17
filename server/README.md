# MuHan API（Cloudflare Workers + D1）

账号、云同步与额度记账后端。部署后 App 通过 `https://api.<你的域名>` 访问。

## 前置

- Node.js 18+
- 域名已托管在 Cloudflare
- 已登录 Cloudflare 账号（免费套餐即可）

## 一、安装依赖

```bash
cd server
npm install
npx wrangler login
```

## 二、创建数据库

```bash
npx wrangler d1 create muhan-db
```

把输出中的 `database_id` 填进 `wrangler.toml` 的 `database_id`。

建表：

```bash
npx wrangler d1 execute muhan-db --file=./schema.sql --remote
```

## 三、配置密钥

```bash
# 生成 32 字节随机串
openssl rand -hex 32
# 或：node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"

npx wrangler secret put JWT_SECRET
# 粘贴上面的随机串，回车
```

## 四、绑定域名

编辑 `wrangler.toml`：

```toml
routes = [
  { pattern = "api.example.com", custom_domain = true }
]
```

把 `api.example.com` 换成你的真实子域。部署时 Cloudflare 会自动创建 DNS 记录并签发证书。

## 五、部署

```bash
npx wrangler deploy
```

## 六、验证

```bash
curl https://api.example.com/health
# {"ok":true,"time":...}

curl -X POST https://api.example.com/auth/register \
  -H 'content-type: application/json' \
  -d '{"email":"you@example.com","password":"password123"}'
# {"user":{"id":"...","email":"..."},"accessToken":"...","refreshToken":"...","expiresIn":900}
```

## 本地调试

```bash
npm run db:local      # 本地建表
npm run dev           # wrangler dev，默认 http://localhost:8787

curl http://localhost:8787/health
```

本地调试时把 `wrangler.toml` 的 `routes` 注释掉，或在 `wrangler dev` 中忽略。

## 接口速查

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 健康检查 |
| POST | `/auth/register` | 注册 `{email,password}` |
| POST | `/auth/login` | 登录 |
| POST | `/auth/refresh` | 刷新 `{refreshToken}` |
| POST | `/auth/logout` | 退出 |
| GET | `/auth/me` | 当前用户（Bearer） |
| GET | `/sync?since=<ms>` | 增量拉取 |
| POST | `/sync` | 批量推送 |
| DELETE | `/sync/conversations/:id` | 软删除会话 |
| GET | `/quota` | 查询额度 |
| POST | `/quota/consume` | 扣减 `{tokens}` |

## 注意事项

- `JWT_SECRET` 只存 Worker Secret，不要提交到仓库。
- 一期不做邮箱验证；如公开注册，建议在 Cloudflare 控制台给 `/auth/*` 配置 Rate Limiting。
- D1 免费额度：5 GB 存储、每天 500 万行读；Workers 免费 10 万请求/天。
- 修改 `schema.sql` 后需重新执行（`CREATE TABLE IF NOT EXISTS` 不会改已有表结构）。
