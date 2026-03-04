package com.xingheyuzhuan.classflow.ui.settings.additional

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.xingheyuzhuan.classflow.BuildConfig
import com.xingheyuzhuan.classflow.R
import com.xingheyuzhuan.classflow.Screen
import com.xingheyuzhuan.classflow.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.classflow.tool.UpdateChannel
import com.xingheyuzhuan.classflow.tool.UpdateChecker
import com.xingheyuzhuan.classflow.tool.UpdateStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsScreen(navController: NavController) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val versionName = BuildConfig.VERSION_NAME
    val coroutineScope = rememberCoroutineScope()
    val updateChecker = remember(context) { UpdateChecker(context.applicationContext) }

    var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Idle) }
    var showChannelDialog by remember { mutableStateOf(false) }
    var showResultDialog by remember { mutableStateOf(false) }
    var selectedChannel by remember { mutableStateOf(UpdateChecker.UPDATE_CHANNELS.first()) }

    val useManualWebViewVpn = remember {
        mutableStateOf(WbuSyncEngine.shouldUseManualWebViewForVpn(navController.context))
    }

    fun startCheck(channel: UpdateChannel) {
        selectedChannel = channel
        updateStatus = UpdateStatus.Checking
        coroutineScope.launch {
            updateStatus = updateChecker.checkUpdate(channel.url)
        }
    }

    LaunchedEffect(updateStatus) {
        if (updateStatus !is UpdateStatus.Checking && updateStatus !is UpdateStatus.Idle) {
            showResultDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "更多选项") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeroCard(versionName = versionName)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InfoCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Stars,
                    title = "产品愿景",
                    subtitle = "为每一位WBUer打造的优雅轻量课表~"
                )
                InfoCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.RocketLaunch,
                    title = "后续计划",
                    subtitle = "持续对接教务系统，优化课表导入体验"
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                ListItem(
                    modifier = Modifier.clickable {
                        navController.navigate(Screen.OpenSourceLicenses.route)
                    },
                    headlineContent = { Text("开源协议") },
                    supportingContent = { Text("查看许可证与合规信息") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ListAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

                ListItem(
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://qm.qq.com/q/bTUS3eDwhq"))
                        context.startActivity(intent)
                    },
                    headlineContent = { Text("智汇AI协会交流群") },
                    supportingContent = { Text("加入QQ群与同学交流") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

                ListItem(
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/shiro123444/ClassFlow/issues"))
                        context.startActivity(intent)
                    },
                    headlineContent = { Text("意见反馈") },
                    supportingContent = { Text("前往 GitHub 提交 Issue") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

                ListItem(
                    headlineContent = { Text(text = stringResource(R.string.item_wbu_vpn_webview_mode)) },
                    supportingContent = { Text(text = stringResource(R.string.desc_wbu_vpn_webview_mode)) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = useManualWebViewVpn.value,
                            onCheckedChange = { enabled ->
                                useManualWebViewVpn.value = enabled
                                WbuSyncEngine.setManualWebViewForVpn(navController.context, enabled)
                            }
                        )
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

                ListItem(
                    modifier = Modifier.clickable { showChannelDialog = true },
                    headlineContent = { Text(stringResource(R.string.item_check_software_update)) },
                    supportingContent = { Text("手动检查新版本并自主选择是否更新") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                )
            }
        }
    }

    if (showChannelDialog) {
        var tempSelectedChannel by remember { mutableStateOf(selectedChannel) }
        AlertDialog(
            onDismissRequest = { showChannelDialog = false },
            title = { Text(stringResource(R.string.dialog_select_update_channel)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdateChecker.UPDATE_CHANNELS.forEach { channel ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { tempSelectedChannel = channel }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = tempSelectedChannel.id == channel.id,
                                onClick = { tempSelectedChannel = channel }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = channel.title, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showChannelDialog = false
                    startCheck(tempSelectedChannel)
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showChannelDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (updateStatus is UpdateStatus.Checking) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.dialog_checking_update)) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Text(text = stringResource(R.string.tip_please_wait), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {}
        )
    }

    if (showResultDialog && updateStatus !is UpdateStatus.Checking) {
        when (val status = updateStatus) {
            is UpdateStatus.Found -> {
                AlertDialog(
                    onDismissRequest = {
                        showResultDialog = false
                        updateStatus = UpdateStatus.Idle
                    },
                    title = { Text(text = stringResource(R.string.dialog_new_version_found, status.flavorInfo.latestVersionName)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "当前版本：$versionName\n可用版本：${status.flavorInfo.latestVersionName}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "更新说明",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = status.flavorInfo.changelog.ifBlank { "本次版本未提供更新说明。" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            updateChecker.launchExternalDownload(status.downloadUrl)
                            showResultDialog = false
                            updateStatus = UpdateStatus.Idle
                        }) {
                            Text(stringResource(R.string.btn_download_update))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showResultDialog = false
                            updateStatus = UpdateStatus.Idle
                        }) {
                            Text("稍后再说")
                        }
                    }
                )
            }

            is UpdateStatus.Latest -> {
                AlertDialog(
                    onDismissRequest = {
                        showResultDialog = false
                        updateStatus = UpdateStatus.Idle
                    },
                    title = { Text(stringResource(R.string.dialog_current_version_latest)) },
                    text = {
                        Text(
                            text = "当前版本：${status.versionName}\n已是最新版本。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showResultDialog = false
                            updateStatus = UpdateStatus.Idle
                        }) {
                            Text(stringResource(R.string.action_confirm))
                        }
                    }
                )
            }

            is UpdateStatus.Error -> {
                AlertDialog(
                    onDismissRequest = {
                        showResultDialog = false
                        updateStatus = UpdateStatus.Idle
                    },
                    title = { Text(stringResource(R.string.dialog_update_check_failed)) },
                    text = {
                        Text(
                            text = status.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showResultDialog = false
                            updateStatus = UpdateStatus.Idle
                        }) {
                            Text(stringResource(R.string.action_confirm))
                        }
                    }
                )
            }

            else -> Unit
        }
    }
}

@Composable
private fun HeroCard(versionName: String) {
    val gradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer
        )
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "ClassFlow",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.sp
                    )
                    Text(
                        text = "欢迎每一位WBUer~",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "当前版本  $versionName",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
