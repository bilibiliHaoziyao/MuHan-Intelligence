-- MuHan Intelligence 云端数据库（Cloudflare D1）
-- 执行：wrangler d1 execute muhan-db --file=./schema.sql --remote

PRAGMA foreign_keys = ON;

-- 用户
CREATE TABLE IF NOT EXISTS users (
  id            TEXT PRIMARY KEY,
  email         TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  password_salt TEXT NOT NULL,
  created_at    INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users(email);

-- 刷新令牌（只存哈希）
CREATE TABLE IF NOT EXISTS refresh_tokens (
  token_hash TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_refresh_user ON refresh_tokens(user_id);

-- 会话
CREATE TABLE IF NOT EXISTS conversations (
  id            TEXT PRIMARY KEY,
  user_id       TEXT NOT NULL,
  title         TEXT NOT NULL DEFAULT '新对话',
  mode          TEXT NOT NULL DEFAULT 'chat',
  model_name    TEXT,
  pinned        INTEGER NOT NULL DEFAULT 0,
  message_count INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  deleted       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_conv_user_updated ON conversations(user_id, updated_at);

-- 消息
CREATE TABLE IF NOT EXISTS messages (
  id                TEXT PRIMARY KEY,
  user_id           TEXT NOT NULL,
  conversation_id   TEXT NOT NULL,
  role              TEXT NOT NULL,
  content           TEXT NOT NULL DEFAULT '',
  reasoning_content TEXT NOT NULL DEFAULT '',
  status            TEXT NOT NULL DEFAULT 'COMPLETE',
  model_name        TEXT,
  token_count       INTEGER,
  attachments       TEXT NOT NULL DEFAULT '[]',
  image_url         TEXT,
  agent_trace       TEXT NOT NULL DEFAULT '[]',
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL,
  deleted           INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_msg_user_updated ON messages(user_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_msg_conv ON messages(conversation_id);

-- 月度额度
CREATE TABLE IF NOT EXISTS usage_quota (
  user_id    TEXT NOT NULL,
  period     TEXT NOT NULL,          -- YYYY-MM（Asia/Shanghai）
  used       INTEGER NOT NULL DEFAULT 0,
  carry      INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, period)
);
