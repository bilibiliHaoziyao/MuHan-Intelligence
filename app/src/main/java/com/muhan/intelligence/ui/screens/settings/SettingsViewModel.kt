package com.muhan.intelligence.ui.screens.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muhan.intelligence.BuildConfig
import com.muhan.intelligence.data.remote.ProviderPresets
import com.muhan.intelligence.data.repository.ConversationRepository
import com.muhan.intelligence.data.repository.ProviderRepository
import com.muhan.intelligence.data.repository.SettingsRepository
import com.muhan.intelligence.domain.model.ApiFlavor
import com.muhan.intelligence.domain.model.AppPreferences
import com.muhan.intelligence.domain.model.ConnectionTestResult
import com.muhan.intelligence.domain.model.GenerationSettings
import com.muhan.intelligence.domain.model.ProviderConfig
import com.muhan.intelligence.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val preferences: AppPreferences = AppPreferences(),
    val providers: List<ProviderConfig> = emptyList(),
    val activeProviderId: String? = null,
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    val isDebugBuild: Boolean = BuildConfig.DEBUG,
    val message: String? = null,
    val isLoading: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val providerRepository: ProviderRepository,
    private val conversationRepository: ConversationRepository,
    private val logRepository: com.muhan.intelligence.data.local.LogRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.preferences.collect { prefs ->
                _state.update { it.copy(preferences = prefs, isLoading = false) }
            }
        }
        viewModelScope.launch {
            providerRepository.observeProviders().collect { providers ->
                _state.update {
                    it.copy(
                        providers = providers,
                        activeProviderId = providers.firstOrNull { p -> p.isActive }?.id,
                    )
                }
            }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    }

    fun setUxImprovement(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setUxImprovement(enabled) }
    }

    // ------------------------------------------------------- 诊断日志 (0.2.0 Fix2)

    fun logFiles(): List<java.io.File> = logRepository.listLogFiles()

    fun readLog(file: java.io.File): String = logRepository.readLog(file)

    fun clearLogs() {
        logRepository.clearLogs()
        _state.update { it.copy(message = "已清空全部日志") }
    }

    fun updateGeneration(settings: GenerationSettings) {
        viewModelScope.launch { settingsRepository.updateGeneration(settings) }
    }

    fun resetGeneration() {
        viewModelScope.launch {
            settingsRepository.resetGeneration()
            _state.update { it.copy(message = "生成参数已恢复默认") }
        }
    }

    fun activateProvider(id: String) {
        viewModelScope.launch {
            providerRepository.setActive(id)
            _state.update { it.copy(message = "已切换当前使用的模型服务") }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            providerRepository.deleteProvider(id)
            _state.update { it.copy(message = "已删除模型服务") }
        }
    }

    fun clearAllConversations() {
        viewModelScope.launch {
            conversationRepository.deleteAllConversations()
            _state.update { it.copy(message = "已清空全部对话记录") }
        }
    }

    /** Non-suspending: the masked value is a local lookup over encrypted prefs. */
    fun maskedKey(providerId: String): String? = providerRepository.maskedApiKey(providerId)

    fun restartOnboarding() {
        viewModelScope.launch {
            settingsRepository.resetOnboarding()
            _state.update { it.copy(message = "已重置引导，返回首页重新进入") }
        }
    }
}

data class ProviderEditorUiState(
    val providerId: String? = null,
    val displayName: String = "",
    val baseUrl: String = "",
    val modelName: String = "",
    val imageModel: String = "",
    val apiKey: String = "",
    val apiKeyVisible: Boolean = false,
    val existingMaskedKey: String? = null,
    val flavor: ApiFlavor = ApiFlavor.OPENAI,
    val isActive: Boolean = false,
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val isLoading: Boolean = false,
    val testResult: ConnectionTestResult? = null,
    val error: String? = null,
    val saved: Boolean = false,
    val deleted: Boolean = false,
) {
    val isEditing: Boolean get() = providerId != null

    /** On edit, leaving the key field blank is valid — the stored key is reused. */
    val canSave: Boolean
        get() = displayName.isNotBlank() &&
            baseUrl.isNotBlank() &&
            modelName.isNotBlank() &&
            (apiKey.isNotBlank() || existingMaskedKey != null)
}

