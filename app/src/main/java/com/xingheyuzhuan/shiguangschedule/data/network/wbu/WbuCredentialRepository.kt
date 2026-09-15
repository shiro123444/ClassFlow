package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 某服务当前账号的本地凭据状态（仅读取本地存储，不联网）。
 */
data class ServiceCredentialState(
    val service: CredentialService,
    val accountId: String,
    val hasPassword: Boolean,
    val hasOwnPassword: Boolean,
    val rememberPassword: Boolean,
    val hasToken: Boolean,
    val tokenValue: String,
    val hasSession: Boolean,
)

/**
 * WBU 校园凭据仓库：统一读写「服务类型 × 账号」维度的密码 / token / 会话。
 *
 * 仅覆盖 [WbuAuthTransport] 管理的 WBU 服务（统一认证 / 教务 / 图书馆 / WebVPN / 一卡通）；
 * WebDAV 凭据由 ApiConfigRepository 单独管理，由 UI 层合并展示。
 */
@Singleton
class WbuCredentialRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** 需要在本仓库管理的 WBU 服务（按展示顺序）。WebDAV 不在此列。 */
        val WBU_SERVICES = listOf(
            CredentialService.UNIFIED_AUTH,
            CredentialService.JIAOWU,
            CredentialService.LIBRARY,
            CredentialService.WEBVPN,
            CredentialService.CAMPUS_CARD,
        )
    }

    fun snapshot(): List<ServiceCredentialState> = WBU_SERVICES.map { service ->
        ServiceCredentialState(
            service = service,
            accountId = WbuAuthTransport.activeAccount(context, service),
            hasPassword = WbuAuthTransport.hasSavedPassword(context, service),
            hasOwnPassword = WbuAuthTransport.hasOwnPassword(context, service),
            rememberPassword = WbuAuthTransport.isRememberPasswordEnabled(context, service),
            hasToken = service == CredentialService.WEBVPN && WbuAuthTransport.getTwfid(context).isNotBlank(),
            tokenValue = if (service == CredentialService.WEBVPN) WbuAuthTransport.getTwfid(context) else "",
            hasSession = WbuAuthTransport.hasServiceSession(context, service),
        )
    }

    fun studentId(): String = WbuAuthTransport.getSavedStudentId(context)

    /** 某服务当前账号的自定义名称（null 表示未命名）。与登录身份无关，可随意修改。 */
    fun accountName(service: CredentialService): String? =
        WbuAuthTransport.getAccountName(context, service)

    fun renameAccount(service: CredentialService, name: String) =
        WbuAuthTransport.setAccountName(context, service, name)

    /** TWFID 明文（WebVPN 的会话凭据）。 */
    fun twfid(): String = WbuAuthTransport.getTwfid(context)

    /** 凭据（Cookie / TWFID）变更信号，供账号页等界面订阅刷新。 */
    val credentialChanges = WbuAuthTransport.credentialChanges

    /**
     * 某服务的会话凭据（Cookie 名 → 值，如 jw_uf / CASTGC / PHPSESSID），供「高级模式」查看。
     */
    fun sessionCredentials(service: CredentialService): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val raw = WbuAuthTransport.cookieBucketJson(context, service) ?: return out
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                val value = obj.optString("value")
                if (name.isNotBlank() && value.isNotBlank()) out.add(name to value)
            }
        }
        return out
    }

    /** 高级模式手动改凭据：改写某服务会话里的某个 Cookie 值。 */
    fun updateSessionCredential(service: CredentialService, name: String, value: String) {
        if (service == CredentialService.WEBVPN) return
        WbuAuthTransport.updateServiceCookie(context, service, name, value)
    }

    fun listAccounts(service: CredentialService): List<String> =
        WbuAuthTransport.listAccounts(context, service)

    fun switchAccount(service: CredentialService, account: String) =
        WbuAuthTransport.switchAccount(context, service, account)

    /** 直接写入 WebVPN 的 TWFID（与登录弹窗输入框共用同一存储）。 */
    fun setTwfid(value: String) {
        WbuAuthTransport.setTwfid(context, value)
    }

    /** 保存新密码并开启「记住密码」。 */
    fun resetPassword(service: CredentialService, password: String) {
        if (password.isBlank()) return
        WbuAuthTransport.savePassword(context, service, password)
        WbuAuthTransport.setRememberPasswordEnabled(context, service, true)
    }

    /** 清除某服务保存的密码（关闭记住并清空密码槽）。 */
    fun clearPassword(service: CredentialService) {
        WbuAuthTransport.setRememberPasswordEnabled(context, service, false)
        WbuAuthTransport.clearSavedPassword(context, service)
    }

    /** 清除某服务的 token（WebVPN 的 TWFID）。 */
    fun clearToken(service: CredentialService) {
        if (service == CredentialService.WEBVPN) WbuAuthTransport.clearTwfid(context)
    }

    /** 清除某服务的会话（Cookie 桶）。 */
    fun clearSession(service: CredentialService) {
        WbuAuthTransport.clearServiceSession(context, service)
    }

    /**
     * 「清除会话」：先尽力向服务端发起退出登录（作废服务端会话），再清本地。
     * 退出接口均为 GET + 会话 Cookie，见退出登录接口文档。
     */
    suspend fun clearSessionWithLogout(service: CredentialService) {
        runCatching { attemptServerLogout(service) }
        // WebVPN 的会话就是 TWFID，退出后一并清本地 TWFID
        if (service == CredentialService.WEBVPN) WbuAuthTransport.clearTwfid(context)
        clearSession(service)
    }

    /** 「清除全部凭据」：先尽力退出登录，再清密码 / token / 会话。 */
    suspend fun clearServiceWithLogout(service: CredentialService) {
        runCatching { attemptServerLogout(service) }
        clearService(service)
    }

    /** 尽力发起服务端退出登录（失败忽略，本地仍会清理）。 */
    private suspend fun attemptServerLogout(service: CredentialService) = withContext(Dispatchers.IO) {
        val useVpn = WbuSyncEngine.getSavedUseVpn(context) ?: false
        val transport = WbuAuthTransport.getShared(context, useVpn)
        when (service) {
            // 统一认证：吊销 CASTGC（服务端 TGT）
            CredentialService.UNIFIED_AUTH -> getNoFollow(
                url = "${transport.idsBase()}/authserver/logout",
                referer = transport.idsBase(),
                transport = transport
            )
            // 教务系统：作废 jw_uf
            CredentialService.JIAOWU -> getNoFollow(
                url = "${transport.jwxtBase}/admin/caslogout",
                referer = "${transport.jwxtBase}/admin",
                transport = transport
            )
            // 图书馆 OPAC：销毁 PHPSESSID 读者会话
            CredentialService.LIBRARY -> getNoFollow(
                url = "${transport.opacBase()}/reader/logout.php",
                referer = transport.opacBase(),
                transport = transport
            )
            // WebVPN 门户：注销 TWFID（成功标志为 <ErrorCode>1</ErrorCode>）
            CredentialService.WEBVPN -> {
                val twfid = WbuAuthTransport.getTwfid(context)
                if (twfid.isNotBlank()) {
                    val req = Request.Builder()
                        .url("${transport.vpnBase}/por/logout.csp")
                        .header("Cookie", "TWFID=$twfid")
                        .header("User-Agent", transport.authUserAgent())
                        .get()
                        .build()
                    runCatching {
                        transport.twfidProbeClient.newCall(req).execute().use { it.body.string() }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun getNoFollow(url: String, referer: String, transport: WbuAuthTransport) {
        runCatching {
            val client = transport.client.newBuilder().followRedirects(false).build()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", transport.authUserAgent())
                .header("Accept-Language", transport.authAcceptLanguage)
                .header("Referer", referer)
                .get()
                .build()
            client.newCall(req).execute().use { it.body.string() }
        }
    }

    /** 清除某服务的全部凭据（密码 + token + 会话）。 */
    fun clearService(service: CredentialService) {
        clearPassword(service)
        clearToken(service)
        clearSession(service)
    }

    fun isAutoVerifyEnabled(): Boolean = WbuAuthTransport.isCredentialAutoVerifyEnabled(context)

    fun setAutoVerifyEnabled(enabled: Boolean) =
        WbuAuthTransport.setCredentialAutoVerifyEnabled(context, enabled)

    /** 当前网络接入模式：true = WebVPN（校外），false = 校园网直连。 */
    fun isUseVpn(): Boolean = WbuSyncEngine.getSavedUseVpn(context) ?: false

    fun setUseVpn(enabled: Boolean) = WbuSyncEngine.setSavedUseVpn(context, enabled)

    /** 统一认证是否经过 WebVPN（对应「更多→网络设置」里的同名开关）。 */
    fun isIdsViaWebVpn(): Boolean = WbuAuthTransport.getIdsViaWebVpn(context)

    fun setIdsViaWebVpn(enabled: Boolean) = WbuAuthTransport.setIdsViaWebVpn(context, enabled)

    /** 二维码是否经过 WebVPN。 */
    fun isQrViaWebVpn(): Boolean = IdsCasClient.getQrViaWebVpn(context)

    fun setQrViaWebVpn(enabled: Boolean) = IdsCasClient.setQrViaWebVpn(context, enabled)

    /** WebVPN 是否使用 HTTPS。 */
    fun isUseHttpsWebVpn(): Boolean = WebVpnClient.getUseHttpsWebVpn(context)

    fun setUseHttpsWebVpn(enabled: Boolean) = WebVpnClient.setUseHttpsWebVpn(context, enabled)

    /** 使用桌面端 User-Agent。 */
    fun isUsePcUserAgent(): Boolean = WbuSyncEngine.getUsePcUserAgent(context)

    fun setUsePcUserAgent(enabled: Boolean) = WbuSyncEngine.setUsePcUserAgent(context, enabled)

    /** 跳过校园网检测。 */
    fun isSkipCampusCheck(): Boolean = WbuSyncEngine.getSkipCampusCheck(context)

    fun setSkipCampusCheck(enabled: Boolean) = WbuSyncEngine.setSkipCampusCheck(context, enabled)

    /** 「高级模式」开关（跨页面记住）。 */
    fun isAdvancedMode(): Boolean = WbuAuthTransport.getCredentialAdvancedMode(context)

    fun setAdvancedMode(enabled: Boolean) =
        WbuAuthTransport.setCredentialAdvancedMode(context, enabled)

    /** 导入前先选学期。 */
    fun isSelectSemesterOnImport(): Boolean = WbuSyncEngine.getSelectSemesterOnImport(context)

    fun setSelectSemesterOnImport(enabled: Boolean) =
        WbuSyncEngine.setSelectSemesterOnImport(context, enabled)

    /** 导入时保留教师工号。 */
    fun isKeepTeacherId(): Boolean = WbuSyncEngine.getKeepTeacherId(context)

    fun setKeepTeacherId(enabled: Boolean) = WbuSyncEngine.setKeepTeacherId(context, enabled)

    /** 导入时保留建筑名称。 */
    fun isKeepBuilding(): Boolean = WbuSyncEngine.getKeepBuilding(context)

    fun setKeepBuilding(enabled: Boolean) = WbuSyncEngine.setKeepBuilding(context, enabled)

    /** IDS 地址不从教务取。 */
    fun isIdsAddrNotFromJwxt(): Boolean = WbuSyncEngine.getIdsAddrNotFromJwxt(context)

    fun setIdsAddrNotFromJwxt(enabled: Boolean) =
        WbuSyncEngine.setIdsAddrNotFromJwxt(context, enabled)

    /** 跳过主页验证。 */
    fun isNoIndexMainVerify(): Boolean = WbuSyncEngine.getNoIndexMainVerify(context)

    fun setNoIndexMainVerify(enabled: Boolean) =
        WbuSyncEngine.setNoIndexMainVerify(context, enabled)

    /** 登录 WebVPN 前先获取学号。 */
    fun isForceFetchStudentIdBeforeVpn(): Boolean =
        WbuSyncEngine.getForceFetchStudentIdBeforeVpn(context)

    fun setForceFetchStudentIdBeforeVpn(enabled: Boolean) =
        WbuSyncEngine.setForceFetchStudentIdBeforeVpn(context, enabled)

    /** 换票时使用固定 service。 */
    fun isUseFixedServiceForTicket(): Boolean = WbuSyncEngine.getUseFixedServiceForTicket(context)

    fun setUseFixedServiceForTicket(enabled: Boolean) =
        WbuSyncEngine.setUseFixedServiceForTicket(context, enabled)
}
