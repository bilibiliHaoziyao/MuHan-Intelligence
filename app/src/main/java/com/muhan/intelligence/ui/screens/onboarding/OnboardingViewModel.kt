package com.muhan.intelligence.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muhan.intelligence.data.remote.ConnectionTester
import com.muhan.intelligence.data.remote.ProviderPreset
import com.muhan.intelligence.data.remote.ProviderPresets
import com.muhan.intelligence.data.repository.ProviderRepository
import com.muhan.intelligence.data.repository.SettingsRepository
import com.muhan.intelligence.domain.model.ApiFlavor
import com.muhan.intelligence.domain.model.BUILTIN_PROVIDER_ID
import com.muhan.intelligence.domain.model.ConnectionTestResult
import com.muhan.intelligence.domain.model.ProviderConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which pane of the wizard is showing. */
enum class OnboardingStep { PERSONA, PROVIDER, CREDENTIALS, VERIFY, DONE }

/** How the user is picking their provider on the second step. */
sealed interface ProviderChoice {
    data object Preset : ProviderChoice
    data object Custom : ProviderChoice
    /** 使用内置「慕寒智能」（已预置、开箱即用），完成时不新建服务。 */
    data object BuiltIn : ProviderChoice
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.PERSONA,
    val persona: com.muhan.intelligence.domain.model.Persona? = null,
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
    /** 内置「慕寒智能」是否已就绪（用于向导优先提供「开箱即用」选项）。 */
    val builtInReady: Boolean = false,
    /** 迪克模式的默认能力开关（完成引导时写入设置）。 */
    val advancedThinking: Boolean = false,
    val advancedWebSearch: Boolean = false,
) {
    /** Live validation gate for the credentials step. */
    val credentialsValid: Boolean
        get() = baseUrl.isNotBlank() &&
            modelName.isNotBlank() &&
            apiKey.isNotBlank() &&
            (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))

    val isRookie: Boolean get() = persona == com.muhan.intelligence.domain.model.Persona.ROOKIE
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

    init {
        // 内置「慕寒智能」已预置并激活时，向导默认走「开箱即用」路径，
        // 避免已配置好的用户被强制重复填写凭据。
        viewModelScope.launch {
            val builtInActive = providerRepository.getActiveProvider()?.id == BUILTIN_PROVIDER_ID
            if (builtInActive) {
                _state.update { it.copy(builtInReady = true, choice = ProviderChoice.BuiltIn) }
            }
        }
    }

    // ---------------------------------------------------------------- navigation

    fun goTo(step: OnboardingStep) {
        _state.update { it.copy(step = step, errorMessage = null) }
    }

    fun next() {
        val current = _state.value.step
        val next = when (current) {
            OnboardingStep.PERSONA ->
                if (_state.value.persona == null) {
                    _state.update { it.copy(errorMessage = "先选一个模式。") }
                    return
                } else {
                    OnboardingStep.PROVIDER
                }
            OnboardingStep.PROVIDER ->
                when (_state.value.choice) {
                    ProviderChoice.BuiltIn -> OnboardingStep.DONE
                    ProviderChoice.Preset ->
                        if (_state.value.selectedPreset == null) {
                            _state.update {
                                it.copy(errorMessage = "选一个模型服务，或者直接用内置的慕寒智能。")
                            }
                            return
                        } else {
                            OnboardingStep.CREDENTIALS
                        }
                    ProviderChoice.Custom -> OnboardingStep.CREDENTIALS
                }
            OnboardingStep.CREDENTIALS ->
                if (!_state.value.credentialsValid) {
                    _state.update { it.copy(errorMessage = "接口地址、模型名称、API Key 都要填。") }
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
            OnboardingStep.PERSONA -> OnboardingStep.PERSONA
            OnboardingStep.PROVIDER -> OnboardingStep.PERSONA
            OnboardingStep.CREDENTIALS -> OnboardingStep.PROVIDER
            OnboardingStep.VERIFY -> OnboardingStep.CREDENTIALS
            OnboardingStep.DONE -> OnboardingStep.VERIFY
        }
        goTo(previous)
    }

    // ------------------------------------------------------------------ persona

    fun selectPersona(persona: com.muhan.intelligence.domain.model.Persona) {
        _state.update { it.copy(persona = persona, errorMessage = null) }
    }

    fun setAdvancedThinking(enabled: Boolean) =
        _state.update { it.copy(advancedThinking = enabled) }

    fun setAdvancedWebSearch(enabled: Boolean) =
        _state.update { it.copy(advancedWebSearch = enabled) }

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

    /** 选择内置「慕寒智能」——开箱即用，完成时不新建服务。 */
    fun selectBuiltIn() {
        _state.update {
            it.copy(
                choice = ProviderChoice.BuiltIn,
                selectedPreset = null,
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
            _state.update { it.copy(errorMessage = "配置还没填完。") }
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

        // 内置「慕寒智能」路径：已预置好，无需新建服务，直接标记引导完成。
        if (current.choice == ProviderChoice.BuiltIn) {
            viewModelScope.launch {
                settingsRepository.completeOnboarding()
                _state.update { it.copy(step = OnboardingStep.DONE, isTesting = false) }
                onSuccess()
            }
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
                    // 迪克模式：把引导里选的能力开关写为全局默认。
                    if (current.persona == com.muhan.intelligence.domain.model.Persona.GEEK) {
                        val prefs = settingsRepository.preferences.first()
                        settingsRepository.updateGeneration(
                            prefs.generation.copy(
                                reasoningEnabled = current.advancedThinking,
                                webSearchEnabled = current.advancedWebSearch,
                            ),
                        )
                    }
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
                            errorMessage = error.message ?: "没保存上，检查下配置。",
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
