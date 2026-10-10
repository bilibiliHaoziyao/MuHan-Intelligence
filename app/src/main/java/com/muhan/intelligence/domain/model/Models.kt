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

/** What kind of payload an attachment carries. */
enum class AttachmentKind { IMAGE, FILE }

/**
 * A file the user attached to a message.
 *
 * [localPath] points into the app's private storage (the content Uri is copied
 * there on pick so the attachment survives across restarts). Images are sent to
 * the model as base64 data URLs; text files are inlined into the prompt.
 */
@Serializable
data class MessageAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val kind: AttachmentKind = AttachmentKind.FILE,
    val sizeBytes: Long = 0,
    val localPath: String,
) {
    val isImage: Boolean get() = kind == AttachmentKind.IMAGE
}

/**
 * A single turn in a conversation.
 *
 * [reasoningContent] captures chain-of-thought emitted by reasoning models
 * (DeepSeek-R1 and friends) so the UI can present it separately from the answer.
 * [imageUrl] carries a locally generated image (image-model replies) or a remote
 * URL returned by the provider.
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
    val attachments: List<MessageAttachment> = emptyList(),
    val imageUrl: String? = null,
) {
    val isStreaming: Boolean get() = status == MessageStatus.STREAMING
    val hasReasoning: Boolean get() = reasoningContent.isNotBlank()
    val hasAttachments: Boolean get() = attachments.isNotEmpty()
    val isImageMessage: Boolean get() = imageUrl != null
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
 * [imageModel] is an optional image-generation model on the same account; when
 * set, the chat composer offers an image-generation mode.
 */
data class ProviderConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val modelName: String,
    val flavor: ApiFlavor = ApiFlavor.OPENAI,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val imageModel: String? = null,
) {
    val sanitizedBaseUrl: String get() = baseUrl.trim().trimEnd('/')
    val supportsImageGeneration: Boolean get() = !imageModel.isNullOrBlank()
}

/**
 * Per-request capability switches shown above the composer. They mirror what
 * the major APIs expose: reasoning budgets (OpenAI o-series / Qwen3 `enable_thinking`
 * / Gemini thinkingConfig / Anthropic thinking) and server-side web search tools.
 */
data class ChatExtras(
    val thinkingEnabled: Boolean = false,
    val webSearchEnabled: Boolean = false,
)

/** Sampling and behaviour knobs exposed in settings. */
data class GenerationSettings(
    val temperature: Float = 1.0f,
    val topP: Float = 1.0f,
    val maxTokens: Int = 4096,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val streamEnabled: Boolean = true,
    val sendHistory: Boolean = true,
    val reasoningEnabled: Boolean = true,
    val webSearchEnabled: Boolean = false,
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
    /** 用户体验改善计划（0.2.0 Fix2，目前仅 UI 开关）。 */
    val uxImprovement: Boolean = true,

    /** 是否已尝试申请存储权限（0.3.0，避免每次启动重复弹窗）。 */
    val storagePermissionRequested: Boolean = false,
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

/** Self-selected user persona picked on the first onboarding step. */
enum class Persona {
    /** 极简引导：选服务 → 贴 Key → 完成。 */
    ROOKIE,

    /** 完整引导 + 高级选项（默认开关深度思考/联网搜索等）。 */
    GEEK,
}

/** Outcome of an image-generation request. */
sealed interface ImageGenerationResult {
    /** [source] is either an https URL returned by the provider or a local file path. */
    data class Success(val source: String, val revisedPrompt: String? = null) : ImageGenerationResult
    data class Failure(val message: String) : ImageGenerationResult
}
