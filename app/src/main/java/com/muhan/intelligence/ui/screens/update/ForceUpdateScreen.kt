package com.muhan.intelligence.ui.screens.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muhan.intelligence.data.repository.AppUpdate
import com.muhan.intelligence.ui.components.BrandMark

/**
 * 强制更新拦截页（3.0.1）。
 *
 * 当客户端版本低于服务端 `min_supported_code` 时全屏展示，**不可关闭**：
 * 只能「立即更新」或「退出应用」。
 */
@Composable
fun ForceUpdateScreen(
    update: AppUpdate,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    // 强制更新：吞掉返回键，避免绕过。
    BackHandler(enabled = true) { }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BrandMark(size = 76.dp)
            Spacer(Modifier.height(22.dp))

            Text(
                text = "需要更新后才能继续使用",
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "当前版本 ${update.currentName} 已不再支持，请更新到 ${update.minName} 或更高版本。",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            Surface(
                color = scheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    VersionRow("当前版本", update.currentName)
                    Spacer(Modifier.height(6.dp))
                    VersionRow("要求版本", "≥ ${update.minName}")
                    Spacer(Modifier.height(6.dp))
                    VersionRow("最新版本", update.latestName)
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val url = update.minDownloadUrl.ifBlank { update.downloadUrl }
                    openUrl(context, url)
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Text("立即更新", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(6.dp))

            TextButton(onClick = onExit) {
                Text(
                    text = "退出应用",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "更新不会丢失你的对话与设置，安装后直接覆盖即可。",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun VersionRow(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurface,
        )
    }
}

/** 用系统浏览器打开下载页（避免 App 内 WebView 与 APK 安装限制）。 */
internal fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
