package com.muhan.intelligence.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.muhan.intelligence.domain.model.AppPreferences
import com.muhan.intelligence.domain.model.GenerationSettings
import com.muhan.intelligence.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.preferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "muhan_settings",
)

/** Persists user preferences via Jetpack DataStore. */
@Singleton
class PreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val temperature = floatPreferencesKey("temperature")
        val topP = floatPreferencesKey("top_p")
        val maxTokens = intPreferencesKey("max_tokens")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val streamEnabled = booleanPreferencesKey("stream_enabled")
        val sendHistory = booleanPreferencesKey("send_history")
        val reasoningEnabled = booleanPreferencesKey("reasoning_enabled")
    }

    val preferences: Flow<AppPreferences> = context.preferencesDataStore.data.map { prefs ->
        AppPreferences(
            onboardingCompleted = prefs[Keys.onboardingCompleted] ?: false,
            themeMode = prefs[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.dynamicColor] ?: true,
            generation = GenerationSettings(
                temperature = prefs[Keys.temperature] ?: 1.0f,
                topP = prefs[Keys.topP] ?: 1.0f,
                maxTokens = prefs[Keys.maxTokens] ?: 4096,
                systemPrompt = prefs[Keys.systemPrompt] ?: GenerationSettings.DEFAULT_SYSTEM_PROMPT,
                streamEnabled = prefs[Keys.streamEnabled] ?: true,
                sendHistory = prefs[Keys.sendHistory] ?: true,
                reasoningEnabled = prefs[Keys.reasoningEnabled] ?: true,
            ),
        )
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.preferencesDataStore.edit { it[Keys.onboardingCompleted] = completed }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.preferencesDataStore.edit { it[Keys.themeMode] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.preferencesDataStore.edit { it[Keys.dynamicColor] = enabled }
    }

    suspend fun updateGeneration(settings: GenerationSettings) {
        context.preferencesDataStore.edit { prefs ->
            prefs[Keys.temperature] = settings.temperature
            prefs[Keys.topP] = settings.topP
            prefs[Keys.maxTokens] = settings.maxTokens
            prefs[Keys.systemPrompt] = settings.systemPrompt
            prefs[Keys.streamEnabled] = settings.streamEnabled
            prefs[Keys.sendHistory] = settings.sendHistory
            prefs[Keys.reasoningEnabled] = settings.reasoningEnabled
        }
    }

    suspend fun resetGeneration() {
        context.preferencesDataStore.edit { prefs ->
            prefs.remove(Keys.temperature)
            prefs.remove(Keys.topP)
            prefs.remove(Keys.maxTokens)
            prefs.remove(Keys.systemPrompt)
            prefs.remove(Keys.streamEnabled)
            prefs.remove(Keys.sendHistory)
            prefs.remove(Keys.reasoningEnabled)
        }
    }
}
