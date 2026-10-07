package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.ui.res.stringResource
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthMode
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuLoginMethod
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuNetworkProbe
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.IdsCasClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WebVpnClient
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.DynamicCodeSendResult
import com.xingheyuzhuan.shiguangschedule.ui.theme.LocalIsDarkTheme
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
/**
 * 二维码登录阶段。
 */
enum class QrPhase {
    PLACEHOLDER, // 无内容，空二维码占位
    GENERATING,  // 正在生成/刷新（模糊 + 进度）
    WAIT,        // 等待扫码
    SCANNED,     // 已扫码，等待手机确认（遮罩 + 对勾）
    CONFIRMING,  // 已确认，正在登录（遮罩 + 旋转对勾）
    EXPIRED,     // 已过期（遮罩 + 刷新图标）
    ERROR        // 出错（遮罩 + 刷新图标）
}
/**
 * 二维码登录的 UI 状态：qrContent 用于本地渲染二维码，phase 决定遮罩/图标，statusText 为提示。
 */
data class QrUiState(
    val qrContent: String? = null,
    val phase: QrPhase = QrPhase.WAIT,
    val statusText: String = ""
)
private const val QR_PLACEHOLDER_CONTENT = " "
private fun generateQrBitmap(content: String, size: Int = 512): ImageBitmap? {
    return runCatching {
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (x in 0 until size) {
            for (y in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WbuAuthBottomSheet(
    onDismissRequest: () -> Unit,
    /**
     * 密码登录。第 5 个参数是**结果回调**：Sheet 只在回调 `true` 时才把密码落盘
     * （打错的密码不该被记住，更不该在下次静默登录里被继续使用）。
     */
    onPasswordLogin: (String, String, Boolean, WbuAuthMode, (Boolean) -> Unit) -> Unit,
    onDynamicCodeLogin: (String, String, Boolean) -> Unit,
    onSendDynamicCode: suspend (String, Boolean) -> DynamicCodeSendResult,
    onStartQr: (Boolean) -> Unit,
    onRefreshQr: (Boolean) -> Unit,
    method: WbuLoginMethod = WbuLoginMethod.PASSWORD,
    onMethodChange: (WbuLoginMethod) -> Unit,
    onUseVpnChange: (Boolean) -> Unit = {},
    qrState: QrUiState? = null,
    isLoading: Boolean = false,
    statusMessage: String = "",
    errorMessage: String = "",
    initialStudentId: String = "",
    initialUseVpn: Boolean = false,
    defaultAuthMode: WbuAuthMode? = null,
    hideSelectSemesterSwitch: Boolean = false,
    hideImportPreferences: Boolean = false,
    hideNetworkSwitch: Boolean = false,
    /**
     * 「仅登录统一认证」场景：本 Sheet 只为拿到/续期统一认证会话（CASTGC），不涉及教务系统与校园网。
     *
     * 开启后：
     * - 「统一认证经过WebVPN」为关：不显示网络访问开关，并强制直连（统一认证走公网）；
     * - 「统一认证经过WebVPN」为开：显示开关，关闭态文案由「校园网直连」改为「直连」；
     * - 一律不探测校园网、不显示校园网提示。
     */
    unifiedAuthOnly: Boolean = false,
    primaryButtonText: String? = null,
    loadingButtonText: String? = null,
    customLoadingTips: List<String>? = null,
    onNavigateToAccount: (() -> Unit)? = null,
    lockPasswordType: Boolean = false,
    /** Hide QR/dynamic-code methods when a target service only supports portal password login. */
    passwordLoginOnly: Boolean = false,
    passwordServiceOverride: CredentialService? = null,
    title: String? = null,
    onSyncWithCredentials: (() -> Unit)? = null,
    /** 是否显示右下角双按钮里的「同步」小按钮（未提供 [onSyncWithCredentials] 时为统一认证登录）。 */
    showSyncButton: Boolean = false,
) {
    val isDark = LocalIsDarkTheme.current
    val context = LocalContext.current
    var authMode by remember {
        mutableStateOf(defaultAuthMode ?: WbuAuthTransport.getSavedAuthMode(context))
    }
    // 记住密码按当前密码类型落到对应服务的槽位（教务系统密码与统一认证密码互不覆盖）
    val passwordService = passwordServiceOverride ?: if (authMode == WbuAuthMode.JYXT_LEGACY) {
        CredentialService.JIAOWU
    } else {
        CredentialService.UNIFIED_AUTH
    }
    var rememberPassword by remember(passwordService) { mutableStateOf(WbuAuthTransport.isRememberPasswordEnabled(context, passwordService)) }
    // 占位符只看该服务**自身**的密码槽：教务「跟随统一认证」不算已经存过教务密码
    var hasOwnSavedPassword by remember(passwordService) { mutableStateOf(WbuAuthTransport.hasOwnSavedPassword(context, passwordService)) }
    var studentId by remember(initialStudentId) { mutableStateOf(initialStudentId) }
    // 每种密码类型各自一份草稿：切来切去不会把用户已经打进去的内容吞掉
    val passwordDrafts = remember { mutableMapOf<CredentialService, PasswordDraft>() }
    val passwordDraft = remember(passwordService) { passwordDrafts.getOrPut(passwordService) { PasswordDraft() } }
    var useVpn by remember(initialUseVpn) { mutableStateOf(initialUseVpn) }
    // 教务自身没存密码时，本次登录会「跟随统一认证」：字段保持空白，只用一行提示说明会用到统一认证密码
    val followsUnifiedAuth = !hasOwnSavedPassword && WbuAuthTransport.hasSavedPassword(context, passwordService)
    // 本次登录能否直接用已保存的密码（含教务回退）
    val hasUsableSavedPassword = hasOwnSavedPassword || followsUnifiedAuth
    /**
     * 解析本次真正要用的密码：占位符态 → 已保存的密码；用户改过 → 草稿；草稿为空但可回退 → 回退到的统一认证密码。
     *
     * 槽位存在却取不出明文（换机 / 恢复备份后 KeyStore 失效）时顺手清掉这个坏槽位并要求重新输入，
     * 绝不把占位符或空串当成密码发出去。
     */
    fun resolveEffectivePassword(): String {
        val stored = if (hasOwnSavedPassword && !passwordDraft.edited) {
            WbuAuthTransport.getSavedPassword(context, passwordService)
        } else {
            null
        }
        val effective = when {
            !stored.isNullOrBlank() -> stored
            passwordDraft.text.isNotBlank() -> passwordDraft.text
            // 完全没动过输入框（显示占位符 / 教务跟随统一认证）时才允许直接用保存的密码；
            // 用户把字段清空后再按登录，就该当成「还没输密码」，而不是又拿保存的密码去登
            !passwordDraft.edited -> WbuAuthTransport.getSavedPassword(context, passwordService).orEmpty()
            else -> ""
        }
        if (effective.isBlank() && hasOwnSavedPassword) {
            WbuAuthTransport.setRememberPasswordEnabled(context, passwordService, false)
            WbuAuthTransport.clearSavedPassword(context, passwordService)
            hasOwnSavedPassword = false
            passwordDraft.edited = true
        }
        return effective
    }
    // 切换密码类型时刷新该类型自己的「已保存」状态（草稿按服务保留，不要动用户已经打进去的内容）
    LaunchedEffect(passwordService) {
        rememberPassword = WbuAuthTransport.isRememberPasswordEnabled(context, passwordService)
        hasOwnSavedPassword = WbuAuthTransport.hasOwnSavedPassword(context, passwordService)
    }
    var authMenuExpanded by remember { mutableStateOf(false) }
    var panelExpanded by remember { mutableStateOf(false) }
    var idsVpnEnabled by remember { mutableStateOf(IdsCasClient.getIdsViaWebVpn(context)) }
    var qrVpnEnabled by remember { mutableStateOf(IdsCasClient.getQrViaWebVpn(context)) }
    var selectSemesterOnImport by remember { mutableStateOf(WbuSyncEngine.getSelectSemesterOnImport(context)) }
    var pcUaEnabled by remember { mutableStateOf(WbuSyncEngine.getUsePcUserAgent(context)) }
    var skipCampusCheck by remember { mutableStateOf(WbuSyncEngine.getSkipCampusCheck(context)) }
    var keepTeacherId by remember { mutableStateOf(WbuSyncEngine.getKeepTeacherId(context)) }
    var keepBuilding by remember { mutableStateOf(WbuSyncEngine.getKeepBuilding(context)) }
    var twfidText by remember { mutableStateOf(WebVpnClient.getTwfid(context)) }
    // 账号页等其它入口改了 TWFID 时同步过来
    LaunchedEffect(Unit) {
        WebVpnClient.credentialChanges.collect { twfidText = WebVpnClient.getTwfid(context) }
    }
    var useHttpsWebVpn by remember { mutableStateOf(WebVpnClient.getUseHttpsWebVpn(context)) }
    val isZhCN = remember { WbuSyncEngine.isSimplifiedChinese(context) }
    var engSmsEnabled by remember { mutableStateOf(IdsCasClient.getSendEnglishSms(context)) }
    var idsAddrNotFromJwxt by remember { mutableStateOf(WbuSyncEngine.getIdsAddrNotFromJwxt(context)) }
    var noIndexMainVerify by remember { mutableStateOf(WbuSyncEngine.getNoIndexMainVerify(context)) }
    var forceFetchStudentIdBeforeVpn by remember { mutableStateOf(WbuSyncEngine.getForceFetchStudentIdBeforeVpn(context)) }
    var useFixedServiceForTicket by remember { mutableStateOf(WbuSyncEngine.getUseFixedServiceForTicket(context)) }
    var dynamicCode by remember(method) { mutableStateOf("") }
    var codeSent by remember(method) { mutableStateOf(false) }
    var sendingCode by remember(method) { mutableStateOf(false) }
    var resendCooldown by remember(method) { mutableIntStateOf(0) }
    var cooldownRun by remember(method) { mutableIntStateOf(0) }
    var dynamicSendError by remember(method) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val campus by WbuNetworkProbe.campusState.collectAsState()
    val tip1 = stringResource(R.string.tip_syncing_hello_jwxt)
    val tip2 = stringResource(R.string.tip_syncing_fairy_moving)
    val tip3 = stringResource(R.string.tip_syncing_finishing_up)
    val loadingTips = remember(tip1, tip2, tip3, customLoadingTips) {
        if (!customLoadingTips.isNullOrEmpty()) customLoadingTips else listOf(tip1, tip2, tip3)
    }
    var loadingTipIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(isLoading) {
        if (!isLoading) {
            loadingTipIndex = 0
            return@LaunchedEffect
        }
        while (isLoading) {
            delay(1700)
            loadingTipIndex = (loadingTipIndex + 1) % loadingTips.size
        }
    }
    // 「仅登录统一认证」时，统一认证是否经 WebVPN 通常由「统一认证经过WebVPN」决定：
    // 若外部调用方显式指定了 initialUseVpn=true（如网页应用在校外需经 WebVPN 穿透），则允许使用 WebVPN
    val casOnlyForceDirect = unifiedAuthOnly && !idsVpnEnabled && !initialUseVpn
    val showNetworkSwitch = !hideNetworkSwitch && (!unifiedAuthOnly || idsVpnEnabled || initialUseVpn)
    LaunchedEffect(casOnlyForceDirect) {
        if (casOnlyForceDirect && useVpn) {
            useVpn = false
            onUseVpnChange(false)
        }
    }
    // 选择校园网直连（非 VPN）时实时探测校园网环境；结果经 campusState 更新提示。
    // 开启「不检测校园网环境」则跳过探测；仅登录统一认证时校园网与本次登录无关，一律不探测。
    LaunchedEffect(useVpn, skipCampusCheck, unifiedAuthOnly) {
        if (!unifiedAuthOnly && !useVpn && !skipCampusCheck) WbuNetworkProbe.refresh()
    }
    // 动态码发送后倒计时；每次开始冷却（cooldownRun 变化）都会重跑（按钮显示重新发送 (Ns)）
    LaunchedEffect(cooldownRun) {
        if (cooldownRun > 0) {
            while (resendCooldown > 0) {
                delay(1000)
                resendCooldown--
            }
        }
    }
            // 「发送过于频繁」红字在几秒后自动消失（其余错误保留持久）
            LaunchedEffect(dynamicSendError) {
                if (dynamicSendError.startsWith("发送过于频繁") || dynamicSendError.startsWith("Sending too frequent") || dynamicSendError.startsWith("發送過於頻繁")) {
                    delay(3000)
                    dynamicSendError = ""
                }
            }
    fun sendCode() {
        if (studentId.isBlank() || sendingCode || resendCooldown > 0) return
        scope.launch {
            dynamicSendError = ""
            sendingCode = true
            val result = runCatching { onSendDynamicCode(studentId.trim(), useVpn) }
                .getOrElse { DynamicCodeSendResult.Failure(context.getString(R.string.err_sms_send_failed)) }
            sendingCode = false
            when (result) {
                is DynamicCodeSendResult.Success -> {
                    dynamicSendError = ""
                    codeSent = true
                    resendCooldown = 120
                    cooldownRun++
                }
                is DynamicCodeSendResult.Failure -> {
                    codeSent = true
                    if (result.waitSeconds > 0) {
                        resendCooldown = result.waitSeconds
                        dynamicSendError = context.getString(R.string.format_err_sms_frequent, result.waitSeconds)
                        cooldownRun++
                    } else {
                        resendCooldown = 0
                        dynamicSendError = result.message
                    }
                }
            }
        }
    }
    // 取消勾选「记住密码」时不立刻抹除（防误触），只记下「这次要清哪些服务」，关闭 Sheet 时再统一清。
    //
    // 旧写法把 DisposableEffect 的 key 设成 rememberPassword，取消勾选那一刻旧 effect 就被 dispose，
    // 而它捕获的委托读到的是**当前值** false —— 于是「延迟清空」实际上还是立刻清空；
    // 而且它捕获的 passwordService 是普通值，切过密码类型之后会去清**上一个服务**的槽位。
    val pendingPasswordClear = remember { mutableStateListOf<CredentialService>() }
    DisposableEffect(Unit) {
        onDispose {
            pendingPasswordClear.forEach { service ->
                WbuAuthTransport.setRememberPasswordEnabled(context, service, false)
                WbuAuthTransport.clearSavedPassword(context, service)
            }
            pendingPasswordClear.clear()
        }
    }
    // 打开即全展开，避免内容过高时停在半展开状态，导致登录后下方的报错信息被遮挡
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 44.dp, height = 5.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(999.dp)
                    )
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title ?: stringResource(R.string.title_wbu_login),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 20.dp)
            )
            // 账号输入（二维码登录无需账号）
            if (method != WbuLoginMethod.QR) {
                OutlinedTextField(
                    value = studentId,
                    onValueChange = { studentId = it },
                    label = { Text(stringResource(R.string.label_account_student_id)) },
                    leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    ),
                    enabled = !isLoading
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            // 按登录方式切换的输入区
            when (method) {
                WbuLoginMethod.PASSWORD -> {
                    PasswordInput(
                        draft = passwordDraft,
                        hasSavedPassword = hasOwnSavedPassword,
                        authMode = authMode,
                        onAuthModeChange = { newMode ->
                            // 只是切一下密码类型不等于要改全局偏好：登录方式在真正提交（或用户显式勾选记住密码）
                            // 时才写回，否则「随手切一下看看」会把用户的默认登录方式改掉
                            authMode = newMode
                        },
                        menuExpanded = authMenuExpanded,
                        onMenuExpandedChange = { authMenuExpanded = it },
                        lockPasswordType = lockPasswordType,
                        rememberPassword = rememberPassword,
                        supportingText = if (followsUnifiedAuth && !passwordDraft.edited) {
                            stringResource(R.string.hint_password_follows_unified_auth)
                        } else {
                            null
                        },
                        onRememberPasswordChange = { checked ->
                            rememberPassword = checked
                            if (!checked) {
                                // 只是取消勾选：先把占位符收起来（字段跟着变空），真正的抹除留到关闭 Sheet 时做
                                hasOwnSavedPassword = false
                                if (passwordService !in pendingPasswordClear) pendingPasswordClear.add(passwordService)
                            } else {
                                // 勾上只表示「这次的密码登录成功后帮我记住」：真正落盘在下面的登录结果回调里，
                                // 这里只撤销「待清理」状态并记下登录方式
                                pendingPasswordClear.remove(passwordService)
                                if (defaultAuthMode == null) {
                                    WbuAuthTransport.setSavedAuthMode(context, authMode)
                                }
                            }
                        },
                        enabled = !isLoading
                    )
                }
                WbuLoginMethod.DYNAMIC_CODE -> {
                    OutlinedTextField(
                        value = dynamicCode,
                        onValueChange = { if (it.length <= 6) dynamicCode = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.label_sms_otp)) },
                        leadingIcon = { Icon(Icons.Default.Sms, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        ),
                        enabled = !isLoading
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.hint_sms_sent_to_phone),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { sendCode() },
                            enabled = !isLoading && !sendingCode && studentId.isNotBlank() && resendCooldown <= 0
                        ) {
                            Text(
                                when {
                                    sendingCode -> stringResource(R.string.status_sending_sms)
                                    resendCooldown > 0 -> stringResource(R.string.format_resend_cooldown, resendCooldown)
                                    codeSent -> stringResource(R.string.action_resend_code)
                                    else -> stringResource(R.string.action_get_code)
                                }
                            )
                        }
                    }
                    if (dynamicSendError.isNotBlank()) {
                        Text(
                            text = dynamicSendError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        )
                    }
                }
                WbuLoginMethod.QR -> QrInput(
                    qrState = qrState,
                    onRefreshQr = { onRefreshQr(useVpn) }
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            // 校园网/VPN 切换
            if (showNetworkSwitch) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (useVpn) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 0.8.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (isDark) 0.18f else 0.55f),
                                Color.White.copy(alpha = if (isDark) 0.05f else 0.16f)
                            )
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (useVpn) Icons.Default.VpnKey else Icons.Default.Wifi,
                        contentDescription = null,
                        tint = if (useVpn) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            // 仅登录统一认证时关闭态不是「校园网直连」：统一认证走公网即可，与校园网无关
                            text = if (useVpn) {
                                stringResource(R.string.label_webvpn_access)
                            } else if (unifiedAuthOnly) {
                                stringResource(R.string.label_direct_connection)
                            } else {
                                stringResource(R.string.label_campus_network_direct)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (useVpn) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Switch(
                        checked = useVpn,
                        onCheckedChange = {
                            useVpn = it
                            onUseVpnChange(it)
                            if (method == WbuLoginMethod.QR) {
                                onRefreshQr(it)
                            }
                        },
                        enabled = !isLoading
                    )
                }
            }
            }
            // 校园网环境提示（仅直连模式显示）：检测中 / 未检测到；检测到校园网则不显示。
            // 开启「不检测校园网环境」时不探测、也不显示该提示；仅登录统一认证时与校园网无关，同样不显示。
            if (!unifiedAuthOnly && !useVpn && !skipCampusCheck && campus != true) {
                val (hintText, hintColor) = when (campus) {
                    null -> stringResource(R.string.status_detecting_campus_network) to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> stringResource(R.string.warn_no_campus_suggest_webvpn) to MaterialTheme.colorScheme.error
                }
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.bodySmall,
                    color = hintColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            // 登录按钮
            val (label, enabled, action) = when (method) {
                WbuLoginMethod.PASSWORD -> {
                    val canSubmit = studentId.isNotBlank() && (
                        passwordDraft.text.isNotBlank() || (hasUsableSavedPassword && !passwordDraft.edited)
                        )
                    Triple(
                        primaryButtonText ?: stringResource(R.string.action_one_tap_sync),
                        canSubmit,
                        {
                            val effectivePassword = resolveEffectivePassword()
                            if (effectivePassword.isNotBlank()) {
                                val shouldRemember = rememberPassword
                                if (!shouldRemember) {
                                    // 没勾「记住密码」也走同一套延迟清理，避免一按登录就把旧密码抹掉
                                    hasOwnSavedPassword = false
                                    if (passwordService !in pendingPasswordClear) pendingPasswordClear.add(passwordService)
                                }
                                // 密码先不落盘：等调用方回报「登录成功」才保存
                                onPasswordLogin(studentId, effectivePassword, useVpn, authMode) { success ->
                                    if (!success) return@onPasswordLogin
                                    if (shouldRemember) {
                                        WbuAuthTransport.setRememberPasswordEnabled(context, passwordService, true)
                                        WbuAuthTransport.savePassword(context, passwordService, effectivePassword)
                                        if (defaultAuthMode == null) {
                                            WbuAuthTransport.setSavedAuthMode(context, authMode)
                                        }
                                        hasOwnSavedPassword = true
                                    }
                                }
                            }
                        }
                    )
                }
                WbuLoginMethod.DYNAMIC_CODE -> Triple(primaryButtonText ?: stringResource(R.string.action_login), studentId.isNotBlank() && dynamicCode.length == 6, { onDynamicCodeLogin(studentId, dynamicCode, useVpn) })
                WbuLoginMethod.QR -> {
                    val phase = qrState?.phase ?: QrPhase.PLACEHOLDER
                    val busy = phase == QrPhase.GENERATING || phase == QrPhase.SCANNED || phase == QrPhase.CONFIRMING
                    val label = when (phase) {
                        QrPhase.EXPIRED, QrPhase.ERROR -> stringResource(R.string.action_regenerate)
                        QrPhase.WAIT -> stringResource(R.string.action_refresh_qr)
                        QrPhase.PLACEHOLDER -> stringResource(R.string.action_generate_qr)
                        else -> stringResource(R.string.status_processing)
                    }
                    Triple(label, !busy, { onStartQr(useVpn) })
                }
            }
            val buttonContent: @Composable () -> Unit = {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = LocalContentColor.current,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                    Text(loadingButtonText ?: stringResource(R.string.status_fetching_schedule), style = MaterialTheme.typography.titleMedium)
                } else {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }
            }
            val primaryColors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
            )
            if (showSyncButton) {
                // 小按钮：默认用统一认证凭据做一次真正的 CAS 登录（不再直接复用当前 Cookie）；
                // 调用方提供了自定义动作时（如课表页）优先用自定义的。
                // 双按钮：左「登录同步」(2/3) + 右「统一认证登录」图标 (1/3)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { action() },
                        modifier = Modifier
                            .weight(2f)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(16.dp),
                        colors = primaryColors,
                        enabled = enabled && !isLoading
                    ) {
                        buttonContent()
                    }
                    Button(
                        onClick = {
                            // 已保存密码的解密只放在点击时做：放进组合体里会让每次重组都在主线程走一遍 KeyStore
                            val syncPassword = passwordDraft.text
                                .takeIf { passwordDraft.edited && it.isNotBlank() }
                                ?: WbuAuthTransport.getSavedPassword(context, CredentialService.UNIFIED_AUTH).orEmpty()
                            // 「同步」用的是已经保存过的密码，没有需要落盘的东西，结果回调给空实现
                            onSyncWithCredentials?.invoke()
                                ?: onPasswordLogin(studentId, syncPassword, useVpn, WbuAuthMode.UNIFIED_CAS) { }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(16.dp),
                        colors = primaryColors,
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = stringResource(R.string.action_sync_short)
                        )
                    }
                }
            } else {
                Button(
                    onClick = { action() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = primaryColors,
                    enabled = enabled && !isLoading
                ) {
                    buttonContent()
                }
            }
            AnimatedVisibility(visible = isLoading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    if (statusMessage.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    width = 0.8.dp,
                                    color = Color.White.copy(alpha = if (isDark) 0.14f else 0.45f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                        ) {
                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = loadingTips[loadingTipIndex],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            if (errorMessage.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = if (isDark) 0.30f else 0.90f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }
            // 同步按钮底部的“更多”（展开：登录方式 + 网络设置 + 导入偏好）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = { panelExpanded = !panelExpanded }) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(if (panelExpanded) 180f else 0f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (panelExpanded) stringResource(R.string.action_collapse_more) else stringResource(R.string.action_expand_more))
                }
            }
            if (panelExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(stringResource(R.string.label_login_method), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MethodRow(stringResource(R.string.method_password), WbuLoginMethod.PASSWORD, method, onMethodChange)
                    if (!passwordLoginOnly) {
                        MethodRow(stringResource(R.string.method_qr), WbuLoginMethod.QR, method, onMethodChange)
                        MethodRow(stringResource(R.string.method_otp), WbuLoginMethod.DYNAMIC_CODE, method, onMethodChange)
                    }
                    if (onNavigateToAccount != null) {
                        Text(
                            text = stringResource(R.string.item_credential_management),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.action_go_to_credential_management),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onNavigateToAccount() }
                                .padding(vertical = 10.dp)
                        )
                    }
                    if (!hideImportPreferences) {
                        Text(
                            text = stringResource(R.string.category_import_preferences),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        if (!hideSelectSemesterSwitch) {
                            ToggleRow(
                                label = stringResource(R.string.title_select_import_semester),
                                checked = selectSemesterOnImport,
                                onCheckedChange = {
                                    selectSemesterOnImport = it
                                    WbuSyncEngine.setSelectSemesterOnImport(context, it)
                                }
                            )
                        }
                        ToggleRow(
                            label = stringResource(R.string.pref_keep_teacher_id),
                            checked = keepTeacherId,
                            onCheckedChange = {
                                keepTeacherId = it
                                WbuSyncEngine.setKeepTeacherId(context, it)
                            }
                        )
                        ToggleRow(
                            label = stringResource(R.string.pref_keep_building_name),
                            checked = keepBuilding,
                            onCheckedChange = {
                                keepBuilding = it
                                WbuSyncEngine.setKeepBuilding(context, it)
                            }
                        )
                    }
                    Text(
                        text = stringResource(R.string.category_network_settings),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    ToggleRow(
                        label = stringResource(R.string.pref_desktop_ua),
                        checked = pcUaEnabled,
                        onCheckedChange = {
                            pcUaEnabled = it
                            WbuSyncEngine.setUsePcUserAgent(context, it)
                        }
                    )
                    ToggleRow(
                        label = stringResource(R.string.pref_skip_campus_network_check),
                        checked = skipCampusCheck,
                        onCheckedChange = {
                            skipCampusCheck = it
                            WbuSyncEngine.setSkipCampusCheck(context, it)
                        }
                    )
                    // 只展示会影响本次登录的选项：
                    // - ids 走 WebVPN：本次走统一认证（或已处于 WebVPN 模式 / 仅登录统一认证）时才相关
                    // - 二维码走 WebVPN：仅二维码登录时相关
                    // - HTTPS WebVPN：仅 WebVPN 模式时相关
                    val loginUsesCas =
                        authMode == WbuAuthMode.UNIFIED_CAS || defaultAuthMode == WbuAuthMode.UNIFIED_CAS
                    val showIdsVpnToggle = loginUsesCas || useVpn || unifiedAuthOnly
                    val showQrVpnToggle = method == WbuLoginMethod.QR
                    val showHttpsVpnToggle = useVpn
                    if (showIdsVpnToggle) {
                        ToggleRow(
                            label = stringResource(R.string.pref_ids_via_webvpn),
                            checked = idsVpnEnabled,
                            onCheckedChange = {
                                idsVpnEnabled = it
                                IdsCasClient.setIdsViaWebVpn(context, it)
                            }
                        )
                    }
                    if (showQrVpnToggle) {
                        ToggleRow(
                            label = stringResource(R.string.pref_qr_with_webvpn),
                            checked = qrVpnEnabled,
                            onCheckedChange = {
                                qrVpnEnabled = it
                                IdsCasClient.setQrViaWebVpn(context, it)
                            }
                        )
                    }
                    if (showHttpsVpnToggle) {
                        ToggleRow(
                            label = stringResource(R.string.pref_use_https_webvpn),
                            checked = useHttpsWebVpn,
                            onCheckedChange = {
                                useHttpsWebVpn = it
                                WebVpnClient.setUseHttpsWebVpn(context, it)
                            }
                        )
                    }
                    OutlinedTextField(
                        value = twfidText,
                        onValueChange = {
                            twfidText = it
                            WebVpnClient.setTwfid(context, it)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        label = { Text("WebVPN TWFID") },
                        placeholder = { Text(stringResource(R.string.placeholder_webvpn_twfid)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )
                    if (!isZhCN) {
                        ToggleRow(
                            label = stringResource(R.string.pref_send_english_sms),
                            checked = engSmsEnabled,
                            onCheckedChange = {
                                engSmsEnabled = it
                                IdsCasClient.setSendEnglishSms(context, it)
                            }
                        )
                    }
                    ToggleRow(
                        label = "IDS addr not from Jwxt",
                        checked = idsAddrNotFromJwxt,
                        onCheckedChange = {
                            idsAddrNotFromJwxt = it
                            WbuSyncEngine.setIdsAddrNotFromJwxt(context, it)
                        }
                    )
                    ToggleRow(
                        label = "no indexMain verify",
                        checked = noIndexMainVerify,
                        onCheckedChange = {
                            noIndexMainVerify = it
                            WbuSyncEngine.setNoIndexMainVerify(context, it)
                        }
                    )
                    ToggleRow(
                        label = stringResource(R.string.pref_fetch_id_before_vpn),
                        checked = forceFetchStudentIdBeforeVpn,
                        onCheckedChange = {
                            forceFetchStudentIdBeforeVpn = it
                            WbuSyncEngine.setForceFetchStudentIdBeforeVpn(context, it)
                        }
                    )
                    ToggleRow(
                        label = stringResource(R.string.pref_fixed_ticket_service),
                        checked = useFixedServiceForTicket,
                        onCheckedChange = {
                            useFixedServiceForTicket = it
                            WbuSyncEngine.setUseFixedServiceForTicket(context, it)
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
@Composable
private fun PasswordInput(
    draft: PasswordDraft,
    hasSavedPassword: Boolean,
    authMode: WbuAuthMode,
    onAuthModeChange: (WbuAuthMode) -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    lockPasswordType: Boolean = false,
    rememberPassword: Boolean,
    onRememberPasswordChange: (Boolean) -> Unit,
    enabled: Boolean,
    supportingText: String? = null
) {
    SavedPasswordField(
        draft = draft,
        hasSavedPassword = hasSavedPassword,
        label = if (authMode == WbuAuthMode.JYXT_LEGACY) {
            stringResource(R.string.label_jwxt_password)
        } else {
            stringResource(R.string.label_cas_password)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        supportingText = supportingText,
        colors = savedPasswordFieldColors(),
        leadingIcon = {
            if (lockPasswordType) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null
                    )
                }
            } else {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = { if (!enabled) {} else onMenuExpandedChange(true) },
                    enabled = enabled
                ) {
                    Icon(
                        imageVector = if (authMode == WbuAuthMode.JYXT_LEGACY) Icons.Default.Key else Icons.Default.Lock,
                        contentDescription = stringResource(R.string.title_choose_auth_type)
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(16.dp)
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.label_cas_password)) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        onClick = {
                            onAuthModeChange(WbuAuthMode.UNIFIED_CAS)
                            onMenuExpandedChange(false)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.label_jwxt_password)) },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                        onClick = {
                            onAuthModeChange(WbuAuthMode.JYXT_LEGACY)
                            onMenuExpandedChange(false)
                        }
                    )
                }
            }
            }
        },
        trailingIcon = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) {
                        onRememberPasswordChange(!rememberPassword)
                    }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.label_remember_password),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Checkbox(
                    checked = rememberPassword,
                    onCheckedChange = { onRememberPasswordChange(it) },
                    enabled = enabled,
                    modifier = Modifier
                        .size(20.dp)
                        .scale(0.85f)
                )
            }
        },
    )
}
@Composable
private fun QrInput(
    qrState: QrUiState?,
    onRefreshQr: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        val phase = qrState?.phase ?: QrPhase.PLACEHOLDER
        // 占位/生成时用空格内容生成空二维码，避免 zxing 对空串报错
        val content = if (qrState?.qrContent.isNullOrBlank()) QR_PLACEHOLDER_CONTENT else qrState!!.qrContent!!
        val bmp = remember(content) { generateQrBitmap(content) }
        // 有遮罩的阶段（生成中/已扫码/确认中/过期/出错）都模糊显示
        val blurRadius = when (phase) {
            QrPhase.GENERATING, QrPhase.SCANNED, QrPhase.CONFIRMING, QrPhase.EXPIRED, QrPhase.ERROR -> 18.dp
            else -> 0.dp
        }
        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = stringResource(R.string.a11y_login_qr_code),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .blur(blurRadius)
                )
            }
            when (phase) {
                QrPhase.GENERATING -> {
                    Scrim()
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                }
                QrPhase.PLACEHOLDER -> {
                    Text(
                        text = stringResource(R.string.status_generating_qr),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                QrPhase.SCANNED -> {
                    Scrim()
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.a11y_qr_scanned),
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                }
                QrPhase.CONFIRMING -> {
                    Scrim()
                    val infinite = rememberInfiniteTransition(label = "qrSpin")
                    val rotation by infinite.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
                        label = "qrRot"
                    )
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.a11y_logging_in),
                        tint = Color.White,
                        modifier = Modifier
                            .rotate(rotation)
                            .size(56.dp)
                    )
                }
                QrPhase.EXPIRED, QrPhase.ERROR -> {
                    Scrim()
                    IconButton(onClick = onRefreshQr) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_refresh_qr),
                            tint = Color.White,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }
                else -> Unit // WAIT：无遮罩
            }
        }
        if (qrState != null && qrState.statusText.isNotBlank()) {
            Text(
                text = qrState.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (phase == QrPhase.EXPIRED || phase == QrPhase.ERROR) {
            Text(
                text = stringResource(R.string.hint_tap_qr_to_refresh),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
@Composable
private fun Scrim() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
    )
}
@Composable
private fun MethodRow(
    label: String,
    candidate: WbuLoginMethod,
    current: WbuLoginMethod,
    onSelect: (WbuLoginMethod) -> Unit
) {
    val selected = candidate == current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(candidate) }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when (candidate) {
                WbuLoginMethod.PASSWORD -> Icons.Default.Lock
                WbuLoginMethod.QR -> Icons.Default.QrCode2
                WbuLoginMethod.DYNAMIC_CODE -> Icons.Default.Sms
            },
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
/**
 * WebVPN 短信验证码输入对话框
 */
@Composable
fun VpnSmsCodeDialog(
    maskedPhone: String,
    isStillValid: Boolean = false,
    sendInterval: Int = 0,
    promptText: String? = null,
    onSubmit: (String) -> Unit,
    /** 重新发送；返回服务端要求的重发冷却秒数（0 = 服务端不限制），失败返回 null。 */
    onResend: suspend () -> Int?,
    onDismiss: () -> Unit,
    isVerifying: Boolean = false,
    errorMessage: String? = null
) {
    val scope = rememberCoroutineScope()
    var smsCode by remember { mutableStateOf("") }
    // 倒计时只认服务端给的秒数：0 = 服务端不限制（门户前端 disableTime = SmsSendInterval || SmsIsStillValid || 0）。
    // <=1 秒直接不锁：SmsIsStillValid 可能是布尔 1，别闪出一个 1 秒倒计时；门户 UI 的
    // countDown() 也只在 1 < disableTime 时才计时。
    var resendCooldown by remember(sendInterval) {
        mutableIntStateOf(if (sendInterval > 1) sendInterval else 0)
    }
    LaunchedEffect(resendCooldown) {
        if (resendCooldown > 1) {
            delay(1000)
            resendCooldown--
        } else if (resendCooldown == 1) {
            resendCooldown = 0
        }
    }
    AlertDialog(
        onDismissRequest = { if (!isVerifying) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        icon = {
            Icon(
                imageVector = Icons.Default.Sms,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.title_webvpn_sms_verification),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val unknownNumStr = stringResource(R.string.label_unknown_number)
                val displayMsg = when {
                    isStillValid -> stringResource(R.string.hint_otp_still_valid)
                    !promptText.isNullOrBlank() -> promptText
                    maskedPhone.isNotBlank() && maskedPhone != "未知号码" && maskedPhone != unknownNumStr -> stringResource(R.string.format_otp_sent_to_phone, maskedPhone)
                    else -> stringResource(R.string.hint_otp_sent_to_bound_phone)
                }
                Text(
                    text = displayMsg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = smsCode,
                    onValueChange = { if (it.length <= 6) smsCode = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.label_sms_otp)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isVerifying,
                    isError = errorMessage != null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (LocalIsDarkTheme.current) 0.14f else 0.24f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (LocalIsDarkTheme.current) 0.10f else 0.18f)
                    )
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        scope.launch {
                            // 重发后按服务端返回的间隔重新计时；没拿到、或只给了 1 秒就不锁
                            // （不要自己编 60 秒，也不要点一下闪一个 1 秒倒计时）
                            resendCooldown = onResend()?.let { if (it > 1) it else 0 } ?: 0
                        }
                    },
                    enabled = resendCooldown <= 0 && !isVerifying
                ) {
                    Text(
                        if (resendCooldown > 0) stringResource(R.string.format_resend_cooldown, resendCooldown)
                        else stringResource(R.string.action_get_or_resend_code)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(smsCode) },
                enabled = smsCode.length == 6 && !isVerifying
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.action_verify))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isVerifying
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
