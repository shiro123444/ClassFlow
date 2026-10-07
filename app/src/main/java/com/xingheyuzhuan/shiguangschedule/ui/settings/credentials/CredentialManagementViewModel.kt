package com.xingheyuzhuan.shiguangschedule.ui.settings.credentials

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.api.webdav.WebDavConfig
import com.xingheyuzhuan.shiguangschedule.data.repository.WebDavStoredInfo
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.CredentialVerifier
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.SessionState
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuCredentialRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.ApiConfigRepository
import com.xingheyuzhuan.shiguangschedule.ui.components.shouldAttemptSavedPasswordLogin
import com.xingheyuzhuan.shiguangschedule.ui.components.silentUnifiedAuthLogin
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuNetworkProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 单个服务的展示状态。 */
data class ServiceUiState(
    val service: CredentialService,
    val accountId: String,
    val accounts: List<String>,
    /** 账号名称：已命名取自定义名，否则派生默认（学号/用户名）；空串表示未设置。与登录身份解耦，可随意改。 */
    val accountLabel: String,
    val hasPassword: Boolean,
    val hasOwnPassword: Boolean,
    val hasToken: Boolean,
    /** 会话凭据（Cookie 名→值，如 jw_uf / CASTGC），仅在解锁「高级模式」时展示。 */
    val credentials: List<Pair<String, String>>,
    /** TWFID 明文（仅在解锁「高级模式」时展示）。 */
    val tokenValue: String,
    val hasSession: Boolean,
    val isVerifying: Boolean,
    val sessionState: SessionState?,
    val webDavBaseUrl: String = "",
    val webDavUsername: String = "",
    val webDavPassword: String? = null,
)

data class CredentialUiState(
    val services: List<ServiceUiState> = emptyList(),
    val autoVerify: Boolean = true,
    /**
     * 「自动使用保存的密码登录」（默认开）：开启后，以前「没有会话就直接甩登录面板 / 直接说未登录」
     * 的场景也先用保存的密码静默登录一次；关掉即回到加这个开关之前的行为。
     */
    val autoLoginWithSavedPassword: Boolean = true,
    /**
     * 「自动校园网探测」（默认开，仅「使用 WebVPN」开启时显示）：
     * 需要校园网的功能先用快速探测判断是否在校园网内，在校园网内就直接连接、不绕 WebVPN。
     */
    val autoCampusProbe: Boolean = true,
    val webDavConfigured: Boolean = false,
    val useVpn: Boolean = false,
    val idsViaWebVpn: Boolean = false,
    val qrViaWebVpn: Boolean = false,
    val useHttpsWebVpn: Boolean = false,
    val usePcUserAgent: Boolean = false,
    val skipCampusCheck: Boolean = false,
    val selectSemesterOnImport: Boolean = false,
    val keepTeacherId: Boolean = false,
    val keepBuilding: Boolean = false,
    val idsAddrNotFromJwxt: Boolean = false,
    val noIndexMainVerify: Boolean = false,
    val forceFetchStudentIdBeforeVpn: Boolean = false,
    val useFixedServiceForTicket: Boolean = false,
)

private data class VerifyState(val verifying: Boolean = false, val state: SessionState? = null)

/**
 * 凭据管理页 ViewModel：聚合 WBU 各服务与 WebDAV 的凭据状态，并支持按需联网验证会话。
 */
