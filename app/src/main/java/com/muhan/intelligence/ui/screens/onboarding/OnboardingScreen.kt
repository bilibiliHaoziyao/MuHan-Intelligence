package com.muhan.intelligence.ui.screens.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muhan.intelligence.data.remote.ProviderPreset
import com.muhan.intelligence.domain.model.ApiFlavor
import com.muhan.intelligence.domain.model.ConnectionTestResult
import com.muhan.intelligence.ui.components.BrandMark
import com.muhan.intelligence.ui.components.clickableNoIndication

/**
 * First-run wizard.
 *
 * The flow is deliberately linear and forgiving: every step can go back, nothing
 * is saved until the user has seen a successful connection test, and there is
 * always an escape hatch ("稍后配置") so a broken API key can never trap someone
 * outside the app.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        WizardHeader(
            step = state.step,
            onBack = viewModel::back,
            onSkip = { viewModel.skip(onFinished) },
        )

        Box(Modifier.weight(1f)) {
            // Crossfade between steps: only one pane is composed at a time, so
            // per-step scroll state resets naturally when moving forwards/backwards.
            Crossfade(
                targetState = state.step,
                animationSpec = tween(280),
                label = "onboardingStep",
            ) { step ->
                when (step) {
                    OnboardingStep.WELCOME -> WelcomeStep(onNext = viewModel::next)
                    OnboardingStep.PROVIDER -> ProviderStep(state = state, viewModel = viewModel)
                    OnboardingStep.CREDENTIALS -> CredentialsStep(state = state, viewModel = viewModel)
                    OnboardingStep.VERIFY -> VerifyStep(
                        state = state,
                        viewModel = viewModel,
                        onDone = onFinished,
                    )
                    OnboardingStep.DONE -> DoneStep(onEnter = onFinished)
                }
            }
        }
    }
}

@Composable
private fun WizardHeader(
    step: OnboardingStep,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val totalSteps = 4
    val index = when (step) {
        OnboardingStep.WELCOME -> 0
        OnboardingStep.PROVIDER -> 1
        OnboardingStep.CREDENTIALS -> 2
        OnboardingStep.VERIFY -> 3
        OnboardingStep.DONE -> 4
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (step == OnboardingStep.WELCOME || step == OnboardingStep.DONE) {
                            Modifier
                        } else {
                            Modifier.clickableNoIndication(onClick = onBack)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (step != OnboardingStep.WELCOME && step != OnboardingStep.DONE) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint = scheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            if (step != OnboardingStep.DONE) {
                TextButton(onClick = onSkip) {
                    Text(
                        text = "稍后配置",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (step != OnboardingStep.WELCOME && step != OnboardingStep.DONE) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(totalSteps) { i ->
                    val active = i <= index - 1
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (active) scheme.primary else scheme.outlineVariant.copy(alpha = 0.5f),
                            ),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- step 1: welcome

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(size = 88.dp, animated = true)
        Spacer(Modifier.height(26.dp))
        Text(
            text = "慕寒智能",
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "0.1.0-dev",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.primary,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = "一个由你自己掌管 API Key 的 AI 对话客户端\n无需注册，数据只留在你的设备上",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(36.dp))

        listOf(
            Triple(Icons.Outlined.Key, "自带密钥", "填入你自己的 API Key，不经过任何中转服务器"),
            Triple(Icons.Outlined.Shield, "本地优先", "密钥加密存储，对话记录仅保存在本机"),
            Triple(Icons.Outlined.CheckCircleOutline, "广泛兼容", "支持 DeepSeek、Kimi、智谱、OpenAI、Claude 等"),
        ).forEach { (icon, title, desc) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurface,
                    )
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(40.dp))

        PrimaryButton(text = "开始设置", onClick = onNext, modifier = Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- step 2: provider

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProviderStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "选择模型服务",
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "挑选一个常用服务商快速开始，或在下方选择自定义接入",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            items(viewModel.presets, key = { it.id }) { preset ->
                PresetCard(
                    preset = preset,
                    selected = state.selectedPreset?.id == preset.id,
                    onClick = { viewModel.selectPreset(preset) },
                )
            }

            item {
                Spacer(Modifier.height(4.dp))
                CustomEndpointCard(
                    selected = state.choice == ProviderChoice.Custom,
                    onClick = { viewModel.chooseCustom() },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        BottomBar(
            error = state.errorMessage,
            primaryText = "下一步",
            onPrimary = viewModel::next,
        )
    }
}

@Composable
private fun PresetCard(
    preset: ProviderPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerLow)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp),
            )
            .clickableNoIndication(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (selected) {
                        scheme.primary
                    } else {
                        scheme.surfaceContainerHighest
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = preset.displayName.take(1),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.width(13.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = preset.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = preset.description,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "默认模型 · ${preset.defaultModel}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }

        if (selected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

@Composable
private fun CustomEndpointCard(selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerLow)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp),
            )
            .clickableNoIndication(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) scheme.primary else scheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.OpenInNew,
                contentDescription = null,
                tint = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "自定义接入",
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            )
            Text(
                text = "兼容 OpenAI / Anthropic / Gemini 协议的任意服务",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

// ------------------------------------------------------------ step 3: credentials

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CredentialsStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val scheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "填写接入信息",
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "这些信息只保存在你的设备上，密钥会使用系统级加密存储",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            LabeledField(
                label = "服务名称",
                value = state.displayName,
                onValueChange = viewModel::onDisplayNameChange,
                placeholder = "例如：我的 DeepSeek",
            )

            Spacer(Modifier.height(14.dp))

            if (state.choice == ProviderChoice.Custom) {
                LabeledField(
                    label = "接口地址 (Base URL)",
                    value = state.baseUrl,
                    onValueChange = viewModel::onBaseUrlChange,
                    placeholder = "https://api.example.com/v1",
                    helper = "填写到版本号即可，例如 https://api.deepseek.com",
                )
                Spacer(Modifier.height(14.dp))

                FlavorSelector(
                    selected = state.flavor,
                    onSelect = viewModel::onFlavorChange,
                )
                Spacer(Modifier.height(14.dp))
            } else {
                LabeledField(
                    label = "接口地址 (Base URL)",
                    value = state.baseUrl,
                    onValueChange = viewModel::onBaseUrlChange,
                    placeholder = "https://api.example.com/v1",
                )
                Spacer(Modifier.height(14.dp))
            }

            LabeledField(
                label = "模型名称",
                value = state.modelName,
                onValueChange = viewModel::onModelNameChange,
                placeholder = "例如：deepseek-chat",
            )

            state.selectedPreset?.let { preset ->
                if (preset.modelSuggestions.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "常用模型",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(7.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        preset.modelSuggestions.forEach { model ->
                            ModelChip(
                                model = model,
                                selected = state.modelName == model,
                                onClick = { viewModel.selectSuggestedModel(model) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            LabeledField(
                label = "API Key",
                value = state.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                placeholder = state.selectedPreset?.apiKeyHint ?: "sk-...",
                isSecret = !state.apiKeyVisible,
                trailing = {
                    Icon(
                        imageVector = if (state.apiKeyVisible) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = if (state.apiKeyVisible) "隐藏" else "显示",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .clickableNoIndication(onClick = viewModel::toggleApiKeyVisibility),
                    )
                },
            )

            state.selectedPreset?.let { preset ->
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickableNoIndication {
                            runCatching { uriHandler.openUri(preset.keyUrl) }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "前往 ${preset.displayName} 获取 API Key",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        BottomBar(
            error = state.errorMessage,
            primaryText = "下一步",
            onPrimary = viewModel::next,
            primaryEnabled = state.credentialsValid,
        )
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    helper: String? = null,
    isSecret: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
            singleLine = true,
            visualTransformation = if (isSecret) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            trailingIcon = trailing,
            shape = RoundedCornerShape(13.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        if (helper != null) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = helper,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FlavorSelector(selected: ApiFlavor, onSelect: (ApiFlavor) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "协议类型",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(
                ApiFlavor.OPENAI to "OpenAI 兼容",
                ApiFlavor.ANTHROPIC to "Anthropic",
                ApiFlavor.GEMINI to "Gemini",
            ).forEach { (flavor, label) ->
                val active = selected == flavor
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (active) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        )
                        .border(
                            1.dp,
                            if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            },
                            RoundedCornerShape(11.dp),
                        )
                        .clickableNoIndication { onSelect(flavor) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelChip(model: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) scheme.primary else scheme.surfaceContainerHigh)
            .clickableNoIndication(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(
            text = model,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- step 4: verify

@Composable
private fun VerifyStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    onDone: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "测试连接",
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "先确认配置可用，再开始对话",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            Surface(
                color = scheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    ConfigLine("服务名称", state.displayName.ifBlank { "未命名" })
                    ConfigLine("接口地址", state.baseUrl)
                    ConfigLine(
                        "协议",
                        when (state.flavor) {
                            ApiFlavor.OPENAI -> "OpenAI 兼容"
                            ApiFlavor.ANTHROPIC -> "Anthropic Messages"
                            ApiFlavor.GEMINI -> "Google Gemini"
                        },
                    )
                    ConfigLine("模型", state.modelName)
                    ConfigLine("API Key", maskKey(state.apiKey))
                }
            }

            Spacer(Modifier.height(18.dp))

            when (val result = state.testResult) {
                is ConnectionTestResult.Success -> ResultCard(
                    success = true,
                    title = "连接成功",
                    detail = buildString {
                        append("响应耗时 ${result.latencyMs} ms")
                        result.modelEcho?.let { append(" · 服务端返回模型 $it") }
                    },
                )

                is ConnectionTestResult.Failure -> Column {
                    ResultCard(
                        success = false,
                        title = "连接失败",
                        detail = result.message,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "常见原因：API Key 填写错误、接口地址缺少 /v1、账户余额不足、模型名称不存在。",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }

                null -> Unit
            }

            Spacer(Modifier.height(20.dp))

            if (state.isTesting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                        color = scheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "正在验证…",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                SecondaryButton(
                    text = if (state.testResult == null) "开始测试" else "重新测试",
                    onClick = viewModel::testConnection,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
        }

        BottomBar(
            error = null,
            primaryText = "完成并开始使用",
            onPrimary = { viewModel.finish(onDone) },
            primaryEnabled = !state.isTesting,
            secondary = {
                state.selectedPreset?.let { preset ->
                    TextButton(onClick = { runCatching { uriHandler.openUri(preset.keyUrl) } }) {
                        Text("去获取 Key", style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
        )
    }
}

@Composable
private fun ConfigLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ResultCard(success: Boolean, title: String, detail: String) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (success) scheme.primaryContainer else scheme.errorContainer
    val fg = if (success) scheme.onPrimaryContainer else scheme.onErrorContainer

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (success) Icons.Filled.CheckCircle else Icons.Outlined.Shield,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(11.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = fg,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = fg,
            )
        }
    }
}

// -------------------------------------------------------------- step 5: done

@Composable
private fun DoneStep(onEnter: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val scale by animateFloatAsState(
            targetValue = 1f,
            animationSpec = tween(420),
            label = "done",
        )
        Box(
            modifier = Modifier.size(88.dp * scale),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(72.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "一切就绪",
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "现在可以开始和慕寒智能对话了\n随时在「设置」中更换模型或调整参数",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        PrimaryButton(
            text = "开始对话",
            onClick = onEnter,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ------------------------------------------------------------------- shared bits

@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(
                if (enabled) {
                    Brush.horizontalGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                    )
                } else {
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    )
                },
                RoundedCornerShape(15.dp),
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(scheme.surfaceContainerHigh)
            .clickableNoIndication(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
        )
    }
}

@Composable
private fun BottomBar(
    error: String?,
    primaryText: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    secondary: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (error != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                    .padding(11.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (secondary != null) {
                secondary()
                Spacer(Modifier.width(8.dp))
            }
            PrimaryButton(
                text = primaryText,
                onClick = onPrimary,
                enabled = primaryEnabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun maskKey(key: String): String = when {
    key.isBlank() -> "未填写"
    key.length <= 10 -> "•".repeat(key.length)
    else -> key.take(5) + "•".repeat(8) + key.takeLast(4)
}
