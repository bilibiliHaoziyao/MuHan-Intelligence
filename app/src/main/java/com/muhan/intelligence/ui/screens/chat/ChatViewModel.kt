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
import kotlinx.coroutines.flow.combine
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

/**
 * 消息显示的单一事实来源是 Room 数据库（按 created_at 排序）。
 *
 * 0.5.1 重构：此前「内存列表 + 数据库列表」双事实来源靠 reconcile 合并，是反复出现
 * 「回答消失 / 记录错乱 / 重复崩溃」的总根源。现在渲染列表完全由数据库驱动，内存只
 * 保存「正在流式的那一条」消息的实时内容（[streamingMessage]），通过
 * [applyStreamingOverride] 覆盖到数据库列表上。这样：
 *  - 顺序永远由数据库 created_at 决定，绝不错乱；
 *  - 不可能出现重复 id（数据库主键唯一），杜绝 LazyColumn 重复 key 崩溃；
 *  - 删除/编辑只写数据库，Room 自动 emit 最新列表，不存在幽灵消息。
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: com.muhan.intelligence.data.repository.SettingsRepository,
    private val attachmentStore: AttachmentStore,
    private val logRepository: com.muhan.intelligence.data.local.LogRepository,
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

    /**
     * 正在生成的那条消息的实时内容（内存覆盖层）。非空时优先于数据库版本渲染，
     * 这样流式 token 不被节流的数据库写入拖累。生成结束后置为 COMPLETE 版本，
     * 待数据库追平后由 [applyStreamingOverride] 自动放下覆盖，避免闪烁。
     */
    private val streamingMessage = MutableStateFlow<ChatMessage?>(null)

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

            logRepository.info("Chat", "打开会话 conversationId=$id（来源：${if (initialConversationId.isBlank()) "新建/复用空会话" else "指定会话"}）")

            // 0.5.1：清掉上次异常退出遗留的 STREAMING 消息，避免永久「正在生成」转圈。
            runCatching { conversationRepository.finalizeStreamingMessages(id) }

            launch {
                conversationRepository.observeConversation(id).collect { conversation ->
                    _uiState.update { it.copy(conversation = conversation) }
                }
            }

            // 数据库消息（顺序/持久化权威） × 流式覆盖层（正在生成那条的实时内容）。
            combine(conversationRepository.observeMessages(id), streamingMessage) { dbMessages, live ->
                applyStreamingOverride(dbMessages, live)
            }
                .catch { e ->
                    _uiState.update { it.copy(isLoading = false, errorBanner = e.message) }
                }
                .collect { merged ->
                    _uiState.update { it.copy(messages = merged, isLoading = false) }
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
            // 清掉可能仍指向被删消息的流式覆盖，避免它被 override 复活。
            streamingMessage.value = null
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
            streamingMessage.value = null
            // 删掉这条及之后的所有消息（保留之前的），再追加编辑后的这条。
            conversationRepository.truncateFrom(message.id, state.conversationId)

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
            try {
                val conversationId = state.conversationId
                val attachments = state.pendingAttachments
                val userMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = Role.USER,
                    content = text,
                    attachments = attachments,
                )
                // 只写数据库；渲染由 Room emission 驱动，无需也不应再手动改内存列表，
                // 否则同一条消息会被内存 + 数据库双写，造成重复 id 崩溃（0.5.0 的回归）。
                conversationRepository.appendMessage(userMessage)
                if (text.isNotBlank()) {
                    conversationRepository.updateTitleFromFirstMessage(conversationId, text)
                }
                logRepository.info(
                    "Chat",
                    "用户发送消息 conversationId=$conversationId，正文长度=${text.length}，附件=${attachments.size}",
                )
                _uiState.update { it.copy(input = "", pendingAttachments = emptyList()) }

                val history = conversationRepository.loadHistory(conversationId)
                generate(history)
            } catch (t: Throwable) {
                // 0.2.0 Fix: 发送链路任何异常只提示，不闪退。
                _uiState.update {
                    it.copy(errorBanner = t.message ?: "发送失败，请稍后重试。")
                }
            }
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
            // 占位气泡只进覆盖层（不落库），渲染时追加到数据库列表末尾。
            streamingMessage.value = ChatMessage(
                id = placeholderId,
                conversationId = conversationId,
                role = Role.ASSISTANT,
                content = "",
                status = MessageStatus.STREAMING,
                modelName = state.activeProvider?.imageModel,
            )

            val result = providerRepository.generateImage(
                provider = state.activeProvider!!,
                prompt = prompt,
            )

            result.fold(
                onSuccess = { reply ->
                    runCatching {
                        val imagePath = if (reply.isBase64) {
                            saveBase64Image(reply.source)
                        } else {
                            reply.source
                        }
                        val done = ChatMessage(
                            id = placeholderId,
                            conversationId = conversationId,
                            role = Role.ASSISTANT,
                            content = reply.revisedPrompt.orEmpty(),
                            status = MessageStatus.COMPLETE,
                            modelName = state.activeProvider?.imageModel,
                            imageUrl = imagePath,
                        )
                        conversationRepository.appendMessage(done)
                        conversationRepository.refreshMessageCount(conversationId)
                        // 已落库，覆盖层清空，渲染直接以数据库为准，避免残留覆盖。
                        streamingMessage.value = null
                    }.onFailure { t ->
                        streamingMessage.value = null
                        _uiState.update {
                            it.copy(errorBanner = t.message ?: "生图结果保存失败。")
                        }
                    }
                },
                onFailure = { error ->
                    runCatching { conversationRepository.deleteMessage(placeholderId) }
                    streamingMessage.value = null
                    _uiState.update {
                        it.copy(errorBanner = error.message ?: "生图失败，请稍后重试。")
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

        logRepository.info("Chat", "用户手动停止生成")

        val live = streamingMessage.value
        viewModelScope.launch {
            if (live != null && live.status == MessageStatus.STREAMING) {
                conversationRepository.finishMessage(
                    messageId = live.id,
                    content = live.content,
                    reasoning = live.reasoningContent,
                    tokenCount = null,
                )
                streamingMessage.value = live.copy(status = MessageStatus.COMPLETE)
            }
            _uiState.update { it.copy(isGenerating = false) }
        }
    }

    /** Creates the assistant placeholder and drives the stream into it. */
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

        // 占位气泡既落库（保证数据库里始终有这条，杜绝「回答消失」），也放进覆盖层
        // （让流式 token 实时上屏，不被节流的数据库写入拖累）。
        streamingMessage.value = placeholder
        _uiState.update { it.copy(isGenerating = true, errorBanner = null) }

        logRepository.info(
            "Chat",
            "开始生成回复 conversationId=${state.conversationId}，服务商=${provider.displayName}，" +
                "模型=${provider.modelName}，历史消息数=${history.size}，深度思考=${extras.thinkingEnabled}，联网搜索=${extras.webSearchEnabled}",
        )

        generationJob = viewModelScope.launch {
            // 0.4.0：先把占位落库。这样 updateStreamingContent / finishMessage 都有行可改，
            // 数据库快照始终包含这条回复。
            conversationRepository.appendMessage(placeholder)
            var content = StringBuilder()
            var reasoning = StringBuilder()
            var tokenCount: Int? = null
            var failureMessage: String? = null

            try {
                // 0.2.0 Fix: 出站构建（附件读取/base64 编码）也放进保护内——
                // 此前它在外面，任何异常都会以未捕获形式杀死进程。
                val outbound = buildOutboundMessages(history, settings)
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
                        is StreamEvent.Failed -> {
                            failureMessage = event.message
                            logRepository.warn("Chat", "服务商返回失败事件：${event.message}")
                        }
                    }
                }
            } catch (ce: CancellationException) {
                // User pressed "stop" — rethrow so structured cancellation works.
                throw ce
            } catch (t: Throwable) {
                // 0.1.0 crashed here: any exception escaping the flow (JSON, IO,
                // provider quirks) had no handler and killed the whole process.
                failureMessage = t.message ?: t.javaClass.simpleName
                logRepository.warn("Chat", "流式生成失败：$failureMessage", t)
            }

            persistJob?.cancel()
            val finalContent = content.toString()
            val finalReasoning = reasoning.toString()

            if (failureMessage != null && finalContent.isBlank()) {
                runCatching {
                    conversationRepository.failMessage(assistantId, failureMessage!!)
                    conversationRepository.deleteMessage(assistantId)
                }
                streamingMessage.value = null
                _uiState.update {
                    it.copy(isGenerating = false, errorBanner = failureMessage)
                }
            } else {
                // 0.2.0 Fix2：空回复写入日志，便于诊断「模型不回复」类问题。
                if (finalContent.isBlank() && finalReasoning.isBlank()) {
                    logRepository.warn(
                        "Chat",
                        "本次生成结束但内容为空（failure=$failureMessage，model=${provider.modelName}）",
                    )
                }
                runCatching {
                    conversationRepository.finishMessage(
                        messageId = assistantId,
                        content = finalContent,
                        reasoning = finalReasoning,
                        tokenCount = tokenCount,
                    )
                    conversationRepository.refreshMessageCount(state.conversationId)
                    logRepository.info(
                        "Chat",
                        "生成完成 conversationId=${state.conversationId}，正文长度=${finalContent.length}，" +
                            "思考长度=${finalReasoning.length}，token=${tokenCount ?: "未知"}",
                    )
                }
                // 数据库已写入最终态，覆盖层直接清空——此后 applyStreamingOverride 返回纯
                // 数据库列表，绝不残留任何覆盖，从根上杜绝「生成结束后上方记录被清空 /
                // 回答消失」的回归。
                streamingMessage.value = null
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        infoBanner = failureMessage?.let { m -> "生成中断：$m" },
                    )
                }
            }
            generationJob = null
        }
    }

    /** Pushes the growing buffer into the streaming overlay and schedules a throttled DB write. */
    private fun publishStreaming(id: String, content: String, reasoning: String) {
        streamingMessage.update { live ->
            if (live?.id == id) live.copy(content = content, reasoningContent = reasoning) else live
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

/**
 * 把「正在流式的那条消息」的实时内容覆盖到数据库列表上，得到最终渲染列表。
 *
 * 规则（0.5.1，顺序与持久化都以数据库为准）：
 * 1. [live] 为 null → 直接返回数据库列表。
 * 2. 数据库里还没有这条（占位尚未落库的瞬间）→ 追加到末尾（它是最新一条）。
 * 3. [live] 已完结且数据库版本也已是 [MessageStatus.COMPLETE] → 返回数据库列表，
 *    相当于自动放下覆盖层，避免生成结束时的内容闪烁。
 * 4. 其余（正在流式，或数据库版本滞后）→ 用 [live] 覆盖数据库中同 id 的那条。
 */
internal fun applyStreamingOverride(
    db: List<ChatMessage>,
    live: ChatMessage?,
): List<ChatMessage> {
    if (live == null) return db
    val dbVersion = db.firstOrNull { it.id == live.id } ?: return db + live
    if (live.status != MessageStatus.STREAMING && dbVersion.status == MessageStatus.COMPLETE) return db
    return db.map { if (it.id == live.id) live else it }
}
