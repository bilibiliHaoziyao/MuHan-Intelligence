package com.muhan.intelligence.ui.screens.agent

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muhan.intelligence.agent.AGENT_SYSTEM_PROMPT
import com.muhan.intelligence.agent.AgentToolRegistry
import com.muhan.intelligence.audio.AudioPlayer
import com.muhan.intelligence.audio.AudioRecorder
import com.muhan.intelligence.data.local.AttachmentStore
import com.muhan.intelligence.data.local.LogRepository
import com.muhan.intelligence.data.remote.AudioRemoteDataSource
import com.muhan.intelligence.data.remote.ChatRemoteDataSource
import com.muhan.intelligence.data.repository.CloudQuotaRepository
import com.muhan.intelligence.data.repository.ConversationRepository
import com.muhan.intelligence.data.repository.ProviderRepository
import com.muhan.intelligence.data.repository.SettingsRepository
import com.muhan.intelligence.domain.model.AgentStep
import com.muhan.intelligence.domain.model.AppPreferences
import com.muhan.intelligence.domain.model.MessageAttachment
import com.muhan.intelligence.domain.model.Conversation
import com.muhan.intelligence.domain.model.GenerationSettings
import com.muhan.intelligence.domain.model.MODE_AGENT
import com.muhan.intelligence.domain.model.MessageStatus
import com.muhan.intelligence.domain.model.ProviderConfig
import com.muhan.intelligence.domain.model.Role
import com.muhan.intelligence.domain.model.ChatMessage
import com.muhan.intelligence.domain.model.StreamEvent
import com.muhan.intelligence.domain.model.isBuiltIn
import com.muhan.intelligence.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import javax.inject.Inject

data class AgentUiState(
    val conversationId: String = "",
    val conversation: Conversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isRunning: Boolean = false,
    /** 当前轮正在流式输出的正文。 */
    val liveContent: String = "",
    /** 当前轮模型输出的思考过程。 */
    val liveReasoning: String = "",
    /** 当前轮已完成的工具调用步骤。 */
    val liveSteps: List<AgentStep> = emptyList(),
    val activeProvider: ProviderConfig? = null,
    val generation: GenerationSettings = GenerationSettings(),
    val isLoading: Boolean = true,
    val errorBanner: String? = null,
    val isRecording: Boolean = false,
    val recordingSeconds: Int = 0,
    val voiceHint: String? = null,
    val speakingMessageId: String? = null,
    /** 待发送附件（图片 + 文本文件）。 */
    val pendingAttachments: List<MessageAttachment> = emptyList(),
) {
    val hasProvider: Boolean get() = activeProvider != null
    val isEmptyConversation: Boolean get() = messages.isEmpty() && !isLoading
    val canSend: Boolean
        get() = (input.isNotBlank() || pendingAttachments.isNotEmpty()) &&
            !isRunning && activeProvider != null
}

/**
 * Agent 模式（3.0.0 Dev）。
 *
 * 在手机端本地驱动一个「模型 → 工具 → 模型」的多步循环：每轮把工具定义随请求下发，
 * 模型返回 tool_calls 时由 [AgentToolRegistry] 在本地执行（网页读取、计算、笔记等），
 * 结果以 tool 消息回填后再请求下一轮，直到模型给出最终回答或达到步数上限。
 * 联网搜索（web_search）由服务端执行，不经过本地工具循环。
 */
