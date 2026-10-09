package com.muhan.intelligence.data.remote

import com.muhan.intelligence.domain.model.ApiFlavor
import com.muhan.intelligence.domain.model.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds request bodies for the three supported provider flavours.
 *
 * Kept separate from the transport so it can be unit-tested without any network
 * and so the exact wire shape stays auditable in one place.
 */
object RequestBodyFactory {

    /**
     * One message on the wire. [text] is the user-visible turn text; [imageDataUrls]
     * holds base64 `data:` URLs for attached images (vision input). Text attachments
     * are already inlined into [text] by the caller.
     */
    data class OutboundMessage(
        val role: Role,
        val text: String,
        val imageDataUrls: List<String> = emptyList(),
    ) {
        val hasImages: Boolean get() = imageDataUrls.isNotEmpty()
    }

    /** Parsed image-generation reply. */
    data class ImageReply(
        /** Remote https URL or base64 image body, depending on what the provider returned. */
        val source: String,
        val isBase64: Boolean,
        val revisedPrompt: String?,
    )

    private val json = Json { encodeDefaults = true }

    fun build(
        flavor: ApiFlavor,
        model: String,
        messages: List<OutboundMessage>,
        systemPrompt: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        stream: Boolean,
        thinkingEnabled: Boolean = false,
        webSearchEnabled: Boolean = false,
    ): String = when (flavor) {
        ApiFlavor.OPENAI ->
            openAi(model, messages, systemPrompt, temperature, topP, maxTokens, stream, thinkingEnabled, webSearchEnabled)
        ApiFlavor.ANTHROPIC ->
            anthropic(model, messages, systemPrompt, temperature, topP, maxTokens, stream, thinkingEnabled, webSearchEnabled)
        ApiFlavor.GEMINI ->
            gemini(model, messages, systemPrompt, temperature, topP, maxTokens, stream, thinkingEnabled, webSearchEnabled)
    }

    // ------------------------------------------------------------------ OpenAI

