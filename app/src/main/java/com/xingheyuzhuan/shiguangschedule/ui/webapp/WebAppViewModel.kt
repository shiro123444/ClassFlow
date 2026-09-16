package com.xingheyuzhuan.shiguangschedule.ui.webapp

import android.app.Application
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
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WebVpnClient
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

    private val _uiState = MutableStateFlow(WebAppUiState())
    val uiState: StateFlow<WebAppUiState> = _uiState.asStateFlow()

    private var currentAppId: String? = null

    /**
     * 启动加载流程：
     * 1. 寻找配置定义；
     * 2. 检查 WebVPN 与校园网；
     * 3. 决定网络策略；
     * 4. 换取 Token 并打开。
     */
    fun start(appId: String) {
        currentAppId = appId
        val def = WebAppCatalog.findByIdString(appId)
        if (def == null) {
            _uiState.update { it.copy(stage = WebAppStage.Error("未知网页应用: $appId")) }
            return
        }

        _uiState.update {
            it.copy(
                definition = def,
                stage = WebAppStage.ProbingNetwork,
                probeStatusText = getApplication<Application>().getString(R.string.status_probing_campus_network),
                needLogin = false
            )
        }

        evaluateNetworkAndProceed(def)
    }

    private fun evaluateNetworkAndProceed(def: WebAppDefinition) {
        viewModelScope.launch {
            val app = getApplication<Application>()
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
                // 2. 开启了 WebVPN：先核验当前 TWFID 是否仍有效
                val twfid = WbuAuthTransport.getTwfid(app)
                val transport = WbuAuthTransport.getShared(app, true)
                transport.restoreCookieStore()
                val vpnClient = WebVpnClient(transport)
                val twfidValid = if (twfid.isNotBlank()) {
                    runCatching { vpnClient.validateTwfid(twfid) }.getOrDefault(false)
                } else false

                if (twfidValid) {
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

            // 1. 若使用 WebVPN，必须首先验证 TWFID 是否有效；若失效必须弹窗登录 WebVPN
            if (useVpn) {
                val twfid = WbuAuthTransport.getTwfid(app)
                if (twfid.isBlank()) {
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
                val twfidValid = runCatching { vpnClient.validateTwfid(twfid) }.getOrDefault(false)
                if (!twfidValid) {
                    WbuAuthTransport.clearTwfid(app)
                    vpnClient.removeTwfidCookie()
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

            // 2. 检查本地是否有统一身份认证凭证 CASTGC
            val hasUnifiedSession = transport.cookieStore.any { it.name == "CASTGC" && it.value.isNotBlank() }
            if (!hasUnifiedSession) {
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
                // CASTGC 或 WebVPN 门禁过期，弹登录 Sheet
                _uiState.update {
                    it.copy(
                        needLogin = true,
                        requireVpnForLogin = useVpn,
                        temporaryUseVpn = useVpn
                    )
                }
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                if (msg.contains("失效") || msg.contains("过期") || msg.contains("登录") || msg.contains("WebVPN") || msg.contains("门禁")) {
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
                            stage = WebAppStage.Error(e.localizedMessage ?: "加载页面凭证失败")
                        )
                    }
                }
            }
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
            _uiState.update {
                it.copy(stage = WebAppStage.OffCampusChoice)
            }
        }
    }

    fun retry() {
        val def = _uiState.value.definition
        if (def != null) {
            evaluateNetworkAndProceed(def)
        } else {
            currentAppId?.let { start(it) }
        }
    }
}
