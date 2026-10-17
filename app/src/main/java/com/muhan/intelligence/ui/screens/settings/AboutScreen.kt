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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muhan.intelligence.ui.components.BrandMark
import com.muhan.intelligence.ui.components.SectionLabel
import com.muhan.intelligence.ui.components.clickableNoIndication
import com.muhan.intelligence.ui.screens.update.UpdateAvailableScreen

private const val BILIBILI_URL = "https://b23.tv/6UtmCB0"

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    updateViewModel: AboutViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                    text = "MuHan Intelligence",
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "听见你，更懂你",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(24.dp))

                SectionLabel("应用")
                Spacer(Modifier.height(8.dp))
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Outlined.Refresh,
                        title = "检查更新",
                        subtitle = when {
                            updateState.checking -> "正在检查…"
                            updateState.message != null -> updateState.message
                            else -> "当前版本 ${state.versionName}"
                        },
                        trailing = Icons.Outlined.ChevronRight,
                        onClick = updateViewModel::checkForUpdate,
                    )
                }

                Spacer(Modifier.height(20.dp))

                SectionLabel("作者链接")
                Spacer(Modifier.height(8.dp))
                SettingsCard {
                    LinkRow(
                        icon = Icons.Outlined.PlayCircle,
                        title = "我的哔哩哔哩",
                        subtitle = "b23.tv/6UtmCB0",
                        onClick = { runCatching { uriHandler.openUri(BILIBILI_URL) } },
                    )
                }

                Spacer(Modifier.height(28.dp))

                VersionFooter(state.versionName)

                Spacer(Modifier.height(32.dp))
            }
        }

        // 手动检查到新版本：全屏提示，可稍后。
        updateState.update?.let { update ->
            UpdateAvailableScreen(
                update = update,
                onLater = updateViewModel::dismissUpdate,
            )
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
