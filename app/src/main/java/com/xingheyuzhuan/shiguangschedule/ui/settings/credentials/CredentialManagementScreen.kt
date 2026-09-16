package com.xingheyuzhuan.shiguangschedule.ui.settings.credentials

import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.SessionState
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthMode
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuAuthTipsScenario
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet
import com.xingheyuzhuan.shiguangschedule.ui.settings.SettingDivider
import com.xingheyuzhuan.shiguangschedule.ui.settings.SettingTile
import com.xingheyuzhuan.shiguangschedule.ui.settings.SettingsCard
import kotlinx.coroutines.delay

/** 待清除的凭据目标。 */
private enum class ClearTarget { PASSWORD, TOKEN, SESSION, ALL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialManagementScreen(
    navBridge: NavBridge,
    viewModel: CredentialManagementViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.onScreenEnter() }

    // 一卡通「使用已有统一认证凭据同步」的结果提示
    val context = LocalContext.current
    val campusCardSyncMessage by viewModel.campusCardSyncMessage.collectAsState()
    LaunchedEffect(campusCardSyncMessage) {
        val message = campusCardSyncMessage ?: return@LaunchedEffect
        val text = if (message == "success") {
            context.getString(R.string.credential_sync_campus_card_success)
        } else {
            context.getString(R.string.credential_sync_campus_card_failed, message)
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        viewModel.clearCampusCardSyncMessage()
    }

    // 连点标题 3 次解锁「高级模式」：明文查看 + 编辑凭据。状态跨页面记住。
    var reveal by remember { mutableStateOf(viewModel.isAdvancedMode()) }
    var titleTaps by remember { mutableStateOf(0) }
    LaunchedEffect(titleTaps) {
        if (titleTaps in 1..2) {
            delay(700)
            titleTaps = 0
        }
    }

    var renameService by remember { mutableStateOf<CredentialService?>(null) }
    var resetService by remember { mutableStateOf<CredentialService?>(null) }
    var webDavReset by remember { mutableStateOf(false) }
    var webDavAddressEdit by remember { mutableStateOf(false) }
    var webDavUsernameEdit by remember { mutableStateOf(false) }
    var twfidEdit by remember { mutableStateOf(false) }
    var credEdit by remember { mutableStateOf<Triple<CredentialService, String, String>?>(null) }
    var confirm by remember { mutableStateOf<Pair<CredentialService, ClearTarget>?>(null) }
    var loginService by remember { mutableStateOf<CredentialService?>(null) }
    var moreExpanded by remember { mutableStateOf(false) }

    val titleText = stringResource(R.string.title_credential_management)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = titleText,
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    titleTaps += 1
                                    if (titleTaps >= 3) {
                                        titleTaps = 0
                                        reveal = !reveal
                                        viewModel.setAdvancedMode(reveal)
                                    }
                                }
                            )
                        }
                    )
                },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            SettingsCard(title = stringResource(R.string.section_credential_verify)) {
                SettingTile(
                    icon = Icons.Rounded.VerifiedUser,
                    title = stringResource(R.string.item_auto_verify),
                    subtitle = stringResource(R.string.desc_auto_verify),
                    trailingContent = {
                        Switch(
                            checked = uiState.autoVerify,
                            onCheckedChange = { viewModel.setAutoVerify(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color.White.copy(alpha = 0.28f),
                                uncheckedThumbColor = Color.White.copy(alpha = 0.9f)
                            )
                        )
                    }
                )
                SettingDivider()
                SettingTile(
                    icon = Icons.Rounded.VpnKey,
                    title = stringResource(R.string.item_webvpn_mode),
                    subtitle = stringResource(R.string.desc_webvpn_mode),
                    trailingContent = {
                        Switch(
                            checked = uiState.useVpn,
                            onCheckedChange = { viewModel.setUseVpn(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color.White.copy(alpha = 0.28f),
                                uncheckedThumbColor = Color.White.copy(alpha = 0.9f)
                            )
                        )
                    }
                )
                SettingDivider()
                SettingTile(
                    icon = Icons.Rounded.Refresh,
                    title = stringResource(R.string.action_verify_now),
                    subtitle = stringResource(R.string.desc_verify_now),
                    trailingContent = null,
                    onClick = { viewModel.verifyAll() }
                )
            }

            uiState.services.forEach { state ->
                ServiceCard(
                    state = state,
                    reveal = reveal,
                    onRenameAccount = { renameService = state.service },
                    onVerify = { viewModel.verify(state.service) },
                    onLogin = {
                        // WebVPN 卡的「登录」即门户登录，必须先处于 WebVPN 模式
                        if (state.service == CredentialService.WEBVPN) viewModel.setUseVpn(true)
                        loginService = state.service
                    },
                    onResetPassword = {
                        if (state.service == CredentialService.WEBDAV) webDavReset = true
                        else resetService = state.service
                    },
                    onEditToken = { twfidEdit = true },
                    onEditCredential = { name, value -> credEdit = Triple(state.service, name, value) },
                    onClear = { target -> confirm = state.service to target },
                    onClearWebDavAll = { confirm = state.service to ClearTarget.ALL },
                    onEditWebDavAddress = { webDavAddressEdit = true },
                    onEditWebDavUsername = { webDavUsernameEdit = true }
                )
            }

            // 更多设置：置于页面最底部，与登录 Sheet「更多」保持同一组开关
            SettingsCard(title = stringResource(R.string.label_more_settings)) {
                SettingTile(
                    icon = Icons.Rounded.ExpandMore,
                    title = stringResource(
                        if (moreExpanded) R.string.action_collapse_more else R.string.action_expand_more
                    ),
                    subtitle = null,
                    trailingContent = null,
                    onClick = { moreExpanded = !moreExpanded }
                )
                if (moreExpanded) {
                    SettingDivider()
                    ToggleRow(
                        label = stringResource(R.string.pref_ids_via_webvpn),
                        checked = uiState.idsViaWebVpn,
                        onCheckedChange = { viewModel.setIdsViaWebVpn(it) }
                    )
                    SettingDivider()
                    ToggleRow(
                        label = stringResource(R.string.pref_qr_with_webvpn),
                        checked = uiState.qrViaWebVpn,
                        onCheckedChange = { viewModel.setQrViaWebVpn(it) }
                    )
                    SettingDivider()
                    ToggleRow(
                        label = stringResource(R.string.pref_use_https_webvpn),
                        checked = uiState.useHttpsWebVpn,
                        onCheckedChange = { viewModel.setUseHttpsWebVpn(it) }
                    )
                    SettingDivider()
                    ToggleRow(
                        label = stringResource(R.string.pref_desktop_ua),
                        checked = uiState.usePcUserAgent,
                        onCheckedChange = { viewModel.setUsePcUserAgent(it) }
                    )
                    SettingDivider()
                    ToggleRow(
                        label = stringResource(R.string.pref_skip_campus_network_check),
                        checked = uiState.skipCampusCheck,
                        onCheckedChange = { viewModel.setSkipCampusCheck(it) }
                    )
                    SettingDivider()
                    ToggleRow(
                        label = stringResource(R.string.title_select_import_semester),
                        checked = uiState.selectSemesterOnImport,
                        onCheckedChange = { viewModel.setSelectSemesterOnImport(it) }
                    )
                    SettingDivider()
                    ToggleRow(
                        label = stringResource(R.string.pref_keep_teacher_id),
                        checked = uiState.keepTeacherId,
                        onCheckedChange = { viewModel.setKeepTeacherId(it) }
                    )
                    SettingDivider()
                    ToggleRow(
                        label = stringResource(R.string.pref_keep_building_name),
                        checked = uiState.keepBuilding,
                        onCheckedChange = { viewModel.setKeepBuilding(it) }
                    )
                    SettingDivider()
                    ToggleRow(
                        label = stringResource(R.string.pref_fetch_id_before_vpn),
                        checked = uiState.forceFetchStudentIdBeforeVpn,
                        onCheckedChange = { viewModel.setForceFetchStudentIdBeforeVpn(it) }
                    )
                    SettingDivider()
                    ToggleRow(
                        label = stringResource(R.string.pref_fixed_ticket_service),
                        checked = uiState.useFixedServiceForTicket,
                        onCheckedChange = { viewModel.setUseFixedServiceForTicket(it) }
                    )
                    SettingDivider()
                    ToggleRow(
                        label = "IDS addr not from Jwxt",
                        checked = uiState.idsAddrNotFromJwxt,
                        onCheckedChange = { viewModel.setIdsAddrNotFromJwxt(it) }
                    )
                    SettingDivider()
                    ToggleRow(
                        label = "no indexMain verify",
                        checked = uiState.noIndexMainVerify,
                        onCheckedChange = { viewModel.setNoIndexMainVerify(it) }
                    )
                }
            }
        }
    }

    renameService?.let { service ->
        val initial = uiState.services.firstOrNull { it.service == service }?.accountLabel.orEmpty()
        TextInputDialog(
            title = stringResource(R.string.dialog_rename_account_title),
            label = stringResource(R.string.label_account_name),
            initial = initial,
            mask = false,
            onDismiss = { renameService = null },
            onConfirm = { name ->
                viewModel.renameAccount(service, name)
                renameService = null
            }
        )
    }

    resetService?.let { service ->
        TextInputDialog(
            title = stringResource(R.string.dialog_reset_password_title, serviceName(service)),
            label = stringResource(R.string.label_new_password),
            initial = "",
            mask = true,
            onDismiss = { resetService = null },
            onConfirm = { pwd ->
                viewModel.resetPassword(service, pwd)
                resetService = null
            }
        )
    }

    if (webDavReset) {
        TextInputDialog(
            title = stringResource(R.string.dialog_reset_password_title, serviceName(CredentialService.WEBDAV)),
            label = stringResource(R.string.label_new_password),
            initial = "",
            mask = true,
            onDismiss = { webDavReset = false },
            onConfirm = { pwd ->
                viewModel.resetWebDavPassword(pwd)
                webDavReset = false
            }
        )
    }

    if (webDavAddressEdit) {
        TextInputDialog(
            title = stringResource(R.string.label_webdav_server),
            label = stringResource(R.string.label_webdav_server),
            initial = uiState.services.firstOrNull { it.service == CredentialService.WEBDAV }
                ?.webDavBaseUrl.orEmpty(),
            mask = false,
            onDismiss = { webDavAddressEdit = false },
            onConfirm = { addr ->
                viewModel.setWebDavAddress(addr)
                webDavAddressEdit = false
            }
        )
    }

    if (webDavUsernameEdit) {
        TextInputDialog(
            title = stringResource(R.string.label_webdav_username),
            label = stringResource(R.string.label_webdav_username),
            initial = uiState.services.firstOrNull { it.service == CredentialService.WEBDAV }
                ?.webDavUsername.orEmpty(),
            mask = false,
            onDismiss = { webDavUsernameEdit = false },
            onConfirm = { name ->
                viewModel.setWebDavUsername(name)
                webDavUsernameEdit = false
            }
        )
    }

    if (twfidEdit) {
        val current = uiState.services.firstOrNull { it.service == CredentialService.WEBVPN }?.tokenValue.orEmpty()
        TextInputDialog(
            title = stringResource(R.string.dialog_edit_twfid_title),
            label = stringResource(R.string.item_twfid),
            initial = current,
            mask = false,
            onDismiss = { twfidEdit = false },
            onConfirm = { value ->
                viewModel.setTwfid(value)
                twfidEdit = false
            }
        )
    }

    credEdit?.let { (service, name, current) ->
        TextInputDialog(
            title = name,
            label = name,
            initial = current,
            mask = false,
            onDismiss = { credEdit = null },
            onConfirm = { value ->
                viewModel.setSessionCredential(service, name, value)
                credEdit = null
            }
        )
    }

    confirm?.let { (service, target) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.dialog_clear_confirm_title)) },
            text = { Text(clearConfirmMessage(service, target)) },
            confirmButton = {
                Button(onClick = {
                    when (target) {
                        ClearTarget.PASSWORD ->
                            if (service == CredentialService.WEBDAV) viewModel.clearWebDavPassword()
                            else viewModel.clearPassword(service)
                        ClearTarget.TOKEN -> viewModel.clearToken(service)
                        ClearTarget.SESSION -> viewModel.clearSession(service)
                        ClearTarget.ALL ->
                            if (service == CredentialService.WEBDAV) viewModel.clearWebDavAll()
                            else viewModel.clearService(service)
                    }
                    confirm = null
                }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // 登录 Sheet：复用校园服务同款（内建 WebVPN 门户登录 / 短信 / 滑块 / 二维码）
    loginService?.let { svc ->
        // 统一认证 / WebVPN / 图书馆 / 一卡通 都只走统一认证密码，锁定密码类型
        val lockToCas = svc == CredentialService.UNIFIED_AUTH ||
            svc == CredentialService.WEBVPN ||
            svc == CredentialService.LIBRARY ||
            svc == CredentialService.CAMPUS_CARD
        // 统一认证 / WebVPN 本身没有可复用的「现成凭据」，不显示同步图标；一卡通可用已有统一认证凭据同步
        val hideSync = svc == CredentialService.UNIFIED_AUTH || svc == CredentialService.WEBVPN
        WbuCampusAuthSheet(
            onDismiss = { loginService = null },
            onLoginSuccess = { viewModel.refreshAfterServiceLogin(svc) },
            requireUnifiedCas = svc == CredentialService.UNIFIED_AUTH ||
                svc == CredentialService.LIBRARY ||
                svc == CredentialService.CAMPUS_CARD,
            defaultAuthMode = if (lockToCas) WbuAuthMode.UNIFIED_CAS else null,
            lockPasswordType = lockToCas,
            showSyncButton = !hideSync,
            // 一卡通「同步」：直接用已有的统一认证凭据换一卡通令牌，无需重新输入密码
            onSyncWithCredentials = if (svc == CredentialService.CAMPUS_CARD) {
                {
                    loginService = null
                    viewModel.syncCampusCardWithExistingCredentials()
                }
            } else null,
            // WebVPN 卡的登录本身就是走 WebVPN，不提供「WebVPN 访问 / 校园网直连」开关；一卡通直连，也不需要
            hideNetworkSwitch = svc == CredentialService.WEBVPN || svc == CredentialService.CAMPUS_CARD,
            // 统一认证卡/一卡通卡只登录统一认证：不校验校园网、不登录教务、网络开关随「统一认证经过WebVPN」显隐
            unifiedAuthOnly = svc == CredentialService.UNIFIED_AUTH || svc == CredentialService.CAMPUS_CARD,
            tipsScenario = when (svc) {
                CredentialService.LIBRARY -> WbuAuthTipsScenario.LIBRARY
                CredentialService.UNIFIED_AUTH, CredentialService.WEBVPN, CredentialService.CAMPUS_CARD -> WbuAuthTipsScenario.IDENTITY
                else -> WbuAuthTipsScenario.CAMPUS
            },
            title = stringResource(
                when (svc) {
                    CredentialService.UNIFIED_AUTH -> R.string.title_login_unified_auth
                    CredentialService.LIBRARY -> R.string.title_login_library
                    CredentialService.WEBVPN -> R.string.title_login_webvpn
                    CredentialService.CAMPUS_CARD -> R.string.title_login_campus_card
                    else -> R.string.title_login_jiaowu
                }
            )
        )
    }
}

@Composable
private fun ServiceCard(
    state: ServiceUiState,
    reveal: Boolean,
    onRenameAccount: () -> Unit,
    onVerify: () -> Unit,
    onLogin: () -> Unit,
    onResetPassword: () -> Unit,
    onEditToken: () -> Unit,
    onEditCredential: (name: String, value: String) -> Unit,
    onClear: (ClearTarget) -> Unit,
    onClearWebDavAll: () -> Unit,
    onEditWebDavAddress: () -> Unit,
    onEditWebDavUsername: () -> Unit,
) {
    val service = state.service
    SettingsCard(title = serviceName(service)) {
        // 账号行：名称可随意修改（与登录身份无关）。
        // WebDAV 的「账号」就是连接用的用户名，由下方 WebDAV 块里的用户名行承担，这里不再重复一行。
        if (service != CredentialService.WEBDAV) {
            SettingTile(
                icon = serviceIcon(service),
                title = stringResource(R.string.label_credential_account),
                subtitle = state.accountLabel.ifBlank { stringResource(R.string.status_not_set_account) },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                },
                onClick = onRenameAccount
            )

            SettingDivider()
        }

        // 会话行
        ActionRow(
            icon = Icons.Rounded.Lock,
            title = stringResource(R.string.item_session),
            subtitle = sessionStateText(state.isVerifying, state.sessionState),
            actions = {
                TextButton(onClick = onVerify, enabled = !state.isVerifying) {
                    Text(stringResource(R.string.action_verify))
                }
                if (state.hasSession) {
                    TextButton(onClick = { onClear(ClearTarget.SESSION) }) {
                        Text(stringResource(R.string.action_clear))
                    }
                }
            }
        )

        // 登录行（WebDAV 无登录）
        if (service != CredentialService.WEBDAV) {
            SettingDivider()
            ActionRow(
                icon = Icons.AutoMirrored.Rounded.Login,
                title = stringResource(R.string.item_login),
                subtitle = stringResource(R.string.desc_login_service),
                actions = {
                    TextButton(onClick = onLogin) { Text(stringResource(R.string.action_login)) }
                }
            )
        }

        // 密码行（图书馆/一卡通无单独密码；WebDAV 的密码并入下方 WebDAV 块）
        if (service != CredentialService.LIBRARY && service != CredentialService.WEBDAV && service != CredentialService.CAMPUS_CARD) {
            PasswordRow(state = state, onResetPassword = onResetPassword, onClear = onClear)
        }

        // Token 行（仅 WebVPN）
        if (service == CredentialService.WEBVPN) {
            SettingDivider()
            ActionRow(
                icon = Icons.Rounded.VpnKey,
                title = stringResource(R.string.item_twfid),
                subtitle = tokenSubtitle(reveal, state),
                actions = {
                    TextButton(onClick = onEditToken) {
                        Text(stringResource(R.string.action_edit))
                    }
                    if (state.hasToken) {
                        TextButton(onClick = { onClear(ClearTarget.TOKEN) }) {
                            Text(stringResource(R.string.action_clear))
                        }
                    }
                }
            )
        }

        // 会话凭据（仅高级模式）：直接列出 jw_uf / CASTGC / PHPSESSID 等，逐条可改
        if (reveal && state.credentials.isNotEmpty()) {
            state.credentials.forEach { (name, value) ->
                SettingDivider()
                ActionRow(
                    icon = Icons.Rounded.Key,
                    title = name,
                    subtitle = value,
                    actions = {
                        TextButton(onClick = { onEditCredential(name, value) }) {
                            Text(stringResource(R.string.action_edit))
                        }
                    }
                )
            }
        }

        // WebDAV：地址/用户名 + 清除全部
        if (service == CredentialService.WEBDAV) {
            SettingDivider()
            SettingTile(
                icon = Icons.Rounded.Cloud,
                title = stringResource(R.string.label_webdav_server),
                subtitle = state.webDavBaseUrl.ifBlank { stringResource(R.string.status_not_saved) },
                trailingContent = null,
                onClick = { onEditWebDavAddress() }
            )
            SettingDivider()
            SettingTile(
                icon = Icons.Rounded.Cloud,
                title = stringResource(R.string.label_webdav_username),
                subtitle = state.webDavUsername.ifBlank { stringResource(R.string.status_not_saved) },
                trailingContent = null,
                onClick = { onEditWebDavUsername() }
            )
            PasswordRow(state = state, onResetPassword = onResetPassword, onClear = onClear)
            SettingDivider()
            ActionRow(
                icon = Icons.Rounded.Cloud,
                title = stringResource(R.string.action_clear_all_webdav),
                subtitle = stringResource(R.string.desc_clear_all_webdav),
                actions = {
                    TextButton(onClick = onClearWebDavAll) {
                        Text(stringResource(R.string.action_clear))
                    }
                }
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingTile(
        icon = Icons.Rounded.Tune,
        title = label,
        subtitle = null,
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color.White.copy(alpha = 0.28f),
                    uncheckedThumbColor = Color.White.copy(alpha = 0.9f)
                )
            )
        },
        onClick = null
    )
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actions: @Composable () -> Unit,
) {
    SettingTile(
        icon = icon,
        title = title,
        subtitle = subtitle,
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { actions() }
        },
        onClick = null
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    mask: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(label) },
                singleLine = true,
                visualTransformation = if (mask) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (mask) KeyboardType.Password else KeyboardType.Text
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(input.trim()) }, enabled = input.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun sessionStateText(isVerifying: Boolean, state: SessionState?): String = when {
    isVerifying -> stringResource(R.string.status_verifying)
    state == SessionState.VALID -> stringResource(R.string.status_session_valid)
    state == SessionState.EXPIRED -> stringResource(R.string.status_session_expired)
    state == SessionState.NOT_LOGGED_IN -> stringResource(R.string.status_not_logged_in)
    state == SessionState.NOT_AVAILABLE -> stringResource(R.string.status_not_available)
    state == SessionState.UNKNOWN -> stringResource(R.string.status_session_unknown)
    else -> stringResource(R.string.status_not_verified)
}