@HiltViewModel
class CredentialManagementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wbuRepository: WbuCredentialRepository,
    private val verifier: CredentialVerifier,
    private val apiConfigRepository: ApiConfigRepository,
) : ViewModel() {

    private val verifyState = MutableStateFlow<Map<CredentialService, VerifyState>>(emptyMap())
    private val refreshTrigger = MutableStateFlow(0)

    /** 一卡通「使用已有统一认证凭据同步」的结果提示（null 表示无提示）。 */
    private val _campusCardSyncMessage = MutableStateFlow<String?>(null)
    val campusCardSyncMessage: StateFlow<String?> = _campusCardSyncMessage.asStateFlow()

    fun clearCampusCardSyncMessage() {
        _campusCardSyncMessage.value = null
    }

    init {
        // 凭据被任何入口改动（登录 / 清除 / TWFID 编辑 / 高级模式改值）都重算本页
        viewModelScope.launch {
            wbuRepository.credentialChanges.collect { refreshTrigger.update { it + 1 } }
        }
    }

    val uiState: StateFlow<CredentialUiState> = combine(
        apiConfigRepository.webDavConfigFlow,
        apiConfigRepository.webDavStoredInfoFlow,
        verifyState,
        refreshTrigger,
    ) { cfg, stored, verify, _ -> buildUiState(cfg, stored, verify) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CredentialUiState()
        )

    /** 进入页面：刷新本地状态；开了「自动使用保存的密码登录」就先静默补上失效的会话，再自动验证。 */
    fun onScreenEnter() {
        refreshTrigger.update { it + 1 }
        viewModelScope.launch {
            rebuildSessionIfNeeded()
            if (wbuRepository.isAutoVerifyEnabled()) verifyAll()
        }
    }

    /**
     * 「自动使用保存的密码登录」：进页面时先悄悄把失效的统一认证会话补回来。
     *
     * 本页以前只做只读校验，于是「会话其实早就过期了」也只显示成一排「未登录」，
     * 用户还得自己去点登录。现在只要本机存着密码，进来就把会话建好，下面的校验才是有意义的。
     *
     * 只在「确实需要重建」时才动手（开关关着、本机没存密码、本地会话还有效，都什么都不做），
     * 静默登录期间的弹窗（门禁密码 / 短信 / 图形校验）走进程级小窗。
     */
    private suspend fun rebuildSessionIfNeeded() {
        if (!shouldAttemptSavedPasswordLogin(context)) return
        val useVpn = wbuRepository.isUseVpn()
        if (WbuAuthTransport.hasLocalSession(context, CredentialService.UNIFIED_AUTH, useVpn = useVpn)) return
        silentUnifiedAuthLogin(
            context = context,
            flowTag = "CREDENTIALS",
            viaWebVpn = WbuAuthTransport.getIdsViaWebVpn(context),
            // 上面已经确认存着密码，这里再兜一层：绝不因为「缺密码」在本页弹窗
            onlyWithSavedPassword = true
        )
    }

    fun setAutoVerify(enabled: Boolean) {
        wbuRepository.setAutoVerifyEnabled(enabled)
        refreshTrigger.update { it + 1 }
    }

    /** 切换「自动使用保存的密码登录」。 */
    fun setAutoLoginWithSavedPassword(enabled: Boolean) {
        wbuRepository.setAutoLoginWithSavedPasswordEnabled(enabled)
        refreshTrigger.update { it + 1 }
    }

    /** 切换网络接入模式：WebVPN（校外） / 校园网直连。 */
    fun setUseVpn(enabled: Boolean) {
        wbuRepository.setUseVpn(enabled)
        refreshTrigger.update { it + 1 }
    }

    /** 统一认证是否经过 WebVPN（与「更多→网络设置」共用同一存储）。 */
    fun setIdsViaWebVpn(enabled: Boolean) {
        wbuRepository.setIdsViaWebVpn(enabled)
        refreshTrigger.update { it + 1 }
    }

    /** 二维码是否经过 WebVPN。 */
    fun setQrViaWebVpn(enabled: Boolean) {
        wbuRepository.setQrViaWebVpn(enabled)
        refreshTrigger.update { it + 1 }
    }

    /** WebVPN 是否使用 HTTPS。 */
    fun setUseHttpsWebVpn(enabled: Boolean) {
        wbuRepository.setUseHttpsWebVpn(enabled)
        refreshTrigger.update { it + 1 }
    }

    /** 使用桌面端 User-Agent。 */
    fun setUsePcUserAgent(enabled: Boolean) {
        wbuRepository.setUsePcUserAgent(enabled)
        refreshTrigger.update { it + 1 }
    }

    /** 跳过校园网检测。 */
    fun setSkipCampusCheck(enabled: Boolean) {
        wbuRepository.setSkipCampusCheck(enabled)
        refreshTrigger.update { it + 1 }
    }

    /**
     * 切换「自动校园网探测」。
     *
     * 关掉后也可以顺手把那次快速探测的缓存忘了：用户刚改完设置，下一次访问理应重新判断，
     * 而不是继续用 15s 内的旧结果。
     */
    fun setAutoCampusProbe(enabled: Boolean) {
        wbuRepository.setAutoCampusProbeEnabled(enabled)
        WbuNetworkProbe.invalidateFastCache()
        refreshTrigger.update { it + 1 }
    }

    /** 导入前先选学期。 */
    fun setSelectSemesterOnImport(enabled: Boolean) {
        wbuRepository.setSelectSemesterOnImport(enabled)
        refreshTrigger.update { it + 1 }
    }

    /** 导入时保留教师工号。 */
    fun setKeepTeacherId(enabled: Boolean) {
        wbuRepository.setKeepTeacherId(enabled)
        refreshTrigger.update { it + 1 }
    }

    /** 导入时保留建筑名称。 */
    fun setKeepBuilding(enabled: Boolean) {
        wbuRepository.setKeepBuilding(enabled)
        refreshTrigger.update { it + 1 }
    }

    /** IDS 地址不从教务取。 */
    fun setIdsAddrNotFromJwxt(enabled: Boolean) {
        wbuRepository.setIdsAddrNotFromJwxt(enabled)
        refreshTrigger.update { it + 1 }
    }

    /** 跳过主页验证。 */
    fun setNoIndexMainVerify(enabled: Boolean) {
        wbuRepository.setNoIndexMainVerify(enabled)
        refreshTrigger.update { it + 1 }
    }

    /** Force IDS account resolution before WebVPN login, regardless of the ID format. */
    fun setForceFetchStudentIdBeforeVpn(enabled: Boolean) {
        wbuRepository.setForceFetchStudentIdBeforeVpn(enabled)
        refreshTrigger.update { it + 1 }
    }

    /** 换票时使用固定 service。 */
    fun setUseFixedServiceForTicket(enabled: Boolean) {
        wbuRepository.setUseFixedServiceForTicket(enabled)
        refreshTrigger.update { it + 1 }
    }

    /** 「高级模式」开关（跨页面记住，不进 UI State）。 */
    fun isAdvancedMode(): Boolean = wbuRepository.isAdvancedMode()

    fun setAdvancedMode(enabled: Boolean) = wbuRepository.setAdvancedMode(enabled)

    /** 登录成功后刷新本地状态；只对图书馆执行必要的 OPAC 会话建立/核验。 */
    fun refreshAfterServiceLogin(service: CredentialService) {
        viewModelScope.launch {
            val state = when (service) {
                CredentialService.LIBRARY -> runCatching { verifier.verify(service) }
                    .getOrDefault(SessionState.UNKNOWN)
                CredentialService.CAMPUS_CARD -> {
                    if (syncCampusCardInternal(silent = true)) SessionState.VALID
                    else SessionState.NOT_LOGGED_IN
                }
                // 这些登录流程本身已验证对应会话；再打一次验证探针没有额外价值。
                else -> SessionState.VALID
            }
            refreshTrigger.update { it + 1 }
            updateVerify(service) { VerifyState(verifying = false, state = state) }
        }
    }

    /**
     * 一卡通「同步」：直接使用已有的统一认证凭据 (CASTGC) 换取一卡通平台令牌。
     * 不弹登录窗、不重新输入密码；成功后刷新卡片状态，失败时给出提示。
     */
    fun syncCampusCardWithExistingCredentials() {
        viewModelScope.launch {
            updateVerify(CredentialService.CAMPUS_CARD) { it.copy(verifying = true) }
            val ok = syncCampusCardInternal(silent = false)
            refreshTrigger.update { it + 1 }
            // Freshly exchanged tokens are accepted by the platform; avoid an immediate
            // duplicate /user probe. If exchange failed, probe an existing token as fallback.
            val result = if (ok) SessionState.VALID else {
                runCatching { verifier.verify(CredentialService.CAMPUS_CARD) }
                    .getOrDefault(SessionState.UNKNOWN)
            }
            updateVerify(CredentialService.CAMPUS_CARD) { VerifyState(verifying = false, state = result) }
            if (ok) _campusCardSyncMessage.value = "success"
        }
    }

    /** 使用已有统一认证凭据换票并落盘；返回是否成功。 */
    private suspend fun syncCampusCardInternal(silent: Boolean): Boolean {
        val ctx = wbuRepository.context
        val result = runCatching {
            val client = com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuCampusCardClient(
                context = ctx,
                useVpn = false
            )
            client.loginWithCasTgc()
        }
        return result.fold(
            onSuccess = { r ->
                com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport.setCampusCardTokens(
                    ctx,
                    r.accessToken,
                    r.refreshToken
                )
                true
            },
            onFailure = { e ->
                if (!silent) _campusCardSyncMessage.value = e.message ?: "failed"
                false
            }
        )
    }

    fun verifyAll() {
        CredentialService.entries.forEach { verify(it) }
    }

    fun verify(service: CredentialService) {
        viewModelScope.launch {
            updateVerify(service) { it.copy(verifying = true) }
            val result = runCatching { verifier.verify(service) }.getOrDefault(SessionState.UNKNOWN)
            updateVerify(service) { VerifyState(verifying = false, state = result) }
        }
    }

    fun resetPassword(service: CredentialService, password: String) {
        wbuRepository.resetPassword(service, password)
        refreshTrigger.update { it + 1 }
    }

    fun clearPassword(service: CredentialService) {
        wbuRepository.clearPassword(service)
        refreshTrigger.update { it + 1 }
    }

    fun clearToken(service: CredentialService) {
        wbuRepository.clearToken(service)
        refreshTrigger.update { it + 1 }
    }

    /** 清除会话：先尽力服务端退出登录，再清本地。 */
    fun clearSession(service: CredentialService) {
        viewModelScope.launch {
            updateVerify(service) { it.copy(verifying = true) }
            wbuRepository.clearSessionWithLogout(service)
            refreshTrigger.update { it + 1 }
            updateVerify(service) { VerifyState(verifying = false, state = SessionState.NOT_LOGGED_IN) }
        }
    }

    /** 清除某服务全部凭据（密码 / token / 会话）：先尽力退出登录，再清本地。 */
    fun clearService(service: CredentialService) {
        viewModelScope.launch {
            updateVerify(service) { it.copy(verifying = true) }
            wbuRepository.clearServiceWithLogout(service)
            refreshTrigger.update { it + 1 }
            updateVerify(service) { VerifyState(verifying = false, state = SessionState.NOT_LOGGED_IN) }
        }
    }

    fun switchAccount(service: CredentialService, account: String) {
        wbuRepository.switchAccount(service, account)
        refreshTrigger.update { it + 1 }
    }

    /** 修改当前账号名称（与登录身份无关，可随意改）。 */
    fun renameAccount(service: CredentialService, name: String) {
        wbuRepository.renameAccount(service, name)
        refreshTrigger.update { it + 1 }
    }

    /** 写入 WebVPN 的 TWFID（与登录弹窗输入框共用同一存储）。 */
    fun setTwfid(value: String) {
        wbuRepository.setTwfid(value)
        refreshTrigger.update { it + 1 }
    }

    /** 高级模式手动改会话凭据（如 jw_uf / CASTGC / PHPSESSID）。 */
    fun setSessionCredential(service: CredentialService, name: String, value: String) {
        wbuRepository.updateSessionCredential(service, name, value)
        refreshTrigger.update { it + 1 }
    }

    /** 重设 WebDAV 密码（保留地址、用户名与根路径）。 */
    fun resetWebDavPassword(password: String) {
        if (password.isBlank()) return
        viewModelScope.launch { apiConfigRepository.saveWebDavPassword(password) }
    }

    /** 修改 WebDAV 服务器地址：只动地址这一个键，不依赖其余字段是否已配置。 */
    fun setWebDavAddress(address: String) {
        viewModelScope.launch { apiConfigRepository.saveWebDavBaseUrl(address) }
    }

    /** 修改 WebDAV 用户名：只动用户名这一个键。 */
    fun setWebDavUsername(name: String) {
        viewModelScope.launch { apiConfigRepository.saveWebDavUsername(name) }
    }

    fun clearWebDavPassword() {
        viewModelScope.launch { apiConfigRepository.clearWebDavPassword() }
    }

    fun clearWebDavAll() {
        viewModelScope.launch { apiConfigRepository.clearWebDavConfig() }
    }

    private fun updateVerify(service: CredentialService, transform: (VerifyState) -> VerifyState) {
        verifyState.update { current ->
            current + (service to transform(current[service] ?: VerifyState()))
        }
    }

    private fun buildUiState(
        cfg: WebDavConfig?,
        stored: WebDavStoredInfo,
        verify: Map<CredentialService, VerifyState>,
    ): CredentialUiState {
        val studentId = wbuRepository.studentId()
        val services = wbuRepository.snapshot().map { s ->
            ServiceUiState(
                service = s.service,
                accountId = s.accountId,
                accounts = wbuRepository.listAccounts(s.service),
                accountLabel = wbuRepository.accountName(s.service) ?: studentId,
                hasPassword = s.hasPassword,
                hasOwnPassword = s.hasOwnPassword,
                hasToken = s.hasToken,
                credentials = if (s.service == CredentialService.WEBVPN) emptyList()
                    else wbuRepository.sessionCredentials(s.service),
                tokenValue = s.tokenValue,
                hasSession = s.hasSession,
                isVerifying = verify[s.service]?.verifying == true,
                sessionState = verify[s.service]?.state,
            )
        } + ServiceUiState(
            service = CredentialService.WEBDAV,
            accountId = cfg?.username?.takeIf { it.isNotBlank() } ?: CredentialService.DEFAULT_ACCOUNT,
            accounts = listOf(cfg?.username?.takeIf { it.isNotBlank() } ?: CredentialService.DEFAULT_ACCOUNT),
            accountLabel = wbuRepository.accountName(CredentialService.WEBDAV) ?: cfg?.username.orEmpty(),
            hasPassword = stored.hasPassword,
            hasOwnPassword = stored.hasPassword,
            hasToken = false,
            credentials = emptyList(),
            tokenValue = "",
            hasSession = cfg != null,
            isVerifying = verify[CredentialService.WEBDAV]?.verifying == true,
            sessionState = verify[CredentialService.WEBDAV]?.state,
            webDavBaseUrl = stored.baseUrl,
            webDavUsername = stored.username,
            webDavPassword = cfg?.password,
        )
        return CredentialUiState(
            services = services,
            autoVerify = wbuRepository.isAutoVerifyEnabled(),
            autoLoginWithSavedPassword = wbuRepository.isAutoLoginWithSavedPasswordEnabled(),
            webDavConfigured = cfg != null,
            useVpn = wbuRepository.isUseVpn(),
            idsViaWebVpn = wbuRepository.isIdsViaWebVpn(),
            qrViaWebVpn = wbuRepository.isQrViaWebVpn(),
            useHttpsWebVpn = wbuRepository.isUseHttpsWebVpn(),
            usePcUserAgent = wbuRepository.isUsePcUserAgent(),
            skipCampusCheck = wbuRepository.isSkipCampusCheck(),
            autoCampusProbe = wbuRepository.isAutoCampusProbeEnabled(),
            selectSemesterOnImport = wbuRepository.isSelectSemesterOnImport(),
            keepTeacherId = wbuRepository.isKeepTeacherId(),
            keepBuilding = wbuRepository.isKeepBuilding(),
            idsAddrNotFromJwxt = wbuRepository.isIdsAddrNotFromJwxt(),
            noIndexMainVerify = wbuRepository.isNoIndexMainVerify(),
            forceFetchStudentIdBeforeVpn = wbuRepository.isForceFetchStudentIdBeforeVpn(),
            useFixedServiceForTicket = wbuRepository.isUseFixedServiceForTicket(),
        )
    }
}
