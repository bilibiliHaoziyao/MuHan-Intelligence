package com.muhan.intelligence.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query(
        """
        SELECT * FROM conversations
        WHERE mode = :mode AND deleted = 0
        ORDER BY pinned DESC, updated_at DESC
        """,
    )
    fun observeByMode(mode: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getByIdAny(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id AND deleted = 0 LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id AND deleted = 0 LIMIT 1")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query(
        """
        SELECT * FROM conversations
        WHERE mode = :mode AND deleted = 0 AND title LIKE '%' || :query || '%'
        ORDER BY pinned DESC, updated_at DESC
        """,
    )
    fun searchByMode(mode: String, query: String): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNew(conversation: ConversationEntity)

    @Update
    suspend fun updateExisting(conversation: ConversationEntity)

    @Query(
        """
        UPDATE conversations
           SET title = :title, updated_at = :updatedAt, sync_state = 'PENDING'
         WHERE id = :id
        """,
    )
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET pinned = :pinned, sync_state = 'PENDING' WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    /** 3.1.0：软删除，保留墓碑供同步。 */
    @Query(
        """
        UPDATE conversations
           SET deleted = 1, updated_at = :updatedAt, sync_state = 'PENDING'
         WHERE id = :id
        """,
    )
    suspend fun softDelete(id: String, updatedAt: Long)

    @Query(
        """
        UPDATE conversations
           SET deleted = 1, updated_at = :updatedAt, sync_state = 'PENDING'
         WHERE deleted = 0
        """,
    )
    suspend fun softDeleteAll(updatedAt: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :id AND deleted = 0")
    suspend fun countMessages(id: String): Int

    /**
     * 只更新消息计数，绝不用 upsert（REPLACE 语义会先删后插整行，
     * messages 表对 conversations 有 ON DELETE CASCADE，会级联删光消息）。
     */
    @Query("UPDATE conversations SET message_count = :count WHERE id = :id")
    suspend fun updateMessageCount(id: String, count: Int)

    // ---------------------------------------------------------- 同步

    @Query("SELECT * FROM conversations WHERE sync_state != 'SYNCED' LIMIT :limit")
    suspend fun pending(limit: Int): List<ConversationEntity>

    @Query("UPDATE conversations SET sync_state = 'SYNCED' WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM conversations WHERE deleted = 1 AND sync_state = 'SYNCED'")
    suspend fun purgeDeleted()

    @Query("DELETE FROM conversations")
    suspend fun clearAll()
}

@Dao
interface MessageDao {

    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId AND deleted = 0
        ORDER BY created_at ASC
        """,
    )
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId AND deleted = 0
        ORDER BY created_at ASC
        """,
    )
    suspend fun getForConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNew(message: MessageEntity)

    @Update
    suspend fun updateExisting(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query(
        """
        UPDATE messages
           SET content = :content, reasoning_content = :reasoning,
               status = :status, sync_state = 'PENDING'
         WHERE id = :id
        """,
    )
    suspend fun updateStreamingContent(
        id: String,
        content: String,
        reasoning: String,
        status: String,
    )

    @Query("UPDATE messages SET status = :status, error_message = :error, updated_at = :updatedAt, sync_state = 'PENDING' WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String?, updatedAt: Long)

    /** 3.1.0：软删除，保留墓碑。 */
    @Query(
        """
        UPDATE messages
           SET deleted = 1, updated_at = :updatedAt, sync_state = 'PENDING'
         WHERE id = :id
        """,
    )
    suspend fun softDelete(id: String, updatedAt: Long)

    @Query(
        """
        UPDATE messages
           SET deleted = 1, updated_at = :updatedAt, sync_state = 'PENDING'
         WHERE conversation_id = :conversationId
           AND created_at >= COALESCE((SELECT created_at FROM messages WHERE id = :messageId), 0)
        """,
    )
    suspend fun softDeleteFrom(conversationId: String, messageId: String, updatedAt: Long)

    @Query(
        """
        UPDATE messages
           SET deleted = 1, updated_at = :updatedAt, sync_state = 'PENDING'
         WHERE conversation_id = :conversationId AND deleted = 0
        """,
    )
    suspend fun softDeleteForConversation(conversationId: String, updatedAt: Long)

    @Query(
        """
        UPDATE messages
           SET deleted = 1, updated_at = :updatedAt, sync_state = 'PENDING'
         WHERE deleted = 0
        """,
    )
    suspend fun softDeleteAll(updatedAt: Long)

    @Query("UPDATE messages SET status = 'COMPLETE' WHERE conversation_id = :conversationId AND status = 'STREAMING'")
    suspend fun finalizeStreaming(conversationId: String)

    // ---------------------------------------------------------- 同步

    @Query("SELECT * FROM messages WHERE sync_state != 'SYNCED' LIMIT :limit")
    suspend fun pending(limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET sync_state = 'SYNCED' WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM messages WHERE deleted = 1 AND sync_state = 'SYNCED'")
    suspend fun purgeDeleted()

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}

@Dao
interface SyncMetaDao {

    @Query("SELECT value FROM sync_meta WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: SyncMetaEntity)

    @Query("DELETE FROM sync_meta")
    suspend fun clear()
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
