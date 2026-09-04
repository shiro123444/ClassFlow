package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.tool.ReleaseUpdateInfo
import com.xingheyuzhuan.shiguangschedule.tool.UpdateStatus

/**
 * 发现新版本更新弹窗
 */
@Composable
fun AppUpdateFoundDialog(
    info: ReleaseUpdateInfo,
    currentVersionName: String,
    onDismiss: () -> Unit,
    onSkipVersion: () -> Unit,
    onUpdateConfirm: () -> Unit
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "发现新版本 ${info.latestVersionName}",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "当前版本：$currentVersionName\n最新版本：${info.latestVersionName}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "更新内容",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(scrollState)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    MarkdownReleaseNotes(info.summary.ifBlank { "本次版本未提供详细说明。" })
                }
            }
        },
        confirmButton = {
            Button(onClick = onUpdateConfirm) {
                Text("立即更新")
            }
        },
        dismissButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = onSkipVersion) {
                    Text("跳过此版本")
                }
                TextButton(onClick = onDismiss) {
                    Text("稍后")
                }
            }
        }
    )
}

/**
 * 更新包下载进度弹窗
 */
@Composable
fun AppDownloadProgressDialog(
    downloading: UpdateStatus.Downloading,
    onCancel: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("正在下载更新") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (downloading.progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { downloading.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${(downloading.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (downloading.totalBytes > 0) {
                            val currentMb = downloading.currentBytes / 1024f / 1024f
                            val totalMb = downloading.totalBytes / 1024f / 1024f
                            Text(
                                text = String.format("%.1f MB / %.1f MB", currentMb, totalMb),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                    )
                    Text(
                        text = "正在连接更新服务器...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "下载完成后将自动唤起安装包安装程序",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text("取消下载")
                }
            }
        }
    )
}

/**
 * 权限引导弹窗（允许安装未知应用）
 */
@Composable
fun InstallPermissionPromptDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要安装权限") },
        text = {
            Text(
                text = "新版本已下载完成，需要授予“允许安装未知应用”权限才能继续完成安装。",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("前往设置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后安装")
            }
        }
    )
}

@Composable
fun MarkdownReleaseNotes(markdown: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        markdown.lines().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.isBlank() -> Spacer(modifier = Modifier.height(2.dp))
                line.startsWith("### ") -> Text(
                    text = line.removePrefix("### ").trim(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                line.startsWith("## ") -> Text(
                    text = line.removePrefix("## ").trim(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                line.startsWith("# ") -> Text(
                    text = line.removePrefix("# ").trim(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                line.startsWith("- ") || line.startsWith("* ") -> Text(
                    text = "• ${line.drop(2).trim()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Regex("^\\d+\\.\\s+.+").matches(line) -> Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
