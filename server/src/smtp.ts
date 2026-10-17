/**
 * 极简 SMTP 客户端（Cloudflare Workers 出站 TCP + 隐式 TLS）。
 *
 * 仅覆盖发信所需的最小协议子集：EHLO → AUTH LOGIN → MAIL/RCPT → DATA → QUIT。
 * 正文统一用 base64 编码，避免 8bit 与 dot-stuffing 问题。
 */

import { connect } from "cloudflare:sockets";

const CRLF = "\r\n";
const encoder = new TextEncoder();
const decoder = new TextDecoder();

export interface MailOptions {
  to: string;
  subject: string;
  html: string;
}

export interface SmtpConfig {
  host: string;
  port: number;
  user: string;
  pass: string;
  /** 发信地址，默认与登录账号一致。 */
  from?: string;
  /** 发信人显示名。 */
  fromName?: string;
  /** 单步超时（毫秒）。 */
  timeoutMs?: number;
}

interface SmtpResponse {
  code: number;
  text: string;
}

function withTimeout<T>(promise: Promise<T>, ms: number, label: string): Promise<T> {
  return Promise.race([
    promise,
    new Promise<T>((_, reject) =>
      setTimeout(() => reject(new Error(`SMTP ${label} 超时（${ms}ms）`)), ms),
    ),
  ]);
}

class SmtpConnection {
  private readonly writer: WritableStreamDefaultWriter<Uint8Array>;
  private readonly reader: ReadableStreamDefaultReader<Uint8Array>;
  private buffer = "";

  private constructor(private readonly socket: Socket) {
    this.writer = socket.writable.getWriter();
    this.reader = socket.readable.getReader();
  }

  static async open(host: string, port: number, timeoutMs: number): Promise<SmtpConnection> {
    const socket = connect({ hostname: host, port }, { secureTransport: "on" });
    await withTimeout(socket.opened, timeoutMs, "连接");
    return new SmtpConnection(socket);
  }

  private async fill(): Promise<void> {
    const { value, done } = await withTimeout(this.reader.read(), 15000, "读取");
    if (done) throw new Error("SMTP 连接已被服务端关闭");
    if (value) this.buffer += decoder.decode(value, { stream: true });
  }

  private takeLine(): string | null {
    const index = this.buffer.indexOf(CRLF);
    if (index < 0) return null;
    const line = this.buffer.slice(0, index);
    this.buffer = this.buffer.slice(index + CRLF.length);
    return line;
  }

  /** 读取一条（可能多行的）响应。 */
  async response(timeoutMs: number): Promise<SmtpResponse> {
    const deadline = Date.now() + timeoutMs;
    const texts: string[] = [];
    let code = 0;

    for (;;) {
      let line = this.takeLine();
      while (line === null) {
        if (Date.now() > deadline) throw new Error("SMTP 响应超时");
        await this.fill();
        line = this.takeLine();
      }
      if (!line) continue;

      const match = /^(\d{3})([ -])(.*)$/.exec(line);
      if (!match) continue;

      code = Number(match[1]);
      texts.push(match[3]);
      if (match[2] === " ") break;
    }

    return { code, text: texts.join("\n") };
  }

  async command(line: string, expect: number[], timeoutMs: number): Promise<SmtpResponse> {
    await withTimeout(this.writer.write(encoder.encode(line + CRLF)), timeoutMs, "写入");
    const response = await this.response(timeoutMs);
    if (!expect.includes(response.code)) {
      throw new Error(`SMTP 命令失败 [${line.split(" ")[0]}] ${response.code} ${response.text}`);
    }
    return response;
  }

  async writeRaw(data: string, timeoutMs: number): Promise<void> {
    await withTimeout(this.writer.write(encoder.encode(data)), timeoutMs, "写入正文");
  }

  close(): void {
    try {
      this.reader.releaseLock();
    } catch {
      /* ignore */
    }
    try {
      this.writer.releaseLock();
    } catch {
      /* ignore */
    }
    try {
      this.socket.close();
    } catch {
      /* ignore */
    }
  }
}

