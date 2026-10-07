package com.xingheyuzhuan.shiguangschedule.ui.webapp

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppCatalog
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppDefinition
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessFailure
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuNetworkProbe
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSessionExpiredException
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuWebAppClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.needsRelogin
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.TwfidState
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WebVpnClient
import com.xingheyuzhuan.shiguangschedule.ui.components.accessFailureText
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.resolveCampusUseVpn
import com.xingheyuzhuan.shiguangschedule.ui.components.silentUnifiedAuthLogin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    /** 本次流程上一次静默登录的失败原因（null = 成功或还没试过）。 */
    private var lastSilentLoginFailure: AccessFailure? = null

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
        lastSilentLoginFailure = null
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

            val savedUseVpn = WbuSyncEngine.getSavedUseVpn(app)

            if (!savedUseVpn) {
                // 1. 未开启 WebVPN：先探针校园网
                _uiState.update {
                    it.copy(
                        stage = WebAppStage.ProbingNetwork,
                        probeStatusText = app.getString(R.string.status_probing_campus_network)
                    )
                }
                val onCampus = WbuNetworkProbe.probeForCampusFlow(app)
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
                // 2. 开启了 WebVPN：但「自动校园网探测」开着时先快速探一次 ——
                //    人就在校园网里就别绕 WebVPN 了（校园网里走代理更慢，也更容易掉线）。
                //    探测关闭 / 「不检测校园网环境」开着时这一步不会探测，结果就是继续用 WebVPN。
                if (!resolveCampusUseVpn(app, savedUseVpn = true)) {
                    proceedWithChannel(def, useVpn = false)
                    return@launch
                }
                // 2.1 不在校园网：核验当前 TWFID 是否仍有效（只读探活，不消耗登录尝试）
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
                    val failure = trySilentUnifiedAuthLogin(forceWebVpn = true)
                    if (failure == null) {
                        proceedWithChannel(def, useVpn)
                        return@launch
                    }
                    settleSilentLoginFailure(failure, requireVpnForLogin = true, keepChoiceOverlay = true)
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
                    val failure = trySilentUnifiedAuthLogin(forceWebVpn = true)
                    if (failure == null) {
                        proceedWithChannel(def, useVpn)
                        return@launch
                    }
                    settleSilentLoginFailure(failure, requireVpnForLogin = true, keepChoiceOverlay = true)
                    return@launch
                }
                vpnClient.injectTwfid(twfid)
            }

            // 2. 检查本地是否有统一身份认证凭证 CASTGC。
            //    没有也不立刻弹登录 Sheet：优先用保存的账号密码静默登录一次（与 /w/ U净出水、扫一扫入口一致），
            //    成功就直接继续换票；只有没保存密码 / 静默登录失败才弹 Sheet。
            val hasUnifiedSession = transport.cookieStore.any { it.name == "CASTGC" && it.value.isNotBlank() }
            if (!hasUnifiedSession) {
                val failure = trySilentUnifiedAuthLogin()
                if (failure != null) {
                    settleSilentLoginFailure(failure, requireVpnForLogin = useVpn)
                    return@launch
                }
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
                val failure = trySilentUnifiedAuthLogin()
                if (failure == null) {
                    proceedWithChannel(def, useVpn)
                    return@launch
                }
                settleSilentLoginFailure(failure, requireVpnForLogin = useVpn)
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                if (e is WbuSessionExpiredException ||
                    msg.contains("失效") || msg.contains("过期") || msg.contains("登录") || msg.contains("WebVPN") || msg.contains("门禁") ||
                    msg.contains("expired", ignoreCase = true) || msg.contains("log in", ignoreCase = true) || msg.contains("login", ignoreCase = true)
                ) {
                    val failure = trySilentUnifiedAuthLogin()
                    if (failure == null) {
                        proceedWithChannel(def, useVpn)
                        return@launch
                    }
                    settleSilentLoginFailure(failure, requireVpnForLogin = useVpn)
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
     * 每次进入（[start] / [retry]）只尝试一次（[silentLoginAttempted]）：重试成环只会白烧服务端失败次数。
     * 失败原因按 [AccessFailure] 结构化返回，调用方据此决定是弹 Sheet 还是只给一条可重试的错误。
     */
    private suspend fun trySilentUnifiedAuthLogin(forceWebVpn: Boolean = false): AccessFailure? {
        // 本次流程已经试过：原样回报上次的原因，不再发起第二次登录
        if (silentLoginAttempted) return lastSilentLoginFailure
        silentLoginAttempted = true

        val app = getApplication<Application>()
        _uiState.update {
            it.copy(
                stage = WebAppStage.LoadingToken,
                probeStatusText = app.getString(R.string.status_login_saved_credentials)
            )
        }
        // 弹窗（未保存的统一认证密码 / WebVPN 门禁密码 / 短信验证码 / 图形校验）统一走进程级小窗。
        // onlyWithSavedPassword = true：本机没存密码就直接回落到登录 Sheet，保持这条路径的老行为
        val failure = silentUnifiedAuthLogin(
            context = app,
            flowTag = "WEBAPP_AUTO_AUTH",
            viaWebVpn = forceWebVpn || WbuAuthTransport.getIdsViaWebVpn(app),
            onlyWithSavedPassword = true
        )
        lastSilentLoginFailure = failure
        return failure
    }

    /**
     * 静默登录失败后的收尾。
     *
     * **只有**「会话失效 / 凭据被拒」才把用户请去登录 Sheet；用户取消小窗、网络不通、服务端异常
     * 只留一条可重试的错误 —— 以前这里任何失败都会弹 Sheet，用户在小窗上点个取消也会被顶一脸登录框。
     *
     * @param keepChoiceOverlay 从「内容必须走 WebVPN」的分支出来的失败：保留离校通道选择浮层，
     *                          别把用户直接推进登录页。
     */
    private fun settleSilentLoginFailure(
        failure: AccessFailure,
        requireVpnForLogin: Boolean,
        keepChoiceOverlay: Boolean = false
    ) {
        if (failure.needsRelogin) {
            val vpn = requireVpnForLogin || keepChoiceOverlay
            _uiState.update {
                it.copy(
                    stage = if (keepChoiceOverlay) WebAppStage.OffCampusChoice else it.stage,
                    needLogin = true,
                    requireVpnForLogin = vpn,
                    temporaryUseVpn = vpn
                )
            }
            return
        }
        val app = getApplication<Application>()
        _uiState.update {
            it.copy(
                needLogin = false,
                stage = WebAppStage.Error(
                    accessFailureText(app, failure)
                        ?: app.getString(R.string.err_need_unified_auth_session)
                )
            )
        }
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
        lastSilentLoginFailure = null
        val def = _uiState.value.definition
        if (def != null) {
            evaluateNetworkAndProceed(def)
        } else {
            currentAppId?.let { start(it) }
        }
    }
}
