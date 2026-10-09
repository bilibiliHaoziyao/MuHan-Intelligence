package com.muhan.intelligence.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muhan.intelligence.data.remote.ProviderPresets
import com.muhan.intelligence.domain.model.ApiFlavor
import com.muhan.intelligence.domain.model.ConnectionTestResult
import com.muhan.intelligence.ui.components.SectionLabel
import com.muhan.intelligence.ui.components.clickableNoIndication

/** Add / edit a single provider. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProviderEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ProviderEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding(),
    ) {
        SettingsTopBar(
            title = if (state.isEditing) "编辑模型服务" else "添加模型服务",
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // Quick-fill presets only make sense for a brand-new provider.
            if (!state.isEditing) {
                SectionLabel("快速填充")
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ProviderPresets.all.forEach { preset ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (state.baseUrl == preset.baseUrl) {
                                        scheme.primaryContainer
                                    } else {
                                        scheme.surfaceContainerHigh
                                    },
                                )
                                .clickableNoIndication {
                                    viewModel.onDisplayNameChange(preset.displayName)
                                    viewModel.onBaseUrlChange(preset.baseUrl)
                                    viewModel.onModelNameChange(preset.defaultModel)
                                    viewModel.onFlavorChange(preset.flavor)
                                }
                                .padding(horizontal = 11.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = preset.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.baseUrl == preset.baseUrl) {
                                    scheme.onPrimaryContainer
                                } else {
                                    scheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            SectionLabel("基本信息")
            Spacer(Modifier.height(8.dp))

            EditorField(
                label = "服务名称",
                value = state.displayName,
                onValueChange = viewModel::onDisplayNameChange,
                placeholder = "例如：我的 DeepSeek",
            )
            Spacer(Modifier.height(14.dp))

            EditorField(
                label = "接口地址 (Base URL)",
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                placeholder = "https://api.deepseek.com",
                helper = "可填写到域名或 /v1，应用会自动补全请求路径",
            )
            Spacer(Modifier.height(14.dp))

            EditorField(
                label = "模型名称",
                value = state.modelName,
                onValueChange = viewModel::onModelNameChange,
                placeholder = "例如：deepseek-chat",
            )
            Spacer(Modifier.height(14.dp))

            EditorField(
                label = "生图模型（可选）",
                value = state.imageModel,
                onValueChange = viewModel::onImageModelChange,
                placeholder = "例如：dall-e-3、gemini-2.0-flash-exp",
                helper = "填写后可在聊天中开启生图模式",
            )
            Spacer(Modifier.height(14.dp))

            FlavorPicker(selected = state.flavor, onSelect = viewModel::onFlavorChange)

            Spacer(Modifier.height(20.dp))

            SectionLabel("API Key")
            Spacer(Modifier.height(8.dp))

            EditorField(
                label = if (state.existingMaskedKey != null) "API Key（留空则不修改）" else "API Key",
                value = state.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                placeholder = state.existingMaskedKey ?: "sk-...",
                helper = if (state.existingMaskedKey != null) {
                    "当前保存：${state.existingMaskedKey}"
                } else {
                    null
                },
                isSecret = !state.apiKeyVisible,
                trailing = {
                    Icon(
                        imageVector = if (state.apiKeyVisible) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .clickableNoIndication(onClick = viewModel::toggleApiKeyVisibility),
                    )
                },
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(scheme.surfaceContainerLow)
                    .padding(13.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "密钥将使用系统级加密保存于本机，不会上传，也不会参与云备份。",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))

            // ---------------- Test ----------------
            when (val result = state.testResult) {
                is ConnectionTestResult.Success -> TestResultRow(
                    success = true,
                    text = "连接成功 · ${result.latencyMs} ms" +
                        (result.modelEcho?.let { " · $it" } ?: ""),
                )
                is ConnectionTestResult.Failure -> TestResultRow(success = false, text = result.message)
                null -> Unit
            }

            if (state.testResult != null) Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedAction(
                    text = "测试连接",
                    loading = state.isTesting,
                    onClick = viewModel::testConnection,
                    modifier = Modifier.weight(1f),
                )
                if (state.isEditing) {
                    OutlinedAction(
                        text = "删除",
                        danger = true,
                        onClick = { confirmDelete = true },
                        modifier = Modifier.width(88.dp),
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }

        // ---------------- Bottom actions ----------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.background)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            state.error?.let { error ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(scheme.errorContainer.copy(alpha = 0.4f))
                        .padding(11.dp),
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onErrorContainer,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            Button(
                onClick = viewModel::save,
                enabled = state.canSave && !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                ),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                        color = scheme.onPrimary,
                    )
                    Spacer(Modifier.width(9.dp))
                }
                Text(
                    text = if (state.isEditing) "保存修改" else "添加并使用",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除该模型服务？") },
            text = { Text("对应的 API Key 也会一并删除，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteProvider() }) {
                    Text("删除", color = scheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    helper: String? = null,
    isSecret: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
            },
            singleLine = true,
            visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
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
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FlavorPicker(selected: ApiFlavor, onSelect: (ApiFlavor) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "协议类型",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
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
                        .background(if (active) scheme.primaryContainer else scheme.surfaceContainerLow)
                        .border(
                            1.dp,
                            if (active) scheme.primary else scheme.outlineVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(11.dp),
                        )
                        .clickableNoIndication { onSelect(flavor) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TestResultRow(success: Boolean, text: String) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (success) scheme.primaryContainer else scheme.errorContainer
    val fg = if (success) scheme.onPrimaryContainer else scheme.onErrorContainer

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(bg)
            .padding(13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (success) Icons.Filled.CheckCircle else Icons.Outlined.Shield,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = fg,
        )
    }
}

@Composable
private fun OutlinedAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    danger: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerHigh)
            .clickableNoIndication(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = scheme.primary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (danger) scheme.error else scheme.onSurface,
            )
        }
    }
}
