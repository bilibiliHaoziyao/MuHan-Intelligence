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
            400 -> "请求被拒绝（400）。请检查模型名称与参数是否正确。"
            401 -> "API Key 无效或已过期（401）。请在「设置 → 模型服务」中重新填写。"
            402 -> "账户余额不足（402）。请前往服务商控制台充值后重试。"
            403 -> "无权访问该模型（403）。请确认账号已开通对应模型权限。"
            404 -> "接口地址不存在（404）。请检查 Base URL 是否包含 /v1 等正确路径。"
            413 -> "请求内容过长（413）。请新建会话或缩短输入。"
            422 -> "参数格式错误（422）。请检查采样参数设置。"
            429 -> "请求过于频繁（429）。请稍候几秒后重试。"
            500, 502, 503, 504 -> "服务商暂时不可用（$httpCode）。请稍后重试。"
            else -> null
        }

        val message = when {
            base != null -> base
            isNetwork(t) -> "网络连接失败。请检查网络或代理设置后重试。"
            isTimeout(t) -> "请求超时。可能是网络较慢或模型响应时间过长。"
            isDns(t) -> "无法解析服务器地址。请检查 Base URL 拼写与网络环境。"
            isTls(t) -> "安全连接失败。请确认接口地址使用 https。"
            else -> t.message?.takeIf { it.isNotBlank() } ?: "发生未知错误，请重试。"
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
