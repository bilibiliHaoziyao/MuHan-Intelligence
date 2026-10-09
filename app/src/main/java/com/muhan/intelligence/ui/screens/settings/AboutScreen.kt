package com.muhan.intelligence.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muhan.intelligence.ui.components.BrandMark
import com.muhan.intelligence.ui.components.SectionLabel
import com.muhan.intelligence.ui.components.clickableNoIndication

private const val GITHUB_URL = "https://github.com/bilibiliHaoziyao/MuHan-Intelligence"
private const val ISSUES_URL = "$GITHUB_URL/issues"
private const val LICENSE_URL = "$GITHUB_URL/blob/main/LICENSE"

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding(),
    ) {
        SettingsTopBar(title = "关于", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))

            BrandMark(size = 76.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "慕寒智能",
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "版本 ${state.versionName}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = "一个完全由你掌控 API Key 的 AI 对话客户端。无需注册账号，" +
                    "对话记录与密钥只保存在本机，支持接入 DeepSeek、Kimi、智谱、" +
                    "通义千问、OpenAI、Claude、Gemini 等主流模型服务。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            SectionLabel("项目链接")
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                LinkRow(
                    icon = Icons.Outlined.Code,
                    title = "GitHub 仓库",
                    subtitle = "bilibiliHaoziyao/MuHan-Intelligence",
                    onClick = { runCatching { uriHandler.openUri(GITHUB_URL) } },
                )
                Divider()
                LinkRow(
                    icon = Icons.Outlined.BugReport,
                    title = "反馈问题",
                    subtitle = "提交 Issue 帮助我们一起改进",
                    onClick = { runCatching { uriHandler.openUri(ISSUES_URL) } },
                )
                Divider()
                LinkRow(
                    icon = Icons.Outlined.Description,
                    title = "开源协议",
                    subtitle = "MIT License",
                    onClick = { runCatching { uriHandler.openUri(LICENSE_URL) } },
                )
            }

            Spacer(Modifier.height(28.dp))

            VersionFooter(state.versionName)

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun LinkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
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
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Outlined.OpenInNew,
            contentDescription = null,
            tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
    }
}
