package com.muhan.intelligence.data.repository

import com.muhan.intelligence.data.local.SessionManager
import com.muhan.intelligence.data.remote.CloudApi
import com.muhan.intelligence.domain.model.TokenQuota
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 额度（3.1.0 起全部在云端）。
 *
 * 本地不再保存任何额度文件；未登录时不可用（强制登录模式）。
 */
@Singleton
class CloudQuotaRepository @Inject constructor(
    private val api: CloudApi,
    private val session: SessionManager,
) {
    suspend fun current(): TokenQuota = parse(api.authorized("/quota"))

    suspend fun consume(tokens: Int): TokenQuota =
        parse(api.authorized("/quota/consume", "POST", buildJsonObject { put("tokens", tokens) }))

    val isLoggedIn: Boolean get() = session.isLoggedIn

    private fun parse(o: JsonObject): TokenQuota = TokenQuota(
        period = o["period"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        used = o["used"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
        carry = o["carry"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
        limit = o["limit"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 100_000L,
    )
}
