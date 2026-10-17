package com.muhan.intelligence.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query(
        """
        SELECT * FROM conversations
        WHERE mode = :mode
        ORDER BY pinned DESC, updated_at DESC
        """,
    )
    fun observeByMode(mode: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query(
        """
        SELECT * FROM conversations
        WHERE mode = :mode AND title LIKE '%' || :query || '%'
        ORDER BY pinned DESC, updated_at DESC
        """,
    )
    fun searchByMode(mode: String, query: String): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :id")
    suspend fun countMessages(id: String): Int

    /**
     * 只更新消息计数，绝不用 [upsert]：后者是 REPLACE 策略（先删后插整行），
     * 而 messages 表对 conversations 建有 ON DELETE CASCADE 外键，DELETE 整行
     * conversation 会级联删光该会话全部消息——这正是「生成完成后记录消失」的根因。
     */
    @Query("UPDATE conversations SET message_count = :count WHERE id = :id")
    suspend fun updateMessageCount(id: String, count: Int)
}

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    suspend fun getForConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("UPDATE messages SET content = :content, reasoning_content = :reasoning, status = :status WHERE id = :id")
    suspend fun updateStreamingContent(
        id: String,
        content: String,
        reasoning: String,
        status: String,
    )

    @Query("UPDATE messages SET status = :status, error_message = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String?)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    // 0.5.1 修复：旧实现 deleteAllExcept 是 `id != keepId`（只保留目标、删掉其余所有），
    // 与「删除目标及其之后」的本意完全相反，导致每次编辑消息都清空整个对话历史。
    // 正确语义：删除目标消息及 created_at 在其之后的所有消息，保留之前的。
    @Query(
        """
        DELETE FROM messages
        WHERE conversation_id = :conversationId
          AND created_at >= COALESCE((SELECT created_at FROM messages WHERE id = :messageId), 0)
        """,
    )
    suspend fun deleteFrom(conversationId: String, messageId: String)

    // 0.5.1：应用若在流式中被杀，数据库会残留 STREAMING 状态的消息，重启后永久转圈。
    // 加载会话时把所有遗留的 STREAMING 消息标记为 COMPLETE（内容为已持久化的部分）。
    @Query("UPDATE messages SET status = 'COMPLETE' WHERE conversation_id = :conversationId AND status = 'STREAMING'")
    suspend fun finalizeStreaming(conversationId: String)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteForConversation(conversationId: String)
}

@Dao
interface ProviderDao {

    @Query("SELECT * FROM providers ORDER BY created_at ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE is_active = 1 LIMIT 1")
    fun observeActive(): Flow<ProviderEntity?>

    @Query("SELECT * FROM providers WHERE is_active = 1 LIMIT 1")
    suspend fun getActive(): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ProviderEntity)

    @Query("UPDATE providers SET is_active = 0")
    suspend fun clearActive()

    @Query("UPDATE providers SET is_active = 1 WHERE id = :id")
    suspend fun markActive(id: String)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Transaction
    suspend fun activate(provider: ProviderEntity) {
        upsert(provider.copy(isActive = false))
        clearActive()
        markActive(provider.id)
    }
}