@Composable
private fun PasswordRow(
    state: ServiceUiState,
    onResetPassword: () -> Unit,
    onClear: (ClearTarget) -> Unit,
) {
    SettingDivider()
    ActionRow(
        icon = Icons.Rounded.Key,
        title = stringResource(R.string.item_password),
        subtitle = passwordSubtitle(state),
        actions = {
            TextButton(onClick = onResetPassword) {
                Text(
                    stringResource(
                        if (state.hasOwnPassword) R.string.action_reset else R.string.action_set
                    )
                )
            }
            if (state.hasOwnPassword) {
                TextButton(onClick = { onClear(ClearTarget.PASSWORD) }) {
                    Text(stringResource(R.string.action_clear))
                }
            }
        }
    )
}

@Composable
private fun passwordSubtitle(state: ServiceUiState): String = when {
    !state.hasPassword -> stringResource(R.string.status_not_saved)
    !state.hasOwnPassword -> stringResource(R.string.status_follow_unified_auth)
    else -> stringResource(R.string.status_saved)
}

@Composable
private fun tokenSubtitle(reveal: Boolean, state: ServiceUiState): String = when {
    reveal && state.tokenValue.isNotBlank() -> state.tokenValue
    state.hasToken -> stringResource(R.string.status_saved)
    else -> stringResource(R.string.status_not_saved)
}

