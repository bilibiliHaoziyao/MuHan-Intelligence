package com.muhan.intelligence.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muhan.intelligence.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val code: String = "",
    val loading: Boolean = false,
    val sendingCode: Boolean = false,
    /** 重新发送验证码的冷却剩余秒数，0 表示可发送。 */
    val codeCooldown: Int = 0,
    val codeSent: Boolean = false,
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

    private var cooldownJob: Job? = null

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onCodeChange(value: String) {
        val digits = value.filter { it.isDigit() }.take(6)
        _state.update { it.copy(code = digits, error = null) }
    }

    fun toggleMode() = _state.update {
        it.copy(
            mode = if (it.mode == AuthUiState.Mode.LOGIN) AuthUiState.Mode.REGISTER else AuthUiState.Mode.LOGIN,
            error = null,
        )
    }

    /** 发送注册验证码（3.0.1）。 */
    fun sendCode() {
        val s = _state.value
        if (s.sendingCode || s.codeCooldown > 0) return
        val email = s.email.trim()
        if (!email.contains("@") || !email.contains(".")) {
            _state.update { it.copy(error = "邮箱填得不对") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(sendingCode = true, error = null) }
            runCatching { authRepository.sendCode(email) }
                .onFailure { e ->
                    val raw = e.message ?: "验证码没发出去"
                    // 服务端 429 会给出「请 N 秒后再试」，把秒数直接转成倒计时。
                    val wait = Regex("""(\d+)\s*秒""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
                    _state.update { it.copy(sendingCode = false, error = raw) }
                    if (wait != null && wait in 1..600) startCooldown(wait)
                }
                .onSuccess {
                    _state.update {
                        it.copy(sendingCode = false, codeSent = true, error = null)
                    }
                    startCooldown(60)
                }
        }
    }

    private fun startCooldown(seconds: Int) {
        cooldownJob?.cancel()
        _state.update { it.copy(codeCooldown = seconds) }
        cooldownJob = viewModelScope.launch {
            var left = seconds
            while (left > 0) {
                delay(1000)
                left -= 1
                _state.update { it.copy(codeCooldown = left) }
            }
        }
    }

    fun submit() {
        val s = _state.value
        if (s.loading) return
        val email = s.email.trim()
        if (!email.contains("@") || !email.contains(".")) {
            _state.update { it.copy(error = "邮箱格式不对") }
            return
        }
        if (s.password.length < 8) {
            _state.update { it.copy(error = "密码至少 8 位") }
            return
        }
        if (s.mode == AuthUiState.Mode.REGISTER && s.code.length != 6) {
            _state.update { it.copy(error = "验证码是 6 位数字") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                if (s.mode == AuthUiState.Mode.LOGIN) {
                    authRepository.login(email, s.password)
                } else {
                    authRepository.register(email, s.password, s.code)
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(loading = false, error = e.message ?: "没成功，再试一次")
                }
            }.onSuccess {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    override fun onCleared() {
        cooldownJob?.cancel()
        super.onCleared()
    }
}
