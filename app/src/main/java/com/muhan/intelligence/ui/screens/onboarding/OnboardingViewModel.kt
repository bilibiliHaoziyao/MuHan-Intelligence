package com.muhan.intelligence.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muhan.intelligence.data.remote.ConnectionTester
import com.muhan.intelligence.data.remote.ProviderPreset
import com.muhan.intelligence.data.remote.ProviderPresets
import com.muhan.intelligence.data.repository.ProviderRepository
import com.muhan.intelligence.data.repository.SettingsRepository
import com.muhan.intelligence.domain.model.ApiFlavor
import com.muhan.intelligence.domain.model.ConnectionTestResult
import com.muhan.intelligence.domain.model.ProviderConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which pane of the wizard is showing. */
enum class OnboardingStep { WELCOME, PROVIDER, CREDENTIALS, VERIFY, DONE }

/** How the user is picking their provider on the second step. */
sealed interface ProviderChoice {
    data object Preset : ProviderChoice
    data object Custom : ProviderChoice
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val choice: ProviderChoice = ProviderChoice.Preset,
    val selectedPreset: ProviderPreset? = null,
    val displayName: String = "",
    val baseUrl: String = "",
    val modelName: String = "",
    val apiKey: String = "",
    val flavor: ApiFlavor = ApiFlavor.OPENAI,
    val apiKeyVisible: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: ConnectionTestResult? = null,
    val errorMessage: String? = null,
    val savedProvider: ProviderConfig? = null,
) {
    /** Live validation gate for the credentials step. */
    val credentialsValid: Boolean
        get() = baseUrl.isNotBlank() &&
            modelName.isNotBlank() &&
            apiKey.isNotBlank() &&
            (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val connectionTester: ConnectionTester,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    val presets: List<ProviderPreset> = ProviderPresets.all

    // ---------------------------------------------------------------- navigation

    fun goTo(step: OnboardingStep) {
        _state.update { it.copy(step = step, errorMessage = null) }
    }

    fun next() {
        val current = _state.value.step
        val next = when (current) {
            OnboardingStep.WELCOME -> OnboardingStep.PROVIDER
            OnboardingStep.PROVIDER ->
                if (_state.value.choice == ProviderChoice.Preset && _state.value.selectedPreset == null) {
                    _state.update { it.copy(errorMessage = "请先选择一个模型服务，或选择「自定义接入」。") }
                    return
                } else {
                    OnboardingStep.CREDENTIALS
                }
            OnboardingStep.CREDENTIALS ->
                if (!_state.value.credentialsValid) {
                    _state.update { it.copy(errorMessage = "请完整填写接口地址、模型名称与 API Key。") }
                    return
                } else {
                    OnboardingStep.VERIFY
                }
            OnboardingStep.VERIFY -> OnboardingStep.DONE
            OnboardingStep.DONE -> OnboardingStep.DONE
        }
        goTo(next)
    }

    fun back() {
        val previous = when (_state.value.step) {
            OnboardingStep.WELCOME -> OnboardingStep.WELCOME
            OnboardingStep.PROVIDER -> OnboardingStep.WELCOME
            OnboardingStep.CREDENTIALS -> OnboardingStep.PROVIDER
            OnboardingStep.VERIFY -> OnboardingStep.CREDENTIALS
            OnboardingStep.DONE -> OnboardingStep.VERIFY
        }
        goTo(previous)
    }

    // ------------------------------------------------------------------ selection

    fun choosePreset() {
        _state.update { it.copy(choice = ProviderChoice.Preset, errorMessage = null) }
    }

    fun chooseCustom() {
        _state.update {
            it.copy(
                choice = ProviderChoice.Custom,
                selectedPreset = null,
                displayName = "",
                baseUrl = "",
                modelName = "",
                apiKey = "",
                flavor = ApiFlavor.OPENAI,
                errorMessage = null,
            )
        }
    }

    fun selectPreset(preset: ProviderPreset) {
        _state.update {
            it.copy(
                selectedPreset = preset,
                displayName = preset.displayName,
                baseUrl = preset.baseUrl,
                modelName = preset.defaultModel,
                flavor = preset.flavor,
                apiKey = if (preset.id == "ollama") "ollama" else it.apiKey,
                errorMessage = null,
                testResult = null,
            )
        }
    }

    // -------------------------------------------------------------------- inputs

    fun onDisplayNameChange(value: String) = _state.update { it.copy(displayName = value, errorMessage = null) }

    fun onBaseUrlChange(value: String) = _state.update {
        it.copy(
            baseUrl = value,
            // Re-infer the protocol whenever the URL changes and it isn't a preset.
            flavor = if (it.choice == ProviderChoice.Custom) ProviderPresets.guessFlavor(value) else it.flavor,
            errorMessage = null,
            testResult = null,
        )
    }

    fun onModelNameChange(value: String) = _state.update { it.copy(modelName = value, errorMessage = null) }

    fun onApiKeyChange(value: String) = _state.update {
        it.copy(apiKey = value, errorMessage = null, testResult = null)
    }

    fun onFlavorChange(flavor: ApiFlavor) = _state.update { it.copy(flavor = flavor, testResult = null) }

    fun toggleApiKeyVisibility() = _state.update { it.copy(apiKeyVisible = !it.apiKeyVisible) }

    fun selectSuggestedModel(model: String) = _state.update { it.copy(modelName = model, testResult = null) }

    // ------------------------------------------------------------------- testing

    fun testConnection() {
        val current = _state.value
        if (!current.credentialsValid) {
            _state.update { it.copy(errorMessage = "请先完整填写配置信息。") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isTesting = true, testResult = null, errorMessage = null) }
            val result = connectionTester.test(
                ProviderConfig(
                    id = "onboarding-probe",
                    displayName = current.displayName.ifBlank { "自定义服务" },
                    baseUrl = current.baseUrl,
                    modelName = current.modelName,
                    flavor = current.flavor,
                ),
                current.apiKey,
            )
            _state.update {
                it.copy(
                    isTesting = false,
                    testResult = result,
                    errorMessage = if (result is ConnectionTestResult.Failure) result.message else null,
                )
            }
        }
    }

    // --------------------------------------------------------------------- commit

    /** Persists the provider and marks onboarding complete. Called from the verify step. */
    fun finish(onSuccess: () -> Unit) {
        val current = _state.value
        if (current.savedProvider != null) {
            onSuccess()
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isTesting = true, errorMessage = null) }

            val result = providerRepository.provisionAndActivate(
                displayName = current.displayName.ifBlank { "我的模型服务" },
                baseUrl = current.baseUrl,
                modelName = current.modelName,
                apiKey = current.apiKey,
                flavor = current.flavor,
            )

            result.fold(
                onSuccess = { provider ->
                    settingsRepository.completeOnboarding()
                    _state.update {
                        it.copy(
                            isTesting = false,
                            savedProvider = provider,
                            testResult = ConnectionTestResult.Success(0, provider.modelName),
                            step = OnboardingStep.DONE,
                        )
                    }
                    onSuccess()
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isTesting = false,
                            errorMessage = error.message ?: "保存失败，请检查配置后重试。",
                        )
                    }
                },
            )
        }
    }

    /** Skips setup entirely — the user can configure later from settings. */
    fun skip(onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.completeOnboarding()
            onDone()
        }
    }
}
