package com.muhan.intelligence.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muhan.intelligence.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val mode: Mode = Mode.LOGIN,
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
) {
    enum class Mode { LOGIN, REGISTER }
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun toggleMode() = _state.update {
        it.copy(
            mode = if (it.mode == AuthUiState.Mode.LOGIN) AuthUiState.Mode.REGISTER else AuthUiState.Mode.LOGIN,
            error = null,
        )
    }

    fun submit() {
        val s = _state.value
        if (s.loading) return
        val email = s.email.trim()
        if (!email.contains("@")) {
            _state.update { it.copy(error = "请输入正确的邮箱") }
            return
        }
        if (s.password.length < 8) {
            _state.update { it.copy(error = "密码至少 8 位") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                if (s.mode == AuthUiState.Mode.LOGIN) {
                    authRepository.login(email, s.password)
                } else {
                    authRepository.register(email, s.password)
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(loading = false, error = e.message ?: "操作失败，请重试")
                }
            }.onSuccess {
                _state.update { it.copy(loading = false) }
            }
        }
    }
}
