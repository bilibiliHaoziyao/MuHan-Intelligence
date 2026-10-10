package com.muhan.intelligence.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.muhan.intelligence.domain.model.StreamEvent

/**
 * Incremental parser for `text/event-stream` responses.
 *
 * Providers differ in how they shape a stream, so parsing is done generically from
 * the JSON tree rather than via typed DTOs: DeepSeek/OpenAI use
 * `choices[0].delta.{content,reasoning_content}`, Anthropic uses
 * `content_block_delta.delta.text`, Gemini uses `candidates[0].content.parts[0].text`.
 * All three collapse onto the same [StreamEvent] stream.
 */
class SseStreamParser(
    private val flavor: com.muhan.intelligence.domain.model.ApiFlavor,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    private val buffer = StringBuilder()
    private var sawDone = false

    /** Feeds raw bytes; returns any complete events that became parseable. */
    fun feed(chunk: String): List<StreamEvent> {
        buffer.append(chunk)
        val events = mutableListOf<StreamEvent>()

        while (true) {
            val boundary = findBoundary(buffer)
            if (boundary == null) break
            val rawEvent = buffer.substring(0, boundary.first)
            buffer.delete(0, boundary.second)
            val payload = extractData(rawEvent) ?: continue
            if (payload == "[DONE]") {
                sawDone = true
                events += StreamEvent.Completed
                continue
            }
            parsePayloadSafely(payload)?.let { events += it }
        }
        return events
    }

    /** Flushes any trailing event when the connection closes without a blank line. */
    fun flush(): List<StreamEvent> {
        if (buffer.isBlank()) return emptyList()
        val payload = extractData(buffer.toString()) ?: return emptyList()
        buffer.clear()
        if (payload == "[DONE]") return listOf(StreamEvent.Completed)
        return listOfNotNull(parsePayloadSafely(payload))
    }

    /**
     * 0.2.0 Fix: `parsePayload` 内部使用 `.jsonObject` / `.jsonPrimitive` 强制访问，
     * 遇到服务商返回类型不符的字段（如 `"delta":null`、错误字段为字符串）会抛
     * IllegalArgumentException。该异常发生在 OkHttp 回调线程，UI 层的 try/catch
     * 无法捕获，会直接杀死整个进程（0.1.0 以来「回答即闪退」的真正根因）。
     * 这里兜底：任何解析异常都视为「该事件无法识别」并跳过，绝不向上抛。
     */
    private fun parsePayloadSafely(payload: String): StreamEvent? =
        runCatching { parsePayload(payload) }.getOrNull()    val completed: Boolean get() = sawDone

    /** Returns (indexOfBoundary, nextIndex) for `\n\n` or `\r\n\r\n`. */
    private fun findBoundary(sb: StringBuilder): Pair<Int, Int>? {
        val text = sb.toString()
        val lf = text.indexOf("\n\n")
        val crlf = text.indexOf("\r\n\r\n")
        return when {
            lf >= 0 && crlf >= 0 ->
                if (lf < crlf) lf to lf + 2 else crlf to crlf + 4
            lf >= 0 -> lf to lf + 2
            crlf >= 0 -> crlf to crlf + 4
            else -> null
        }
    }

    /** Collects `data:` lines per the SSE spec, ignoring comments and other fields. */
    private fun extractData(block: String): String? {
        val dataLines = block.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trimStart() }
            .toList()
        if (dataLines.isEmpty()) return null
        return dataLines.joinToString("\n").trim().ifBlank { null }
    }

    private fun parsePayload(payload: String): StreamEvent? {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null

        // Some providers signal failures with an inline error object mid-stream.
        // 0.2.0 Fix2: error 可能是字符串/数组等任意类型，逐字段安全访问。
        (root["error"] as? JsonObject)?.let { err ->
            val msg = (err["message"] as? JsonPrimitive)?.contentOrNull
            if (!msg.isNullOrBlank()) return StreamEvent.Failed(msg)
        }

        return when (flavor) {
            com.muhan.intelligence.domain.model.ApiFlavor.OPENAI -> parseOpenAi(root)
            com.muhan.intelligence.domain.model.ApiFlavor.ANTHROPIC -> parseAnthropic(root)
            com.muhan.intelligence.domain.model.ApiFlavor.GEMINI -> parseGemini(root)
        }
    }

    private fun parseOpenAi(root: JsonObject): StreamEvent? {
        // 0.2.0 Fix2: DeepSeek 等服务商在回复中途的每个 chunk 都会带 "usage":null，
        // 此前 usageOf() 的 .jsonObject 强制访问会抛异常导致整个内容事件被丢弃
        // （表现为「API 连通但模型不回复」）。所有字段访问改为安全转换。
        usageOf(root)?.let { usage ->
            val prompt = (usage["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
            val completion = (usage["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
            if (prompt > 0 || completion > 0) return StreamEvent.Usage(prompt, completion)
        }

        val choices = root["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val finish = (first["finish_reason"] as? JsonPrimitive)?.contentOrNull
        val delta = first["delta"] as? JsonObject

        if (delta != null) {
            reasoning(delta)?.let { return StreamEvent.ReasoningDelta(it) }
            val text = (delta["content"] as? JsonPrimitive)?.contentOrNull
            if (!text.isNullOrEmpty()) return StreamEvent.ContentDelta(text)
        }

        // Non-streaming shape: `message.content`
        (first["message"] as? JsonObject)?.let { msg ->
            val text = (msg["content"] as? JsonPrimitive)?.contentOrNull
            if (!text.isNullOrEmpty()) return StreamEvent.ContentDelta(text)
        }

        if (finish != null) return StreamEvent.Completed
        return null
    }

    private fun reasoning(delta: JsonObject): String? {
        (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { return it }
        (delta["reasoning"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { return it }
        (delta["thinking"] as? JsonObject)?.get("content")?.let { c ->
            (c as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun parseAnthropic(root: JsonObject): StreamEvent? {
        when ((root["type"] as? JsonPrimitive)?.contentOrNull) {
            "content_block_delta" -> {
                val delta = root["delta"] as? JsonObject ?: return null
                when ((delta["type"] as? JsonPrimitive)?.contentOrNull) {
                    "thinking_delta" -> {
                        val t = (delta["thinking"] as? JsonPrimitive)?.contentOrNull
                        if (!t.isNullOrEmpty()) return StreamEvent.ReasoningDelta(t)
                    }
                    "text_delta" -> {
                        val t = (delta["text"] as? JsonPrimitive)?.contentOrNull
                        if (!t.isNullOrEmpty()) return StreamEvent.ContentDelta(t)
                    }
                }
                return null
            }
            "message_delta" -> {
                val usage = root["usage"] as? JsonObject
                val out = (usage?.get("output_tokens") as? JsonPrimitive)?.intOrNull ?: 0
                if (out > 0) return StreamEvent.Usage(0, out)
                return null
            }
            "message_stop" -> return StreamEvent.Completed
            "error" -> {
                val msg = (root["error"] as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
                    ?: "Anthropic 返回错误"
                return StreamEvent.Failed(msg)
            }
        }
        return null
    }

    private fun parseGemini(root: JsonObject): StreamEvent? {
        val candidates = root["candidates"] as? JsonArray ?: return null
        val first = candidates.firstOrNull() as? JsonObject ?: return null
        val parts = (first["content"] as? JsonObject)?.get("parts") as? JsonArray
        val text = (parts?.firstOrNull() as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (!text.isNullOrEmpty()) return StreamEvent.ContentDelta(text)
        (first["finishReason"] as? JsonPrimitive)?.contentOrNull?.let { return StreamEvent.Completed }
        return null
    }

    /** 0.2.0 Fix2: usage 可能是 null / 对象 / 其他类型，只接受对象。 */
    private fun usageOf(root: JsonObject): JsonObject? = root["usage"] as? JsonObject
}
