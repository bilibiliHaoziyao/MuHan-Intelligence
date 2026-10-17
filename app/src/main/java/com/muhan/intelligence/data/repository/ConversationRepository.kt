package com.muhan.intelligence.data.repository

import com.muhan.intelligence.data.local.ConversationDao
import com.muhan.intelligence.data.local.ConversationEntity
import com.muhan.intelligence.data.local.MessageDao
import com.muhan.intelligence.data.local.MessageEntity
import com.muhan.intelligence.data.local.LogRepository
import com.muhan.intelligence.domain.model.AgentStep
import com.muhan.intelligence.domain.model.Conversation
import com.muhan.intelligence.domain.model.MessageStatus
import com.muhan.intelligence.domain.model.MODE_CHAT
import com.muhan.intelligence.domain.model.Role
import com.muhan.intelligence.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Single source of truth for conversation + message persistence. */
@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val attachmentStore: com.muhan.intelligence.data.local.AttachmentStore,
    private val logRepository: LogRepository,
    private val syncRepository: SyncRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun observeConversations(mode: String = MODE_CHAT): Flow<List<Conversation>> =
        conversationDao.observeByMode(mode).map { list -> list.map { it.toDomain() } }

    fun searchConversations(query: String, mode: String = MODE_CHAT): Flow<List<Conversation>> =
        conversationDao.searchByMode(mode, query).map { list -> list.map { it.toDomain() } }

    fun observeConversation(id: String): Flow<Conversation?> =
        conversationDao.observeById(id).map { it?.toDomain() }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messageDao.observeForConversation(conversationId).map { list -> list.map { it.toDomain() } }

    suspend fun createConversation(
        title: String = "新对话",
        modelName: String?,
        mode: String = MODE_CHAT,
    ): Conversation {
        val now = System.currentTimeMillis()
        val entity = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
            modelName = modelName,
            pinned = false,
            messageCount = 0,
            mode = mode,
        )
        conversationDao.upsert(entity)
        syncRepository.requestSync()
        return entity.toDomain()
    }

    suspend fun getConversation(id: String): Conversation? = conversationDao.getById(id)?.toDomain()

    suspend fun updateTitleFromFirstMessage(conversationId: String, firstUserMessage: String) {
        val conversation = conversationDao.getById(conversationId) ?: return
        if (conversation.title != DEFAULT_TITLE) return
        val title = firstUserMessage
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(24)
            .ifBlank { DEFAULT_TITLE }
        conversationDao.rename(conversationId, title, System.currentTimeMillis())
        syncRepository.requestSync()
    }

    suspend fun renameConversation(id: String, title: String) {
        conversationDao.rename(id, title.take(60).ifBlank { DEFAULT_TITLE }, System.currentTimeMillis())
        syncRepository.requestSync()
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        conversationDao.setPinned(id, pinned)
        syncRepository.requestSync()
    }

    suspend fun deleteConversation(id: String) {
        val now = System.currentTimeMillis()
        conversationDao.softDelete(id, now)
        messageDao.softDeleteForConversation(id, now)
        syncRepository.requestSync()
    }

    suspend fun deleteAllConversations() {
        val now = System.currentTimeMillis()
        conversationDao.softDeleteAll(now)
        messageDao.softDeleteAll(now)
        syncRepository.requestSync()
    }

    suspend fun appendMessage(message: ChatMessage) {
        messageDao.upsert(message.toEntity())
        conversationDao.touch(message.conversationId, System.currentTimeMillis())
        val total = conversationDao.countMessages(message.conversationId)
        logRepository.info("DB", "追加消息 id=${message.id}，角色=${message.role}，会话=${message.conversationId}，会话当前消息数=$total")
        syncRepository.requestSync()
    }

    suspend fun updateStreamingContent(
        messageId: String,
        content: String,
        reasoning: String,
    ) {
        messageDao.updateStreamingContent(
            id = messageId,
            content = content,
            reasoning = reasoning,
            status = com.muhan.intelligence.domain.model.MessageStatus.STREAMING.name,
        )
    }

    suspend fun finishMessage(
        messageId: String,
        content: String,
        reasoning: String,
        tokenCount: Int?,
        agentSteps: List<AgentStep> = emptyList(),
    ) {
        val entity = messageDao.getById(messageId) ?: run {
            logRepository.warn("DB", "finishMessage 未找到占位消息 id=$messageId，无法落库（可能导致回答不显示）")
            return
        }
        messageDao.upsert(
            entity.copy(
                content = content,
                reasoningContent = reasoning,
                status = MessageStatus.COMPLETE.name,
                errorMessage = null,
                tokenCount = tokenCount,
                agentTrace = runCatching { json.encodeToString(agentSteps) }.getOrDefault("[]"),
                syncState = "PENDING",
                updatedAt = System.currentTimeMillis(),
            ),
        )
        conversationDao.touch(entity.conversationId, System.currentTimeMillis())
        val total = conversationDao.countMessages(entity.conversationId)
        logRepository.info("DB", "AI回复落库完成 id=$messageId，会话=${entity.conversationId}，会话当前消息数=$total")
        syncRepository.requestSync()
    }

    suspend fun failMessage(messageId: String, error: String) {
        messageDao.updateStatus(messageId, MessageStatus.FAILED.name, error, System.currentTimeMillis())
        syncRepository.requestSync()
    }

    suspend fun deleteMessage(messageId: String) {
        messageDao.softDelete(messageId, System.currentTimeMillis())
        logRepository.info("DB", "删除消息 id=$messageId")
        syncRepository.requestSync()
    }

    /** Removes [messageId] and every message that came after it (keeps the ones before). */
    suspend fun truncateFrom(messageId: String, conversationId: String) {
        messageDao.softDeleteFrom(conversationId, messageId, System.currentTimeMillis())
        logRepository.info("DB", "截断消息（含其后）messageId=$messageId，会话=$conversationId")
        syncRepository.requestSync()
    }

    /** Marks any leftover STREAMING rows (e.g. after a mid-stream process kill) as COMPLETE. */
    suspend fun finalizeStreamingMessages(conversationId: String) =
        messageDao.finalizeStreaming(conversationId)

    suspend fun clearMessages(conversationId: String) {
        messageDao.softDeleteForConversation(conversationId, System.currentTimeMillis())
        syncRepository.requestSync()
    }

    /** 退出登录时清空本地缓存（云端为权威数据源）。 */
    suspend fun clearLocalData() = syncRepository.clearLocalCache()

    suspend fun loadMessage(messageId: String): ChatMessage? =
        messageDao.getById(messageId)?.toDomain()

    suspend fun loadHistory(conversationId: String): List<ChatMessage> =
        messageDao.getForConversation(conversationId).map { it.toDomain() }.also { list ->
            val roles = list.groupingBy { it.role }.eachCount()
            logRepository.info("DB", "加载历史 conversationId=$conversationId，返回条数=${list.size}，角色分布=$roles")
        }

    suspend fun refreshMessageCount(conversationId: String) {
        val count = conversationDao.countMessages(conversationId)
        // 关键修复：只 UPDATE 计数，绝不能用 upsert（REPLACE 语义会先删后插整行
        // conversation，而 messages 表对其建有 ON DELETE CASCADE，从而级联删光该会话
        // 全部消息——这正是「生成完成后记录消失」的根因）。
        conversationDao.updateMessageCount(conversationId, count)
        logRepository.info("DB", "刷新消息计数 conversationId=$conversationId，count=$count")
    }

    private fun ConversationEntity.toDomain() = Conversation(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        modelName = modelName,
        pinned = pinned,
        messageCount = messageCount,
        mode = mode,
    )

    private fun MessageEntity.toDomain() = ChatMessage(
        id = id,
        conversationId = conversationId,
        role = Role.fromWire(role),
        content = content,
        reasoningContent = reasoningContent,
        status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.COMPLETE),
        errorMessage = errorMessage,
        modelName = modelName,
        createdAt = createdAt,
        tokenCount = tokenCount,
        attachments = attachmentStore.deserialize(attachments),
        imageUrl = imageUrl,
        agentSteps = runCatching {
            json.decodeFromString<List<AgentStep>>(agentTrace)
        }.getOrDefault(emptyList()),
    )

    private fun ChatMessage.toEntity() = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role.wireValue,
        content = content,
        reasoningContent = reasoningContent,
        status = status.name,
        errorMessage = errorMessage,
        modelName = modelName,
        createdAt = createdAt,
        tokenCount = tokenCount,
        attachments = attachmentStore.serialize(attachments),
        imageUrl = imageUrl,
        agentTrace = runCatching { json.encodeToString(agentSteps) }.getOrDefault("[]"),
        syncState = "PENDING",
        deleted = false,
    )

    companion object {
        const val DEFAULT_TITLE = "新对话"
    }
}
