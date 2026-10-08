package com.muhan.intelligence.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted-at-rest store for provider credentials.
 *
 * Uses [EncryptedSharedPreferences] backed by an AES256-GCM master key held in
 * the Android Keystore, so API keys are never written to disk in plain text and
 * are excluded from cloud backup / device transfer (see res/xml backup rules).
 */
@Singleton
class SecureKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun putApiKey(providerId: String, apiKey: String) {
        prefs.edit().putString(keyFor(providerId), apiKey.trim()).apply()
    }

    fun getApiKey(providerId: String): String? =
        prefs.getString(keyFor(providerId), null)?.takeIf { it.isNotBlank() }

    fun removeApiKey(providerId: String) {
        prefs.edit().remove(keyFor(providerId)).apply()
    }

    fun hasApiKey(providerId: String): Boolean = getApiKey(providerId) != null

    /** Masks a key for display, e.g. `sk-1234••••••cdef`. */
    fun maskedApiKey(providerId: String): String? {
        val key = getApiKey(providerId) ?: return null
        if (key.length <= 10) return "•".repeat(key.length)
        return key.take(6) + "•".repeat(8) + key.takeLast(4)
    }

    private fun keyFor(providerId: String) = "api_key_$providerId"

    companion object {
        private const val FILE_NAME = "muhan_secure_prefs"
    }
}
