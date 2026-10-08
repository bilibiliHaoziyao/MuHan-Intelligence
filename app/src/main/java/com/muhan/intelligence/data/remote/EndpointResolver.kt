package com.muhan.intelligence.data.remote

import com.muhan.intelligence.domain.model.ApiFlavor
import com.muhan.intelligence.domain.model.ProviderConfig

/**
 * Turns a user-typed Base URL into a fully-qualified request URL.
 *
 * Users paste wildly different things (`api.deepseek.com`, `.../v1`,
 * `.../v1/chat/completions`, `.../v1beta`), so the resolver normalises all of
 * them instead of demanding one exact form.
 */
object EndpointResolver {

    fun resolve(provider: ProviderConfig, stream: Boolean): String {
        val base = provider.sanitizedBaseUrl
        return when (provider.flavor) {
            ApiFlavor.OPENAI -> resolveOpenAi(base)
            ApiFlavor.ANTHROPIC -> resolveAnthropic(base)
            ApiFlavor.GEMINI -> resolveGemini(base, provider.modelName, stream)
        }
    }

    /** e.g. https://api.deepseek.com → https://api.deepseek.com/v1/chat/completions */
    private fun resolveOpenAi(base: String): String {
        val lower = base.lowercase()
        if (lower.endsWith("/chat/completions")) return base
        if (lower.endsWith("/completions")) return base

        val trimmed = base.trimEnd('/')
        val hasVersionSegment = Regex("""/v\d+([a-z0-9\-]*)?$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(trimmed)

        return when {
            hasVersionSegment -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    /** Anthropic Messages API lives at /v1/messages. */
    private fun resolveAnthropic(base: String): String {
        val trimmed = base.trimEnd('/')
        if (trimmed.lowercase().endsWith("/messages")) return trimmed
        if (Regex("""/v\d+$""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
            return "$trimmed/messages"
        }
        return "$trimmed/v1/messages"
    }

    /** Gemini: https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent */
    private fun resolveGemini(base: String, model: String, stream: Boolean): String {
        val trimmed = base.trimEnd('/')
        val method = if (stream) "streamGenerateContent" else "generateContent"

        if (trimmed.contains(":generateContent") || trimmed.contains(":streamGenerateContent")) {
            val withoutMethod = trimmed.substringBefore(":")
            return "$withoutMethod:$method?alt=sse"
        }

        val withVersion = if (Regex("""/v\d+[a-z]*$""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
            trimmed
        } else {
            "$trimmed/v1beta"
        }

        val modelId = model.removePrefix("models/")
        val suffix = if (stream) "?alt=sse" else ""
        return "$withVersion/models/$modelId:$method$suffix"
    }
}
