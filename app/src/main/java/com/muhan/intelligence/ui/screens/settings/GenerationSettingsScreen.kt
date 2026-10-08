package com.muhan.intelligence.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muhan.intelligence.domain.model.GenerationSettings
import com.muhan.intelligence.ui.components.SectionLabel
import com.muhan.intelligence.ui.components.clickableNoIndication

@Composable
fun GenerationSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    // Local draft so slider drags stay smooth; committed on release.
    var temperature by remember { mutableStateOf(state.preferences.generation.temperature) }
    var topP by remember { mutableStateOf(state.preferences.generation.topP) }
    var maxTokens by remember { mutableStateOf(state.preferences.generation.maxTokens) }
    var systemPrompt by remember { mutableStateOf(state.preferences.generation.systemPrompt) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding(),
    ) {
        SettingsTopBar(
            title = "生成参数",
            onBack = onBack,
            subtitle = "影响模型如何组织回答",
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            SectionLabel("采样")
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                SliderRow(
                    title = "温度 (temperature)",
                    subtitle = "值越高回答越发散，越低越稳定确定",
                    value = temperature,
                    range = 0f..2f,
                    steps = 19,
                    format = { String.format("%.1f", it) },
                    onValueChange = { temperature = it },
                    onValueChangeFinished = {
                        viewModel.updateGeneration(
                            state.preferences.generation.copy(temperature = temperature),
                        )
                    },
                )
                Divider()
                SliderRow(
                    title = "核采样 (top_p)",
                    subtitle = "控制候选词范围，通常保持 1.0 即可",
                    value = topP,
                    range = 0.1f..1f,
                    steps = 8,
                    format = { String.format("%.1f", it) },
                    onValueChange = { topP = it },
                    onValueChangeFinished = {
                        viewModel.updateGeneration(
                            state.preferences.generation.copy(topP = topP),
                        )
                    },
                )
                Divider()
                SliderRow(
                    title = "最大回复长度",
                    subtitle = "单次回答允许生成的最大 token 数",
                    value = maxTokens.toFloat(),
                    range = 512f..16384f,
                    steps = 30,
                    format = { "${it.toInt()}" },
                    onValueChange = { maxTokens = it.toInt() },
                    onValueChangeFinished = {
                        viewModel.updateGeneration(
                            state.preferences.generation.copy(maxTokens = maxTokens),
                        )
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            SectionLabel("系统提示词")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                placeholder = {
                    Text(
                        text = "设定助手的身份、语气与回答风格…",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Default,
                    lineHeight = 21.dp.value.let { androidx.compose.ui.unit.TextUnit(it, androidx.compose.ui.unit.TextUnitType.Sp) },
                ),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surfaceContainerHigh)
                    .clickableNoIndication {
                        viewModel.updateGeneration(
                            state.preferences.generation.copy(systemPrompt = systemPrompt),
                        )
                    }
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "保存系统提示词",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                )
            }

            Spacer(Modifier.height(20.dp))

            SectionLabel("行为")
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                SwitchRow(
                    icon = Icons.Outlined.Restore,
                    title = "流式输出",
                    subtitle = "逐字显示回复，关闭后等待完整回答",
                    checked = state.preferences.generation.streamEnabled,
                    onCheckedChange = {
                        viewModel.updateGeneration(state.preferences.generation.copy(streamEnabled = it))
                    },
                )
                Divider()
                SwitchRow(
                    icon = Icons.Outlined.Restore,
                    title = "携带历史上下文",
                    subtitle = "关闭后每次只发送当前这一轮消息",
                    checked = state.preferences.generation.sendHistory,
                    onCheckedChange = {
                        viewModel.updateGeneration(state.preferences.generation.copy(sendHistory = it))
                    },
                )
                Divider()
                SwitchRow(
                    icon = Icons.Outlined.Restore,
                    title = "显示思考过程",
                    subtitle = "对 DeepSeek-R1 等推理模型展示思维链",
                    checked = state.preferences.generation.reasoningEnabled,
                    onCheckedChange = {
                        viewModel.updateGeneration(state.preferences.generation.copy(reasoningEnabled = it))
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .clickableNoIndication(onClick = viewModel::resetGeneration)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Restore,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "恢复默认设置",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    SettingsMessageDialog(message = state.message, onDismiss = viewModel::dismissMessage)
}

@Composable
private fun SliderRow(
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.primaryContainer)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = format(value),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

/** Read-only view of the effective defaults, useful for debugging odd responses. */
@Composable
fun EffectiveConfigCard(settings: GenerationSettings) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerLow)
            .padding(14.dp),
    ) {
        Text(
            text = "当前生效参数",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "temperature=${settings.temperature}  top_p=${settings.topP}  " +
                "max_tokens=${settings.maxTokens}  stream=${settings.streamEnabled}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = scheme.onSurface,
        )
    }
}
