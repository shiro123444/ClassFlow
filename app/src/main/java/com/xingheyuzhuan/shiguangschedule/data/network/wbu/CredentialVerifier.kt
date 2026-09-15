package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.repository.ApiConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** 服务会话验证结果。 */
enum class SessionState {
    /** 会话有效。 */
    VALID,

    /** 会话存在但已失效，需要重新登录。 */
    EXPIRED,

    /** 本地无该服务的登录态。 */
    NOT_LOGGED_IN,

    /** 服务尚未接入，无法验证（如一卡通）。 */
    NOT_AVAILABLE,

    /** 网络异常等无法判定。 */
    UNKNOWN,
}

/**
 * 会话验证器：进入凭据管理页时按需联网核验各服务的登录态是否仍然有效。
 *
 * 复用现有探针实现：教务 [WbuQueryClient.checkSession]、图书馆 [WbuQueryClient.ensureOpacSession]、
 * WebVPN [WebVpnClient.validateTwfid]、WebDAV 根目录探活。
 */
@Singleton
class CredentialVerifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiConfigRepository: ApiConfigRepository,
) {

    suspend fun verify(service: CredentialService): SessionState = withContext(Dispatchers.IO) {
        val useVpn = WbuSyncEngine.getSavedUseVpn(context) ?: false
        when (service) {
            CredentialService.UNIFIED_AUTH -> verifyUnifiedAuth(useVpn)
            CredentialService.JIAOWU -> verifyJiaowu(useVpn)
            CredentialService.LIBRARY -> verifyLibrary(useVpn)
            CredentialService.WEBVPN -> verifyWebVpn()
            CredentialService.CAMPUS_CARD -> SessionState.NOT_AVAILABLE
            CredentialService.WEBDAV -> verifyWebDav()
        }
    }

    /** 统一认证：持有 CASTGC 且能换到 service ticket 视为有效。 */
    private fun verifyUnifiedAuth(useVpn: Boolean): SessionState {
        val transport = WbuAuthTransport.getShared(context, useVpn)
        transport.restoreCookieStore()
        val hasTgc = transport.cookieStore.any { it.name == "CASTGC" && it.value.isNotBlank() }
        if (!hasTgc) return SessionState.NOT_LOGGED_IN
        return runCatching {
            val service = URLEncoder.encode(transport.casServiceTarget, "UTF-8")
            val url = "${transport.idsBase()}/authserver/login?service=$service"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", transport.authUserAgent())
                .header("Accept-Language", transport.authAcceptLanguage)
                .get()
                .build()
            val client = transport.client.newBuilder().followRedirects(false).build()
            client.newCall(req).execute().use { resp ->
                val location = resp.header("Location").orEmpty()
                when {
                    location.contains("ticket=") -> SessionState.VALID
                    resp.code in 300..399 -> SessionState.EXPIRED
                    else -> SessionState.UNKNOWN
                }
            }
        }.getOrDefault(SessionState.UNKNOWN)
    }

    /** 教务系统：持有 jw_uf 且 /admin 接口可访问视为有效。 */
    private suspend fun verifyJiaowu(useVpn: Boolean): SessionState {
        val transport = WbuAuthTransport.getShared(context, useVpn)
        transport.restoreCookieStore()
        val hasSessionCookie = transport.cookieStore.any { it.name == "jw_uf" && it.value.isNotBlank() }
        if (!hasSessionCookie) return SessionState.NOT_LOGGED_IN
        return if (WbuQueryClient(context, useVpn).checkSession()) SessionState.VALID else SessionState.EXPIRED
    }

    /** 图书馆：持有 OPAC 会话或 CASTGC，能确保会话有效则视为有效。 */
    private suspend fun verifyLibrary(useVpn: Boolean): SessionState {
        val transport = WbuAuthTransport.getShared(context, useVpn)
        transport.restoreCookieStore()
        val hasPhp = transport.cookieStore.any { it.name == "PHPSESSID" && it.value.isNotBlank() }
        val hasTgc = transport.cookieStore.any { it.name == "CASTGC" && it.value.isNotBlank() }
        if (!hasPhp && !hasTgc) return SessionState.NOT_LOGGED_IN
        return try {
            WbuQueryClient(context, useVpn).ensureOpacSession()
            SessionState.VALID
        } catch (e: WbuSessionExpiredException) {
            SessionState.EXPIRED
        } catch (e: Exception) {
            SessionState.UNKNOWN
        }
    }

    /** WebVPN：TWFID 探活。 */
    private suspend fun verifyWebVpn(): SessionState {
        val twfid = WbuAuthTransport.getTwfid(context)
        if (twfid.isBlank()) return SessionState.NOT_LOGGED_IN
        val transport = WbuAuthTransport.getShared(context, true)
        val valid = WebVpnClient(transport).validateTwfid(twfid)
        return if (valid) SessionState.VALID else SessionState.EXPIRED
    }

    /** WebDAV：连接并确保根目录可访问。 */
    private suspend fun verifyWebDav(): SessionState {
        val config = apiConfigRepository.webDavConfigFlow.firstOrNull() ?: return SessionState.NOT_LOGGED_IN
        if (config.password.isBlank()) return SessionState.NOT_LOGGED_IN
        val client = apiConfigRepository.createWebDavClient(config) ?: return SessionState.NOT_LOGGED_IN
        return try {
            if (client.ensureRootDirectoryExists()) SessionState.VALID else SessionState.EXPIRED
        } catch (e: Exception) {
            SessionState.EXPIRED
        } finally {
            client.close()
        }
    }
}
