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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Sync
import com.xingheyuzhuan.shiguangschedule.data.model.UpdateChannelType
import com.xingheyuzhuan.shiguangschedule.tool.UpdateChecker
import com.xingheyuzhuan.shiguangschedule.tool.UpdateStatus
import com.xingheyuzhuan.shiguangschedule.ui.components.AppDownloadProgressDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.AppUpdateFoundDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.InstallPermissionPromptDialog
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import java.io.File
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

    val autoCheckUpdate by viewModel.autoCheckUpdate.collectAsState()
    val customUpdateApiUrl by viewModel.customUpdateApiUrl.collectAsState()
    val ignoredUpdateVersion by viewModel.ignoredUpdateVersion.collectAsState()
    val updateChannel by viewModel.updateChannel.collectAsState()
    val effectiveApiUrl = customUpdateApiUrl.ifBlank { BuildConfig.UPDATE_API_URL }.trim()

    var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Idle) }
    var showResultDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showStartScreenDialog by remember { mutableStateOf(false) }
    var showInstallPermissionDialog by remember { mutableStateOf(false) }
    var showServerUrlDialog by remember { mutableStateOf(false) }
    var showChannelDialog by remember { mutableStateOf(false) }
    var inputServerUrl by remember { mutableStateOf("") }
    var pendingApkFile by remember { mutableStateOf<File?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val apk = pendingApkFile
                if (apk != null && apk.exists() && updateChecker.canRequestPackageInstalls()) {
                    updateChecker.installApk(apk)
                    pendingApkFile = null
                    showInstallPermissionDialog = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val useManualWebViewVpn = remember {
        mutableStateOf(WebVpnClient.shouldUseManualWebViewForVpn(navBridge.context))
    }

    fun startCheck() {
        if (effectiveApiUrl.isBlank()) {
            inputServerUrl = customUpdateApiUrl
            showServerUrlDialog = true
            return
        }
        updateStatus = UpdateStatus.Checking
        coroutineScope.launch {
            updateStatus = updateChecker.checkUpdate(effectiveApiUrl, updateChannel)
        }
    }

    LaunchedEffect(updateStatus) {
        when (updateStatus) {
            is UpdateStatus.Latest -> {
                snackbarHostState.showSnackbar(context.getString(R.string.dialog_current_version_latest))
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
                title = { Text(text = stringResource(R.string.title_more_options)) },
                navigationIcon = {
                    IconButton(onClick = { navBridge.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back)
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
                    title = stringResource(R.string.title_product_vision),
                    subtitle = stringResource(R.string.desc_product_vision)
                )
                InfoCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.RocketLaunch,
                    title = stringResource(R.string.title_future_plan),
                    subtitle = stringResource(R.string.desc_future_plan)
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
                    supportingContent = { Text(stringResource(R.string.desc_contributors)) },
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
                    headlineContent = { Text(stringResource(R.string.title_open_source_licenses)) },
                    supportingContent = { Text(stringResource(R.string.desc_open_source_licenses)) },
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
                        val groupUin = "1050669511"
                        val webUrl = "https://qun.qq.com/universal-share/share?ac=1&authKey=RFtUQvg2d0iCUGzJW%2B5DOI8B74Xn%2FgY0cgk9U6mmMyeZ%2BpCzRQL0k3W5VEUjQI%2Br&busi_data=eyJncm91cENvZGUiOiIxMDUwNjY5NTExIiwidG9rZW4iOiJ1U3RhbjRpMjNQMzUyN3BuTjZ1NXhIU1J5REdDbW53eC9TeVVhbCs5T0p6dTdLNmU3S1FWOXQ3ZW96OFhDeDk5IiwidWluIjoiMjg2Nzk2NDQyNSJ9&data=Iy_pb6rJpzb3GkdcRsYYfzjreuUAO0UEd77PgpQbVU2dHoUvBCOIprV2k2sghzp6qRXcsLhMZJhU3PJREr5kyA&svctype=4&tempid=h5_group_info"
                        // 1. 优先使用 Android 手机 QQ 专用的直接打开群资料/加群页面协议
                        val cardIntent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$groupUin&card_type=group&source=qrcode")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                        // 2. 备选通用唤起/网页加群链接
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                        // 依次尝试唤起 QQ 客户端，若无法处理则唤起网页链接
                        try {
                            context.startActivity(cardIntent)
                        } catch (_: Exception) {
                            try {
                                context.startActivity(webIntent)
                            } catch (_: Exception) { }
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.title_user_group)) },
                    supportingContent = { Text(stringResource(R.string.desc_user_group)) },
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
                    headlineContent = { Text(stringResource(R.string.item_feedback)) },
                    supportingContent = { Text(stringResource(R.string.desc_feedback_github)) },
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
                    headlineContent = { Text(stringResource(R.string.item_auto_check_update)) },
                    supportingContent = { Text(stringResource(R.string.desc_auto_check_update)) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = autoCheckUpdate,
                            onCheckedChange = { viewModel.onAutoCheckUpdateChanged(it) }
                        )
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

                ListItem(
                    modifier = Modifier.clickable { showChannelDialog = true },
                    headlineContent = { Text(stringResource(R.string.item_update_channel)) },
                    supportingContent = { Text(stringResource(UpdateChannelType.fromId(updateChannel).titleRes)) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.AltRoute,
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
                        if (updateStatus !is UpdateStatus.Checking && updateStatus !is UpdateStatus.Downloading) {
                            startCheck()
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.item_check_software_update)) },
                    supportingContent = {
                        Text(
                            text = when {
                                ignoredUpdateVersion.isNotBlank() -> stringResource(R.string.format_skipped_update_version, ignoredUpdateVersion)
                                customUpdateApiUrl.isNotBlank() -> stringResource(R.string.status_custom_update_source_set)
                                else -> stringResource(R.string.status_get_latest_support)
                            },
                            maxLines = 1
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        IconButton(
                            onClick = {
                                inputServerUrl = customUpdateApiUrl
                                showServerUrlDialog = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.action_configure_update_server)
                            )
                        }
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
        AppDownloadProgressDialog(
            downloading = updateStatus as UpdateStatus.Downloading
        )
    }

    if (showResultDialog && updateStatus !is UpdateStatus.Checking && updateStatus !is UpdateStatus.Downloading) {
        when (val status = updateStatus) {
            is UpdateStatus.Found -> {
                AppUpdateFoundDialog(
                    info = status.info,
                    currentVersionName = versionName,
                    onDismiss = {
                        showResultDialog = false
                        updateStatus = UpdateStatus.Idle
                    },
                    onSkipVersion = {
                        viewModel.ignoreUpdateVersion(status.info.latestVersionName)
                        showResultDialog = false
                        updateStatus = UpdateStatus.Idle
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.toast_skipped_version, status.info.latestVersionName))
                        }
                    },
                    onUpdateConfirm = {
                        showResultDialog = false
                        coroutineScope.launch {
                            updateStatus = UpdateStatus.Downloading()
                            val result = updateChecker.downloadAndInstallUpdate(
                                downloadUrl = status.info.downloadUrl,
                                versionName = status.info.latestVersionName,
                                expectedSize = status.info.expectedSize,
                                expectedMd5 = status.info.expectedMd5,
                                onProgress = { progress, currentBytes, totalBytes ->
                                    updateStatus = UpdateStatus.Downloading(progress, currentBytes, totalBytes)
                                }
                            )
                            if (result.isSuccess) {
                                val apk = result.getOrNull()
                                pendingApkFile = apk
                                updateStatus = UpdateStatus.Idle
                                viewModel.clearIgnoredUpdateVersion()
                                if (!updateChecker.canRequestPackageInstalls()) {
                                    showInstallPermissionDialog = true
                                } else {
                                    snackbarHostState.showSnackbar(context.getString(R.string.toast_installer_launched))
                                }
                            } else {
                                updateStatus = UpdateStatus.Error(
                                    context.getString(R.string.toast_download_or_install_failed, result.exceptionOrNull()?.message ?: "")
                                )
                                showResultDialog = true
                            }
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
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showResultDialog = false
                            updateStatus = UpdateStatus.Idle
                            inputServerUrl = customUpdateApiUrl
                            showServerUrlDialog = true
                        }) {
                            Text(stringResource(R.string.action_configure_address))
                        }
                    }
                )
            }

            else -> Unit
        }
    }

    if (showServerUrlDialog) {
        AlertDialog(
            onDismissRequest = { showServerUrlDialog = false },
            title = { Text(stringResource(R.string.title_custom_update_server)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.desc_custom_update_server),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = inputServerUrl,
                        onValueChange = { inputServerUrl = it },
                        placeholder = { Text(stringResource(R.string.hint_custom_update_server_input)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (ignoredUpdateVersion.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.label_ignored_version, ignoredUpdateVersion),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = {
                                    viewModel.clearIgnoredUpdateVersion()
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.toast_restored_version_reminder))
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.action_restore_reminder))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.onCustomUpdateApiUrlChanged(inputServerUrl.trim())
                    showServerUrlDialog = false
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            if (inputServerUrl.isBlank()) context.getString(R.string.toast_server_reset_to_default)
                            else context.getString(R.string.toast_server_saved)
                        )
                    }
                }) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerUrlDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showInstallPermissionDialog) {
        InstallPermissionPromptDialog(
            onConfirm = {
                showInstallPermissionDialog = false
                updateChecker.openInstallPermissionSettings()
            },
            onDismiss = {
                showInstallPermissionDialog = false
            }
        )
    }

    UpdateChannelDialog(
        showDialog = showChannelDialog,
        currentChannelId = updateChannel,
        onDismiss = { showChannelDialog = false },
        onSelectChannel = { newChannel ->
            viewModel.onUpdateChannelChanged(newChannel)
            coroutineScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.toast_channel_switched, context.getString(UpdateChannelType.fromId(newChannel).titleRes)))
            }
        }
    )

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
                        text = stringResource(R.string.brand_welcome_wbuer),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.format_current_version, versionName),
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
