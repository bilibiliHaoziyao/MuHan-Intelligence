package com.muhan.intelligence.data.repository

import com.muhan.intelligence.data.local.ConversationDao
import com.muhan.intelligence.data.local.ConversationEntity
import com.muhan.intelligence.data.local.LogRepository
import com.muhan.intelligence.data.local.MessageDao
import com.muhan.intelligence.data.local.MessageEntity
import com.muhan.intelligence.data.local.SessionManager
import com.muhan.intelligence.data.local.SyncMetaEntity
import com.muhan.intelligence.data.local.SyncMetaDao
import com.muhan.intelligence.data.remote.CloudApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppScope @Inject constructor() {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

data class SyncState(
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    val error: String? = null,
)

/**
 * 云同步引擎（3.1.0）。
 *
 * - 每次本地写操作由 [ConversationRepository] 调用 [requestSync]：300ms 合并窗口后
 *   立即 push 本地变更并 pull 增量。
 * - 首次登录时本地旧数据全部是 PENDING，因此「迁移」等价于一次全量 push。
 * - 冲突：消息 append-only；会话按 updatedAt 大者胜（LWW）。
 */
@Singleton
class SyncRepository @Inject constructor(
    private val api: CloudApi,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val syncMetaDao: SyncMetaDao,
    private val session: SessionManager,
    private val appScope: AppScope,
    private val logRepository: LogRepository,
) {
    private val mutex = Mutex()
    private var scheduled: Job? = null

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** 本地写操作后调用；300ms 内的多次调用会合并。 */
    fun requestSync() {
        if (!session.isLoggedIn) return
        scheduled?.cancel()
        scheduled = appScope.scope.launch {
            delay(300)
            syncNow()
        }
    }

    suspend fun syncNow() = withContext(Dispatchers.IO) {
        if (!session.isLoggedIn) return@withContext
        mutex.withLock {
            _state.update { it.copy(syncing = true, error = null) }
            try {
                push()
                pull()
                _state.update {
                    it.copy(
                        syncing = false,
                        lastSyncAt = System.currentTimeMillis(),
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                logRepository.warn("Sync", "同步失败：${t.message}")
                _state.update { it.copy(syncing = false, error = t.message) }
            }
        }
    }

    // ------------------------------------------------------------------ push

    private suspend fun push() {
        // 先清掉「已同步且已删」的墓碑（PENDING 的删除不会被动到）。
        conversationDao.purgeDeleted()
        messageDao.purgeDeleted()

        var guard = 0
        while (guard++ < MAX_PUSH_ROUNDS) {
            val conversations = conversationDao.pending(PUSH_CONVERSATIONS)
            val messages = messageDao.pending(PUSH_MESSAGES)
            if (conversations.isEmpty() && messages.isEmpty()) break

            val body = buildJsonObject {
                put("conversations", buildJsonArray {
                    conversations.forEach { add(conversationJson(it)) }
                })
                put("messages", buildJsonArray {
                    messages.forEach { add(messageJson(it)) }
                })
            }

            api.authorized("/sync", "POST", body)
            if (conversations.isNotEmpty()) conversationDao.markSynced(conversations.map { it.id })
            if (messages.isNotEmpty()) messageDao.markSynced(messages.map { it.id })
        }
    }

    private fun conversationJson(c: ConversationEntity): JsonObject = buildJsonObject {
        put("id", c.id)
        put("title", c.title)
        put("mode", c.mode)
        put("modelName", c.modelName)
        put("pinned", c.pinned)
        put("messageCount", c.messageCount)
        put("createdAt", c.createdAt)
        put("updatedAt", c.updatedAt)
        put("deleted", c.deleted)
    }

    private fun messageJson(m: MessageEntity): JsonObject = buildJsonObject {
        put("id", m.id)
        put("conversationId", m.conversationId)
        put("role", m.role)
        put("content", m.content)
        put("reasoningContent", m.reasoningContent)
        put("status", m.status)
        put("modelName", m.modelName)
        put("tokenCount", m.tokenCount)
        put("attachments", m.attachments)
        put("imageUrl", m.imageUrl)
        put("agentTrace", m.agentTrace)
        put("createdAt", m.createdAt)
        put("updatedAt", m.updatedAt)
        put("deleted", m.deleted)
    }

    // ------------------------------------------------------------------ pull

    private suspend fun pull() {
        var cursor = syncMetaDao.get(KEY_CURSOR)?.toLongOrNull() ?: 0L
        var guard = 0

        while (guard++ < MAX_PULL_PAGES) {
            val res = api.authorized("/sync?since=$cursor&limit=$PULL_LIMIT")

            val conversations = res["conversations"]?.let {
                runCatching { it.jsonArray }.getOrNull()
            } ?: JsonArray(emptyList())
            val messages = res["messages"]?.let {
                runCatching { it.jsonArray }.getOrNull()
            } ?: JsonArray(emptyList())

            conversations.forEach { element ->
                runCatching { applyRemoteConversation(element.jsonObject) }
            }
            messages.forEach { element ->
                runCatching { applyRemoteMessage(element.jsonObject) }
            }

            val newCursor = res["cursor"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: cursor
            val hasMore = res["hasMore"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            cursor = newCursor
            syncMetaDao.put(SyncMetaEntity(KEY_CURSOR, cursor.toString()))
            if (!hasMore) break
        }
    }

    private suspend fun applyRemoteConversation(o: JsonObject) {
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return
        val updatedAt = o["updatedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val existing = conversationDao.getByIdAny(id)
        val remote = ConversationEntity(
            id = id,
            title = o["title"]?.jsonPrimitive?.contentOrNull ?: "新对话",
            createdAt = o["createdAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: updatedAt,
            updatedAt = updatedAt,
            modelName = o["modelName"]?.jsonPrimitive?.contentOrNull,
            pinned = o["pinned"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
            messageCount = o["messageCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            mode = o["mode"]?.jsonPrimitive?.contentOrNull ?: "chat",
            syncState = "SYNCED",
            deleted = o["deleted"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
        )

        when {
            existing == null && remote.deleted -> Unit // 本地没有的墓碑无需落库
            existing == null -> conversationDao.insertNew(remote)
            updatedAt >= existing.updatedAt -> conversationDao.updateExisting(remote)
            else -> Unit // 本地更新，保留待推送版本
        }
    }

    private suspend fun applyRemoteMessage(o: JsonObject) {
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return
        val conversationId = o["conversationId"]?.jsonPrimitive?.contentOrNull ?: return
        val updatedAt = o["updatedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val existing = messageDao.getById(id)
        val remote = MessageEntity(
            id = id,
            conversationId = conversationId,
            role = o["role"]?.jsonPrimitive?.contentOrNull ?: "user",
            content = o["content"]?.jsonPrimitive?.contentOrNull ?: "",
            reasoningContent = o["reasoningContent"]?.jsonPrimitive?.contentOrNull ?: "",
            status = o["status"]?.jsonPrimitive?.contentOrNull ?: "COMPLETE",
            errorMessage = null,
            modelName = o["modelName"]?.jsonPrimitive?.contentOrNull,
            createdAt = o["createdAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: updatedAt,
            tokenCount = o["tokenCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            attachments = o["attachments"]?.jsonPrimitive?.contentOrNull ?: "[]",
            imageUrl = o["imageUrl"]?.jsonPrimitive?.contentOrNull,
            agentTrace = o["agentTrace"]?.jsonPrimitive?.contentOrNull ?: "[]",
            syncState = "SYNCED",
            deleted = o["deleted"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
        )

        when {
            existing == null && remote.deleted -> Unit
            existing == null -> messageDao.insertNew(remote)
            updatedAt >= existing.updatedAt -> messageDao.updateExisting(remote)
            else -> Unit
        }
    }

    /** 退出登录时清空本地缓存（云端为权威数据源）。 */
    suspend fun clearLocalCache() = withContext(Dispatchers.IO) {
        messageDao.clearAll()
        conversationDao.clearAll()
        syncMetaDao.clear()
    }

    private companion object {
        const val KEY_CURSOR = "cursor"
        const val PUSH_CONVERSATIONS = 100
        const val PUSH_MESSAGES = 200
        const val MAX_PUSH_ROUNDS = 100
        const val PULL_LIMIT = 500
        const val MAX_PULL_PAGES = 50
    }
}
