package com.muhan.intelligence.ui.screens.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muhan.intelligence.domain.model.ProviderConfig
import com.muhan.intelligence.domain.model.ThemeMode
import com.muhan.intelligence.ui.components.SectionLabel
import com.muhan.intelligence.ui.components.clickableNoIndication

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenGeneration: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLogs: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        SettingsTopBar(title = "设置", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(10.dp))

            // ---------------- Model services ----------------
            SectionLabel("模型服务")
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                state.providers.forEachIndexed { index, provider ->
                    ProviderRow(
                        provider = provider,
                        active = provider.id == state.activeProviderId,
                        onClick = { viewModel.activateProvider(provider.id) },
                    )
                    if (index != state.providers.lastIndex) Divider()
                }

                if (state.providers.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "还没有添加模型服务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Divider()
                }

                SettingsRow(
                    icon = Icons.Outlined.Add,
                    title = "添加 / 管理模型服务",
                    subtitle = "${state.providers.size} 个已配置",
                    trailing = Icons.Outlined.ChevronRight,
                    onClick = onOpenProviders,
                )
            }

            Spacer(Modifier.height(20.dp))

            // ---------------- Generation ----------------
            SectionLabel("对话")
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                SettingsRow(
                    icon = Icons.Outlined.Tune,
                    title = "生成参数",
                    subtitle = "温度 ${state.preferences.generation.temperature} · 最大 ${state.preferences.generation.maxTokens} tokens",
                    trailing = Icons.Outlined.ChevronRight,
                    onClick = onOpenGeneration,
                )
            }

            Spacer(Modifier.height(20.dp))

            // ---------------- Appearance ----------------
            SectionLabel("外观")
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                ThemeSelector(
                    current = state.preferences.themeMode,
                    onSelect = viewModel::setThemeMode,
                )
                Divider()
                SwitchRow(
                    icon = Icons.Outlined.Palette,
                    title = "动态取色",
                    subtitle = "跟随系统壁纸配色（Android 12 及以上）",
                    checked = state.preferences.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }

            Spacer(Modifier.height(20.dp))

            // ---------------- Data ----------------
            SectionLabel("数据")
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                SettingsRow(
                    icon = Icons.Outlined.DeleteOutline,
                    title = "清空全部对话",
                    subtitle = "删除本机保存的所有对话记录",
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirmClear = true },
                )
                Divider()
                SettingsRow(
                    icon = Icons.Outlined.Refresh,
                    title = "重新运行新手引导",
                    subtitle = "再次查看设置向导",
                    onClick = viewModel::restartOnboarding,
                )
            }

            Spacer(Modifier.height(20.dp))

            // ---------------- Diagnostics ----------------
            SectionLabel("诊断")
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                SwitchRow(
                    icon = Icons.Outlined.Info,
                    title = "用户体验改善计划",
                    subtitle = "帮助我们改进产品（当前版本仅记录本地诊断日志）",
                    checked = state.preferences.uxImprovement,
                    onCheckedChange = viewModel::setUxImprovement,
                )
                Divider()
                SettingsRow(
                    icon = Icons.Outlined.Storage,
                    title = "查看日志",
                    subtitle = "警告及以上级别与崩溃记录，可用邮件发送",
                    trailing = Icons.Outlined.ChevronRight,
                    onClick = onOpenLogs,
                )
            }

            Spacer(Modifier.height(20.dp))

            // ---------------- About ----------------
            SectionLabel("关于")
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = "关于慕寒智能",
                    subtitle = "版本 ${state.versionName}",
                    trailing = Icons.Outlined.ChevronRight,
                    onClick = onOpenAbout,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部对话？") },
            text = { Text("所有对话记录将被永久删除，此操作无法撤销。模型服务配置不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllConversations()
                        confirmClear = false
                    },
                ) { Text("确定清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
fun SettingsTopBar(title: String, onBack: () -> Unit, subtitle: String? = null) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickableNoIndication(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = scheme.onSurface,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(scheme.outlineVariant.copy(alpha = 0.35f)),
        )
    }
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                RoundedCornerShape(16.dp),
            ),
        content = { content() },
    )
}

@Composable
fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    )
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: ImageVector? = null,
    titleColor: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickableNoIndication(onClick = onClick) else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = titleColor ?: scheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = titleColor ?: scheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Icon(
                imageVector = trailing,
                contentDescription = null,
                tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ProviderRow(
    provider: ProviderConfig,
    active: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoIndication(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (active) scheme.primaryContainer else scheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.SwapHoriz,
                contentDescription = null,
                tint = if (active) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = scheme.onSurface,
                )
                if (active) {
                    Spacer(Modifier.width(7.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(scheme.primary)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "使用中",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onPrimary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = provider.modelName,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (active) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ThemeSelector(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Palette,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = "主题",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
        }
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ThemeMode.SYSTEM to "跟随系统",
                ThemeMode.LIGHT to "浅色",
                ThemeMode.DARK to "深色",
            ).forEach { (mode, label) ->
                val selected = current == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest)
                        .border(
                            1.dp,
                            if (selected) scheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(11.dp),
                        )
                        .clickableNoIndication { onSelect(mode) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsMessageDialog(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提示") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

@Composable
fun VersionFooter(versionName: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "慕寒智能 $versionName",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Code,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "MIT License · 开源项目",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}