    private fun openAi(
        model: String,
        messages: List<OutboundMessage>,
        systemPrompt: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        stream: Boolean,
        thinkingEnabled: Boolean,
        webSearchEnabled: Boolean,
    ): String = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            if (systemPrompt.isNotBlank()) {
                addJsonObject {
                    put("role", Role.SYSTEM.wireValue)
                    put("content", systemPrompt)
                }
            }
            messages.forEach { message ->
                addJsonObject {
                    put("role", message.role.wireValue)
                    if (message.hasImages) {
                        // Vision shape: multipart content with image_url parts.
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.text)
                            })
                            message.imageDataUrls.forEach { dataUrl ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", dataUrl) }
                                })
                            }
                        }
                    } else {
                        put("content", message.text)
                    }
                }
            }
        }
        put("temperature", temperature)

        // Reasoning models (DeepSeek-R1) reject sampling overrides; only send when
        // the user kept the default-ish range.
        if (temperature > 0f || topP > 0f) {
            put("top_p", topP)
        }
        put("max_tokens", maxTokens)
        put("stream", stream)
        if (stream) {
            putJsonObject("stream_options") { put("include_usage", true) }
        }

        // Deep-think switch: Qwen3 / vLLM / SiliconFlow honour `enable_thinking`;
        // providers that don't know the field ignore it.
        if (!thinkingEnabled) {
            put("enable_thinking", false)
        }
        // Server-side web search: OpenAI's documented knob. Aggregators either
        // support it or ignore it.
        if (webSearchEnabled) {
            putJsonObject("web_search_options") {}
        }
    }.toString()

    // ---------------------------------------------------------------- Anthropic

    private fun anthropic(
        model: String,
        messages: List<OutboundMessage>,
        systemPrompt: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        stream: Boolean,
        thinkingEnabled: Boolean,
        webSearchEnabled: Boolean,
    ): String = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        if (systemPrompt.isNotBlank()) put("system", systemPrompt)
        if (thinkingEnabled) {
            // Extended thinking: temperature/topP must be omitted when enabled.
            putJsonObject("thinking") {
                put("type", "enabled")
                put("budget_tokens", maxOf(maxTokens / 2, 1024))
            }
        } else {
            put("temperature", temperature)
            put("top_p", topP)
        }
        put("stream", stream)
        if (webSearchEnabled) {
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("type", "web_search_20250305")
                    put("name", "web_search")
                })
            }
        }
        putJsonArray("messages") {
            messages.filter { it.role != Role.SYSTEM }
                .forEach { message ->
                    addJsonObject {
                        put("role", message.role.wireValue)
                        putJsonArray("content") {
                            message.imageDataUrls.forEach { dataUrl ->
                                parseDataUrl(dataUrl)?.let { (mime, b64) ->
                                    add(buildJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", mime)
                                            put("data", b64)
                                        }
                                    })
                                }
                            }
                            if (message.text.isNotBlank()) {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", message.text)
                                }
                            }
                        }
                    }
                }
        }
    }.toString()

    // ------------------------------------------------------------------ Gemini

    private fun gemini(
        model: String,
        messages: List<OutboundMessage>,
        systemPrompt: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        stream: Boolean,
        thinkingEnabled: Boolean,
        webSearchEnabled: Boolean,
    ): String = buildJsonObject {
        if (systemPrompt.isNotBlank()) {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
            }
        }
        putJsonArray("contents") {
            messages.filter { it.role != Role.SYSTEM }.forEach { message ->
                addJsonObject {
                    put("role", if (message.role == Role.ASSISTANT) "model" else "user")
                    putJsonArray("parts") {
                        message.imageDataUrls.forEach { dataUrl ->
                            parseDataUrl(dataUrl)?.let { (mime, b64) ->
                                add(buildJsonObject {
                                    putJsonObject("inline_data") {
                                        put("mime_type", mime)
                                        put("data", b64)
                                    }
                                })
                            }
                        }
                        if (message.text.isNotBlank()) {
                            addJsonObject { put("text", message.text) }
                        }
                    }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("temperature", temperature)
            put("topP", topP)
            put("maxOutputTokens", maxTokens)
            putJsonObject("thinkingConfig") {
                // 0 disables thinking on Flash-class models; -1 = dynamic budget.
                put("thinkingBudget", if (thinkingEnabled) -1 else 0)
            }
        }
        if (webSearchEnabled) {
            putJsonArray("tools") {
                add(buildJsonObject { putJsonObject("google_search") {} })
            }
        }
    }.toString()

    // ----------------------------------------------------------- image models

    /** OpenAI-compatible `/images/generations` request body. */
    fun buildImageRequest(
        model: String,
        prompt: String,
        size: String = "1024x1024",
    ): String = buildJsonObject {
        put("model", model)
        put("prompt", prompt)
        put("n", 1)
        put("size", size)
        put("response_format", "b64_json")
    }.toString()

    /** Extracts the first image from an `/images/generations` response. */
    fun extractImageReply(body: String): ImageReply? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val data = root["data"] as? JsonArray ?: return null
        val first = data.firstOrNull() as? JsonObject ?: return null

        val b64 = (first["b64_json"] as? JsonPrimitive)?.content
        if (!b64.isNullOrBlank()) {
            return ImageReply(source = b64, isBase64 = true, revisedPrompt = (first["revised_prompt"] as? JsonPrimitive)?.content)
        }
        val url = (first["url"] as? JsonPrimitive)?.content
        if (!url.isNullOrBlank()) {
            return ImageReply(source = url, isBase64 = false, revisedPrompt = (first["revised_prompt"] as? JsonPrimitive)?.content)
        }
        return null
    }

    // ----------------------------------------------------------------- helpers

    /** Splits a `data:<mime>;base64,<payload>` URL into its parts. */
    private fun parseDataUrl(dataUrl: String): Pair<String, String>? {
        val match = Regex("^data:([^;]+);base64,(.+)$", RegexOption.DOT_MATCHES_ALL)
            .find(dataUrl.trim()) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    /** Extracts assistant text from a non-streaming response body. */
    fun extractNonStreamingText(flavor: ApiFlavor, body: String): String {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return ""
        return when (flavor) {
            ApiFlavor.OPENAI -> {
                val arr = root["choices"] as? JsonArray
                (arr?.firstOrNull() as? JsonObject)
                    ?.get("message")?.let { it as? JsonObject }
                    ?.get("content")?.let { (it as? JsonPrimitive)?.content }
                    .orEmpty()
            }
            ApiFlavor.ANTHROPIC -> {
                (root["content"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.get("text")?.let { t -> (t as? JsonPrimitive)?.content } }
                    ?.joinToString("")
                    .orEmpty()
            }
            ApiFlavor.GEMINI -> {
                (root["candidates"] as? JsonArray)
                    ?.firstOrNull()?.let { it as? JsonObject }
                    ?.get("content")?.let { it as? JsonObject }
                    ?.get("parts")?.let { it as? JsonArray }
                    ?.firstOrNull()?.let { it as? JsonObject }
                    ?.get("text")?.let { (it as? JsonPrimitive)?.content }
                    .orEmpty()
            }
        }
    }

    /** Pulls the echoed model name out of a probe response, for the connection test. */
    fun extractModelEcho(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        (root["model"] as? JsonPrimitive)?.content?.let { return it }
        (root["modelVersion"] as? JsonPrimitive)?.content?.let { return it }
        return null
    }

    fun extractErrorCode(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val err = root["error"] as? JsonObject ?: return null
        return (err["code"] as? JsonPrimitive)?.content ?: (err["type"] as? JsonPrimitive)?.content
    }
}
