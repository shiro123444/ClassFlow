package com.xingheyuzhuan.shiguangschedule.ui.webapp

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppCatalog
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppDefinition
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuNetworkProbe
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSessionExpiredException
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuWebAppClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.TwfidState
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WebVpnClient
import com.xingheyuzhuan.shiguangschedule.ui.components.WbuAuthPromptRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网页应用加载阶段。
 */
sealed class WebAppStage {
    /** 正在检测当前网络通道与校园网环境。 */
    object ProbingNetwork : WebAppStage()

    /** 处于非校园网环境，等待用户选择：继续直连访问、或临时启用 WebVPN。 */
    object OffCampusChoice : WebAppStage()

    /** 正在向统一认证 (CAS) 与 SSO 服务申请加载 Token。 */
    object LoadingToken : WebAppStage()

    /** Token 已成功就绪，WebView 正在全屏渲染目标页面。 */
    data class ContentReady(
        val url: String,
        val token: String,
        val useVpn: Boolean
    ) : WebAppStage()

    /** 遇到错误。 */
    data class Error(val message: String) : WebAppStage()
}

data class WebAppUiState(
    val stage: WebAppStage = WebAppStage.ProbingNetwork,
    val definition: WebAppDefinition? = null,
    val probeStatusText: String = "",
    val needLogin: Boolean = false,
    val requireVpnForLogin: Boolean = false,
    val temporaryUseVpn: Boolean = false,
    val isRetrying: Boolean = false
)

class WebAppViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        private const val TAG = "WebAppViewModel"
    }

    private val _uiState = MutableStateFlow(WebAppUiState())
    val uiState: StateFlow<WebAppUiState> = _uiState.asStateFlow()

    private var currentAppId: String? = null
    private var overrideTargetUrl: String? = null

    /** 静默登录时向 UI 索取的补充输入（WebVPN 密码 / 短信验证码），null 表示当前无需输入。 */
    private val _authPrompt = MutableStateFlow<WbuAuthPromptRequest?>(null)
    val authPrompt: StateFlow<WbuAuthPromptRequest?> = _authPrompt.asStateFlow()

    private var authPromptDeferred: CompletableDeferred<String?>? = null
    private var silentLoginEngine: WbuSyncEngine? = null

    /**
     * 本次流程是否已经尝试过「用保存的密码静默登录」。
     *
     * 静默登录失败后如果又走到需要登录的分支，就直接弹 Sheet —— 否则会反复重试成环。
     * 每次 [start] / [retry] 重置。
     */
    private var silentLoginAttempted = false

    /**
     * 启动加载流程：
     * 1. 寻找配置定义；
     * 2. 检查 WebVPN 与校园网；
     * 3. 决定网络策略；
     * 4. 换取 Token 并打开。
     */
    fun start(appId: String, initialTargetUrl: String? = null) {
        currentAppId = appId
        overrideTargetUrl = initialTargetUrl
        silentLoginAttempted = false
        val def = WebAppCatalog.findByIdString(appId)
        if (def == null) {
            _uiState.update { it.copy(stage = WebAppStage.Error(getApplication<Application>().getString(R.string.err_unknown_web_app, appId))) }
            return
        }

        _uiState.update {
            it.copy(
                definition = def,
                // 直连应用（如一卡通）不做校园网检测，直接进入凭据加载阶段
                stage = if (def.directOnly) WebAppStage.LoadingToken else WebAppStage.ProbingNetwork,
                probeStatusText = getApplication<Application>().getString(R.string.status_probing_campus_network),
                needLogin = false
            )
        }

        evaluateNetworkAndProceed(def)
    }

    private fun evaluateNetworkAndProceed(def: WebAppDefinition) {
        viewModelScope.launch {
            val app = getApplication<Application>()

            // 0. 若定义为 directOnly（如一卡通移动服务平台），无需检测校园网或 WebVPN，直接直连加载
            if (def.directOnly) {
                proceedWithChannel(def, useVpn = false)
                return@launch
            }

            val savedUseVpn = WbuSyncEngine.getSavedUseVpn(app) ?: false

            if (!savedUseVpn) {
                // 1. 未开启 WebVPN：先探针校园网
                _uiState.update {
                    it.copy(
                        stage = WebAppStage.ProbingNetwork,
                        probeStatusText = app.getString(R.string.status_probing_campus_network)
                    )
                }
                val onCampus = WbuNetworkProbe.refresh()
                if (onCampus) {
                    // 在校内，直接以校园网直连加载
                    proceedWithChannel(def, useVpn = false)
                } else {
                    // 非校内，停在选择界面询问：继续直连还是临时使用 WebVPN
                    _uiState.update {
                        it.copy(
                            stage = WebAppStage.OffCampusChoice,
                            temporaryUseVpn = false
                        )
                    }
                }
            } else {
                // 2. 开启了 WebVPN：先核验当前 TWFID 是否仍有效（只读探活，不消耗登录尝试）
                val twfid = WbuAuthTransport.getTwfid(app)
                val transport = WbuAuthTransport.getShared(app, true)
                transport.restoreCookieStore()
                val vpnClient = WebVpnClient(transport)
                val twfidState = if (twfid.isNotBlank()) {
                    val state = runCatching { vpnClient.probeTwfid(twfid) }.getOrDefault(TwfidState.UNKNOWN)
                    if (state == TwfidState.NOT_AUTHENTICATED) {
                        // 服务端明确说这个槽位没认证：清掉本地凭据
                        WbuAuthTransport.clearTwfid(app)
                        vpnClient.removeTwfidCookie()
                    }
                    state
                } else TwfidState.NOT_AUTHENTICATED

                if (twfidState == TwfidState.VALID) {
                    vpnClient.injectTwfid(twfid)
                    // WebVPN 门禁有效，直接通过 WebVPN 代理通道加载
                    proceedWithChannel(def, useVpn = true)
                } else {
                    // WebVPN 失效，检测校园网
                    _uiState.update {
                        it.copy(
                            stage = WebAppStage.ProbingNetwork,
                            probeStatusText = app.getString(R.string.status_probing_campus_after_vpn_fail)
                        )
                    }
                    val onCampus = WbuNetworkProbe.refresh()
                    if (onCampus) {
                        // 虽然配置了 VPN 但在校内，可直接以校园网直连进入
                        proceedWithChannel(def, useVpn = false)
                    } else {
                        // 不在校园网且 VPN 失效，切换为离校选择状态并直接弹登录 Sheet 登录 WebVPN
                        _uiState.update {
                            it.copy(
                                stage = WebAppStage.OffCampusChoice,
                                needLogin = true,
                                requireVpnForLogin = true,
                                temporaryUseVpn = true
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 用户在离校探测浮层中选择「直接继续（直连）」。
     */
    fun chooseContinueDirect() {
        val def = _uiState.value.definition ?: return
        _uiState.update { it.copy(temporaryUseVpn = false, requireVpnForLogin = false) }
        proceedWithChannel(def, useVpn = false)
    }

    /**
     * 用户在离校探测浮层中选择「临时使用 WebVPN」。
     */
    fun chooseUseVpnTemporarily() {
        val def = _uiState.value.definition ?: return
        _uiState.update { it.copy(temporaryUseVpn = true, requireVpnForLogin = true) }
        proceedWithChannel(def, useVpn = true)
    }

    /**
     * 进入指定通道（直连或 WebVPN）提取 Token 并生成启动 URL。
     */
    private fun proceedWithChannel(def: WebAppDefinition, useVpn: Boolean) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val transport = WbuAuthTransport.getShared(app, useVpn)
            transport.restoreCookieStore()

            // 已确定通道，进入凭据加载阶段（避免继续显示校园网探测浮层）
            _uiState.update { it.copy(stage = WebAppStage.LoadingToken) }

            // 1. 若使用 WebVPN，必须首先验证 TWFID 是否有效；失效时先用保存的密码静默恢复门禁，失败才弹窗
            if (useVpn) {
                val twfid = WbuAuthTransport.getTwfid(app)
                if (twfid.isBlank()) {
                    // 内容本身必须走 WebVPN：静默重登也得经 WebVPN，才能把门禁 TWFID 建起来
                    if (trySilentUnifiedAuthLogin(forceWebVpn = true)) {
                        proceedWithChannel(def, useVpn)
                        return@launch
                    }
                    _uiState.update {
                        it.copy(
                            stage = WebAppStage.OffCampusChoice,
                            needLogin = true,
                            requireVpnForLogin = true,
                            temporaryUseVpn = true
                        )
                    }
                    return@launch
                }

                val vpnClient = WebVpnClient(transport)
                val twfidState = runCatching { vpnClient.probeTwfid(twfid) }.getOrDefault(TwfidState.UNKNOWN)
                if (twfidState != TwfidState.VALID) {
                    if (twfidState == TwfidState.NOT_AUTHENTICATED) {
                        // 只有服务端明确说未认证才清本地槽位；探活失败（网络抖动）时保留
                        WbuAuthTransport.clearTwfid(app)
                        vpnClient.removeTwfidCookie()
                    }
                    if (trySilentUnifiedAuthLogin(forceWebVpn = true)) {
                        proceedWithChannel(def, useVpn)
                        return@launch
                    }
                    _uiState.update {
                        it.copy(
                            stage = WebAppStage.OffCampusChoice,
                            needLogin = true,
                            requireVpnForLogin = true,
                            temporaryUseVpn = true
                        )
                    }
                    return@launch
                }
                vpnClient.injectTwfid(twfid)
            }

            // 2. 检查本地是否有统一身份认证凭证 CASTGC。
            //    没有也不立刻弹登录 Sheet：优先用保存的账号密码静默登录一次（与 /w/ U净出水、扫一扫入口一致），
            //    成功就直接继续换票；只有没保存密码 / 静默登录失败才弹 Sheet。
            val hasUnifiedSession = transport.cookieStore.any { it.name == "CASTGC" && it.value.isNotBlank() }
            if (!hasUnifiedSession && !trySilentUnifiedAuthLogin()) {
                _uiState.update {
                    it.copy(
                        needLogin = true,
                        requireVpnForLogin = useVpn,
                        temporaryUseVpn = useVpn
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    stage = WebAppStage.LoadingToken,
                    temporaryUseVpn = useVpn,
                    needLogin = false
                )
            }

            try {
                // 特判：一卡通移动服务平台 (CAMPUS_CARD)
                if (def.id == com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppId.CAMPUS_CARD) {
                    val cardClient = com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuCampusCardClient(app, useVpn = false)
                    val token = cardClient.ensureValidAccessToken()
                    val launchUrl = overrideTargetUrl ?: cardClient.buildLaunchUrl(token)
                    _uiState.update {
                        it.copy(
                            stage = WebAppStage.ContentReady(
                                url = launchUrl,
                                token = token,
                                useVpn = false
                            ),
                            needLogin = false
                        )
                    }
                    return@launch
                }

                val client = WbuWebAppClient(app, useVpn = useVpn)
                val token = client.fetchCasCallbackToken(def)
                val launchUrl = client.buildLaunchUrl(def, token)

                _uiState.update {
                    it.copy(
                        stage = WebAppStage.ContentReady(
                            url = launchUrl,
                            token = token,
                            useVpn = useVpn
                        ),
                        needLogin = false
                    )
                }
            } catch (e: WbuSessionExpiredException) {
                // CASTGC / WebVPN 门禁过期：先用保存的密码静默重登一次，失败才弹登录 Sheet
                if (trySilentUnifiedAuthLogin()) {
                    proceedWithChannel(def, useVpn)
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        needLogin = true,
                        requireVpnForLogin = useVpn,
                        temporaryUseVpn = useVpn
                    )
                }
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                if (e is WbuSessionExpiredException ||
                    msg.contains("失效") || msg.contains("过期") || msg.contains("登录") || msg.contains("WebVPN") || msg.contains("门禁") ||
                    msg.contains("expired", ignoreCase = true) || msg.contains("log in", ignoreCase = true) || msg.contains("login", ignoreCase = true)
                ) {
                    if (trySilentUnifiedAuthLogin()) {
                        proceedWithChannel(def, useVpn)
                        return@launch
                    }
                    _uiState.update {
                        it.copy(
                            needLogin = true,
                            requireVpnForLogin = useVpn,
                            temporaryUseVpn = useVpn
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            stage = WebAppStage.Error(e.localizedMessage ?: getApplication<Application>().getString(R.string.err_load_page_credential_failed))
                        )
                    }
                }
            }
        }
    }

    /**
     * 用保存的账号密码静默登录统一认证。
     *
     * 顺序固定：**优先复用仍然有效的 CASTGC**（上面的 `hasUnifiedSession` 检查与换票逻辑直接命中），
     * 只有它缺失 / 失效时才走到这里；这里再失败（没保存密码、用户取消、需要滑块）
     * 才把 `needLogin` 交给 UI 弹登录 Sheet —— 与 `/w/` U净出水、扫一扫等入口保持同一套行为。
     *
     * 是否经 WebVPN：默认只由「统一认证经过 WebVPN」决定（[forceWebVpn] = false，即没开就完全不牵扯
     * WebVPN）；只有在内容本身必须走 WebVPN（TWFID 门禁失效要重建）时才强制为 true。
     * 需要门禁密码时优先用本地保存的，没保存就弹小窗让用户补 WebVPN 密码 + 短信验证码，补不上才回落 Sheet。
     *
     * 每次进入（[start] / [retry]）只尝试一次，避免失败后成环。
     */
    private suspend fun trySilentUnifiedAuthLogin(forceWebVpn: Boolean = false): Boolean {
        val app = getApplication<Application>()
        if (silentLoginAttempted) return false
        silentLoginAttempted = true

        val studentId = WbuAuthTransport.getSavedStudentId(app)
        val password = WbuAuthTransport.getSavedPassword(app, CredentialService.UNIFIED_AUTH)
        if (studentId.isBlank() || password.isNullOrBlank()) {
            Log.d(TAG, "没有保存的统一认证账号密码，跳过静默登录")
            return false
        }

        return try {
            _uiState.update {
                it.copy(
                    stage = WebAppStage.LoadingToken,
                    probeStatusText = app.getString(R.string.status_login_saved_credentials)
                )
            }
            val viaWebVpn = forceWebVpn || WbuAuthTransport.getIdsViaWebVpn(app)
            val engine = WbuSyncEngine(app, useVpn = viaWebVpn)
            silentLoginEngine = engine
            val ok = engine.loginUnifiedAuthOnly(
                studentId = studentId,
                password = password,
                viaWebVpn = viaWebVpn,
                flowTag = "WEBAPP_AUTO_AUTH",
                vpnPasswordProvider = {
                    WbuAuthTransport.getSavedVpnPassword(app)?.takeIf { it.isNotBlank() }
                        ?: requestAuthPrompt(WbuAuthPromptRequest.VpnPassword)
                },
                smsCodeProvider = { maskedPhone, isStillValid, sendInterval, promptText ->
                    requestAuthPrompt(
                        WbuAuthPromptRequest.SmsCode(
                            maskedPhone = maskedPhone,
                            isStillValid = isStillValid,
                            sendInterval = sendInterval,
                            promptText = promptText
                        )
                    )
                }
            )
            Log.i(TAG, "静默统一认证登录结果: $ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "静默统一认证登录失败", e)
            false
        } finally {
            silentLoginEngine = null
            dismissAuthPrompt()
        }
    }

    /** UI 提交（传 null = 取消）当前补充输入弹窗。 */
    fun submitAuthPrompt(value: String?) {
        val deferred = authPromptDeferred
        authPromptDeferred = null
        _authPrompt.value = null
        deferred?.complete(value)
    }

    /** 短信验证码「重新发送」。 */
    /** 短信验证码「重新发送」：返回服务端要求的重发冷却秒数（0 = 不限制），失败返回 null。 */
    suspend fun resendVpnSmsCode(): Int? {
        val engine = silentLoginEngine ?: return null
        return runCatching { engine.resendVpnSmsCode() }
            .onFailure { Log.w(TAG, "重新发送短信验证码失败", it) }
            .getOrNull()
            ?.takeIf { it.success }
            ?.cooldownSeconds
    }

    /** 向 UI 索取一次补充输入，挂起直到用户提交或取消。 */
    private suspend fun requestAuthPrompt(request: WbuAuthPromptRequest): String? {
        val deferred = CompletableDeferred<String?>()
        withContext(Dispatchers.Main) {
            authPromptDeferred = deferred
            _authPrompt.value = request
        }
        return deferred.await()
    }

    private fun dismissAuthPrompt() {
        authPromptDeferred = null
        _authPrompt.value = null
    }

    fun onLoginSuccess() {
        _uiState.update { it.copy(needLogin = false) }
        val def = _uiState.value.definition
        if (def != null) {
            proceedWithChannel(def, useVpn = _uiState.value.temporaryUseVpn || _uiState.value.requireVpnForLogin)
        }
    }

    fun onLoginDismissed() {
        _uiState.update { it.copy(needLogin = false) }
        if (_uiState.value.stage !is WebAppStage.ContentReady) {
            // 直连应用（如一卡通）无校园网选择语义，取消登录即视为无法继续
            _uiState.update {
                if (_uiState.value.definition?.directOnly == true) {
                    it.copy(stage = WebAppStage.Error(getApplication<Application>().getString(R.string.err_unified_auth_required)))
                } else {
                    it.copy(stage = WebAppStage.OffCampusChoice)
                }
            }
        }
    }

    fun retry() {
        silentLoginAttempted = false
        val def = _uiState.value.definition
        if (def != null) {
            evaluateNetworkAndProceed(def)
        } else {
            currentAppId?.let { start(it) }
        }
    }
}
