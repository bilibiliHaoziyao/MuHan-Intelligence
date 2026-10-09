package com.muhan.intelligence.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muhan.intelligence.data.local.AttachmentStore
import com.muhan.intelligence.data.remote.RequestBodyFactory
import com.muhan.intelligence.data.repository.ConversationRepository
import com.muhan.intelligence.data.repository.ProviderRepository
import com.muhan.intelligence.domain.model.ChatExtras
import com.muhan.intelligence.domain.model.ChatMessage
import com.muhan.intelligence.domain.model.Conversation
import com.muhan.intelligence.domain.model.GenerationSettings
import com.muhan.intelligence.domain.model.ImageGenerationResult
import com.muhan.intelligence.domain.model.MessageAttachment
import com.muhan.intelligence.domain.model.MessageStatus
import com.muhan.intelligence.domain.model.ProviderConfig
import com.muhan.intelligence.domain.model.Role
import com.muhan.intelligence.domain.model.StreamEvent
import com.muhan.intelligence.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Everything the chat screen needs to render, in one immutable snapshot. */
data class ChatUiState(
    val conversationId: String = "",
    val conversation: Conversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isGenerating: Boolean = false,
    val activeProvider: ProviderConfig? = null,
    val generation: GenerationSettings = GenerationSettings(),
    val isLoading: Boolean = true,
    val errorBanner: String? = null,
    val infoBanner: String? = null,
    /** Attachments picked but not yet sent. */
    val pendingAttachments: List<MessageAttachment> = emptyList(),
    /** Per-request capability switches (deep thinking / web search / image mode). */
    val extras: ChatExtras = ChatExtras(),
    val imageMode: Boolean = false,
) {
    /** The user cannot send while a stream is in flight or nothing is configured. */
    val canSend: Boolean
        get() = (input.isNotBlank() || pendingAttachments.isNotEmpty()) &&
            !isGenerating && activeProvider != null

    val isEmptyConversation: Boolean get() = messages.isEmpty() && !isLoading

    val hasProvider: Boolean get() = activeProvider != null

    /** Image-generation composer entry is only offered when a model is configured. */
    val supportsImageGeneration: Boolean
        get() = activeProvider?.supportsImageGeneration == true
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: com.muhan.intelligence.data.repository.SettingsRepository,
    private val attachmentStore: AttachmentStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialConversationId: String =
        savedStateHandle.get<String>(Routes.ARG_CONVERSATION_ID).orEmpty()

    private val _uiState = MutableStateFlow(ChatUiState(conversationId = initialConversationId))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Live streaming job, so the stop button can cancel it mid-flight. */
    private var generationJob: Job? = null

    /** Coalesces DB writes while tokens stream in, to avoid hammering Disk I/O. */
    private var persistJob: Job? = null

    /** Whether generation defaults have been seeded from settings at least once. */
    private var extrasSeeded = false

    init {
        observeSettings()
        observeActiveProvider()
        ensureConversationExists()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.preferences.collect { prefs ->
                _uiState.update { state ->
                    state.copy(generation = prefs.generation).let { seeded ->
                        if (extrasSeeded) {
                            seeded
                        } else {
                            // First emission seeds the composer toggles; afterwards the
                            // user's per-chat choices win over the settings defaults.
                            extrasSeeded = true
                            seeded.copy(
                                extras = ChatExtras(
                                    thinkingEnabled = prefs.generation.reasoningEnabled,
                                    webSearchEnabled = prefs.generation.webSearchEnabled,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun observeActiveProvider() {
        viewModelScope.launch {
            providerRepository.observeActiveProvider().collect { provider ->
                _uiState.update { it.copy(activeProvider = provider) }
            }
        }
    }

    private fun ensureConversationExists() {
        // Resolve the conversation id once (creating an empty thread if the caller
        // arrived via "new chat"), then bind both conversation and message streams.
        viewModelScope.launch {
            val id = initialConversationId.ifBlank {
                // Reuse the most recent empty conversation instead of piling up
                // blank entries in the history list.
                val existing = conversationRepository.observeConversations().first()
                    .firstOrNull { it.messageCount == 0 }
                existing?.id ?: conversationRepository.createConversation(
                    modelName = providerRepository.getActiveProvider()?.modelName,
                ).id
            }

            _uiState.update { it.copy(conversationId = id) }

            launch {
                conversationRepository.observeConversation(id).collect { conversation ->
                    _uiState.update { it.copy(conversation = conversation) }
                }
            }

            conversationRepository.observeMessages(id)
                .catch { e ->
                    _uiState.update { it.copy(isLoading = false, errorBanner = e.message) }
                }
                .collect { messages ->
                    // While streaming, the in-memory bubble is authoritative because
                    // DB rows lag behind the token stream by design.
                    if (_uiState.value.isGenerating) {
                        _uiState.update { it.copy(isLoading = false) }
                    } else {
                        _uiState.update { it.copy(messages = messages, isLoading = false) }
                    }
                }
        }
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun dismissBanners() {
        _uiState.update { it.copy(errorBanner = null, infoBanner = null) }
    }

    // ------------------------------------------------------------------ toggles

    fun toggleThinking() = _uiState.update {
        it.copy(extras = it.extras.copy(thinkingEnabled = !it.extras.thinkingEnabled))
    }

    fun toggleWebSearch() = _uiState.update {
        it.copy(extras = it.extras.copy(webSearchEnabled = !it.extras.webSearchEnabled))
    }

    fun toggleImageMode() = _uiState.update { it.copy(imageMode = !it.imageMode) }

    // -------------------------------------------------------------- attachments

    /** Imports picked content Uris into private storage and queues them. */
    fun addAttachments(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val imported = uris.mapNotNull { attachmentStore.import(it) }
            if (imported.isEmpty()) {
                _uiState.update { it.copy(errorBanner = "无法读取所选文件，请重试。") }
                return@launch
            }
            _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + imported) }
        }
    }

    fun removeAttachment(id: String) = _uiState.update {
        it.copy(pendingAttachments = it.pendingAttachments.filterNot { a -> a.id == id })
    }

    // ------------------------------------------------------------------ history

    /**
     * One-shot fetch backing the history drawer.
     *
     * The drawer is an incidental surface (opened on demand), so a suspend read is
     * simpler and cheaper than holding a second long-lived subscription.
     */
    suspend fun observeHistory(query: String): List<Conversation> =
        if (query.isBlank()) {
            conversationRepository.observeConversations().first()
        } else {
            conversationRepository.searchConversations(query).first()
        }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { conversationRepository.renameConversation(id, title) }
    }

    fun togglePinned(id: String, pinned: Boolean) {
        viewModelScope.launch { conversationRepository.setPinned(id, pinned) }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch { conversationRepository.deleteConversation(id) }
    }

    /** Clears the error state on a failed bubble and retries that turn. */
    fun retryMessage(messageId: String) {
        val state = _uiState.value
        val message = state.messages.firstOrNull { it.id == messageId } ?: return
        if (message.role != Role.ASSISTANT) return

        viewModelScope.launch {
            conversationRepository.deleteMessage(messageId)
            val history = conversationRepository.loadHistory(state.conversationId)
            generate(history)
        }
    }

    /** Edits a user turn: removes it (and everything after) and re-sends the new text. */
    fun editAndResend(messageId: String, newContent: String) {
        val state = _uiState.value
        val message = state.messages.firstOrNull { it.id == messageId } ?: return

        viewModelScope.launch {
            // Drop this message and every later one, then re-append the edited turn.
            conversationRepository.truncateFrom(message.id, state.conversationId)
            conversationRepository.deleteMessage(message.id)

            val edited = message.copy(content = newContent.trim(), createdAt = System.currentTimeMillis())
            conversationRepository.appendMessage(edited)
            conversationRepository.refreshMessageCount(state.conversationId)

            val history = conversationRepository.loadHistory(state.conversationId)
            generate(history)
        }
    }

    fun send() {
        val state = _uiState.value
        if (state.imageMode) {
            sendImageRequest()
            return
        }

        val text = state.input.trim()
        if (text.isEmpty() && state.pendingAttachments.isEmpty()) return

        if (state.activeProvider == null) {
            _uiState.update { it.copy(errorBanner = "尚未配置模型服务，请先前往「设置 → 模型服务」添加。") }
            return
        }

        viewModelScope.launch {
            val conversationId = state.conversationId
            val attachments = state.pendingAttachments
            val userMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = Role.USER,
                content = text,
                attachments = attachments,
            )
            conversationRepository.appendMessage(userMessage)
            if (text.isNotBlank()) {
                conversationRepository.updateTitleFromFirstMessage(conversationId, text)
            }
            _uiState.update { it.copy(input = "", pendingAttachments = emptyList()) }

            val history = conversationRepository.loadHistory(conversationId)
            generate(history)
        }
    }

    private fun sendImageRequest() {
        val state = _uiState.value
        val prompt = state.input.trim()
        if (prompt.isEmpty()) return
        if (!state.supportsImageGeneration) {
            _uiState.update { it.copy(errorBanner = "请先在「设置 → 模型服务」中为当前服务填写生图模型。") }
            return
        }

        viewModelScope.launch {
            val conversationId = state.conversationId
            conversationRepository.appendMessage(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = Role.USER,
                    content = prompt,
                ),
            )
            conversationRepository.updateTitleFromFirstMessage(conversationId, prompt)
            _uiState.update { it.copy(input = "", isGenerating = true, errorBanner = null) }

            val placeholderId = UUID.randomUUID().toString()
            _uiState.update {
                it.copy(
                    messages = it.messages + ChatMessage(
                        id = placeholderId,
                        conversationId = conversationId,
                        role = Role.ASSISTANT,
                        content = "",
                        status = MessageStatus.STREAMING,
                        modelName = it.activeProvider?.imageModel,
                    ),
                )
            }

            val result = providerRepository.generateImage(
                provider = state.activeProvider!!,
                prompt = prompt,
            )

            result.fold(
                onSuccess = { reply ->
                    val imagePath = if (reply.isBase64) {
                        saveBase64Image(reply.source)
                    } else {
                        reply.source
                    }
                    conversationRepository.appendMessage(
                        ChatMessage(
                            id = placeholderId,
                            conversationId = conversationId,
                            role = Role.ASSISTANT,
                            content = reply.revisedPrompt.orEmpty(),
                            status = MessageStatus.COMPLETE,
                            modelName = state.activeProvider?.imageModel,
                            imageUrl = imagePath,
                        ),
                    )
                    conversationRepository.refreshMessageCount(conversationId)
                },
                onFailure = { error ->
                    conversationRepository.deleteMessage(placeholderId)
                    _uiState.update {
                        it.copy(
                            messages = it.messages.filterNot { m -> m.id == placeholderId },
                            errorBanner = error.message ?: "生图失败，请稍后重试。",
                        )
                    }
                },
            )
            _uiState.update { it.copy(isGenerating = false) }
        }
    }

    /** Writes a base64 image body to private storage and returns the file path. */
    private fun saveBase64Image(base64: String): String? = attachmentStore.saveBase64Image(base64)

    fun stopGenerating() {
        generationJob?.cancel()
        generationJob = null

        val state = _uiState.value
        val streaming = state.messages.lastOrNull() ?: return
        viewModelScope.launch {
            if (streaming.status == MessageStatus.STREAMING) {
                conversationRepository.finishMessage(
                    messageId = streaming.id,
                    content = streaming.content,
                    reasoning = streaming.reasoningContent,
                    tokenCount = null,
                )
            }
            _uiState.update { it.copy(isGenerating = false) }
        }
    }

    /** Creates the assistant placeholder bubble and drives the stream into it. */
    private fun generate(history: List<ChatMessage>) {
        val state = _uiState.value
        val provider = state.activeProvider ?: return
        val settings = state.generation
        val extras = state.extras

        val assistantId = UUID.randomUUID().toString()
        val placeholder = ChatMessage(
            id = assistantId,
            conversationId = state.conversationId,
            role = Role.ASSISTANT,
            content = "",
            status = MessageStatus.STREAMING,
            modelName = provider.modelName,
        )

        _uiState.update {
            it.copy(
                messages = it.messages.filter { m -> m.status != MessageStatus.FAILED } + placeholder,
                isGenerating = true,
                errorBanner = null,
            )
        }

        val outbound = buildOutboundMessages(history, settings)

        generationJob = viewModelScope.launch {
            var content = StringBuilder()
            var reasoning = StringBuilder()
            var tokenCount: Int? = null
            var failureMessage: String? = null

            try {
                providerRepository.streamCompletion(
                    provider = provider,
                    messages = outbound,
                    systemPrompt = settings.systemPrompt,
                    temperature = settings.temperature,
                    topP = settings.topP,
                    maxTokens = settings.maxTokens,
                    thinkingEnabled = extras.thinkingEnabled,
                    webSearchEnabled = extras.webSearchEnabled,
                ).collect { event ->
                    when (event) {
                        is StreamEvent.ContentDelta -> {
                            content.append(event.text)
                            publishStreaming(assistantId, content.toString(), reasoning.toString())
                        }
                        is StreamEvent.ReasoningDelta -> {
                            reasoning.append(event.text)
                            publishStreaming(assistantId, content.toString(), reasoning.toString())
                        }
                        is StreamEvent.Usage -> tokenCount = event.promptTokens + event.completionTokens
                        is StreamEvent.Completed -> Unit
                        is StreamEvent.Failed -> failureMessage = event.message
                    }
                }
            } catch (ce: CancellationException) {
                // User pressed "stop" — rethrow so structured cancellation works.
                throw ce
            } catch (t: Throwable) {
                // 0.1.0 crashed here: any exception escaping the flow (JSON, IO,
                // provider quirks) had no handler and killed the whole process.
                failureMessage = t.message ?: t.javaClass.simpleName
            }

            persistJob?.cancel()
            val finalContent = content.toString()
            val finalReasoning = reasoning.toString()

            if (failureMessage != null && finalContent.isBlank()) {
                conversationRepository.failMessage(assistantId, failureMessage!!)
                conversationRepository.deleteMessage(assistantId)
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        messages = it.messages.filterNot { m -> m.id == assistantId },
                        errorBanner = failureMessage,
                    )
                }
            } else {
                conversationRepository.finishMessage(
                    messageId = assistantId,
                    content = finalContent,
                    reasoning = finalReasoning,
                    tokenCount = tokenCount,
                )
                conversationRepository.refreshMessageCount(state.conversationId)
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        messages = it.messages.map { m ->
                            if (m.id == assistantId) {
                                m.copy(
                                    content = finalContent,
                                    reasoningContent = finalReasoning,
                                    status = MessageStatus.COMPLETE,
                                )
                            } else {
                                m
                            }
                        },
                        infoBanner = failureMessage?.let { "生成中断：$it" },
                    )
                }
            }
            generationJob = null
        }
    }

    /** Pushes the growing buffer into UI state and schedules a throttled DB write. */
    private fun publishStreaming(id: String, content: String, reasoning: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id == id) {
                        message.copy(content = content, reasoningContent = reasoning)
                    } else {
                        message
                    }
                },
            )
        }
        schedulePersist(id, content, reasoning)
    }

    private fun schedulePersist(id: String, content: String, reasoning: String) {
        if (persistJob?.isActive == true) return
        persistJob = viewModelScope.launch {
            delay(PERSIST_INTERVAL_MS)
            runCatching { conversationRepository.updateStreamingContent(id, content, reasoning) }
        }
    }

    /**
     * Applies "send history" trimming: keeps the most recent turns that fit a
     * rough character budget, so long threads don't blow the context window.
     *
     * Images travel only with the newest user turn — re-sending every historical
     * photo would be slow and expensive.
     */
    private fun buildOutboundMessages(
        history: List<ChatMessage>,
        settings: GenerationSettings,
    ): List<RequestBodyFactory.OutboundMessage> {
        val usable = history
            .filter { it.role == Role.USER || it.role == Role.ASSISTANT }
            .map { it to attachmentText(it) }
            .filter { (_, text) -> text.isNotBlank() }

        if (!settings.sendHistory) return usable.takeLast(1).map { toOutbound(it, withImages = true) }

        var budget = CONTEXT_CHAR_BUDGET
        val result = ArrayDeque<Pair<ChatMessage, String>>()
        for (item in usable.asReversed()) {
            if (budget - item.second.length < 0 && result.isNotEmpty()) break
            budget -= item.second.length
            result.addFirst(item)
        }

        val newestUserId = history.lastOrNull { it.role == Role.USER }?.id
        return result.toList().map { (message, text) ->
            toOutbound(message to text, withImages = message.id == newestUserId)
        }
    }

    private fun toOutbound(pair: Pair<ChatMessage, String>, withImages: Boolean): RequestBodyFactory.OutboundMessage {
        val (message, text) = pair
        val images = if (withImages) {
            message.attachments.mapNotNull { attachmentStore.imageDataUrl(it) }
        } else {
            emptyList()
        }
        return RequestBodyFactory.OutboundMessage(role = message.role, text = text, imageDataUrls = images)
    }

    /** Inlines text-file attachments into the prompt; images are handed over separately. */
    private fun attachmentText(message: ChatMessage): String {
        if (message.attachments.isEmpty()) return message.content
        val builder = StringBuilder(message.content)
        message.attachments.forEach { attachment ->
            if (attachment.isImage) return@forEach
            val body = attachmentStore.readText(attachment)
            if (body != null) {
                builder.append("\n\n【附件：${attachment.name}】\n```\n").append(body).append("\n```")
            } else {
                builder.append("\n\n（附件 ${attachment.name} 无法作为文本读取，已跳过）")
            }
        }
        return builder.toString()
    }

    private companion object {
        const val PERSIST_INTERVAL_MS = 400L

        /** ~24k characters ≈ 8-12k tokens, safe for most providers' context windows. */
        const val CONTEXT_CHAR_BUDGET = 24_000
    }
}
