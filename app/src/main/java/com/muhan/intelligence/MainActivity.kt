package com.muhan.intelligence

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.rememberNavController
import com.muhan.intelligence.data.repository.ProviderRepository
import com.muhan.intelligence.data.repository.SettingsRepository
import com.muhan.intelligence.domain.model.ThemeMode
import com.muhan.intelligence.ui.navigation.MuHanNavHost
import com.muhan.intelligence.ui.navigation.Routes
import com.muhan.intelligence.ui.theme.MuHanTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Decides where to send the user on cold start.
 *
 * Onboarding appears only when it has never been completed *and* no provider is
 * configured — someone who skipped the wizard but later added a key should not be
 * dragged back into it.
 *
 * The preference flow is collected *continuously* (not via [kotlinx.coroutines.flow.first]):
 * the theme picker in settings must take effect immediately, and it drives this
 * state — a one-shot read froze the theme for the whole process, which was the
 * "浅色/深色切换不生效" bug in 0.1.0.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val providerRepository: ProviderRepository,
) : ViewModel() {

    data class StartState(
        val resolved: Boolean = false,
        val onboardingCompleted: Boolean = false,
        val hasProvider: Boolean = false,
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val dynamicColor: Boolean = true,
    )

    private val _state = MutableStateFlow(StartState())
    val state: StateFlow<StartState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.preferences.collect { prefs ->
                _state.update {
                    it.copy(
                        resolved = true,
                        onboardingCompleted = prefs.onboardingCompleted,
                        themeMode = prefs.themeMode,
                        dynamicColor = prefs.dynamicColor,
                    )
                }
            }
        }
        viewModelScope.launch {
            providerRepository.observeActiveProvider().collect { provider ->
                _state.update { it.copy(hasProvider = provider != null) }
            }
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MuHanApp()
        }
    }
}

@Composable
private fun MuHanApp(viewModel: StartupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    MuHanTheme(
        themeMode = state.themeMode,
        dynamicColor = state.dynamicColor,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // Hold the first frame until the destination is known, otherwise a
            // configured user would flash the onboarding screen on every launch.
            if (state.resolved) {
                val navController = rememberNavController()
                val start = if (!state.onboardingCompleted && !state.hasProvider) {
                    Routes.ONBOARDING
                } else {
                    Routes.CHAT
                }
                MuHanNavHost(navController = navController, startDestination = start)
            }
        }
    }
}
