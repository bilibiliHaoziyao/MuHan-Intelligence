package com.muhan.intelligence

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.muhan.intelligence.data.local.LogRepository
import com.muhan.intelligence.data.local.PreferencesStore
import com.muhan.intelligence.data.repository.AuthRepository
import com.muhan.intelligence.data.repository.ProviderRepository
import com.muhan.intelligence.data.repository.SettingsRepository
import com.muhan.intelligence.domain.model.ThemeMode
import com.muhan.intelligence.ui.components.BrandMark
import com.muhan.intelligence.ui.navigation.MuHanNavHost
import com.muhan.intelligence.ui.navigation.Routes
import com.muhan.intelligence.ui.screens.auth.AuthScreen
import com.muhan.intelligence.ui.screens.update.ForceUpdateScreen
import com.muhan.intelligence.ui.theme.MuHanTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Decides where to send the user on cold start.
 *
 * Onboarding appears whenever it has not been completed. The built-in 「慕寒智能」
 * is seeded as the default provider on first launch, so the wizard offers an
 * "开箱即用" path instead of forcing a duplicate configuration.
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
    private val authRepository: AuthRepository,
    private val updateRepository: com.muhan.intelligence.data.repository.UpdateRepository,
) : ViewModel() {

    data class StartState(
        val resolved: Boolean = false,
        val onboardingCompleted: Boolean = false,
        val hasProvider: Boolean = false,
        val loggedIn: Boolean = false,
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val dynamicColor: Boolean = true,
        /** 服务端下发的版本信息（3.0.1，检查完成前为 null）。 */
        val update: com.muhan.intelligence.data.repository.AppUpdate? = null,
        val updateChecked: Boolean = false,
    )

    private val _state = MutableStateFlow(StartState())
    val state: StateFlow<StartState> = _state.asStateFlow()

    init {
        // 3.0.1：每次冷启动检查更新（超时保护，离线用户不被卡住）。
        viewModelScope.launch {
            val update = kotlinx.coroutines.withTimeoutOrNull(2500) {
                updateRepository.check()
            }
            _state.update { it.copy(update = update, updateChecked = true) }
        }

        viewModelScope.launch {
            // 1.0.0：首次启动先种子化内置「慕寒智能」默认服务，确保 hasProvider 在 resolved
            // 之前已确定，从而跳过引导直接进聊天（避免新装用户误入引导页的时序竞态）。
            runCatching { providerRepository.ensureDefaultProvider() }

            // 先读取一次初始值，确保 resolved 时 hasProvider 已是正确结果。
            val prefs = settingsRepository.preferences.first()
            val activeProvider = providerRepository.observeActiveProvider().first()
            _state.update {
                it.copy(
                    resolved = true,
                    onboardingCompleted = prefs.onboardingCompleted,
                    themeMode = prefs.themeMode,
                    dynamicColor = prefs.dynamicColor,
                    hasProvider = activeProvider != null,
                    loggedIn = authRepository.isLoggedIn,
                )
            }

            // 之后持续观察，保证主题/激活服务/登录态变更能即时生效。
            launch {
                settingsRepository.preferences.collect { p ->
                    _state.update {
                        it.copy(
                            onboardingCompleted = p.onboardingCompleted,
                            themeMode = p.themeMode,
                            dynamicColor = p.dynamicColor,
                        )
                    }
                }
            }
            launch {
                providerRepository.observeActiveProvider().collect { provider ->
                    _state.update { it.copy(hasProvider = provider != null) }
                }
            }
            launch {
                authRepository.sessionState.collect { session ->
                    _state.update { it.copy(loggedIn = session != null) }
                }
            }
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var preferencesStore: PreferencesStore

    @Inject lateinit var logRepository: LogRepository

    /**
     * 0.3.0：首次启动申请存储权限（读取图片附件 / 导出日志到外部存储时用到）。
     * 仅弹一次，之后由 [PreferencesStore] 标记，不再打扰用户。
     */
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.all { it }
        logRepository.info(
            "Permission",
            "存储权限申请结果：${if (granted) "已授予" else "被拒绝"}",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        requestStoragePermissionIfNeeded()

        setContent {
            MuHanApp()
        }
    }

    private fun requestStoragePermissionIfNeeded() {
        lifecycleScope.launch {
            val prefs = preferencesStore.preferences.first()
            if (prefs.storagePermissionRequested) return@launch

            val needed = storagePermissionsForSdk()
            val allGranted = needed.all {
                checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (!allGranted) {
                logRepository.info("Permission", "首次启动申请存储权限")
                storagePermissionLauncher.launch(needed.toTypedArray())
            }
            preferencesStore.setStoragePermissionRequested(true)
        }
    }

    private fun storagePermissionsForSdk(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            listOf(Manifest.permission.READ_MEDIA_IMAGES)
        else ->
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
    }
}

@Composable
private fun MuHanApp(viewModel: StartupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var optionalUpdateDismissed by remember { mutableStateOf(false) }

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
                val update = state.update
                when {
                    // 3.0.1：低于最低支持版本 → 全屏强制更新（最高优先级，不可关闭）。
                    update != null && update.isForced -> {
                        ForceUpdateScreen(update = update) {
                            (context as? android.app.Activity)?.finish()
                        }
                    }
                    // 版本检查尚未完成（最多等 2.5 秒），先显示品牌占位，避免闪主界面。
                    !state.updateChecked -> StartupPlaceholder()
                    !state.loggedIn -> AuthScreen()
                    else -> {
                        val navController = rememberNavController()
                        val start = if (!state.onboardingCompleted) {
                            Routes.ONBOARDING
                        } else {
                            Routes.CHAT_START
                        }
                        MuHanNavHost(navController = navController, startDestination = start)
                    }
                }

                // 非强制更新：每次启动提示一次，可稍后。
                if (update != null && !update.isForced && update.hasUpdate && !optionalUpdateDismissed) {
                    OptionalUpdateDialog(
                        update = update,
                        onDismiss = { optionalUpdateDismissed = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun StartupPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BrandMark(size = 64.dp)
    }
}

@Composable
private fun OptionalUpdateDialog(
    update: com.muhan.intelligence.data.repository.AppUpdate,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${update.latestName}") },
        text = {
            Text("当前版本 ${update.currentName}。更新后可获得最新改进，且不会丢失对话与设置。")
        },
        confirmButton = {
            TextButton(
                onClick = {
                    com.muhan.intelligence.ui.screens.update.openUrl(context, update.downloadUrl)
                    onDismiss()
                },
            ) { Text("立即更新") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后") }
        },
    )
}
