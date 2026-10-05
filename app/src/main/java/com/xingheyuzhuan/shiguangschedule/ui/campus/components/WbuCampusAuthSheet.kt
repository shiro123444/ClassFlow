package com.xingheyuzhuan.shiguangschedule.ui.campus.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessFailure
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.CredentialKind
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AuthForm
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.DynamicCodeSendResult
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.IdsCasClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.QrStatus
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.SliderCaptchaData
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.SliderCaptchaResult
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthMode
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuLoginMethod
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.VpnFullLoginStatus
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.PortalCaptchaData
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.PortalCaptchaResult
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WebVpnClient
import com.xingheyuzhuan.shiguangschedule.ui.components.QrPhase
import com.xingheyuzhuan.shiguangschedule.ui.components.QrUiState
import com.xingheyuzhuan.shiguangschedule.ui.components.PortalCaptchaDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.SliderCaptchaDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.SslIssueDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.VpnSmsCodeDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.VpnPasswordPromptDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.WbuAuthBottomSheet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xingheyuzhuan.shiguangschedule.ui.components.accessFailureText

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
    /** 只认证 WebVPN 门户并取得 TWFID，不继续登录 IDS 或教务。 */
    webVpnPortalOnly: Boolean = false,
    passwordServiceOverride: CredentialService? = null,
    dismissOnSuccess: Boolean = true,
    externalLoading: Boolean = false,
    externalStatusMessage: String = "",
    externalErrorMessage: String = "",
    flowTagPrefix: String = "CAMPUS",
    onVpnStatus: ((VpnFullLoginStatus, WbuAuthMode) -> Unit)? = null,
    confirmCampusNetwork: (suspend (Boolean) -> Boolean)? = null,
    onCaptchaFallback: ((String, String, Boolean) -> Unit)? = null,
    initialUseVpnOverride: Boolean? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loginMethod by remember { mutableStateOf(WbuLoginMethod.PASSWORD) }
    LaunchedEffect(webVpnPortalOnly) {
        if (webVpnPortalOnly) loginMethod = WbuLoginMethod.PASSWORD
    }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var initialUseVpn by remember(initialUseVpnOverride) {
        mutableStateOf(
            initialUseVpnOverride ?: when {
                webVpnPortalOnly -> true
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

    // WebVPN 门户字符验证码（与 CAS 滑块验证码分开处理）
    var portalCaptchaData by remember { mutableStateOf<PortalCaptchaData?>(null) }
    var portalCaptchaDeferred by remember { mutableStateOf<CompletableDeferred<PortalCaptchaResult?>?>(null) }

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
            portalCaptchaDeferred?.complete(PortalCaptchaResult.Cancel)
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
        created.portalCaptchaProvider = { captcha ->
            val deferred = CompletableDeferred<PortalCaptchaResult?>()
            withContext(Dispatchers.Main) {
                portalCaptchaData = captcha
                portalCaptchaDeferred = deferred
            }
            deferred.await() ?: PortalCaptchaResult.Cancel
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

    // WebVPN 门户图形验证码：图片由当前认证会话获取，答案完全由用户输入。
    portalCaptchaData?.let { captcha ->
        PortalCaptchaDialog(
            captcha = captcha,
            onSubmit = { code ->
                portalCaptchaDeferred?.complete(PortalCaptchaResult.Submit(code))
                portalCaptchaDeferred = null
                portalCaptchaData = null
            },
            onRefresh = {
                portalCaptchaDeferred?.complete(PortalCaptchaResult.Refresh)
                portalCaptchaDeferred = null
                portalCaptchaData = null
            },
            onDismiss = {
                portalCaptchaDeferred?.complete(PortalCaptchaResult.Cancel)
                portalCaptchaDeferred = null
                portalCaptchaData = null
            }
        )
    }

    // 2. WebVPN 统一认证密码询问弹窗（教务密码模式且未配置有效 TWFID 时触发）
        // 2. WebVPN 统一认证密码询问弹窗（教务密码模式且未配置有效 TWFID 时触发）
    vpnPasswordDeferred?.let { deferred ->
        VpnPasswordPromptDialog(
            onSubmit = { value ->
                deferred.complete(value)
                vpnPasswordDeferred = null
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
                        smsError = context.getString(R.string.err_resend_failed)
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
                                    errorMessage = context.getString(R.string.err_login_verify_failed)
                                    qrState = QrUiState(qrContent = session.content, phase = QrPhase.ERROR, statusText = context.getString(R.string.status_qr_login_failed_retry))
                                }
                            } catch (e: Exception) {
                                isLoading = false
                                errorMessage = e.message ?: context.getString(R.string.err_login_exception_short)
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
            if (!webVpnPortalOnly || m == WbuLoginMethod.PASSWORD) {
                loginMethod = m
                if (m != WbuLoginMethod.QR) {
                    qrJob?.cancel()
                    qrState = null
                } else if (qrState == null) {
                    startQrFlow(initialUseVpn)
                }
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
        passwordLoginOnly = webVpnPortalOnly,
        passwordServiceOverride = passwordServiceOverride,
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
        onPasswordLogin = { sid, pwd, useVpn, authMode, reportResult ->
            scope.launch {
                try {
                    isLoading = true
                    statusMessage = context.getString(R.string.status_connecting_verifying)
                    errorMessage = ""
                    val engine = newEngine(useVpn)
                    activeVpnEngine = engine

                    val ok = if (webVpnPortalOnly) {
                        // 凭据管理中的 WebVPN 卡只需要门户 TWFID；不要顺带做 CAS/教务登录。
                        engine.loginWebVpnPortalOnly(
                            username = sid,
                            password = pwd,
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
                                val deferred = CompletableDeferred<SliderCaptchaResult?>()
                                withContext(Dispatchers.Main) {
                                    captchaDeferred = deferred
                                    captchaDialogData = captcha
                                }
                                deferred.await() ?: SliderCaptchaResult.Cancel
                            }
                        )
                    } else if (unifiedAuthOnly) {
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
                                statusMessage = context.getString(R.string.status_completing_unified_auth)
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
                    // 回报结果：只有成功时 Sheet 才会把密码落盘（失败时什么都不存）
                    reportResult(ok)
                    if (ok) {
                        onLoginSuccess()
                        if (dismissOnSuccess) onDismiss()
                    } else {
                        // 失败原因完全由引擎的结构化结果给出：这里只做「原因 → 文案」渲染，
                        // 不再用 UI 自己的开关状态（useVpn）去反推原因 —— 开关开着不等于门禁有效。
                        val failure = engine.lastFailure
                        if (failure is AccessFailure.CredentialRejected &&
                            failure.kind == CredentialKind.Captcha &&
                            onCaptchaFallback != null
                        ) {
                            onCaptchaFallback.invoke(sid, pwd, useVpn)
                            onDismiss()
                        } else {
                            errorMessage = when (failure) {
                                null -> context.getString(R.string.error_login_unsuccessful)
                                // 用户主动取消（如关掉验证码弹窗）时 accessFailureText 返回 null → 不报错
                                else -> accessFailureText(context, failure).orEmpty()
                            }
                        }
                    }
                } catch (e: Exception) {
                    isLoading = false
                    errorMessage = accessFailureText(context, e)
                        ?: context.getString(R.string.error_login_generic_retry)
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
                        errorMessage = context.getString(R.string.err_get_login_params_failed_short)
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
                        errorMessage = res.failure?.let { accessFailureText(context, it) }
                            ?: res.message.ifBlank { context.getString(R.string.err_otp_login_failed_short) }
                    }
                } catch (e: Exception) {
                    isLoading = false
                    errorMessage = accessFailureText(context, e)
                        ?: context.getString(R.string.err_dynamic_code_login_failed_short)
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
