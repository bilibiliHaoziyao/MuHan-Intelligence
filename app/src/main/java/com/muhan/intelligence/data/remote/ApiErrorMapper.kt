package com.muhan.intelligence.data.remote

/**
 * Maps provider HTTP/IO failures onto short, actionable Chinese messages.
 *
 * Raw exceptions from OkHttp are rarely useful to an end user, so every failure
 * path funnels through here before it reaches the UI.
 */
object ApiErrorMapper {

    fun describe(t: Throwable, httpCode: Int? = null, rawBody: String? = null): String {
        val serverHint = rawBody?.let { extractServerMessage(it) }

        val base = when (httpCode) {
            400 -> "请求被拒绝（400）。检查下模型名称和参数。"
            401 -> "API Key 无效或已过期（401）。去「设置 → 模型服务」重新填一下。"
            402 -> "账户余额不足（402）。去服务商后台充值。"
            403 -> "无权访问该模型（403）。确认账号已开通这个模型的权限。"
            404 -> "接口地址不存在（404）。看看 Base URL 是不是漏了 /v1。"
            413 -> "内容太长了（413）。新建一个对话，或者缩短输入。"
            422 -> "参数格式不对（422）。检查采样参数。"
            429 -> "请求太频繁（429）。等几秒再试。"
            500, 502, 503, 504 -> "服务商暂时不可用（$httpCode）。稍后再试。"
            else -> null
        }

        val message = when {
            base != null -> base
            isNetwork(t) -> "网络连不上。检查网络或代理。"
            isTimeout(t) -> "请求超时。网络慢，或者模型响应太久。"
            isDns(t) -> "解析不了服务器地址。检查 Base URL 拼写。"
            isTls(t) -> "安全连接失败。接口地址要用 https。"
            else -> t.message?.takeIf { it.isNotBlank() } ?: "出了点问题，再试一次。"
        }

        return if (serverHint.isNullOrBlank()) message else "$message\n服务商提示：$serverHint"
    }

    private fun isNetwork(t: Throwable) = t is java.net.SocketException ||
        t is java.net.ConnectException ||
        t is java.net.UnknownHostException ||
        t is java.io.IOException && t.message?.contains("unexpected end of stream", true) == true

    private fun isTimeout(t: Throwable) = t is java.net.SocketTimeoutException

    private fun isDns(t: Throwable) = t is java.net.UnknownHostException

    private fun isTls(t: Throwable) = t is javax.net.ssl.SSLException

    /**
     * Best-effort extraction of `{"error":{"message":"..."}}` or `{"message":"..."}`
     * without pulling in a JSON parser on the error path.
     */
    private fun extractServerMessage(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        val marker = "\"message\""
        val idx = trimmed.indexOf(marker)
        if (idx < 0) return trimmed.take(200)
        val colon = trimmed.indexOf(':', idx)
        if (colon < 0) return trimmed.take(200)
        val startQuote = trimmed.indexOf('"', colon + 1)
        if (startQuote < 0) return trimmed.take(200)
        val sb = StringBuilder()
        var i = startQuote + 1
        while (i < trimmed.length) {
            val c = trimmed[i]
            if (c == '\\' && i + 1 < trimmed.length) {
                sb.append(trimmed[i + 1])
                i += 2
                continue
            }
            if (c == '"') break
            sb.append(c)
            i++
        }
        return sb.toString().take(300).ifBlank { null }
    }
}
