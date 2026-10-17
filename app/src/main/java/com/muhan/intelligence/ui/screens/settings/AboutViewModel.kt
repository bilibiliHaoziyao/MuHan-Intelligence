package com.muhan.intelligence.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muhan.intelligence.data.repository.AppUpdate
import com.muhan.intelligence.data.repository.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AboutUiState(
    val checking: Boolean = false,
    /** 手动检查到的新版本；非空时展示全屏更新页。 */
    val update: AppUpdate? = null,
    /** 「已是最新版本」或检查失败的提示。 */
    val message: String? = null,
)

/** 关于页（3.1.1）：手动检查更新。 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AboutUiState())
    val state: StateFlow<AboutUiState> = _state.asStateFlow()

    fun checkForUpdate() {
        if (_state.value.checking) return
        viewModelScope.launch {
            _state.update { it.copy(checking = true, message = null) }
            val result = updateRepository.check()
            when {
                result == null -> _state.update {
                    it.copy(checking = false, message = "检查失败，稍后再试")
                }
                result.hasUpdate -> _state.update {
                    it.copy(checking = false, update = result, message = null)
                }
                else -> _state.update {
                    it.copy(checking = false, message = "已是最新版本")
                }
            }
        }
    }

    fun dismissUpdate() = _state.update { it.copy(update = null) }
}
