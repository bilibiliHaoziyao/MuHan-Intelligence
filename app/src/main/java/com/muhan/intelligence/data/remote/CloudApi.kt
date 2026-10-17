package com.muhan.intelligence.data.remote

import com.muhan.intelligence.BuildConfig
import com.muhan.intelligence.data.local.LogRepository
import com.muhan.intelligence.data.local.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class ApiException(val code: Int, message: String) : IOException(message)

/**
 * 账号与同步后端客户端（3.1.0，Cloudflare Workers）。
 *
 * 统一处理：Bearer token、过期前自动刷新、401 重试一次、刷新失败清除登录态。
 */
@Singleton
class CloudApi @Inject constructor(
    private val session: SessionManager,
    private val logRepository: LogRepository,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()

    private val base: String get() = BuildConfig.CLOUD_BASE_URL.trimEnd('/')

    // ------------------------------------------------------------------ 认证

    suspend fun sendCode(email: String) {
        raw("POST", "/auth/send-code", jsonObject("email" to email), token = null)
    }

    suspend fun register(email: String, password: String, code: String) {
        val body = jsonObject(
            "email" to email,
            "password" to password,
            "code" to code,
        )
        val res = raw("POST", "/auth/register", body, token = null)
        saveAuth(res)
    }

    suspend fun login(email: String, password: String) {
        val body = jsonObject(
            "email" to email,
            "password" to password,
        )
        val res = raw("POST", "/auth/login", body, token = null)
        saveAuth(res)
    }

    suspend fun logout() {
        val refresh = session.refreshToken()
        if (refresh != null) {
            runCatching {
                raw("POST", "/auth/logout", jsonObject("refreshToken" to refresh), token = null)
            }
        }
        session.clear()
    }

    suspend fun me(): JsonObject = authorized("/auth/me")

    /** 检查更新（公开接口，启动时无需登录即可调用）。 */
    suspend fun appVersion(): JsonObject = raw("GET", "/app/version", null, token = null)

    // ------------------------------------------------------------------ 通用请求

    suspend fun authorized(
        path: String,
        method: String = "GET",
        body: JsonObject? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        if (session.refreshToken() == null) throw ApiException(401, "未登录")

        // 提前 60 秒刷新，避免临界过期。
        if (session.expiresAt() - System.currentTimeMillis() < 60_000) {
            if (!refresh()) throw ApiException(401, "登录已过期，请重新登录")
        }
        try {
            raw(method, path, body, session.accessToken())
        } catch (e: ApiException) {
            if (e.code != 401) throw e
            if (!refresh()) {
                session.clear()
                throw ApiException(401, "登录已过期，请重新登录")
            }
            raw(method, path, body, session.accessToken())
        }
    }

    private suspend fun refresh(): Boolean = refreshMutex.withLock {
        val refreshToken = session.refreshToken() ?: return@withLock false
        try {
            val res = raw(
                "POST",
                "/auth/refresh",
                jsonObject("refreshToken" to refreshToken),
                token = null,
            )
            saveAuth(res, keepUser = true)
            true
        } catch (e: Throwable) {
            logRepository.warn("Cloud", "刷新登录态失败：${e.message}")
            session.clear()
            false
        }
    }

    private suspend fun raw(
        method: String,
        path: String,
        body: JsonObject?,
        token: String?,
    ): JsonObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(base + path)
        if (token != null) builder.addHeader("Authorization", "Bearer $token")
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body?.toString() ?: "{}").toRequestBody(jsonMedia))
            "PUT" -> builder.put((body?.toString() ?: "{}").toRequestBody(jsonMedia))
            "DELETE" -> builder.delete()
            else -> throw ApiException(400, "不支持的请求方法")
        }
        client.newCall(builder.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!res.isSuccessful) {
                val msg = parsed?.get("error")?.jsonPrimitive?.contentOrNull
                    ?: "请求失败（HTTP ${res.code}）"
                throw ApiException(res.code, msg)
            }
            parsed ?: JsonObject(emptyMap())
        }
    }

    private fun saveAuth(res: JsonObject, keepUser: Boolean = false) {
        val access = res["accessToken"]?.jsonPrimitive?.contentOrNull
            ?: throw ApiException(500, "登录响应缺少 accessToken")
        val refresh = res["refreshToken"]?.jsonPrimitive?.contentOrNull
            ?: throw ApiException(500, "登录响应缺少 refreshToken")
        val expiresIn = res["expiresIn"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 900L
        val user = res["user"]?.jsonObject
        val userId = user?.get("id")?.jsonPrimitive?.contentOrNull
            ?: session.session.value?.userId.orEmpty()
        val email = user?.get("email")?.jsonPrimitive?.contentOrNull
            ?: session.session.value?.email.orEmpty()

        session.save(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
            userId = userId,
            email = email,
        )
    }

    private fun jsonObject(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to kotlinx.serialization.json.JsonPrimitive(v) })
}
