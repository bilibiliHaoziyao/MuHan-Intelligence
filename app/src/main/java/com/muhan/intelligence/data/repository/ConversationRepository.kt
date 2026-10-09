package com.muhan.intelligence.data.repository

import com.muhan.intelligence.data.local.ConversationDao
import com.muhan.intelligence.data.local.ConversationEntity
import com.muhan.intelligence.data.local.MessageDao
import com.muhan.intelligence.data.local.MessageEntity
import com.muhan.intelligence.domain.model.Conversation
import com.muhan.intelligence.domain.model.MessageStatus
import com.muhan.intelligence.domain.model.Role
import com.muhan.intelligence.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Single source of truth for conversation + message persistence. */
@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val attachmentStore: com.muhan.intelligence.data.local.AttachmentStore,
) {

    fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun searchConversations(query: String): Flow<List<Conversation>> =
        conversationDao.search(query).map { list -> list.map { it.toDomain() } }

    fun observeConversation(id: String): Flow<Conversation?> =
        conversationDao.observeById(id).map { it?.toDomain() }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messageDao.observeForConversation(conversationId).map { list -> list.map { it.toDomain() } }

    suspend fun createConversation(
        title: String = "新对话",
        modelName: String?,
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
        )
        conversationDao.upsert(entity)
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
    }

    suspend fun renameConversation(id: String, title: String) {
        conversationDao.rename(id, title.take(60).ifBlank { DEFAULT_TITLE }, System.currentTimeMillis())
    }

    suspend fun setPinned(id: String, pinned: Boolean) = conversationDao.setPinned(id, pinned)

    suspend fun deleteConversation(id: String) = conversationDao.deleteById(id)

    suspend fun deleteAllConversations() = conversationDao.deleteAll()

    suspend fun appendMessage(message: ChatMessage) {
        messageDao.upsert(message.toEntity())
        conversationDao.touch(message.conversationId, System.currentTimeMillis())
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

    suspend fun finishMessage(messageId: String, content: String, reasoning: String, tokenCount: Int?) {
        messageDao.upsert(
            (messageDao.getById(messageId) ?: return).copy(
                content = content,
                reasoningContent = reasoning,
                status = MessageStatus.COMPLETE.name,
                errorMessage = null,
                tokenCount = tokenCount,
            ),
        )
        messageDao.getById(messageId)?.let { conversationDao.touch(it.conversationId, System.currentTimeMillis()) }
    }

    suspend fun failMessage(messageId: String, error: String) {
        messageDao.updateStatus(messageId, MessageStatus.FAILED.name, error)
    }

    suspend fun deleteMessage(messageId: String) = messageDao.deleteById(messageId)

    /** Removes [messageId] and every message that came after it, then re-appends [messageId]. */
    suspend fun truncateFrom(messageId: String, conversationId: String) {
        messageDao.deleteAllExcept(conversationId, messageId)
    }

    suspend fun clearMessages(conversationId: String) = messageDao.deleteForConversation(conversationId)

    suspend fun loadMessage(messageId: String): ChatMessage? =
        messageDao.getById(messageId)?.toDomain()

    suspend fun loadHistory(conversationId: String): List<ChatMessage> =
        messageDao.getForConversation(conversationId).map { it.toDomain() }

    suspend fun refreshMessageCount(conversationId: String) {
        val count = conversationDao.countMessages(conversationId)
        conversationDao.getById(conversationId)?.let {
            conversationDao.upsert(it.copy(messageCount = count))
        }
    }

    private fun ConversationEntity.toDomain() = Conversation(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        modelName = modelName,
        pinned = pinned,
        messageCount = messageCount,
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
    )

    companion object {
        const val DEFAULT_TITLE = "新对话"
    }
}