@Composable
private fun clearConfirmMessage(service: CredentialService, target: ClearTarget): String {
    val name = serviceName(service)
    return when (target) {
        ClearTarget.PASSWORD -> stringResource(R.string.msg_clear_password_confirm, name)
        ClearTarget.TOKEN -> stringResource(R.string.msg_clear_token_confirm, name)
        ClearTarget.SESSION -> stringResource(R.string.msg_clear_session_confirm, name)
        ClearTarget.ALL -> stringResource(R.string.msg_clear_all_confirm, name)
    }
}

@Composable
private fun serviceName(service: CredentialService): String = stringResource(
    when (service) {
        CredentialService.UNIFIED_AUTH -> R.string.service_unified_auth
        CredentialService.JIAOWU -> R.string.service_jiaowu
        CredentialService.LIBRARY -> R.string.service_library
        CredentialService.WEBVPN -> R.string.service_webvpn
        CredentialService.CAMPUS_CARD -> R.string.service_campus_card
        CredentialService.WEBDAV -> R.string.service_webdav
    }
)

private fun serviceIcon(service: CredentialService): ImageVector = when (service) {
    CredentialService.UNIFIED_AUTH -> Icons.Rounded.Fingerprint
    CredentialService.JIAOWU -> Icons.Rounded.School
    CredentialService.LIBRARY -> Icons.Rounded.AutoStories
    CredentialService.WEBVPN -> Icons.Rounded.VpnKey
    CredentialService.CAMPUS_CARD -> Icons.Rounded.CreditCard
    CredentialService.WEBDAV -> Icons.Rounded.Cloud
}