function base64Utf8(text: string): string {
  const bytes = encoder.encode(text);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

/** RFC 2047 编码（用于中文主题/显示名）。 */
function encodeHeader(text: string): string {
  // 纯 ASCII 直接放行，避免无谓编码。
  if (/^[\x20-\x7E]*$/.test(text)) return text;
  return `=?UTF-8?B?${base64Utf8(text)}?=`;
}

/** 每 76 字符换行的 base64。 */
function wrapBase64(text: string): string {
  const encoded = base64Utf8(text);
  const lines: string[] = [];
  for (let i = 0; i < encoded.length; i += 76) {
    lines.push(encoded.slice(i, i + 76));
  }
  return lines.join(CRLF);
}

function rfc2822Date(date: Date): string {
  const days = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  const pad = (n: number) => String(n).padStart(2, "0");
  // 用 UTC 表示，避免依赖运行环境时区。
  return (
    `${days[date.getUTCDay()]}, ${pad(date.getUTCDate())} ${months[date.getUTCMonth()]} ` +
    `${date.getUTCFullYear()} ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}:` +
    `${pad(date.getUTCSeconds())} +0000`
  );
}

function buildMessage(config: SmtpConfig, options: MailOptions): string {
  const from = config.from || config.user;
  const domain = from.split("@")[1] || "localhost";
  const displayName = config.fromName ? `${encodeHeader(config.fromName)} ` : "";
  const messageId = `<${crypto.randomUUID()}@${domain}>`;

  const headers = [
    `From: ${displayName}<${from}>`,
    `To: <${options.to}>`,
    `Subject: ${encodeHeader(options.subject)}`,
    `Date: ${rfc2822Date(new Date())}`,
    `Message-ID: ${messageId}`,
    "MIME-Version: 1.0",
    'Content-Type: text/html; charset="UTF-8"',
    "Content-Transfer-Encoding: base64",
  ];

  return headers.join(CRLF) + CRLF + CRLF + wrapBase64(options.html) + CRLF;
}

/** 发送一封 HTML 邮件。 */
export async function sendMail(config: SmtpConfig, options: MailOptions): Promise<void> {
  const timeoutMs = config.timeoutMs ?? 20000;
  const from = config.from || config.user;

  if (!config.user || !config.pass) {
    throw new Error("SMTP 凭据未配置");
  }

  const connection = await SmtpConnection.open(config.host, config.port, timeoutMs);

  try {
    const banner = await connection.response(timeoutMs);
    if (banner.code !== 220) throw new Error(`SMTP 拒绝连接：${banner.code} ${banner.text}`);

    await connection.command("EHLO muhan.novasync.cn", [250], timeoutMs);
    await connection.command("AUTH LOGIN", [334], timeoutMs);
    await connection.command(base64Utf8(config.user), [334], timeoutMs);
    await connection.command(base64Utf8(config.pass), [235], timeoutMs);
    await connection.command(`MAIL FROM:<${from}>`, [250], timeoutMs);
    await connection.command(`RCPT TO:<${options.to}>`, [250, 251], timeoutMs);
    await connection.command("DATA", [354], timeoutMs);

    // 正文以 "\r\n.\r\n" 结束；行首单独的点需要转义（base64 正文不会出现，此处仅保证头部安全）。
    const message = buildMessage(config, options).replace(/\r\n\./g, "\r\n..");
    await connection.writeRaw(message + "." + CRLF, timeoutMs);

    const accepted = await connection.response(timeoutMs);
    if (accepted.code !== 250) {
      throw new Error(`SMTP 投递失败：${accepted.code} ${accepted.text}`);
    }

    // 尽力优雅退出，失败不影响投递结果。
    try {
      await connection.command("QUIT", [221], 5000);
    } catch {
      /* ignore */
    }
  } finally {
    connection.close();
  }
}
