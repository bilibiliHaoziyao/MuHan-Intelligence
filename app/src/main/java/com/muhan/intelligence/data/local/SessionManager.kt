package com.muhan.intelligence.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账号会话（3.1.0）。
 *
 * 强制登录模式下，App 的所有云端调用都依赖这里的 token。
 * token 存 EncryptedSharedPreferences，登录态以 StateFlow 暴露，供启动门禁订阅。
 */
@Singleton
class SessionManager @Inject constructor(
    private val store: SecureKeyStore,
) {
    data class Session(val userId: String, val email: String)

    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> = _session.asStateFlow()

    val isLoggedIn: Boolean get() = _session.value != null

    fun accessToken(): String? = store.getSecret(KEY_ACCESS)
    fun refreshToken(): String? = store.getSecret(KEY_REFRESH)
    fun expiresAt(): Long = store.getSecret(KEY_EXPIRES)?.toLongOrNull() ?: 0L

    fun save(
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
        userId: String,
        email: String,
    ) {
        store.putSecret(KEY_ACCESS, accessToken)
        store.putSecret(KEY_REFRESH, refreshToken)
        store.putSecret(KEY_EXPIRES, expiresAt.toString())
        store.putSecret(KEY_UID, userId)
        store.putSecret(KEY_EMAIL, email)
        _session.value = Session(userId, email)
    }

    fun clear() {
        store.removeSecret(KEY_ACCESS)
        store.removeSecret(KEY_REFRESH)
        store.removeSecret(KEY_EXPIRES)
        store.removeSecret(KEY_UID)
        store.removeSecret(KEY_EMAIL)
        _session.value = null
    }

    private fun load(): Session? {
        val refresh = store.getSecret(KEY_REFRESH) ?: return null
        if (refresh.isBlank()) return null
        return Session(
            userId = store.getSecret(KEY_UID).orEmpty(),
            email = store.getSecret(KEY_EMAIL).orEmpty(),
        )
    }

    private companion object {
        const val KEY_ACCESS = "cf_access"
        const val KEY_REFRESH = "cf_refresh"
        const val KEY_EXPIRES = "cf_expires"
        const val KEY_UID = "cf_uid"
        const val KEY_EMAIL = "cf_email"
    }
}
