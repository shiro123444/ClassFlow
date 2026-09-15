package com.xingheyuzhuan.shiguangschedule.ui.settings.credentials

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.api.webdav.WebDavConfig
import com.xingheyuzhuan.shiguangschedule.data.repository.WebDavStoredInfo
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.CredentialVerifier
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.SessionState
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuCredentialRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.ApiConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val wbuRepository: WbuCredentialRepository,
    private val verifier: CredentialVerifier,
    private val apiConfigRepository: ApiConfigRepository,
) : ViewModel() {

    private val verifyState = MutableStateFlow<Map<CredentialService, VerifyState>>(emptyMap())
    private val refreshTrigger = MutableStateFlow(0)

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

    /** 进入页面：刷新本地状态，并按开关决定是否自动验证。 */
    fun onScreenEnter() {
        refreshTrigger.update { it + 1 }
        if (wbuRepository.isAutoVerifyEnabled()) verifyAll()
    }

    fun setAutoVerify(enabled: Boolean) {
        wbuRepository.setAutoVerifyEnabled(enabled)
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

    /** 登录 WebVPN 前先获取学号。 */
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

    /** 登录成功后刷新该服务状态并重新核验。 */
    fun refreshAfterServiceLogin(service: CredentialService) {
        refreshTrigger.update { it + 1 }
        verify(service)
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
            webDavConfigured = cfg != null,
            useVpn = wbuRepository.isUseVpn(),
            idsViaWebVpn = wbuRepository.isIdsViaWebVpn(),
            qrViaWebVpn = wbuRepository.isQrViaWebVpn(),
            useHttpsWebVpn = wbuRepository.isUseHttpsWebVpn(),
            usePcUserAgent = wbuRepository.isUsePcUserAgent(),
            skipCampusCheck = wbuRepository.isSkipCampusCheck(),
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
