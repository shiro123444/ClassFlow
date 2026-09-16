package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import android.util.Log
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * 通用网页应用客户端：负责与统一认证 (CAS) 及业务系统的 SSO 回调服务交互，
 * 获取用于进入业务前端的有效 JWT 凭证，并构造承载 Token 的完整全屏 WebView 访问 URL。
 */
class WbuWebAppClient(
    private val context: Context,
    val useVpn: Boolean = false
) {
    private val transport: WbuAuthTransport = WbuAuthTransport.getShared(context, useVpn)
    private val client = transport.client

    /**
     * 计算该网页应用在当前网络通道（直连或 WebVPN）下的基础基址（不含结尾斜杠）。
     */
    fun resolveBaseUrl(def: WebAppDefinition): String {
        return if (useVpn) {
            transport.webVpnProxyBase(def.targetHost, withSingleSuffix = def.vpnSingleSuffix)
        } else {
            def.realOrigin
        }
    }

    /**
     * 为指定的网页应用从 CAS 和 SSO 换取一次性回跳 Token (JWT)。
     *
     * 流程：
     * 1. 检查并恢复 Cookie 库，必须持有有效的统一身份认证凭证 CASTGC，否则抛出 [WbuSessionExpiredException]；
     * 2. (若定义) 请求业务系统的 SSO 入口（如 /rem/static/sso/login），建立与业务 SSO 网关的会话 (rem_JSESSIONID)；
     * 3. 请求 CAS 认证服务核验 CASTGC 并申请目标业务 Service 的 ST ticket；
     * 4. 携带 ST ticket 访问业务系统的核销回调（如 /rem/static/sso/webOAuthRed?ticket=ST-xxx）；
     *    在 WebVPN 环境下需将 Location 重定向目标 Host 映射为 WebVPN 代理宿主；
     * 5. 捕获重定向结果并解析提取 `token` 参数 (JWT)。
     *
     * @throws WbuSessionExpiredException 当凭据缺失或已过期时抛出
     * @throws IOException 当网络通信失败或返回异常时抛出
     */
    suspend fun fetchCasCallbackToken(def: WebAppDefinition): String = withContext(Dispatchers.IO) {
        transport.restoreCookieStore()

        // 若处于 WebVPN 模式，确保将 TWFID 门禁 Cookie 注入到 OkHttp 的 cookieStore 内存中
        if (useVpn) {
            val twfid = WbuAuthTransport.getTwfid(context)
            if (twfid.isNotBlank()) {
                WebVpnClient(transport).injectTwfid(twfid)
            }
        }

        val hasTgc = transport.cookieStore.any { it.name == "CASTGC" && it.value.isNotBlank() }
        if (!hasTgc) {
            Log.w("WbuWebAppClient", "No CASTGC found in cookie store for WebApp: ${def.id}")
            throw WbuSessionExpiredException(message = "统一身份认证已失效，请重新登录")
        }

        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val currentBase = resolveBaseUrl(def)

        // 1. 若配置了 SSO 前置入口，先访问以在业务网关建立会话 (如 rem_JSESSIONID)
        val ssoPath = def.remSsoLoginPath
        if (!ssoPath.isNullOrBlank()) {
            val vueServiceUrl = "${def.realOrigin}${def.appPath}#/login"
            val ssoInitUrl = "$currentBase$ssoPath?redirectUrl=${URLEncoder.encode(vueServiceUrl, "UTF-8")}"
            val ssoReq = Request.Builder()
                .url(ssoInitUrl)
                .header("User-Agent", transport.authUserAgent())
                .header("Accept-Language", transport.authAcceptLanguage)
                .get()
                .build()

            runCatching {
                noRedirectClient.newCall(ssoReq).execute().use { resp ->
                    val loc = resp.header("Location").orEmpty()
                    Log.d("WbuWebAppClient", "SSO init resp: ${resp.code}, Location: $loc")
                    if (loc.contains("/por/login") || loc.contains("/por/login_psw") || loc.contains("/portal/")) {
                        throw WbuSessionExpiredException(message = "WebVPN 门禁已失效，请重新登录")
                    }
                }
            }.getOrThrow()
        }

        // 2. 向 CAS 申请目标业务服务的 Service Ticket (ST)
        // 注意：CAS 服务端登记的 service 验证地址始终必须是业务端声明的公网真实 URL
        val encodedService = URLEncoder.encode(def.casServiceUrl, "UTF-8")
        val casLoginUrl = "${transport.idsBase()}/authserver/login?service=$encodedService"

        val casReq = Request.Builder()
            .url(casLoginUrl)
            .header("User-Agent", transport.authUserAgent())
            .header("Accept-Language", transport.authAcceptLanguage)
            .get()
            .build()

        val casLocation = noRedirectClient.newCall(casReq).execute().use { resp ->
            resp.header("Location")
        }

        if (casLocation.isNullOrBlank() || !casLocation.contains("ticket=")) {
            if (casLocation.orEmpty().contains("/por/") || casLocation.orEmpty().contains("webvpn")) {
                throw WbuSessionExpiredException(message = "WebVPN 门禁已失效，请重新登录")
            }
            Log.w("WbuWebAppClient", "CAS failed to grant ST ticket. Location: $casLocation")
            throw WbuSessionExpiredException(message = "统一认证会话已过期，请重新登录")
        }

        // 3. 将 CAS 回跳的目标重写为当前通道宿主（WebVPN 下重写为代理宿主）
        val targetCallbackUrl = if (useVpn) {
            rewriteToProxyHost(casLocation, def)
        } else {
            casLocation
        }

        Log.i("WbuWebAppClient", "Accessing SSO callback: $targetCallbackUrl")

        // 4. 访问核销地址，捕获签发出来的 JWT Location
        val callbackReq = Request.Builder()
            .url(targetCallbackUrl)
            .header("User-Agent", transport.authUserAgent())
            .header("Accept-Language", transport.authAcceptLanguage)
            .get()
            .build()

        val finalLocation = noRedirectClient.newCall(callbackReq).execute().use { resp ->
            resp.header("Location")
        }

        if (finalLocation.isNullOrBlank()) {
            Log.e("WbuWebAppClient", "SSO callback returned no redirect Location")
            throw IOException("SSO 认证回调未返回有效凭据")
        }

        if (finalLocation.contains("/por/") || finalLocation.contains("webvpn.wbu.edu.cn/por")) {
            Log.w("WbuWebAppClient", "SSO callback redirected to WebVPN portal: $finalLocation")
            throw WbuSessionExpiredException(message = "WebVPN 门禁已失效，请重新登录")
        }

        // 5. 从重定向 URL 解析提取 token
        val token = extractTokenFromUrl(finalLocation)
        if (token.isNullOrBlank()) {
            if (finalLocation.contains("login")) {
                throw WbuSessionExpiredException(message = "统一认证会话已过期，请重新登录")
            }
            Log.e("WbuWebAppClient", "Failed to extract token from Location: $finalLocation")
            throw WbuSessionExpiredException(message = "未能从 SSO 回调中解析出 Token 凭证，请重新登录")
        }

        transport.persistCookieStore()
        Log.i("WbuWebAppClient", "Successfully obtained WebApp token for ${def.id}")
        token
    }

    /**
     * 将公网 URL 的 Host 重写为对应的 WebVPN 代理宿主，保留其路径与 Query。
     */
    private fun rewriteToProxyHost(urlStr: String, def: WebAppDefinition): String {
        val parsed = urlStr.toHttpUrlOrNull() ?: return urlStr
        val proxyBase = transport.webVpnProxyBase(def.targetHost, withSingleSuffix = def.vpnSingleSuffix).toHttpUrlOrNull()
            ?: return urlStr
        return parsed.newBuilder()
            .scheme(proxyBase.scheme)
            .host(proxyBase.host)
            .port(proxyBase.port)
            .build()
            .toString()
    }

    /**
     * 模拟前端 Fe(href) 逻辑：提取 `?` 与 `#` 之间的 query 参数，或者 URL 中的 `token` 参数。
     */
    fun extractTokenFromUrl(url: String): String? {
        val queryPart = (url.split("?").getOrNull(1) ?: "").split("#")[0]
        if (queryPart.isNotBlank()) {
            val map = queryPart.split("&").mapNotNull { pair ->
                val parts = pair.split("=")
                if (parts.size >= 2) parts[0] to parts.subList(1, parts.size).joinToString("=") else null
            }.toMap()
            map["token"]?.takeIf { it.isNotBlank() }?.let { return it }
        }

        // 兜底正则匹配
        return Regex("""[?&]token=([^&#]+)""").find(url)?.groupValues?.getOrNull(1)
    }

    /**
     * 构造供 WebView 直接打开的全屏页面 URL（注入 ?token=xxx 并挂接默认 Hash 路由）。
     */
    fun buildLaunchUrl(def: WebAppDefinition, token: String): String {
        val base = resolveBaseUrl(def)
        val path = if (def.appPath.startsWith("/")) def.appPath else "/${def.appPath}"
        val normalizedPath = if (path.endsWith("/")) path else "$path/"
        val hash = def.defaultHash.takeIf { it.isNotBlank() } ?: ""
        return "$base$normalizedPath?token=$token$hash"
    }
}
