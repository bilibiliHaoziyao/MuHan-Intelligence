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
        ORDER BY pinned DESC, updated_at DESC
        """,
    )
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query(
        """
        SELECT * FROM conversations
        WHERE title LIKE '%' || :query || '%'
        ORDER BY pinned DESC, updated_at DESC
        """,
    )
    fun search(query: String): Flow<List<ConversationEntity>>

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

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId AND id != :keepId")
    suspend fun deleteAllExcept(conversationId: String, keepId: String)

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