@HiltViewModel
class ProviderEditorViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val providerId: String? =
        savedStateHandle.get<String>("providerId")?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(
        ProviderEditorUiState(providerId = providerId, isLoading = providerId != null),
    )
    val state: StateFlow<ProviderEditorUiState> = _state.asStateFlow()

    init {
        if (providerId != null) {
            viewModelScope.launch {
                val existing = providerRepository.observeProviders().first()
                    .firstOrNull { it.id == providerId }
                if (existing == null) {
                    _state.update { it.copy(isLoading = false, error = "未找到该模型服务。") }
                } else {
                    _state.update {
                        it.copy(
                            displayName = existing.displayName,
                            baseUrl = existing.baseUrl,
                            modelName = existing.modelName,
                            imageModel = existing.imageModel.orEmpty(),
                            flavor = existing.flavor,
                            isActive = existing.isActive,
                            existingMaskedKey = providerRepository.maskedApiKey(providerId),
                            isLoading = false,
                        )
                    }
                }
            }
        }
    }

    fun onDisplayNameChange(value: String) = _state.update { it.copy(displayName = value, error = null) }

    fun onBaseUrlChange(value: String) = _state.update {
        it.copy(
            baseUrl = value,
            // Auto-detect the protocol only for new custom endpoints, never
            // silently change it for an existing working configuration.
            flavor = if (it.providerId == null) ProviderPresets.guessFlavor(value) else it.flavor,
            error = null,
            testResult = null,
        )
    }

    fun onModelNameChange(value: String) = _state.update { it.copy(modelName = value, error = null) }

    fun onImageModelChange(value: String) = _state.update { it.copy(imageModel = value, error = null) }

    fun onApiKeyChange(value: String) = _state.update {
        it.copy(apiKey = value, error = null, testResult = null)
    }

    fun onFlavorChange(flavor: ApiFlavor) = _state.update { it.copy(flavor = flavor, testResult = null) }

    fun toggleApiKeyVisibility() = _state.update { it.copy(apiKeyVisible = !it.apiKeyVisible) }

    fun setIsActive(active: Boolean) = _state.update { it.copy(isActive = active) }

    fun testConnection() {
        val current = _state.value
        if (current.baseUrl.isBlank() || current.modelName.isBlank()) {
            _state.update { it.copy(error = "请先填写接口地址与模型名称。") }
            return
        }

        val key = current.apiKey.ifBlank {
            providerId?.let { providerRepository.getApiKey(it) }.orEmpty()
        }
        if (key.isBlank()) {
            _state.update { it.copy(error = "请先填写 API Key。") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isTesting = true, testResult = null, error = null) }
            val result = providerRepository.testConnection(
                ProviderConfig(
                    id = providerId ?: "probe",
                    displayName = current.displayName.ifBlank { "自定义服务" },
                    baseUrl = current.baseUrl,
                    modelName = current.modelName,
                    flavor = current.flavor,
                ),
                key,
            )
            _state.update {
                it.copy(
                    isTesting = false,
                    testResult = result,
                    error = if (result is ConnectionTestResult.Failure) result.message else null,
                )
            }
        }
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) {
            _state.update { it.copy(error = "请完整填写必要信息。") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            runCatching {
                providerRepository.saveProvider(
                    config = ProviderConfig(
                        id = providerId.orEmpty(),
                        displayName = current.displayName.trim(),
                        baseUrl = current.baseUrl.trim().trimEnd('/'),
                        modelName = current.modelName.trim(),
                        imageModel = current.imageModel.trim().takeIf { it.isNotBlank() },
                        flavor = current.flavor,
                        // New providers become active immediately so the user is
                        // never left with an empty selection.
                        isActive = if (providerId == null) true else current.isActive,
                    ),
                    // null means "leave the stored key untouched".
                    apiKey = current.apiKey.takeIf { it.isNotBlank() },
                )
            }.fold(
                onSuccess = { _state.update { it.copy(isSaving = false, saved = true) } },
                onFailure = { e ->
                    _state.update { it.copy(isSaving = false, error = e.message ?: "保存失败") }
                },
            )
        }
    }

    fun deleteProvider() {
        val id = providerId ?: return
        viewModelScope.launch {
            providerRepository.deleteProvider(id)
            _state.update { it.copy(deleted = true) }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }
}
