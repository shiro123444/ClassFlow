package com.xingheyuzhuan.shiguangschedule.ui.campus.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AuthForm
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.DynamicCodeSendResult
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.IdsCasClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.LocalLoginFailure
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.QrStatus
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.SliderCaptchaData
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.SliderCaptchaResult
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthMode
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuLoginMethod
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.VpnFullLoginStatus
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WebVpnClient
import com.xingheyuzhuan.shiguangschedule.ui.components.QrPhase
import com.xingheyuzhuan.shiguangschedule.ui.components.QrUiState
import com.xingheyuzhuan.shiguangschedule.ui.components.SliderCaptchaDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.SslIssueDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.VpnSmsCodeDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.WbuAuthBottomSheet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 校园服务（成绩、空教室、学业进程）通用登录 Sheet。
 * 复用顶级的 [WbuAuthBottomSheet]，隐藏课表导入偏好，并真正对齐成熟的 WebVPN 统一认证门禁与短信二次校验。
 */
/** 登录 Sheet 底部加载提示的场景，决定加载时轮播哪一组文案。 */
enum class WbuAuthTipsScenario { CAMPUS, LIBRARY, IDENTITY, IMPORT }

@Composable
fun WbuCampusAuthSheet(
    onDismiss: () -> Unit,
    onLoginSuccess: () -> Unit,
    requireUnifiedCas: Boolean = false,
    forceDirectCampus: Boolean = false,
    defaultAuthMode: WbuAuthMode? = null,
    lockPasswordType: Boolean = false,
    title: String? = null,
    hideImportPreferences: Boolean = true,
    hideSelectSemesterSwitch: Boolean = true,
    tipsScenario: WbuAuthTipsScenario = WbuAuthTipsScenario.CAMPUS,
    onNavigateToAccount: (() -> Unit)? = null,
    primaryButtonText: String? = null,
    loadingButtonText: String? = null,
    onSyncWithCredentials: (() -> Unit)? = null,
    showSyncButton: Boolean = true,
    hideNetworkSwitch: Boolean = false,
    /**
     * 「仅登录统一认证」场景（账号与凭据页统一认证卡 / 扫一扫页）：
     * 本 Sheet 只为拿到或续期统一认证会话（CASTGC），不涉及教务系统与校园网。
     *
     * - 「统一认证经过WebVPN」关闭：不显示网络开关、强制直连，也不校验 WebVPN 与校园网；
     * - 「统一认证经过WebVPN」开启：开关可用（关闭态文案为「直连」），开启时先校验 WebVPN；
     * - 三种登录方式都不再尝试登录教务系统，也不改写全局「网络接入模式」偏好。
     */
    unifiedAuthOnly: Boolean = false,
    dismissOnSuccess: Boolean = true,
    externalLoading: Boolean = false,
    externalStatusMessage: String = "",
    externalErrorMessage: String = "",
    flowTagPrefix: String = "CAMPUS",
    onVpnStatus: ((VpnFullLoginStatus, WbuAuthMode) -> Unit)? = null,
    confirmCampusNetwork: (suspend (Boolean) -> Boolean)? = null,
    onCaptchaFallback: ((String, String, Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loginMethod by remember { mutableStateOf(WbuLoginMethod.PASSWORD) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var initialUseVpn by remember {
        mutableStateOf(
            when {
                forceDirectCampus -> false
                // 仅登录统一认证：是否经 WebVPN 由「统一认证经过WebVPN」决定
                // （该设置关闭时开关不显示，并由 Sheet 强制直连）
                unifiedAuthOnly -> IdsCasClient.getIdsViaWebVpn(context)
                else -> WbuSyncEngine.getSavedUseVpn(context) ?: false
            }
        )
    }

    var dynamicPrep by remember { mutableStateOf<AuthForm?>(null) }
    var qrState by remember { mutableStateOf<QrUiState?>(null) }
    var qrJob by remember { mutableStateOf<Job?>(null) }

    var activeVpnEngine by remember { mutableStateOf<WbuSyncEngine?>(null) }

    // 滑块验证码
    var captchaDialogData by remember { mutableStateOf<SliderCaptchaData?>(null) }
    var captchaDeferred by remember { mutableStateOf<CompletableDeferred<SliderCaptchaResult?>?>(null) }

    // WebVPN 统一认证密码弹窗状态
    var vpnPasswordDeferred by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }

    // WebVPN 短信二次验证码弹窗状态
    var smsDeferred by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }
    var smsDialogPhone by remember { mutableStateOf<String?>(null) }
    var smsDialogIsStillValid by remember { mutableStateOf(false) }
    var smsDialogSendInterval by remember { mutableIntStateOf(60) }
    var smsDialogPromptText by remember { mutableStateOf("") }
    var smsVerifying by remember { mutableStateOf(false) }
    var smsError by remember { mutableStateOf<String?>(null) }

    // WebVPN TLS 证书校验异常
    var sslIssueMessage by remember { mutableStateOf("") }
    var sslIssueDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            qrJob?.cancel()
            vpnPasswordDeferred?.complete(null)
            smsDeferred?.complete(null)
            captchaDeferred?.complete(SliderCaptchaResult.Cancel)
            sslIssueDeferred?.complete(false)
        }
    }

    /** 统一创建引擎：挂上 WebVPN 证书异常询问链路。 */
    fun newEngine(useVpn: Boolean): WbuSyncEngine {
        val created = WbuSyncEngine(context = context, useVpn = useVpn)
        created.sslIssueHandler = { msg ->
            val deferred = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                sslIssueMessage = msg
                sslIssueDeferred = deferred
            }
            deferred.await()
        }
        return created
    }

    // 0. WebVPN 证书校验异常弹窗
    sslIssueDeferred?.let { deferred ->
        SslIssueDialog(
            message = sslIssueMessage,
            onConfirm = {
                deferred.complete(true)
                sslIssueDeferred = null
            },
            onDismiss = {
                deferred.complete(false)
                sslIssueDeferred = null
            }
        )
    }

    // 1. 滑块验证码弹窗
    if (captchaDialogData != null) {
        SliderCaptchaDialog(
            captcha = captchaDialogData!!,
            onSubmit = { result ->
                captchaDeferred?.complete(result)
                captchaDeferred = null
                captchaDialogData = null
            },
            onDismiss = {
                captchaDeferred?.complete(SliderCaptchaResult.Cancel)
                captchaDeferred = null
                captchaDialogData = null
            }
        )
    }

    // 2. WebVPN 统一认证密码询问弹窗（教务密码模式且未配置有效 TWFID 时触发）
    vpnPasswordDeferred?.let { deferred ->
        var rememberVpnPassword by remember { mutableStateOf(WbuSyncEngine.isRememberVpnPasswordEnabled(context)) }
        var hasSavedVpnPassword by remember { mutableStateOf(WbuSyncEngine.hasSavedVpnPassword(context)) }
        var isVpnPasswordModified by remember { mutableStateOf(false) }
        var inputPassword by remember {
            mutableStateOf(if (WbuSyncEngine.hasSavedVpnPassword(context)) "••••••••" else "")
        }
        var passwordVisible by remember { mutableStateOf(false) }

        DisposableEffect(rememberVpnPassword) {
            onDispose {
                if (!rememberVpnPassword) {
                    WbuSyncEngine.setRememberVpnPasswordEnabled(context, false)
                    WbuSyncEngine.clearSavedVpnPassword(context)
                }
            }
        }

        AlertDialog(
            onDismissRequest = {
                deferred.complete(null)
                vpnPasswordDeferred = null
            },
            title = { Text(stringResource(R.string.title_connect_webvpn)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.desc_connect_webvpn),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inputPassword,
                        onValueChange = { newValue ->
                            if (hasSavedVpnPassword && !isVpnPasswordModified) {
                                isVpnPasswordModified = true
                                inputPassword = if (newValue.startsWith("••••••••")) {
                                    newValue.removePrefix("••••••••")
                                } else if (newValue.endsWith("••••••••")) {
                                    newValue.removeSuffix("••••••••")
                                } else if (newValue.contains("••••••••")) {
                                    newValue.replace("••••••••", "")
                                } else {
                                    newValue
                                }
                            } else {
                                inputPassword = newValue
                            }
                        },
                        label = { Text(stringResource(R.string.label_webvpn_password)) },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = 6.dp)
                            ) {
                                // 预填已记住的密码（••••••••）时隐藏"显示密码"按钮，避免展示无意义的占位符
                                if (!(hasSavedVpnPassword && !isVpnPasswordModified)) {
                                    IconButton(
                                        onClick = { passwordVisible = !passwordVisible },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            val next = !rememberVpnPassword
                                            rememberVpnPassword = next
                                            if (!next) {
                                                hasSavedVpnPassword = false
                                                if (!isVpnPasswordModified && inputPassword == "••••••••") {
                                                    inputPassword = ""
                                                }
                                            } else {
                                                WbuSyncEngine.setRememberVpnPasswordEnabled(context, true)
                                            }
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
                                        checked = rememberVpnPassword,
                                        onCheckedChange = { next ->
                                            rememberVpnPassword = next
                                            if (!next) {
                                                hasSavedVpnPassword = false
                                                if (!isVpnPasswordModified && inputPassword == "••••••••") {
                                                    inputPassword = ""
                                                }
                                            } else {
                                                WbuSyncEngine.setRememberVpnPasswordEnabled(context, true)
                                            }
                                        },
                                        modifier = Modifier
                                            .size(20.dp)
                                            .scale(0.85f)
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val finalPassword = if (hasSavedVpnPassword && !isVpnPasswordModified) {
                            WbuSyncEngine.getSavedVpnPassword(context) ?: ""
                        } else {
                            inputPassword
                        }
                        if (rememberVpnPassword && finalPassword.isNotBlank()) {
                            WbuSyncEngine.saveVpnPassword(context, finalPassword)
                        }
                        deferred.complete(finalPassword.ifBlank { null })
                        vpnPasswordDeferred = null
                    }
                ) {
                    Text(stringResource(R.string.action_continue_connect))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deferred.complete(null)
                        vpnPasswordDeferred = null
                    }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 3. WebVPN 短信二次验证码弹窗
    if (smsDialogPhone != null) {
        VpnSmsCodeDialog(
            maskedPhone = smsDialogPhone!!,
            isStillValid = smsDialogIsStillValid,
            sendInterval = smsDialogSendInterval,
            promptText = smsDialogPromptText,
            isVerifying = smsVerifying,
            errorMessage = smsError,
            onSubmit = { code ->
                smsError = null
                smsDeferred?.complete(code)
                smsDialogPhone = null
                smsDeferred = null
                smsVerifying = false
            },
            onResend = {
                scope.launch {
                    val result = activeVpnEngine?.resendVpnSmsCode()
                    if (result?.success == true) {
                        smsDialogSendInterval = result.cooldownSeconds
                    } else {
                        smsError = "重新发送失败，请稍后重试"
                    }
                }
            },
            onDismiss = {
                smsDeferred?.complete(null)
                smsDialogPhone = null
                smsDeferred = null
                smsVerifying = false
                smsError = null
            }
        )
    }

    val startQrFlow: (Boolean) -> Unit = { useVpn ->
        errorMessage = ""
        qrJob?.cancel()
        val engine = newEngine(useVpn)
        activeVpnEngine = engine
        qrState = QrUiState(
            qrContent = null,
            phase = QrPhase.GENERATING,
            statusText = context.getString(R.string.status_qr_fetching)
        )
        scope.launch {
            val session = engine.startQrLogin("CAMPUS_QR", unifiedAuthOnly)
            if (session == null) {
                qrState = QrUiState(
                    qrContent = null,
                    phase = QrPhase.ERROR,
                    statusText = context.getString(R.string.status_qr_fetch_failed)
                )
                return@launch
            }
            qrState = QrUiState(
                qrContent = session.content,
                phase = QrPhase.WAIT,
                statusText = context.getString(R.string.status_scan_qr_to_login)
            )
            qrJob = scope.launch {
                while (true) {
                    delay(2000)
                    when (val st = engine.pollQrStatus(session)) {
                        QrStatus.WAIT -> qrState = QrUiState(qrContent = session.content, phase = QrPhase.WAIT, statusText = context.getString(R.string.status_scan_qr_to_login))
                        QrStatus.CONFIRM -> qrState = QrUiState(qrContent = session.content, phase = QrPhase.SCANNED, statusText = context.getString(R.string.status_qr_scanned))
                        QrStatus.SUCCESS -> {
                            qrState = QrUiState(qrContent = session.content, phase = QrPhase.CONFIRMING, statusText = context.getString(R.string.status_qr_confirming))
                            isLoading = true
                            statusMessage = context.getString(R.string.status_logging_in)
                            try {
                                val qrOk = engine.completeQrLogin(
                                    session = session,
                                    flowTag = "${flowTagPrefix}_QR",
                                    unifiedAuthOnly = unifiedAuthOnly,
                                    vpnPasswordProvider = {
                                        val d = CompletableDeferred<String?>()
                                        withContext(Dispatchers.Main) {
                                            vpnPasswordDeferred = d
                                        }
                                        d.await()
                                    },
                                    smsCodeProvider = { maskedPhone, isStillValid, sendInterval, promptText ->
                                        val d = CompletableDeferred<String?>()
                                        withContext(Dispatchers.Main) {
                                            smsError = null
                                            smsVerifying = false
                                            smsDeferred = d
                                            smsDialogPhone = maskedPhone
                                            smsDialogIsStillValid = isStillValid
                                            smsDialogSendInterval = sendInterval
                                            smsDialogPromptText = promptText
                                        }
                                        d.await()
                                    }
                                )
                                isLoading = false
                                if (qrOk) {
                                    if (!forceDirectCampus && !unifiedAuthOnly) WbuSyncEngine.setSavedUseVpn(context, useVpn)
                                    onLoginSuccess()
                                    if (dismissOnSuccess) onDismiss()
                                } else {
                                    errorMessage = "登录验证失败，请重试"
                                    qrState = QrUiState(qrContent = session.content, phase = QrPhase.ERROR, statusText = context.getString(R.string.status_qr_login_failed_retry))
                                }
                            } catch (e: Exception) {
                                isLoading = false
                                errorMessage = e.message ?: "登录异常"
                            }
                            break
                        }
                        QrStatus.EXPIRED -> {
                            qrState = QrUiState(qrContent = session.content, phase = QrPhase.EXPIRED, statusText = context.getString(R.string.status_qr_expired))
                            break
                        }
                        QrStatus.ERROR -> {
                            qrState = QrUiState(qrContent = session.content, phase = QrPhase.ERROR, statusText = context.getString(R.string.status_qr_query_failed))
                            break
                        }
                    }
                }
            }
        }
    }

    // 底部加载提示随场景变化：校园服务 / 图书馆 / 统一身份认证 / 课表导入
    val scenarioTips = when (tipsScenario) {
        WbuAuthTipsScenario.CAMPUS -> listOf(
            stringResource(R.string.tip_campus_connecting_securely),
            stringResource(R.string.tip_campus_syncing_records),
            stringResource(R.string.tip_campus_finalizing_data)
        )
        WbuAuthTipsScenario.LIBRARY -> listOf(
            stringResource(R.string.tip_library_connecting_securely),
            stringResource(R.string.tip_library_verifying_reader),
            stringResource(R.string.tip_library_preparing_data)
        )
        WbuAuthTipsScenario.IDENTITY -> listOf(
            stringResource(R.string.tip_identity_connecting),
            stringResource(R.string.tip_identity_verifying),
            stringResource(R.string.tip_identity_preparing_session)
        )
        WbuAuthTipsScenario.IMPORT -> null
    }

    WbuAuthBottomSheet(
        onDismissRequest = {
            if (!isLoading) {
                qrJob?.cancel()
                onDismiss()
            }
        },
        method = loginMethod,
        onMethodChange = { m ->
            loginMethod = m
            if (m != WbuLoginMethod.QR) {
                qrJob?.cancel()
                qrState = null
            } else if (qrState == null) {
                startQrFlow(initialUseVpn)
            }
        },
        onUseVpnChange = {
            initialUseVpn = it
            // 仅登录统一认证时该开关只描述「本次统一认证是否经 WebVPN」，
            // 不写全局「网络接入模式」，避免影响教务/图书馆等校园服务的接入方式
            if (!forceDirectCampus && !unifiedAuthOnly) WbuSyncEngine.setSavedUseVpn(context, it)
        },
        qrState = qrState,
        isLoading = isLoading || externalLoading,
        statusMessage = externalStatusMessage.ifBlank { statusMessage },
        errorMessage = externalErrorMessage.ifBlank { errorMessage },
        initialStudentId = WbuSyncEngine.getSavedStudentId(context),
        initialUseVpn = if (forceDirectCampus) false else initialUseVpn,
        defaultAuthMode = defaultAuthMode,
        lockPasswordType = lockPasswordType,
        title = title,
        hideSelectSemesterSwitch = hideSelectSemesterSwitch,
        hideImportPreferences = hideImportPreferences,
        hideNetworkSwitch = forceDirectCampus || hideNetworkSwitch,
        unifiedAuthOnly = unifiedAuthOnly,
        primaryButtonText = primaryButtonText ?: stringResource(R.string.action_confirm_login),
        loadingButtonText = loadingButtonText ?: stringResource(R.string.status_logging_in),
        customLoadingTips = scenarioTips,
        onNavigateToAccount = onNavigateToAccount,
        showSyncButton = showSyncButton,
        onSyncWithCredentials = onSyncWithCredentials,
        onStartQr = { useVpn -> startQrFlow(useVpn) },
        onRefreshQr = { useVpn -> startQrFlow(useVpn) },
        onPasswordLogin = { sid, pwd, useVpn, authMode ->
            scope.launch {
                try {
                    isLoading = true
                    statusMessage = context.getString(R.string.status_connecting_verifying)
                    errorMessage = ""
                    val engine = newEngine(useVpn)
                    activeVpnEngine = engine

                    val ok = if (unifiedAuthOnly) {
                        // 仅登录统一认证：只为拿到/续期 CASTGC，全程不登录教务系统。
                        // 经 WebVPN 时先用已输入的统一认证密码打通门禁；未输入则弹 WebVPN 密码窗。
                        var casVpnPassword: String? = null
                        if (useVpn && pwd.isBlank()) {
                            val d = CompletableDeferred<String?>()
                            withContext(Dispatchers.Main) {
                                vpnPasswordDeferred = d
                            }
                            casVpnPassword = d.await()
                            if (casVpnPassword.isNullOrBlank()) {
                                isLoading = false
                                statusMessage = ""
                                return@launch
                            }
                        }
                        engine.loginUnifiedAuthOnly(
                            studentId = sid,
                            password = pwd,
                            viaWebVpn = useVpn,
                            flowTag = "${flowTagPrefix}_CAS",
                            vpnPasswordProvider = { casVpnPassword ?: pwd },
                            smsCodeProvider = { maskedPhone, isStillValid, sendInterval, promptText ->
                                val deferred = CompletableDeferred<String?>()
                                withContext(Dispatchers.Main) {
                                    smsError = null
                                    smsVerifying = false
                                    smsDeferred = deferred
                                    smsDialogPhone = maskedPhone
                                    smsDialogIsStillValid = isStillValid
                                    smsDialogSendInterval = sendInterval
                                    smsDialogPromptText = promptText
                                }
                                deferred.await()
                            },
                            captchaProvider = { captcha ->
                                val def = CompletableDeferred<SliderCaptchaResult?>()
                                captchaDeferred = def
                                captchaDialogData = captcha
                                def.await() ?: SliderCaptchaResult.Cancel
                            }
                        )
                    } else if (useVpn) {
                        // 如果是教务系统密码模式且无有效 TWFID，先弹窗索取 WebVPN/统一认证密码打通门禁
                        // 若开启了 requireUnifiedCas，该统一认证密码后续也将用于获取 CASTGC，无需重复询问
                        var customVpnPassword: String? = null
                        if (authMode == WbuAuthMode.JYXT_LEGACY && WebVpnClient.getTwfid(context).isBlank()) {
                            val d = CompletableDeferred<String?>()
                            withContext(Dispatchers.Main) {
                                vpnPasswordDeferred = d
                            }
                            customVpnPassword = d.await()
                            if (customVpnPassword == null) {
                                isLoading = false
                                statusMessage = ""
                                return@launch
                            }
                        }

                        val vpnOk = engine.loginVpnFull(
                            studentId = sid,
                            password = pwd,
                            authMode = authMode,
                            vpnPassword = customVpnPassword,
                            statusCallback = { status -> onVpnStatus?.invoke(status, authMode) },
                            smsCodeProvider = { maskedPhone, isStillValid, sendInterval, promptText ->
                                val deferred = CompletableDeferred<String?>()
                                withContext(Dispatchers.Main) {
                                    smsError = null
                                    smsVerifying = false
                                    smsDeferred = deferred
                                    smsDialogPhone = maskedPhone
                                    smsDialogIsStillValid = isStillValid
                                    smsDialogSendInterval = sendInterval
                                    smsDialogPromptText = promptText
                                }
                                deferred.await()
                            },
                            captchaProvider = { captcha ->
                                val def = CompletableDeferred<SliderCaptchaResult?>()
                                captchaDeferred = def
                                captchaDialogData = captcha
                                def.await() ?: SliderCaptchaResult.Cancel
                            }
                        )
                        if (vpnOk) {
                            if (!forceDirectCampus) WbuSyncEngine.setSavedUseVpn(context, true)
                            // 若要求持有有效 CASTGC（如访问图书馆），且当前是 JYXT_LEGACY 模式
                            val hasCastgc = engine.transport.cookieStore.any { it.name == "CASTGC" && !it.value.isBlank() }
                            if (requireUnifiedCas && !hasCastgc) {
                                statusMessage = "正在完成统一身份认证..."
                                // 若前面已索取过统一认证密码则直接复用；否则无论是否已记住密码都弹窗，
                                // 让用户确认或修改，避免服务端改密后静默沿用旧密码导致登录失败
                                var casPwd = customVpnPassword
                                if (casPwd.isNullOrBlank()) {
                                    val d = CompletableDeferred<String?>()
                                    withContext(Dispatchers.Main) {
                                        vpnPasswordDeferred = d
                                    }
                                    casPwd = d.await()
                                    if (casPwd.isNullOrBlank()) {
                                        isLoading = false
                                        statusMessage = ""
                                        return@launch
                                    }
                                }
                                engine.loginViaVpnCas(
                                    studentId = sid,
                                    password = casPwd,
                                    captchaProvider = { captcha ->
                                        val def = CompletableDeferred<SliderCaptchaResult?>()
                                        captchaDeferred = def
                                        captchaDialogData = captcha
                                        def.await() ?: SliderCaptchaResult.Cancel
                                    },
                                    authMode = WbuAuthMode.UNIFIED_CAS
                                )
                            }
                        }
                        vpnOk
                    } else {
                        // 直连前先确认校园网可达（是否需要确认由调用方决定）
                        if (confirmCampusNetwork?.invoke(false) == false) {
                            isLoading = false
                            statusMessage = ""
                            return@launch
                        }
                        // 直连模式：业务要求统一认证时改用统一认证模式。
                        // 不走 WebVPN 就不该弹「WebVPN 密码」对话框，直接用已输入的密码走 CAS。
                        var actualPassword = pwd
                        var actualAuthMode = authMode
                        if (requireUnifiedCas && authMode == WbuAuthMode.JYXT_LEGACY) {
                            actualAuthMode = WbuAuthMode.UNIFIED_CAS
                        }

                        val directOk = engine.login(
                            studentId = sid,
                            password = actualPassword,
                            authMode = actualAuthMode,
                            captchaProvider = { captcha ->
                                val def = CompletableDeferred<SliderCaptchaResult?>()
                                captchaDeferred = def
                                captchaDialogData = captcha
                                def.await() ?: SliderCaptchaResult.Cancel
                            }
                        )
                        if (directOk) {
                            if (!forceDirectCampus) WbuSyncEngine.setSavedUseVpn(context, false)
                        }
                        directOk
                    }

                    isLoading = false
                    if (ok) {
                        onLoginSuccess()
                        if (dismissOnSuccess) onDismiss()
                    } else {
                        val realErr = engine.lastLocalLoginError?.takeIf { it.isNotBlank() }
                        if (engine.lastLocalLoginFailure == LocalLoginFailure.CAPTCHA && onCaptchaFallback != null) {
                            onCaptchaFallback.invoke(sid, pwd, useVpn)
                            onDismiss()
                        } else {
                            errorMessage = when {
                                engine.lastLocalLoginNetworkError && useVpn ->
                                    context.getString(R.string.err_webvpn_connect_failed)

                                // 仅登录统一认证的直连不依赖校园网，不能提示「请确认已连接校园网」
                                engine.lastLocalLoginNetworkError && unifiedAuthOnly ->
                                    context.getString(R.string.err_unified_auth_direct_failed)

                                engine.lastLocalLoginNetworkError ->
                                    context.getString(R.string.err_campus_direct_failed)

                                realErr != null -> realErr
                                else -> context.getString(R.string.error_login_unsuccessful)
                            }
                        }
                    }
                } catch (e: Exception) {
                    isLoading = false
                    errorMessage = e.message ?: context.getString(R.string.error_login_generic_retry)
                }
            }
        },
        onDynamicCodeLogin = { sid, code, useVpn ->
            scope.launch {
                try {
                    isLoading = true
                    statusMessage = context.getString(R.string.status_logging_in)
                    errorMessage = ""
                    val engine = newEngine(useVpn)
                    activeVpnEngine = engine
                    val prep = dynamicPrep ?: engine.obtainDynamicCodeForm("${flowTagPrefix}_DYNAMIC", unifiedAuthOnly)
                    if (prep == null) {
                        isLoading = false
                        errorMessage = "无法获取登录参数，请重试"
                        return@launch
                    }
                    val res = engine.dynamicCodeLogin(
                        studentId = sid.trim(),
                        code = code,
                        prep = prep,
                        flowTag = "${flowTagPrefix}_DYNAMIC",
                        unifiedAuthOnly = unifiedAuthOnly,
                        vpnPasswordProvider = {
                            val d = CompletableDeferred<String?>()
                            withContext(Dispatchers.Main) {
                                vpnPasswordDeferred = d
                            }
                            d.await()
                        },
                        smsCodeProvider = { maskedPhone, isStillValid, sendInterval, promptText ->
                            val d = CompletableDeferred<String?>()
                            withContext(Dispatchers.Main) {
                                smsError = null
                                smsVerifying = false
                                smsDeferred = d
                                smsDialogPhone = maskedPhone
                                smsDialogIsStillValid = isStillValid
                                smsDialogSendInterval = sendInterval
                                smsDialogPromptText = promptText
                            }
                            d.await()
                        }
                    )
                    isLoading = false
                    if (res.success) {
                        if (!forceDirectCampus && !unifiedAuthOnly) WbuSyncEngine.setSavedUseVpn(context, useVpn)
                        onLoginSuccess()
                        if (dismissOnSuccess) onDismiss()
                    } else {
                        errorMessage = res.message.ifBlank { "验证码登录失败" }
                    }
                } catch (e: Exception) {
                    isLoading = false
                    errorMessage = e.message ?: "动态码登录失败"
                }
            }
        },
        onSendDynamicCode = { sid, useVpn ->
            val engine = newEngine(useVpn)
            activeVpnEngine = engine
            val result = engine.sendDynamicCode(
                studentId = sid.trim(),
                flowTag = "${flowTagPrefix}_DYNAMIC",
                unifiedAuthOnly = unifiedAuthOnly,
                captchaProvider = { captcha ->
                    val def = CompletableDeferred<SliderCaptchaResult?>()
                    captchaDeferred = def
                    captchaDialogData = captcha
                    def.await() ?: SliderCaptchaResult.Cancel
                }
            )
            dynamicPrep = (result as? DynamicCodeSendResult.Success)?.prep
            result
        }
    )
}
