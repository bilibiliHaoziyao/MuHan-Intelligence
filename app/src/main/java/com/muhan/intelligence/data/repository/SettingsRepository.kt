package com.muhan.intelligence.data.repository

import com.muhan.intelligence.data.local.PreferencesStore
import com.muhan.intelligence.domain.model.AppPreferences
import com.muhan.intelligence.domain.model.GenerationSettings
import com.muhan.intelligence.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Thin domain-facing wrapper over [PreferencesStore]. */
@Singleton
class SettingsRepository @Inject constructor(
    private val store: PreferencesStore,
) {
    val preferences: Flow<AppPreferences> = store.preferences

    suspend fun completeOnboarding() = store.setOnboardingCompleted(true)

    suspend fun resetOnboarding() = store.setOnboardingCompleted(false)

    suspend fun setThemeMode(mode: ThemeMode) = store.setThemeMode(mode)

    suspend fun setDynamicColor(enabled: Boolean) = store.setDynamicColor(enabled)

    suspend fun setUxImprovement(enabled: Boolean) = store.setUxImprovement(enabled)

    suspend fun updateGeneration(settings: GenerationSettings) = store.updateGeneration(settings)

    suspend fun resetGeneration() = store.resetGeneration()
}