@HiltViewModel
class AgentViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val chatRemoteDataSource: ChatRemoteDataSource,
    private val toolRegistry: AgentToolRegistry,
    private val quotaRepository: CloudQuotaRepository,
    private val audioRemoteDataSource: AudioRemoteDataSource,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val attachmentStore: AttachmentStore,
    private val logRepository: LogRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialConversationId: String =
        savedStateHandle.get<String>(Routes.ARG_CONVERSATION_ID).orEmpty()

    private val _uiState = MutableStateFlow(AgentUiState(conversationId = initialConversationId))
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private var agentJob: Job? = null
    private var recordTimerJob: Job? = null
    private var speakJob: Job? = null
    private var ttsVoice = AppPreferences.DEFAULT_TTS_VOICE

    private val json = Json { ignoreUnknownKeys = true }

    init {
        observeSettings()
        observeActiveProvider()
        ensureConversationExists()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.preferences.collect { prefs ->
                ttsVoice = prefs.ttsVoice
                _uiState.update { it.copy(generation = prefs.generation) }
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
        viewModelScope.launch {
            val id = initialConversationId.ifBlank {
                val existing = conversationRepository.observeConversations(MODE_AGENT).first()
                    .firstOrNull { it.messageCount == 0 }
                existing?.id ?: conversationRepository.createConversation(
                    modelName = providerRepository.getActiveProvider()?.modelName,
                    mode = MODE_AGENT,
                ).id
            }
            _uiState.update { it.copy(conversationId = id) }
            runCatching { conversationRepository.finalizeStreamingMessages(id) }

            launch {
                conversationRepository.observeConversation(id).collect { c ->
                    _uiState.update { it.copy(conversation = c) }
                }
            }
            conversationRepository.observeMessages(id).collect { messages ->
                _uiState.update { it.copy(messages = messages, isLoading = false) }
            }
        }
    }

    fun onInputChange(value: String) = _uiState.update { it.copy(input = value) }

    fun dismissBanner() = _uiState.update { it.copy(errorBanner = null) }

    // ------------------------------------------------------------------ 附件

    fun addAttachments(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val imported = uris.mapNotNull { attachmentStore.import(it) }
            if (imported.isEmpty()) {
                _uiState.update { it.copy(errorBanner = "这个文件读不了，换一个试试。") }
                return@launch
            }
            _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + imported) }
        }
    }

    fun removeAttachment(id: String) = _uiState.update {
        it.copy(pendingAttachments = it.pendingAttachments.filterNot { a -> a.id == id })
    }

    suspend fun observeHistory(query: String): List<Conversation> =
        if (query.isBlank()) {
            conversationRepository.observeConversations(MODE_AGENT).first()
        } else {
            conversationRepository.searchConversations(query, MODE_AGENT).first()
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

    // ------------------------------------------------------------------ 发送

    fun send() {
        val state = _uiState.value
        if (state.isRunning) return
        val text = state.input.trim()
        if (text.isEmpty() && state.pendingAttachments.isEmpty()) return
        if (state.activeProvider == null) {
            _uiState.update { it.copy(errorBanner = "还没有模型服务，先去设置里加一个。") }
            return
        }

        viewModelScope.launch {
            val provider = _uiState.value.activeProvider ?: return@launch

            // 与 Chat 一致：额度以云端为准。
            if (provider.isBuiltIn) {
                val quota = runCatching { quotaRepository.current() }.getOrElse { e ->
                    _uiState.update {
                        it.copy(errorBanner = "拿不到云端额度：${e.message ?: "检查下网络"}")
                    }
                    return@launch
                }
                if (quota.isExhausted) {
                    _uiState.update { it.copy(errorBanner = "本月额度用完了，每月 1 日恢复") }
                    return@launch
                }
            }

            val conversationId = _uiState.value.conversationId
            val attachments = _uiState.value.pendingAttachments
            conversationRepository.appendMessage(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = Role.USER,
                    content = text,
                    attachments = attachments,
                ),
            )
            if (text.isNotBlank()) {
                conversationRepository.updateTitleFromFirstMessage(conversationId, text)
            }
            _uiState.update { it.copy(input = "", pendingAttachments = emptyList()) }

            val history = conversationRepository.loadHistory(conversationId)
            runAgent(provider, history)
        }
    }

    /** 把文本附件内联进用户消息（图片单独以多模态 part 发送）。 */
    private fun userContentText(message: ChatMessage): String {
        if (message.attachments.isEmpty()) return message.content
        val builder = StringBuilder(message.content)
        message.attachments.forEach { a ->
            if (a.isImage) return@forEach
            val body = attachmentStore.readText(a)
            if (body != null) {
                builder.append("\n\n【附件：${a.name}】\n```\n").append(body).append("\n```")
            } else {
                builder.append("\n\n（附件 ${a.name} 无法作为文本读取，已跳过）")
            }
        }
        return builder.toString()
    }

    private class ToolCallAcc {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    private fun runAgent(provider: ProviderConfig, history: List<ChatMessage>) {
        agentJob?.cancel()
        agentJob = viewModelScope.launch {
            val apiKey = providerRepository.getApiKey(provider.id).orEmpty()
            val settings = _uiState.value.generation
            val conversationId = _uiState.value.conversationId
            _uiState.update {
                it.copy(
                    isRunning = true,
                    liveContent = "",
                    liveReasoning = "",
                    liveSteps = emptyList(),
                    errorBanner = null,
                )
            }

            val working = mutableListOf<JsonObject>()
            working += buildJsonObject {
                put("role", "system")
                put("content", AGENT_SYSTEM_PROMPT)
            }
            val newestUserId = history.lastOrNull { it.role == Role.USER }?.id
            history.forEach { m ->
                when {
                    m.role == Role.USER && (m.content.isNotBlank() || m.attachments.isNotEmpty()) -> {
                        val text = userContentText(m)
                        val images = if (m.id == newestUserId) {
                            m.attachments.filter { it.isImage }
                                .mapNotNull { attachmentStore.imageDataUrl(it) }
                        } else {
                            emptyList()
                        }
                        working += buildJsonObject {
                            put("role", "user")
                            if (images.isEmpty()) {
                                put("content", text)
                            } else {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", text)
                                    }
                                    images.forEach { url ->
                                        addJsonObject {
                                            put("type", "image_url")
                                            putJsonObject("image_url") { put("url", url) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    m.role == Role.ASSISTANT && m.content.isNotBlank() -> {
                        working += buildJsonObject {
                            put("role", "assistant")
                            put("content", m.content)
                        }
                    }
                }
            }

            val steps = mutableListOf<AgentStep>()
            val allReasoning = StringBuilder()
            var totalTokens = 0
            var failure: String? = null
            var finalText = ""
            var finishedNaturally = false

            try {
                for (round in 0 until MAX_ROUNDS) {
                    val contentBuf = StringBuilder()
                    val calls = LinkedHashMap<Int, ToolCallAcc>()
                    // 3.1.0：流式节流，避免每个 token 触发整页重组。
                    var lastPublish = 0L

                    chatRemoteDataSource.streamChat(
                        provider = provider,
                        apiKey = apiKey,
                        messages = emptyList(),
                        systemPrompt = "",
                        temperature = settings.temperature,
                        topP = settings.topP,
                        maxTokens = settings.maxTokens,
                        toolsJson = toolRegistry.toolsJson(),
                        rawMessagesJson = JsonArray(working).toString(),
                    ).collect { event ->
                        when (event) {
                            is StreamEvent.ContentDelta -> {
                                contentBuf.append(event.text)
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastPublish >= STREAM_PUBLISH_INTERVAL_MS) {
                                    lastPublish = now
                                    _uiState.update { it.copy(liveContent = contentBuf.toString()) }
                                }
                            }
                            is StreamEvent.ReasoningDelta -> {
                                allReasoning.append(event.text)
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastPublish >= STREAM_PUBLISH_INTERVAL_MS) {
                                    lastPublish = now
                                    _uiState.update { it.copy(liveReasoning = allReasoning.toString()) }
                                }
                            }
                            is StreamEvent.ToolCallDelta -> {
                                val acc = calls.getOrPut(event.index) { ToolCallAcc() }
                                event.id?.let { if (it.isNotBlank()) acc.id = it }
                                event.name?.let { if (it.isNotBlank()) acc.name = it }
                                event.argumentsDelta?.let { acc.arguments.append(it) }
                            }
                            is StreamEvent.Usage ->
                                totalTokens += event.promptTokens + event.completionTokens
                            is StreamEvent.Failed -> failure = event.message
                            else -> Unit
                        }
                    }

                    // 补发最后一帧，避免节流吞掉尾段。
                    if (contentBuf.isNotEmpty()) {
                        _uiState.update { it.copy(liveContent = contentBuf.toString()) }
                    }

                    if (failure != null) break
                    if (calls.isEmpty()) {
                        finalText = contentBuf.toString()
                        finishedNaturally = true
                        break
                    }

                    // 回填 assistant.tool_calls
                    working += buildJsonObject {
                        put("role", "assistant")
                        put("content", contentBuf.toString())
                        putJsonArray("tool_calls") {
                            calls.forEach { (index, acc) ->
                                addJsonObject {
                                    put("id", acc.id.ifBlank { "call_$index" })
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", acc.name)
                                        put("arguments", acc.arguments.toString().ifBlank { "{}" })
                                    }
                                }
                            }
                        }
                    }

                    // 逐个执行本地工具
                    for ((index, acc) in calls) {
                        val argsText = acc.arguments.toString().ifBlank { "{}" }
                        val args = runCatching { json.parseToJsonElement(argsText).jsonObject }
                            .getOrElse { JsonObject(emptyMap()) }
                        val pending = AgentStep(name = acc.name, arguments = argsText, result = "")
                        _uiState.update { it.copy(liveSteps = steps + pending) }

                        val result = toolRegistry.execute(acc.name, args)
                        val ok = !result.startsWith("工具执行失败") && !result.startsWith("未知工具")
                        steps += pending.copy(result = result.take(1200), ok = ok)
                        _uiState.update { it.copy(liveSteps = steps.toList()) }

                        working += buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", acc.id.ifBlank { "call_$index" })
                            put("content", result)
                        }
                    }
                }

                val content = finalText.ifBlank {
                    if (finishedNaturally) "" else "已经走了 $MAX_ROUNDS 步，还没做完，可以继续追问。"
                }

                persistAssistant(
                    conversationId = conversationId,
                    content = content,
                    reasoning = allReasoning.toString(),
                    steps = steps,
                    tokenCount = totalTokens,
                    provider = provider,
                    status = if (failure == null) MessageStatus.COMPLETE else MessageStatus.FAILED,
                    error = failure,
                )

                if (provider.isBuiltIn && totalTokens > 0) {
                    runCatching { quotaRepository.consume(totalTokens) }
                }
                failure?.let { _uiState.update { s -> s.copy(errorBanner = "执行中断了：$it") } }
            } catch (ce: CancellationException) {
                // 用户停止：保留已产生的部分内容。
                val partial = _uiState.value.liveContent
                if (partial.isNotBlank() || steps.isNotEmpty()) {
                    persistAssistant(
                        conversationId = conversationId,
                        content = partial.ifBlank { "（已停止）" },
                        reasoning = allReasoning.toString(),
                        steps = steps,
                        tokenCount = totalTokens,
                        provider = provider,
                        status = MessageStatus.COMPLETE,
                        error = null,
                    )
                }
                throw ce
            } catch (t: Throwable) {
                logRepository.warn("Agent", "执行异常：${t.message}", t)
                _uiState.update { it.copy(errorBanner = t.message ?: "执行失败") }
            } finally {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        liveContent = "",
                        liveReasoning = "",
                        liveSteps = emptyList(),
                    )
                }
                agentJob = null
            }
        }
    }

    private suspend fun persistAssistant(
        conversationId: String,
        content: String,
        reasoning: String,
        steps: List<AgentStep>,
        tokenCount: Int,
        provider: ProviderConfig,
        status: MessageStatus,
        error: String?,
    ) {
        conversationRepository.appendMessage(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = Role.ASSISTANT,
                content = content,
                reasoningContent = reasoning,
                status = status,
                errorMessage = error,
                modelName = provider.displayModelLabel,
                tokenCount = tokenCount.takeIf { it > 0 },
                agentSteps = steps,
            ),
        )
        conversationRepository.refreshMessageCount(conversationId)
    }

    fun stop() {
        agentJob?.cancel()
        agentJob = null
        _uiState.update { it.copy(isRunning = false) }
    }

    // ------------------------------------------------------------------ 语音输入

    fun startRecording() {
        if (_uiState.value.isRecording) return
        if (!audioRecorder.start()) {
            _uiState.update { it.copy(voiceHint = "打不开麦克风，检查一下权限") }
            return
        }
        _uiState.update { it.copy(isRecording = true, recordingSeconds = 0, voiceHint = null) }
        recordTimerJob = viewModelScope.launch {
            var s = 0
            while (true) {
                delay(1000)
                s++
                _uiState.update { it.copy(recordingSeconds = s) }
            }
        }
    }

    fun stopRecordingAndTranscribe() {
        if (!_uiState.value.isRecording) return
        recordTimerJob?.cancel()
        _uiState.update { it.copy(isRecording = false) }
        val wav = audioRecorder.stop()
        if (wav == null) {
            _uiState.update { it.copy(voiceHint = "太短了，再说一次") }
            return
        }
        _uiState.update { it.copy(voiceHint = "正在识别…") }
        viewModelScope.launch {
            runCatching { audioRemoteDataSource.transcribe(wav) }
                .fold(
                    onSuccess = { text ->
                        val trimmed = text.trim()
                        if (trimmed.isEmpty()) {
                            _uiState.update { it.copy(voiceHint = "没听清，再说一次") }
                        } else {
                            val current = _uiState.value.input
                            onInputChange(if (current.isBlank()) trimmed else "$current$trimmed")
                            _uiState.update { it.copy(voiceHint = null) }
                        }
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(voiceHint = e.message ?: "识别失败，再试一次") }
                    },
                )
        }
    }

    fun cancelRecording() {
        recordTimerJob?.cancel()
        audioRecorder.cancel()
        _uiState.update { it.copy(isRecording = false, recordingSeconds = 0) }
    }

    // ------------------------------------------------------------------ 语音输出

    fun speakMessage(messageId: String) {
        val current = _uiState.value
        if (current.speakingMessageId == messageId) {
            stopSpeaking()
            return
        }
        stopSpeaking()
        val message = current.messages.firstOrNull { it.id == messageId } ?: return
        if (message.role != Role.ASSISTANT || message.content.isBlank()) return
        val text = speechText(message.content)
        if (text.isBlank()) return

        speakJob?.cancel()
        speakJob = viewModelScope.launch {
            _uiState.update { it.copy(speakingMessageId = messageId) }
            val wav = runCatching { audioRemoteDataSource.synthesize(text, ttsVoice) }
                .getOrElse { e ->
                    _uiState.update { it.copy(speakingMessageId = null, errorBanner = e.message ?: "朗读失败") }
                    return@launch
                }
            audioPlayer.play(wav) {
                _uiState.update { it.copy(speakingMessageId = null) }
            }
        }
    }

    fun stopSpeaking() {
        speakJob?.cancel()
        speakJob = null
        audioPlayer.stop()
        _uiState.update { it.copy(speakingMessageId = null) }
    }

    private fun speechText(markdown: String): String {
        var t = markdown
        t = t.replace(Regex("```[\\s\\S]*?```"), "（省略代码）")
        t = t.replace(Regex("`([^`]*)`"), "$1")
        t = t.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
        t = t.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        t = t.replace(Regex("(?m)^#{1,6}\\s*"), "")
        t = t.replace(Regex("(?m)^>\\s?"), "")
        t = t.replace(Regex("(?m)^[-*+]\\s+"), "")
        t = t.replace(Regex("(?m)^\\d+\\.\\s+"), "")
        t = t.replace(Regex("\\*\\*([^*]*)\\*\\*"), "$1")
        t = t.replace(Regex("__([^_]*)__"), "$1")
        t = t.replace(Regex("\\*([^*]*)\\*"), "$1")
        return t.trim()
    }

    override fun onCleared() {
        recordTimerJob?.cancel()
        speakJob?.cancel()
        agentJob?.cancel()
        audioRecorder.cancel()
        audioPlayer.release()
        super.onCleared()
    }

    private companion object {
        const val MAX_ROUNDS = 8

        /** 3.1.0：流式上屏间隔，合并高频 token。 */
        const val STREAM_PUBLISH_INTERVAL_MS = 60L
    }
}
