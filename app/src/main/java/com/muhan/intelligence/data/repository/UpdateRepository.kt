package com.muhan.intelligence.data.repository

import com.muhan.intelligence.BuildConfig
import com.muhan.intelligence.data.local.LogRepository
import com.muhan.intelligence.data.remote.CloudApi
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** 服务端下发的版本信息（3.0.1）。 */
data class AppUpdate(
    val latestCode: Int,
    val latestName: String,
    val downloadUrl: String,
    val minCode: Int,
    val minName: String,
    val minDownloadUrl: String,
) {
    /** 当前版本低于最低支持版本 → 必须更新。 */
    val isForced: Boolean get() = BuildConfig.VERSION_CODE < minCode

    /** 有更新（含强制）。 */
    val hasUpdate: Boolean get() = BuildConfig.VERSION_CODE < latestCode

    val currentName: String get() = BuildConfig.VERSION_NAME
}

/**
 * 检查更新（3.0.1）。
 *
 * 版本门槛由服务端 `app_config.min_supported_code` 控制：客户端低于该版本即强制更新。
 * 每次冷启动调用一次；网络失败时放行，避免离线用户被锁死。
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val api: CloudApi,
    private val logRepository: LogRepository,
) {
    suspend fun check(): AppUpdate? = runCatching {
        val res = api.appVersion()
        val latest = res["latest"]?.jsonObject ?: return@runCatching null
        val min = res["minSupported"]?.jsonObject ?: latest

        val latestCode = latest["code"]?.jsonPrimitive?.intOrNull ?: return@runCatching null
        val latestName = latest["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val latestUrl = latest["url"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val minCode = min["code"]?.jsonPrimitive?.intOrNull ?: latestCode
        val minName = min["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val minUrl = min["url"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val update = AppUpdate(
            latestCode = latestCode,
            latestName = latestName,
            downloadUrl = latestUrl,
            minCode = minCode,
            minName = minName,
            minDownloadUrl = minUrl.ifBlank { latestUrl },
        )
        logRepository.info(
            "Update",
            "版本检查：当前 ${BuildConfig.VERSION_CODE}(${BuildConfig.VERSION_NAME})，" +
                "最新 $latestCode($latestName)，最低支持 $minCode($minName)，" +
                "强制=${update.isForced}",
        )
        update
    }.getOrElse { e ->
        logRepository.warn("Update", "版本检查失败（已放行）：${e.message}")
        null
    }
}
