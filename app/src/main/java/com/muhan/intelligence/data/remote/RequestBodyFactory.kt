package com.muhan.intelligence.data.remote

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

    private val json = Json { encodeDefaults = true }

    fun build(
        flavor: com.muhan.intelligence.domain.model.ApiFlavor,
        model: String,
        messages: List<Pair<Role, String>>,
        systemPrompt: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        stream: Boolean,
    ): String = when (flavor) {
        com.muhan.intelligence.domain.model.ApiFlavor.OPENAI ->
            openAi(model, messages, systemPrompt, temperature, topP, maxTokens, stream)
        com.muhan.intelligence.domain.model.ApiFlavor.ANTHROPIC ->
            anthropic(model, messages, systemPrompt, temperature, topP, maxTokens, stream)
        com.muhan.intelligence.domain.model.ApiFlavor.GEMINI ->
            gemini(model, messages, systemPrompt, temperature, topP, maxTokens, stream)
    }

    private fun openAi(
        model: String,
        messages: List<Pair<Role, String>>,
        systemPrompt: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        stream: Boolean,
    ): String = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            if (systemPrompt.isNotBlank()) {
                addJsonObject {
                    put("role", Role.SYSTEM.wireValue)
                    put("content", systemPrompt)
                }
            }
            messages.forEach { (role, content) ->
                addJsonObject {
                    put("role", role.wireValue)
                    put("content", content)
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
    }.toString()

    private fun anthropic(
        model: String,
        messages: List<Pair<Role, String>>,
        systemPrompt: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        stream: Boolean,
    ): String = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        if (systemPrompt.isNotBlank()) put("system", systemPrompt)
        put("temperature", temperature)
        put("top_p", topP)
        put("stream", stream)
        putJsonArray("messages") {
            messages.filter { it.first != Role.SYSTEM }.forEach { (role, content) ->
                addJsonObject {
                    put("role", role.wireValue)
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", content)
                        }
                    }
                }
            }
        }
    }.toString()

    private fun gemini(
        model: String,
        messages: List<Pair<Role, String>>,
        systemPrompt: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        stream: Boolean,
    ): String = buildJsonObject {
        if (systemPrompt.isNotBlank()) {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
            }
        }
        putJsonArray("contents") {
            messages.filter { it.first != Role.SYSTEM }.forEach { (role, content) ->
                addJsonObject {
                    put("role", if (role == Role.ASSISTANT) "model" else "user")
                    putJsonArray("parts") { addJsonObject { put("text", content) } }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("temperature", temperature)
            put("topP", topP)
            put("maxOutputTokens", maxTokens)
        }
    }.toString()

    /** Extracts assistant text from a non-streaming response body. */
    fun extractNonStreamingText(flavor: com.muhan.intelligence.domain.model.ApiFlavor, body: String): String {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return ""
        return when (flavor) {
            com.muhan.intelligence.domain.model.ApiFlavor.OPENAI -> {
                val arr = root["choices"] as? JsonArray
                (arr?.firstOrNull() as? JsonObject)
                    ?.get("message")?.let { it as? JsonObject }
                    ?.get("content")?.let { (it as? JsonPrimitive)?.content }
                    .orEmpty()
            }
            com.muhan.intelligence.domain.model.ApiFlavor.ANTHROPIC -> {
                (root["content"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.get("text")?.let { t -> (t as? JsonPrimitive)?.content } }
                    ?.joinToString("")
                    .orEmpty()
            }
            com.muhan.intelligence.domain.model.ApiFlavor.GEMINI -> {
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
