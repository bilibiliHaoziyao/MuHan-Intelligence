package com.muhan.intelligence.data.repository

import com.muhan.intelligence.data.local.SessionManager
import com.muhan.intelligence.data.remote.CloudApi
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账号（3.1.0）。
 *
 * 登录/注册成功后立即执行一次同步：把本地旧数据（Room 里全部 PENDING）
 * 推送到云端，再拉取该账号已有数据 —— 即「旧版本数据迁移」。
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: CloudApi,
    private val session: SessionManager,
    private val syncRepository: SyncRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsSyncRepository: SettingsSyncRepository,
) {
    val sessionState: StateFlow<SessionManager.Session?> = session.session

    val isLoggedIn: Boolean get() = session.isLoggedIn

    suspend fun login(email: String, password: String) {
        api.login(normalize(email), password)
        syncRepository.syncNow()
        syncSettings()
    }

    suspend fun register(email: String, password: String, code: String) {
        api.register(normalize(email), password, code.trim())
        syncRepository.syncNow()
        syncSettings()
    }

    /** 发送注册邮箱验证码（3.0.1 起用于防机器人）。 */
    suspend fun sendCode(email: String) {
        api.sendCode(normalize(email))
    }

    private suspend fun syncSettings() {
        if (!settingsSyncRepository.pullAndApply()) {
            // 新账号：把本地设置作为初始值上传。
            settingsSyncRepository.push()
        }
    }

    /** 退出：尽力同步一次，再清本地缓存，避免账号间数据串台。 */
    suspend fun logout() {
        runCatching { syncRepository.syncNow() }
        api.logout()
        conversationRepository.clearLocalData()
    }

    private fun normalize(email: String): String = email.trim().lowercase()
}
