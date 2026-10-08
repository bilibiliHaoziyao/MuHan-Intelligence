package com.muhan.intelligence.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Who authored a message. */
enum class Role {
    USER,
    ASSISTANT,
    SYSTEM,
    ;

    val wireValue: String
        get() = when (this) {
            USER -> "user"
            ASSISTANT -> "assistant"
            SYSTEM -> "system"
        }

    companion object {
        fun fromWire(value: String): Role = when (value.lowercase()) {
            "user" -> USER
            "assistant" -> ASSISTANT
            "system" -> SYSTEM
            else -> ASSISTANT
        }
    }
}

/** Delivery state of a single message. */
enum class MessageStatus {
    /** Persisted and settled. */
    COMPLETE,

    /** Currently being streamed from the provider. */
    STREAMING,

    /** Local-only failure, user can retry. */
    FAILED,
}

/**
 * A single turn in a conversation.
 *
 * [reasoningContent] captures chain-of-thought emitted by reasoning models
 * (DeepSeek-R1 and friends) so the UI can present it separately from the answer.
 */
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: Role,
    val content: String,
    val reasoningContent: String = "",
    val status: MessageStatus = MessageStatus.COMPLETE,
    val errorMessage: String? = null,
    val modelName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val tokenCount: Int? = null,
) {
    val isStreaming: Boolean get() = status == MessageStatus.STREAMING
    val hasReasoning: Boolean get() = reasoningContent.isNotBlank()
}

/** A conversation thread shown in the history drawer. */
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val modelName: String? = null,
    val pinned: Boolean = false,
    val messageCount: Int = 0,
)

/** Provider wire-format flavour. */
enum class ApiFlavor {
    /** OpenAI-compatible `/chat/completions` (DeepSeek, Moonshot, Zhipu, ...). */
    OPENAI,

    /** Anthropic Messages API `/v1/messages`. */
    ANTHROPIC,

    /** Google Gemini `/v1beta/models/{model}:streamGenerateContent`. */
    GEMINI,
}

/**
 * A user-configured model provider.
 *
 * The API key is deliberately kept out of this model — it lives in encrypted
 * storage keyed by [id] so it never lands in a plain-text database or log.
 */
data class ProviderConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val modelName: String,
    val flavor: ApiFlavor = ApiFlavor.OPENAI,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val sanitizedBaseUrl: String get() = baseUrl.trim().trimEnd('/')
}

/** Sampling and behaviour knobs exposed in settings. */
data class GenerationSettings(
    val temperature: Float = 1.0f,
    val topP: Float = 1.0f,
    val maxTokens: Int = 4096,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val streamEnabled: Boolean = true,
    val sendHistory: Boolean = true,
    val reasoningEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are 慕寒智能, a helpful, careful and concise AI assistant. " +
                "Answer in the user's language. Use Markdown for structure and code."
    }
}

/** Theme preference. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Persisted app preferences. */
data class AppPreferences(
    val onboardingCompleted: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val generation: GenerationSettings = GenerationSettings(),
)

/** Streaming events emitted while a completion is in flight. */
sealed interface StreamEvent {
    data class ReasoningDelta(val text: String) : StreamEvent
    data class ContentDelta(val text: String) : StreamEvent
    data class Usage(val promptTokens: Int, val completionTokens: Int) : StreamEvent
    data object Completed : StreamEvent
    data class Failed(val message: String) : StreamEvent
}

/** Result of a connectivity probe against the configured provider. */
sealed interface ConnectionTestResult {
    data class Success(val latencyMs: Long, val modelEcho: String?) : ConnectionTestResult
    data class Failure(val message: String, val httpCode: Int? = null) : ConnectionTestResult
}
