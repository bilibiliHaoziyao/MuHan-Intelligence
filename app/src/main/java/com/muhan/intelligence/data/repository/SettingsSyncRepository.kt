package com.muhan.intelligence.data.repository

import com.muhan.intelligence.data.remote.CloudApi
import com.muhan.intelligence.domain.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 偏好设置云同步（3.1.0）。
 *
 * 登录时以云端为准（云端无记录则把本地推上去）；之后每次修改立即推送。
 */
@Singleton
class SettingsSyncRepository @Inject constructor(
    private val api: CloudApi,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun push() {
        val p = settingsRepository.preferences.first()
        val body = buildJsonObject {
            put("themeMode", p.themeMode.name)
            put("dynamicColor", p.dynamicColor)
            put("ttsEnabled", p.ttsEnabled)
            put("ttsVoice", p.ttsVoice)
            put("uxImprovement", p.uxImprovement)
            put("updatedAt", System.currentTimeMillis())
        }
        runCatching { api.authorized("/settings", "PUT", body) }
    }

    /** @return 云端是否存在设置记录（false 表示新账号，调用方应 push 本地值）。 */
    suspend fun pullAndApply(): Boolean {
        val o = runCatching { api.authorized("/settings") }.getOrNull() ?: return false
        val updatedAt = o["updatedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        if (updatedAt <= 0L) return false

        val theme = o["themeMode"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
        if (theme != null) settingsRepository.setThemeMode(theme)

        o["dynamicColor"]?.jsonPrimitive?.contentOrNull?.toBoolean()?.let {
            settingsRepository.setDynamicColor(it)
        }
        o["ttsEnabled"]?.jsonPrimitive?.contentOrNull?.toBoolean()?.let {
            settingsRepository.setTtsEnabled(it)
        }
        o["ttsVoice"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            settingsRepository.setTtsVoice(it)
        }
        o["uxImprovement"]?.jsonPrimitive?.contentOrNull?.toBoolean()?.let {
            settingsRepository.setUxImprovement(it)
        }
        return true
    }
}
