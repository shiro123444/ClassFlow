package com.xingheyuzhuan.shiguangschedule.ui.settings.additional

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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PeopleAlt
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.BuildConfig
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WebVpnClient
import com.xingheyuzhuan.shiguangschedule.tool.UpdateChecker
import com.xingheyuzhuan.shiguangschedule.tool.UpdateStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsScreen(
    navBridge: NavBridge,
    viewModel: MoreOptionsViewModel = hiltViewModel()
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val versionName = BuildConfig.VERSION_NAME
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val updateChecker = remember(context) { UpdateChecker(context.applicationContext) }

    var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Idle) }
    var showResultDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showStartScreenDialog by remember { mutableStateOf(false) }

    val useManualWebViewVpn = remember {
        mutableStateOf(WebVpnClient.shouldUseManualWebViewForVpn(navBridge.context))
    }

    fun startCheck() {
        updateStatus = UpdateStatus.Checking
        coroutineScope.launch {
            updateStatus = updateChecker.checkUpdate()
        }
    }

    LaunchedEffect(updateStatus) {
        when (updateStatus) {
            is UpdateStatus.Latest -> {
                snackbarHostState.showSnackbar("你已经是最新版本")
                updateStatus = UpdateStatus.Idle
            }

            is UpdateStatus.Found,
            is UpdateStatus.Error -> {
                showResultDialog = true
            }

            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(text = "更多选项") },
                navigationIcon = {
                    IconButton(onClick = { navBridge.popBackStack() }) {
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

            // 个性化设置卡片（主题 / 语言 / 备份恢复）
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
                        navBridge.navigate(Destination.ThemeSettings)
                    },
                    headlineContent = { Text(stringResource(R.string.theme_settings_title)) },
                    supportingContent = { Text(stringResource(R.string.theme_mode_label)) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Palette,
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
                        handleLanguageSettingClick(context) { showLanguageDialog = true }
                    },
                    headlineContent = { Text(stringResource(R.string.item_language_settings)) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

                // 启动页面设置（上游同步）
                val currentStartScreen by viewModel.startScreen.collectAsState()
                ListItem(
                    modifier = Modifier.clickable { showStartScreenDialog = true },
                    headlineContent = { Text(stringResource(R.string.item_start_screen_settings)) },
                    supportingContent = { Text(stringResource(currentStartScreen.labelRes)) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Home,
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
                        navBridge.navigate(Destination.BackupAndRestore)
                    },
                    headlineContent = { Text(stringResource(R.string.item_backup_restore)) },
                    supportingContent = { Text(stringResource(R.string.desc_backup_restore)) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Backup,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                )
            }

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
                        navBridge.navigate(Destination.ContributionList)
                    },
                    headlineContent = { Text(stringResource(R.string.item_contributors)) },
                    supportingContent = { Text("查看为项目做出贡献的开发者") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.PeopleAlt,
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
                        navBridge.navigate(Destination.OpenSourceLicenses)
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
                        val groupUin = "133364402"
                        val groupKey = "bTUS3eDwhq"
                        // 1. 优先使用 Android 手机 QQ 专用的直接打开群资料/加群页面协议
                        val cardIntent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$groupUin&card_type=group&source=qrcode")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                        // 2. 备选通用唤起加群协议
                        val qrIntent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D$groupKey")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                        // 依次尝试唤起 QQ 客户端，若均无法处理则唤起浏览器打开加群网页
                        try {
                            context.startActivity(cardIntent)
                        } catch (_: Exception) {
                            try {
                                context.startActivity(qrIntent)
                            } catch (_: Exception) {
                                try {
                                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://qm.qq.com/q/$groupKey")).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(webIntent)
                                } catch (_: Exception) { }
                            }
                        }
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
                                WebVpnClient.setManualWebViewForVpn(navBridge.context, enabled)
                            }
                        )
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

                ListItem(
                    modifier = Modifier.clickable {
                        if (updateStatus !is UpdateStatus.Checking && updateStatus !is UpdateStatus.Downloading) {
                            startCheck()
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.item_check_software_update)) },
                    supportingContent = { Text("获取最新版本支持") },
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

    if (updateStatus is UpdateStatus.Downloading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在下载更新") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Text(text = "下载完成后将自动唤起安装", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {}
        )
    }

    if (showResultDialog && updateStatus !is UpdateStatus.Checking && updateStatus !is UpdateStatus.Downloading) {
        when (val status = updateStatus) {
            is UpdateStatus.Found -> {
                val notesScrollState = rememberScrollState()
                AlertDialog(
                    onDismissRequest = {
                        showResultDialog = false
                        updateStatus = UpdateStatus.Idle
                    },
                    title = { Text(text = "发现新版本 ${status.info.latestVersionName}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "当前版本：$versionName\n最新版本：${status.info.latestVersionName}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "更新摘要",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(notesScrollState)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerLow,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                MarkdownReleaseNotes(status.info.summary.ifBlank { "本次版本未提供摘要。" })
                                Spacer(modifier = Modifier.height(10.dp))
                                TextButton(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(status.info.releaseUrl))
                                        context.startActivity(intent)
                                    }
                                ) {
                                    Text("查看发行说明")
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showResultDialog = false
                            coroutineScope.launch {
                                updateStatus = UpdateStatus.Downloading
                                val result = updateChecker.downloadAndInstallUpdate(
                                    downloadUrl = status.info.downloadUrl,
                                    versionName = status.info.latestVersionName
                                )
                                if (result.isSuccess) {
                                    updateStatus = UpdateStatus.Idle
                                    snackbarHostState.showSnackbar("已开始安装更新包")
                                } else {
                                    updateStatus = UpdateStatus.Error(
                                        "下载或安装失败: ${result.exceptionOrNull()?.message ?: "未知错误"}"
                                    )
                                    showResultDialog = true
                                }
                            }
                        }) {
                            Text(stringResource(R.string.btn_update_now))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showResultDialog = false
                            updateStatus = UpdateStatus.Idle
                        }) {
                            Text("暂不安装")
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

    LanguageSelectionDialog(
        showDialog = showLanguageDialog,
        onDismiss = { showLanguageDialog = false }
    )

    val currentStartScreen by viewModel.startScreen.collectAsState()
    StartScreenSelectionDialog(
        showDialog = showStartScreenDialog,
        currentSelected = currentStartScreen,
        onDismiss = { showStartScreenDialog = false },
        onConfirm = { screen ->
            viewModel.onStartScreenChanged(screen)
            showStartScreenDialog = false
        }
    )
}

@Composable
private fun MarkdownReleaseNotes(markdown: String) {
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
                    text = "? ${line.drop(2).trim()}",
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
