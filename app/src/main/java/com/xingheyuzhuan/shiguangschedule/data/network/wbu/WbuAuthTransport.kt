package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.QrScanEngine
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * 共享传输层：三端（教务 / WebVPN 门户 / ids 统一认证）共用的网络载体。
 *
 * 集中持有 OkHttp 客户端、Cookie 仓库、基址工厂、UA/语言偏好，以及 WebVPN 场景的
 * TLS 证书身份白名单。三个业务客户端（[WbuSyncEngine] / [WebVpnClient] / [IdsCasClient]）
 * 都注入同一个实例，从而一次握手、TLS 只配置一次、Cookie 全局共享。
 */
internal class WbuAuthTransport(
    val context: Context,
    val useVpn: Boolean,
) {

    val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /**
     * 进程内唯一的 Cookie 库，与 [useVpn] 无关：VPN 与直连共享同一份内存。
     * 原先两个 transport 各持一份，写盘时会把对方 scope 的桶删掉，切模式就像凭据丢失。
     */
    val cookieStore: CopyOnWriteArrayList<Cookie> get() = sharedCookieStore

    /** WebVPN TLS 证书校验异常回调（见 WbuSyncEngine 同名属性说明）。 */
    @Volatile
    var sslIssueHandler: (suspend (message: String) -> Boolean)? = null

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                // 特殊保全：若为教务核心会话凭证 jw_uf，确保 path 覆盖所有 /admin 子接口
                val normalizedCookie = if (cookie.name == "jw_uf" && cookie.path.length > 6 && cookie.path.startsWith("/admin")) {
                    cookie.newBuilder().path("/admin").build()
                } else cookie

                val singleValued = cookieNameFamily(normalizedCookie.name) != null
                cookieStore.removeAll {
                    if (singleValued) {
                        // 核心会话 Cookie 按「名」唯一：直连与隧道是同一个后端会话，只留最新一份
                        it.name == normalizedCookie.name
                    } else {
                        it.name == normalizedCookie.name && it.domain == normalizedCookie.domain && (it.path == normalizedCookie.path || (normalizedCookie.name == "jw_uf" && it.path.startsWith("/admin")))
                    }
                }
                if (!normalizedCookie.expiresAt.let { expiresAt -> expiresAt <= System.currentTimeMillis() }) {
                    cookieStore.add(normalizedCookie)
                }
            }
            persistCookieStore()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val validCookies = cookieStore.filter { it.expiresAt > now }
            cookieStore.removeAll { it.expiresAt <= now }

            return validCookies.filter { cookie ->
                val family = cookieNameFamily(cookie.name)
                when {
                    // TWFID 是 WebVPN 网关门禁通行证，只要是 webvpn 域或其代理子域均全域匹配放行
                    cookie.name == "TWFID" ->
                        url.host == "webvpn.wbu.edu.cn" || url.host.endsWith(".webvpn.wbu.edu.cn")

                    // 核心会话 Cookie 在校内域名之间跨域放行：
                    // 直连登录得到的会话，经 WebVPN 隧道（<sub>-wbu-edu-cn.webvpn.wbu.edu.cn）同样复用
                    family != null -> url.host.endsWith("wbu.edu.cn") && url.host.contains(family)

                    else -> cookie.matches(url)
                }
            }
        }
    }

    /** 核心会话 Cookie 对应的域名族：这些 Cookie 按名唯一，且可在校内域名间跨域复用。 */
    private fun cookieNameFamily(name: String): String? = when (name) {
        "jw_uf" -> "jwxt"
        "CASTGC" -> "ids"
        "PHPSESSID" -> "opac"
        else -> null
    }

    /** 延迟构建，确保构造期间所有属性（含 [defaultTrustManager]、[authAcceptLanguage]）已初始化。 */
    val client: OkHttpClient by lazy { buildClient() }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val originalUrl = originalRequest.url
                // 防御性校正：WebVPN 门户接口（/por/...）必须严格发往根门户 https://webvpn.wbu.edu.cn
                // 绝不允许受代理宿主（如 jwxt-wbu-edu-cn-s）302 相对路径重定向干扰而向代理子域发送门户请求。
                val adjustedUrl = if (originalUrl.encodedPath.startsWith("/por/")) {
                    if (originalUrl.host != "webvpn.wbu.edu.cn" || originalUrl.scheme != "https" || originalUrl.port != 443) {
                        Log.i("WbuAuthTransport", "Corrected /por/ request host: ${originalUrl.host} -> webvpn.wbu.edu.cn")
                        originalUrl.newBuilder()
                            .scheme("https")
                            .host("webvpn.wbu.edu.cn")
                            .port(443)
                            .build()
                    } else originalUrl
                } else originalUrl

                val req = originalRequest.newBuilder()
                    .url(adjustedUrl)
                    .header("User-Agent", authUserAgent())
                    .header("Accept-Language", authAcceptLanguage)
                    .build()
                val matched = cookieStore.filter { it.matches(req.url) }.map { it.name }
                Log.d("WbuSyncEngine", "REQ ${req.method} ${req.url} [Cookies: $matched]")
                chain.proceed(req)
            }

        // WebVPN：证书是合法的 *.wbu.edu.cn 通配（链可校验），但只覆盖一层子域，
        // 两级代理子域（如 ids-wbu-edu-cn.webvpn.wbu.edu.cn）主机名不匹配，故按证书身份
        // （SAN/CN 命中 wbu.edu.cn 域名族）而非连接主机名放行。
        // 校验失败（其它/未知证书）时经 sslIssueHandler 弹窗询问（会话内放行该证书）。
        if (useVpn) {
            val trustManager = buildVpnTrustManager()
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            builder.hostnameVerifier { hostname, session ->
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                if (leaf != null) {
                    val thumb = certThumbprint(leaf)
                    if (allowedCertThumbprints.contains(thumb)) return@hostnameVerifier true
                    if (certMatchesWbuFamily(leaf)) return@hostnameVerifier true

                    // 证书既不在放行列表也不符合 wbu 族系时，通过 sslIssueHandler 弹窗询问用户
                    val prompt = "主机名 $hostname 与证书不匹配（DN: ${leaf.subjectX500Principal.name}）"
                    val allow = runBlocking { sslIssueHandler?.invoke(prompt) ?: false }
                    if (allow) {
                        allowedCertThumbprints.add(thumb)
                        return@hostnameVerifier true
                    }
                }
                false
            }
        }

        return builder.build()
    }

    /** WebVPN 门户登录专用独立客户端：专属于 https://webvpn.wbu.edu.cn，禁止自动重定向，物理隔离连接池。 */
    val portalClient: OkHttpClient by lazy { buildPortalClient() }

    private fun buildPortalClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val req = originalRequest.newBuilder()
                    .header("Host", "webvpn.wbu.edu.cn")
                    .header("User-Agent", authUserAgent())
                    .header("Accept-Language", authAcceptLanguage)
                    .build()
                Log.d("WbuSyncEngine", "PORTAL REQ ${req.method} ${req.url}")
                chain.proceed(req)
            }

        if (useVpn) {
            val trustManager = buildVpnTrustManager()
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            builder.hostnameVerifier { hostname, session ->
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                if (leaf != null) {
                    val thumb = certThumbprint(leaf)
                    if (allowedCertThumbprints.contains(thumb)) return@hostnameVerifier true
                    if (certMatchesWbuFamily(leaf)) return@hostnameVerifier true
                    val prompt = "主机名 $hostname 与证书不匹配（DN: ${leaf.subjectX500Principal.name}）"
                    val allow = runBlocking { sslIssueHandler?.invoke(prompt) ?: false }
                    if (allow) {
                        allowedCertThumbprints.add(thumb)
                        return@hostnameVerifier true
                    }
                }
                false
            }
        }
        return builder.build()
    }

    /** TWFID 校验专用客户端：无 cookieJar，避免混入会话 Cookie；仅用于门户 /por/login_psw.csp 探活。 */
    val twfidProbeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", authUserAgent())
                    .header("Accept-Language", authAcceptLanguage)
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    // ------------------- 基址工厂 -------------------

    /** 教务(jwxt)基址：VPN 时走代理镜像，否则直连。 */
    val jwxtBase: String = if (useVpn) jwxtProxyBase() else "https://jwxt.wbu.edu.cn"

    /** WebVPN 门户基址。 */
    val vpnBase: String = "https://webvpn.wbu.edu.cn"

    private val idsPublicBase: String = "http://ids.wbu.edu.cn"

    /** 统一认证(CAS) service 目标：支持通过「使用固定service获取ticket」开关定制。 */
    val casServiceTarget: String
        get() = if (getUseFixedServiceForTicket(context)) {
            IDS_PERSON_CENTER_SERVICE
        } else {
            "https://jwxt.wbu.edu.cn/admin/caslogin"
        }

    private fun useHttpsWebVpn(): Boolean = getUseHttpsWebVpn(context)

    private fun webVpnScheme(): String = if (useHttpsWebVpn()) "https" else "http"
    private fun webVpnPort(): String = if (useHttpsWebVpn()) "" else ":8118"

    /** 教务(jwxt)代理宿主基址：scheme/端口跟随 WebVPN 设置。供 CAS 回跳重写使用。 */
    fun jwxtProxyBase(): String = "${webVpnScheme()}://jwxt-wbu-edu-cn-s.webvpn.wbu.edu.cn${webVpnPort()}"

    /** 图书馆(opac)基址：WebVPN 模式下走代理子域，校内直连走公网 */
    fun opacBase(): String = if (useVpn) {
        "${webVpnScheme()}://opac-wbu-edu-cn-s.webvpn.wbu.edu.cn${webVpnPort()}"
    } else {
        "https://opac.wbu.edu.cn"
    }

    private fun idsProxyBase(): String = "${webVpnScheme()}://ids-wbu-edu-cn.webvpn.wbu.edu.cn${webVpnPort()}"

    /** ids 认证基址：受「ids 走 WebVPN」开关控制（默认公网）。 */
    fun idsBase(): String = if (getIdsViaWebVpn(context)) idsProxyBase() else idsPublicBase

    /** 二维码认证基址：受「二维码走 WebVPN」开关控制（默认公网）。 */
    fun qrBase(): String = if (getQrViaWebVpn(context)) idsProxyBase() else idsPublicBase

    // ------------------- UA / 语言偏好 -------------------

    /** 认证请求统一使用中文语言偏好（短信语言由 locale cookie 决定）。 */
    val authAcceptLanguage = "zh-CN,zh;q=0.9,en;q=0.5"

    /** Spring CookieLocaleResolver 会话 locale cookie 名，决定短信文案/通道语言。 */
    private val authLocaleCookieName = "org.springframework.web.servlet.i18n.CookieLocaleResolver.LOCALE"

    /** 无头登录使用的 User-Agent。 */
    fun authUserAgent(): String =
        if (getUsePcUserAgent(context)) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

    /** 在发送验证码请求前，向当前认证域注入 locale cookie。 */
    fun injectAuthLocaleCookie(authBase: String) {
        val host = authBase.toHttpUrlOrNull()?.host ?: return
        val value = if (!isSimplifiedChinese(context) && getSendEnglishSms(context)) "en" else "zh_CN"
        cookieStore.removeAll { it.name == authLocaleCookieName }
        cookieStore.add(
            Cookie.Builder()
                .name(authLocaleCookieName)
                .value(value)
                .domain(host)
                .path("/")
                .build()
        )
    }

    // ------------------- TLS 证书身份白名单 -------------------

    /** 系统默认信任管理器（校验证书链是否受信 CA 签名）。 */
    private val defaultTrustManager: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** 用户确认信任的证书指纹（SHA-256），用于本会话内对该证书放行。 */
    private val allowedCertThumbprints = CopyOnWriteArrayList<String>()

    private fun certThumbprint(cert: X509Certificate): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
    }.getOrDefault(cert.publicKey.toString().hashCode().toString())

    /**
     * 证书身份白名单：只认 wbu.edu.cn 这条域名族。
     * 任意公共 CA 为其它域名签发的证书，其 SAN/CN 不命中即拒绝。
     * 解析异常一律返回 false（fail-closed）。
     */
    private fun certMatchesWbuFamily(cert: X509Certificate): Boolean {
        fun allowed(name: String): Boolean {
            val n = name.trim().trimEnd('.')
            return n == "wbu.edu.cn" || n == "*.wbu.edu.cn" ||
                n.endsWith(".wbu.edu.cn") || n.endsWith(".*.wbu.edu.cn")
        }
        return runCatching {
            val sanNames = cert.subjectAlternativeNames.orEmpty()
                .filter { it.size >= 2 && it[0] == 2 }          // 2 = DNS
                .mapNotNull { it[1] as? String }
            if (sanNames.isNotEmpty()) sanNames.any { allowed(it) } else {
                val cnNames = cert.subjectX500Principal.name
                    .split(',')
                    .map { it.trim() }
                    .filter { it.startsWith("CN=", ignoreCase = true) }
                    .map { it.substringAfter('=') }
                cnNames.any { allowed(it) }
            }
        }.getOrDefault(false)
    }

    /** WebVPN 专用 X509ExtendedTrustManager：先按系统信任校验；失败时经 [sslIssueHandler] 询问。 */
    private fun buildVpnTrustManager(): X509ExtendedTrustManager = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            defaultTrustManager.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            checkServerTrustedInternal(chain, authType, null)

        private fun checkServerTrustedInternal(chain: Array<X509Certificate>, authType: String, host: String?) {
            try {
                defaultTrustManager.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                val thumb = chain.firstOrNull()?.let { certThumbprint(it) } ?: "<unknown>"
                if (allowedCertThumbprints.contains(thumb)) return
                val hostPart = host?.takeIf { it.isNotBlank() }?.let { "（主机 $it）" } ?: ""
                val detail = (e.message ?: "证书校验失败") + hostPart
                val allow = runBlocking { sslIssueHandler?.invoke(detail) ?: false }
                if (allow) {
                    allowedCertThumbprints.add(thumb)
                    return
                }
                throw e
            }
        }

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            socket: Socket?,
        ) = checkClientTrusted(chain, authType)

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            socket: Socket?,
        ) = checkServerTrustedInternal(chain, authType, (socket as? SSLSocket)?.handshakeSession?.peerHost)

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            engine: SSLEngine?,
        ) = checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
            checkServerTrustedInternal(chain, authType, engine?.peerHost)

        override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTrustManager.acceptedIssuers
    }

    // ------------------- Cookie 持久化 / 导入 -------------------

    fun isWebVpnCookie(cookie: Cookie): Boolean {
        // jw_uf 是教务系统的身份凭证，TWFID 是 WebVPN 网关门禁通行凭证，
        // CASTGC 是统一认证凭证，PHPSESSID 是图书馆等系统的核心会话凭证，
        // 即便在 WebVPN 镜像域名下也必须允许持久化，以便冷启动/跨组件调用时恢复网络通道
        if (cookie.name == "jw_uf" || cookie.name == "TWFID" || cookie.name == "CASTGC" || cookie.name == "PHPSESSID") return false
        return cookie.domain == "webvpn.wbu.edu.cn" || cookie.domain.endsWith(".webvpn.wbu.edu.cn")
    }

    /** 按服务归属持久化 Cookie：运行时内存 jar 仍全局共用，仅落盘时按服务分桶。 */
    fun persistCookieStore() {
        val buckets = HashMap<String, JSONArray>()
        cookieStore.forEach { cookie ->
            // 不持久化 WebVPN 会话 Cookie，只保留手动 TWFID(prefs 的 webvpn 槽)
            if (isWebVpnCookie(cookie)) return@forEach
            val scope = cookieScopeId(cookie.name, cookie.domain)
            buckets.getOrPut(scope) { JSONArray() }.put(cookieToJson(cookie))
        }
        val editor = prefs.edit()
        allCookieScopeIds().forEach { scope ->
            val key = cookieKeyFor(scope, accountForCookieScope(context, scope))
            val arr = buckets[scope]
            if (arr == null || arr.length() == 0) editor.remove(key) else editor.putString(key, arr.toString())
        }
        editor.apply()
        _credentialChanges.tryEmit(Unit)
    }

    private fun cookieToJson(cookie: Cookie): JSONObject = JSONObject()
        .put("name", cookie.name)
        .put("value", cookie.value)
        .put("domain", cookie.domain)
        .put("path", cookie.path)
        .put("expiresAt", cookie.expiresAt)
        .put("secure", cookie.secure)
        .put("httpOnly", cookie.httpOnly)
        .put("hostOnly", cookie.hostOnly)
        .put("persistent", cookie.persistent)

    private fun parseCookies(raw: String, into: MutableList<Cookie>) {
        val token = JSONTokener(raw).nextValue()
        val arr = when (token) {
            is JSONArray -> token
            else -> JSONArray()
        }
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            val value = obj.optString("value")
            val domain = obj.optString("domain")
            if (name.isBlank() || value.isBlank() || domain.isBlank()) continue

            val builder = Cookie.Builder()
                .name(name)
                .value(value)
                .path(obj.optString("path", "/"))

            if (obj.optBoolean("hostOnly", false)) {
                builder.hostOnlyDomain(domain)
            } else {
                builder.domain(domain)
            }

            val expiresAt = obj.optLong("expiresAt", 0L)
            if (expiresAt > System.currentTimeMillis()) {
                builder.expiresAt(expiresAt)
            }
            if (obj.optBoolean("secure", false)) builder.secure()
            if (obj.optBoolean("httpOnly", false)) builder.httpOnly()

            into.add(builder.build())
        }
    }

    fun restoreCookieStore() {
        val restored = mutableListOf<Cookie>()
        var loaded = false
        var parseFailed = false
        allCookieScopeIds().forEach { scope ->
            val raw = prefs.getString(cookieKeyFor(scope, accountForCookieScope(context, scope)), null) ?: return@forEach
            loaded = true
            try {
                parseCookies(raw, restored)
            } catch (e: JSONException) {
                Log.w("WbuSyncEngine", "Failed to restore cookies for scope=$scope", e)
                parseFailed = true
            }
        }
        // 兼容旧版整包：新分桶尚未建立时回退读取 cookies_json
        if (!loaded) {
            val legacy = prefs.getString(LEGACY_KEY_COOKIES_JSON, null)
            if (legacy != null) {
                try {
                    parseCookies(legacy, restored)
                    loaded = true
                } catch (e: JSONException) {
                    Log.w("WbuSyncEngine", "Failed to restore legacy cookies; clearing persisted session", e)
                    prefs.edit().remove(LEGACY_KEY_COOKIES_JSON).apply()
                    parseFailed = true
                }
            }
        }
        // WebVPN 网关会话 Cookie 不落盘（见 persistCookieStore 的过滤），
        // 恢复时不能把它们一起清掉：Cookie 库是两个 transport 共用的。
        val preservedWebVpn = cookieStore.filter { isWebVpnCookie(it) }
        if (!loaded) return
        if (parseFailed && restored.isEmpty()) {
            cookieStore.clear()
            return
        }
        cookieStore.clear()
        cookieStore.addAll(restored)
        preservedWebVpn.forEach { cookie ->
            if (cookieStore.none { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }) {
                cookieStore.add(cookie)
            }
        }
    }

    /** 清除指定 host 的历史凭证 Cookie（保留 TWFID）。 */
    fun clearAuthCookies(host: String) {
        val removed = cookieStore.removeAll { it.domain == host && it.name != "TWFID" }
        if (removed) {
            Log.d("WbuSyncEngine", "Cleared persisted auth cookies for host=$host")
            persistCookieStore()
        }
    }

    /** 从内存 jar 移除某服务的会话 Cookie（不影响其它服务）。 */
    fun removeServiceCookies(service: CredentialService) {
        cookieStore.removeAll { cookieScopeId(it.name, it.domain) == service.id }
    }

    /** 高级模式手动改凭据：同步改写内存 jar 里该服务的同名 Cookie。 */
    fun updateServiceCookie(service: CredentialService, name: String, value: String) {
        cookieStore.replaceAll { cookie ->
            if (cookie.name == name && cookieScopeId(cookie.name, cookie.domain) == service.id) {
                cookie.newBuilder().value(value).build()
            } else {
                cookie
            }
        }
    }

    fun clearPersistedSession() {
        cookieStore.clear()
        clearAllCookieBuckets(context)
    }

    /** 会话快照：用于登录失败时还原，避免冲掉既有的有效会话。 */
    class SessionSnapshot(val buckets: Map<String, String?>, val twfid: String)

    private var pendingSessionSnapshot: SessionSnapshot? = null

    fun snapshotSessions(): SessionSnapshot {
        val buckets = HashMap<String, String?>()
        allCookieScopeIds().forEach { scope ->
            val key = cookieKeyFor(scope, accountForCookieScope(context, scope))
            buckets[key] = prefs.getString(key, null)
        }
        return SessionSnapshot(buckets, currentTwfid())
    }

    fun restoreSessions(snapshot: SessionSnapshot) {
        val editor = prefs.edit()
        snapshot.buckets.forEach { (key, value) ->
            if (value.isNullOrBlank()) editor.remove(key) else editor.putString(key, value)
        }
        if (snapshot.twfid.isBlank()) {
            editor.remove(twfidKey(context))
        } else {
            editor.putString(twfidKey(context), snapshot.twfid)
        }
        editor.apply()
        restoreCookieStore()
    }

    /** 登录成功后调用：丢弃快照，接受本次登录产生的会话。 */
    fun commitNewLoginSession() {
        pendingSessionSnapshot = null
    }

    /** 登录失败后调用：还原到 [startNewLoginSession] 之前的状态。 */
    fun rollbackNewLoginSession() {
        pendingSessionSnapshot?.let { restoreSessions(it) }
        pendingSessionSnapshot = null
    }

    /**
     * 在登录事务启动时彻底清空上一次的历史会话 Cookie（CASTGC、JSESSIONID、jw_uf 等），
     * 但保留全局配置（例如手动配置的 TWFID）。确保本次登录不受旧会话干扰，必须重新认证一次。
     * 清空前会先做快照，登录失败时可通过 [rollbackNewLoginSession] 还原。
     */
    fun startNewLoginSession() {
        pendingSessionSnapshot = snapshotSessions()
        val manualTwfid = currentTwfid().trim()
        cookieStore.clear()
        clearAllCookieBuckets(context)
        // 彻底清空客户端所有闲置的 TCP/TLS 连接，防止上一次会话的 Keep-Alive 连接被错误复用导致 Host 漂移
        runCatching { client.connectionPool.evictAll() }
        runCatching { portalClient.connectionPool.evictAll() }
        runCatching { twfidProbeClient.connectionPool.evictAll() }
        // 若用户配置了手动 TWFID，恢复注入
        if (manualTwfid.isNotEmpty()) {
            cookieStore.add(
                Cookie.Builder()
                    .name("TWFID")
                    .value(manualTwfid)
                    .domain("webvpn.wbu.edu.cn")
                    .path("/")
                    .build()
            )
        }
        Log.i("WbuAuthTransport", "startNewLoginSession: cleared old cookies and evicted connection pools; manualTwfidPresent=${manualTwfid.isNotEmpty()}")
    }

    /** 从 Android WebView CookieManager 导入 cookies 到 OkHttp cookie jar。 */
    fun importCookiesFromWebView(cookieManager: android.webkit.CookieManager) {
        val urls = listOf(
            "https://webvpn.wbu.edu.cn",
            "https://jwxt.wbu.edu.cn",
            "http://jwxt-wbu-edu-cn-s.webvpn.wbu.edu.cn:8118",
            "http://ids-wbu-edu-cn.webvpn.wbu.edu.cn:8118",
        )
        for (url in urls) {
            val raw = cookieManager.getCookie(url) ?: continue
            val httpUrl = url.toHttpUrlOrNull() ?: continue
            val domain = httpUrl.host
            raw.split(";").forEach { segment ->
                val trimmed = segment.trim()
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx <= 0) return@forEach
                val name = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (name.isBlank()) return@forEach
                val cookie = Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(domain)
                    .path("/")
                    .expiresAt(System.currentTimeMillis() + 24 * 60 * 60 * 1000) // 24h
                    .build()
                cookieStore.removeAll {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                cookieStore.add(cookie)
            }
        }
        persistCookieStore()
        Log.d("WbuSyncEngine", "Imported ${cookieStore.size} cookies from WebView")
    }

    /** 通用绝对地址解析（相对/协议相对/绝对）。 */
    fun resolveAbsoluteUrl(baseUrl: String, maybeRelative: String): String {
        val raw = maybeRelative.trim()
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("//")) {
            val scheme = if (baseUrl.startsWith("https://")) "https:" else "http:"
            return "$scheme$raw"
        }
        val base = baseUrl.toHttpUrlOrNull() ?: return raw
        return base.resolve(raw)?.toString() ?: raw
    }

    /** 判断响应是否为 HTML 登录页。 */
    fun looksLikeHtml(content: String): Boolean {
        val trimmed = content.trimStart()
        return trimmed.startsWith("<html", ignoreCase = true) ||
            trimmed.startsWith("<!doctype html", ignoreCase = true)
    }

    // ------------------- 实例便捷访问（读取静态偏好） -------------------

    /** 当前手动 TWFID（prefs 的 webvpn 槽）。 */
    fun currentTwfid(): String = prefs.getString(twfidKey(context), "").orEmpty()

    fun clearTwfidPref() {
        prefs.edit().remove(twfidKey(context)).apply()
    }

    // ====================== 静态偏好访问器（供各业务模块共用） ======================

    companion object {
        const val PREFS_NAME = "wbu_sync_auth"

        // ── 旧版扁平 key（迁移来源，保留不删，绝不销毁用户数据） ──
        private const val LEGACY_KEY_COOKIES_JSON = "cookies_json"
        private const val LEGACY_KEY_REMEMBER_PASSWORD = "remember_password"
        private const val LEGACY_KEY_ENCRYPTED_PASSWORD = "encrypted_password"
        private const val LEGACY_KEY_PASSWORD_CRYPTO_IV = "password_crypto_iv"
        private const val LEGACY_KEY_SAVED_AUTH_MODE = "saved_auth_mode"
        private const val LEGACY_KEY_REMEMBER_VPN_PASSWORD = "remember_vpn_password"
        private const val LEGACY_KEY_ENCRYPTED_VPN_PASSWORD = "encrypted_vpn_password"
        private const val LEGACY_KEY_VPN_PASSWORD_CRYPTO_IV = "vpn_password_crypto_iv"
        private const val LEGACY_KEY_LAST_STUDENT_ID = "last_student_id"
        private const val LEGACY_KEY_TWFID = "twfid"
        private const val KEY_CRED_KEYS_MIGRATED = "cred_keys_migrated"

        /** VPN / 直连两个 transport 共用的内存 Cookie 库（落盘仍按服务 scope 分桶）。 */
        private val sharedCookieStore = CopyOnWriteArrayList<Cookie>()

        /** 是否已从落盘恢复过：只需一次，否则第二个 transport 建库时会清空共享内存。 */
        @Volatile
        private var cookieStoreRestored = false

        private val _credentialChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /**
         * 凭据变更信号：任何一次 Cookie 落盘、TWFID 写入或清除都会发一次。
         * 账号页 / 登录 Sheet 订阅它来同步刷新，避免各自 remember 的状态发霉。
         */
        val credentialChanges: SharedFlow<Unit> = _credentialChanges.asSharedFlow()

        // ── 全局（非账号维度）key ──
        private const val KEY_LAST_USE_VPN = "last_use_vpn"
        private const val KEY_LAST_USE_VPN_SET = "last_use_vpn_set"
        private const val KEY_USE_WEBVIEW_VPN_MANUAL_MODE = "use_webview_vpn_manual_mode"
        private const val KEY_IDS_VIA_WEBVPN = "ids_via_webvpn"
        private const val KEY_QR_VIA_WEBVPN = "qr_via_webvpn"
        private const val KEY_QR_SCAN_ENGINE = "qr_scan_engine"
        private const val KEY_SEND_ENGLISH_SMS = "send_english_sms"
        private const val KEY_USE_PC_USER_AGENT = "use_pc_user_agent"
        private const val KEY_SKIP_CAMPUS_CHECK = "skip_campus_check"
        private const val KEY_KEEP_TEACHER_ID = "keep_teacher_id"
        private const val KEY_KEEP_BUILDING = "keep_building"
        private const val KEY_SELECT_SEMESTER_ON_IMPORT = "select_semester_on_import"
        private const val KEY_USE_HTTPS_WEBVPN = "use_https_webvpn"
        private const val KEY_IDS_ADDR_NOT_FROM_JWXT = "ids_addr_not_from_jwxt"
        private const val KEY_NO_INDEXMAIN_VERIFY = "no_indexmain_verify"
        private const val KEY_FORCE_FETCH_STUDENT_ID_BEFORE_VPN = "force_fetch_student_id_before_vpn"
        private const val KEY_USE_FIXED_SERVICE_FOR_TICKET = "use_fixed_service_for_ticket"
        private const val KEY_CREDENTIAL_ADVANCED_MODE = "credential_advanced_mode"
        private const val KEY_CREDENTIAL_AUTO_VERIFY = "credential_auto_verify_enabled"
        const val IDS_PERSON_CENTER_SERVICE = "http://ids.wbu.edu.cn/personalInfo/personCenter/index.html"
        const val MAX_CAPTCHA_ATTEMPTS = 5

        // ── 凭据字段名（按「服务类型 × 账号」拼接 key，形如 password@ids@primary） ──
        private const val FIELD_PASSWORD = "password"
        private const val FIELD_PASSWORD_IV = "password_iv"
        private const val FIELD_REMEMBER_PASSWORD = "remember_password"
        private const val FIELD_COOKIES = "cookies"
        private const val FIELD_TWFID = "twfid"
        private const val FIELD_STUDENT_ID = "student_id"
        private const val FIELD_AUTH_MODE = "auth_mode"
        private const val FIELD_ACCOUNT_NAME = "account_name"

        /** 未归属任何服务的 Cookie（如 locale 等）统一落到共享桶。 */
        private const val SHARED_COOKIE_SCOPE = "shared"

        private fun prefsOf(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private fun activeAccountKey(service: CredentialService) = "active_account@${service.id}"
        private fun accountsKey(service: CredentialService) = "accounts@${service.id}"
        private fun fieldKey(field: String, service: CredentialService, account: String) =
            "$field@${service.id}@$account"
        private fun cookieKeyFor(scopeId: String, account: String) = "$FIELD_COOKIES@$scopeId@$account"

        private fun twfidKey(context: Context) =
            fieldKey(FIELD_TWFID, CredentialService.WEBVPN, activeAccount(context, CredentialService.WEBVPN))

        /** 某服务当前激活的账号（单用户阶段恒为 "primary"）。 */
        fun activeAccount(context: Context, service: CredentialService): String =
            prefsOf(context).getString(activeAccountKey(service), null)
                ?.takeIf { it.isNotBlank() } ?: CredentialService.DEFAULT_ACCOUNT

        /** 某服务已登记的账号列表（预埋多账号；单用户下仅返回当前账号）。 */
        fun listAccounts(context: Context, service: CredentialService): List<String> {
            val raw = prefsOf(context).getString(accountsKey(service), null)
                ?: return listOf(activeAccount(context, service))
            val parsed = runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }.getOrDefault(emptyList())
            return parsed.ifEmpty { listOf(activeAccount(context, service)) }
        }

        /** 切换某服务的当前账号（预埋；当前仅更新指针与账号登记）。 */
        fun switchAccount(context: Context, service: CredentialService, account: String) {
            if (account.isBlank()) return
            val accounts = (listAccounts(context, service) + account).distinct()
            prefsOf(context).edit()
                .putString(activeAccountKey(service), account)
                .putString(accountsKey(service), JSONArray(accounts).toString())
                .apply()
        }

        /** 某服务当前账号的自定义名称（null 表示未命名）。与登录身份无关，可随意修改。 */
        fun getAccountName(context: Context, service: CredentialService): String? =
            prefsOf(context).getString(fieldKey(FIELD_ACCOUNT_NAME, service, activeAccount(context, service)), null)

        fun setAccountName(context: Context, service: CredentialService, name: String) {
            prefsOf(context).edit()
                .putString(fieldKey(FIELD_ACCOUNT_NAME, service, activeAccount(context, service)), name.trim())
                .apply()
        }

        private fun accountForCookieScope(context: Context, scopeId: String): String {
            val service = CredentialService.fromId(scopeId)
            return if (service != null) activeAccount(context, service) else CredentialService.DEFAULT_ACCOUNT
        }

        private fun allCookieScopeIds(): List<String> =
            CredentialService.entries.map { it.id } + SHARED_COOKIE_SCOPE

        /** 依据 Cookie 名/域判定其归属的服务 scope id。 */
        private fun cookieScopeId(name: String, domain: String): String = when {
            name == "CASTGC" -> CredentialService.UNIFIED_AUTH.id
            name == "jw_uf" -> CredentialService.JIAOWU.id
            name == "TWFID" -> CredentialService.WEBVPN.id
            name == "PHPSESSID" -> CredentialService.LIBRARY.id
            name == "JSESSIONID" -> when {
                domain.contains("jwxt") -> CredentialService.JIAOWU.id
                domain.contains("opac") -> CredentialService.LIBRARY.id
                domain.contains("ids") -> CredentialService.UNIFIED_AUTH.id
                else -> SHARED_COOKIE_SCOPE
            }
            else -> SHARED_COOKIE_SCOPE
        }

        private fun clearAllCookieBuckets(context: Context) {
            val editor = prefsOf(context).edit()
            allCookieScopeIds().forEach { scope ->
                editor.remove(cookieKeyFor(scope, accountForCookieScope(context, scope)))
            }
            editor.remove(LEGACY_KEY_COOKIES_JSON).apply()
        }

        /** 某服务当前是否持有落盘的会话（Cookie 桶非空）。 */
        fun hasServiceSession(context: Context, service: CredentialService): Boolean {
            val key = cookieKeyFor(service.id, activeAccount(context, service))
            return !prefsOf(context).getString(key, null).isNullOrBlank()
        }

        /** 某服务落盘的会话 Cookie 原始 JSON（供高级模式查看凭据，如 jw_uf）。 */
        fun cookieBucketJson(context: Context, service: CredentialService): String? =
            prefsOf(context).getString(cookieKeyFor(service.id, activeAccount(context, service)), null)

        /**
         * 高级模式手动改凭据：改写某服务落盘会话里某个 Cookie 的值。
         * @param value 新的 Cookie 值（不含 `名=值` 前缀）；传空串则把该 Cookie 值清空。
         */
        fun updateServiceCookie(context: Context, service: CredentialService, name: String, value: String) {
            val key = cookieKeyFor(service.id, activeAccount(context, service))
            val raw = prefsOf(context).getString(key, null) ?: return
            val updated = runCatching {
                val arr = JSONArray(raw)
                var hit = false
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (obj.optString("name") == name) {
                        obj.put("value", value.trim())
                        hit = true
                    }
                }
                if (hit) arr.toString() else null
            }.getOrNull() ?: return
            prefsOf(context).edit().putString(key, updated).apply()
            getShared(context, false).updateServiceCookie(service, name, value.trim())
            getShared(context, true).updateServiceCookie(service, name, value.trim())
        }

        /** 高级模式开关（跨页面记住）。 */
        fun getCredentialAdvancedMode(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_CREDENTIAL_ADVANCED_MODE, false)

        fun setCredentialAdvancedMode(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CREDENTIAL_ADVANCED_MODE, enabled).apply()
        }

        /** 清除某服务的会话（Cookie 桶 + 内存 jar 内该服务的 Cookie）。 */
        fun clearServiceSession(context: Context, service: CredentialService) {
            val key = cookieKeyFor(service.id, activeAccount(context, service))
            prefsOf(context).edit().remove(key).apply()
            getShared(context, false).removeServiceCookies(service)
            getShared(context, true).removeServiceCookies(service)
        }

        fun isCredentialAutoVerifyEnabled(context: Context): Boolean =
            prefsOf(context).getBoolean(KEY_CREDENTIAL_AUTO_VERIFY, true)

        fun setCredentialAutoVerifyEnabled(context: Context, enabled: Boolean) {
            prefsOf(context).edit().putBoolean(KEY_CREDENTIAL_AUTO_VERIFY, enabled).apply()
        }

        /**
         * 一次性迁移：旧版扁平 key → 「服务类型 × 账号」分桶 key。
         * 一律**复制**，保留旧 key，确保升级不丢失任何凭据。
         */
        fun migrateLegacyCredentialKeysOnce(context: Context) {
            val prefs = prefsOf(context)
            if (prefs.getBoolean(KEY_CRED_KEYS_MIGRATED, false)) return
            val editor = prefs.edit()
            val acc = CredentialService.DEFAULT_ACCOUNT

            fun copyString(legacyKey: String, field: String, service: CredentialService) {
                val value = prefs.getString(legacyKey, null) ?: return
                val target = fieldKey(field, service, acc)
                if (prefs.getString(target, null).isNullOrBlank()) editor.putString(target, value)
            }

            fun copyBoolean(legacyKey: String, field: String, service: CredentialService) {
                if (!prefs.contains(legacyKey)) return
                val target = fieldKey(field, service, acc)
                if (!prefs.contains(target)) editor.putBoolean(target, prefs.getBoolean(legacyKey, false))
            }

            copyString(LEGACY_KEY_ENCRYPTED_PASSWORD, FIELD_PASSWORD, CredentialService.UNIFIED_AUTH)
            copyString(LEGACY_KEY_PASSWORD_CRYPTO_IV, FIELD_PASSWORD_IV, CredentialService.UNIFIED_AUTH)
            copyBoolean(LEGACY_KEY_REMEMBER_PASSWORD, FIELD_REMEMBER_PASSWORD, CredentialService.UNIFIED_AUTH)
            copyString(LEGACY_KEY_ENCRYPTED_VPN_PASSWORD, FIELD_PASSWORD, CredentialService.WEBVPN)
            copyString(LEGACY_KEY_VPN_PASSWORD_CRYPTO_IV, FIELD_PASSWORD_IV, CredentialService.WEBVPN)
            copyBoolean(LEGACY_KEY_REMEMBER_VPN_PASSWORD, FIELD_REMEMBER_PASSWORD, CredentialService.WEBVPN)
            copyString(LEGACY_KEY_TWFID, FIELD_TWFID, CredentialService.WEBVPN)
            copyString(LEGACY_KEY_LAST_STUDENT_ID, FIELD_STUDENT_ID, CredentialService.JIAOWU)
            copyString(LEGACY_KEY_SAVED_AUTH_MODE, FIELD_AUTH_MODE, CredentialService.JIAOWU)

            val legacyCookies = prefs.getString(LEGACY_KEY_COOKIES_JSON, null)
            if (!legacyCookies.isNullOrBlank()) {
                splitCookiesByScope(legacyCookies).forEach { (scopeId, json) ->
                    val key = cookieKeyFor(scopeId, acc)
                    if (prefs.getString(key, null).isNullOrBlank()) editor.putString(key, json)
                }
            }

            editor.putBoolean(KEY_CRED_KEYS_MIGRATED, true).apply()
        }

        private fun splitCookiesByScope(raw: String): Map<String, String> {
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyMap()
            val buckets = HashMap<String, JSONArray>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                val domain = obj.optString("domain")
                if (name.isBlank() || domain.isBlank()) continue
                buckets.getOrPut(cookieScopeId(name, domain)) { JSONArray() }.put(obj)
            }
            return buckets.mapValues { it.value.toString() }
        }

        @Volatile
        private var sharedDirectTransport: WbuAuthTransport? = null
        @Volatile
        private var sharedVpnTransport: WbuAuthTransport? = null

        fun getShared(context: Context, useVpn: Boolean): WbuAuthTransport {
            val appCtx = context.applicationContext ?: context
            return if (useVpn) {
                sharedVpnTransport ?: synchronized(this) {
                    sharedVpnTransport ?: WbuAuthTransport(appCtx, true).also {
                        // Cookie 库是进程级共享的，只允许恢复一次
                        if (!cookieStoreRestored) {
                            it.restoreCookieStore()
                            cookieStoreRestored = true
                        }
                        sharedVpnTransport = it
                    }
                }
            } else {
                sharedDirectTransport ?: synchronized(this) {
                    sharedDirectTransport ?: WbuAuthTransport(appCtx, false).also {
                        if (!cookieStoreRestored) {
                            it.restoreCookieStore()
                            cookieStoreRestored = true
                        }
                        sharedDirectTransport = it
                    }
                }
            }
        }

        fun prefKeyLastStudentId(context: Context): String =
            fieldKey(FIELD_STUDENT_ID, CredentialService.JIAOWU, activeAccount(context, CredentialService.JIAOWU))

        fun getIdsViaWebVpn(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_IDS_VIA_WEBVPN, false)

        fun setIdsViaWebVpn(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_IDS_VIA_WEBVPN, enabled).apply()
        }

        fun getQrViaWebVpn(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_QR_VIA_WEBVPN, false)

        fun setQrViaWebVpn(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_QR_VIA_WEBVPN, enabled).apply()
        }

        /** 扫一扫使用的二维码解码引擎（默认 ML Kit，识别不理想时切 ZXing）。 */
        fun getQrScanEngine(context: Context): QrScanEngine {
            val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_QR_SCAN_ENGINE, null) ?: return QrScanEngine.DEFAULT
            return try {
                QrScanEngine.valueOf(name)
            } catch (e: Exception) {
                QrScanEngine.DEFAULT
            }
        }

        fun setQrScanEngine(context: Context, engine: QrScanEngine) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_QR_SCAN_ENGINE, engine.name).apply()
        }

        fun getUsePcUserAgent(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_USE_PC_USER_AGENT, false)

        fun setUsePcUserAgent(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USE_PC_USER_AGENT, enabled).apply()
        }

        fun getSkipCampusCheck(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SKIP_CAMPUS_CHECK, false)

        fun setSkipCampusCheck(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SKIP_CAMPUS_CHECK, enabled).apply()
        }

        fun getKeepTeacherId(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_TEACHER_ID, false)

        fun setKeepTeacherId(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_KEEP_TEACHER_ID, enabled).apply()
        }

        fun getKeepBuilding(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_BUILDING, false)

        fun setKeepBuilding(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_KEEP_BUILDING, enabled).apply()
        }

        fun getSelectSemesterOnImport(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SELECT_SEMESTER_ON_IMPORT, false)

        fun setSelectSemesterOnImport(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SELECT_SEMESTER_ON_IMPORT, enabled).apply()
        }

        /**
         * 仅读本地 Cookie 罐判断该服务是否还剩「值得一试」的登录态（不联网）。
         *
         * 用于数据请求前的快速短路：只有确定什么都没存时才拦。Cookie 存在但已过期不会被拦，
         * 仍交由服务端的 [WbuSessionExpiredException] 判定，避免网络抖动误伤。
         */
        fun hasLocalSession(
            context: Context,
            service: CredentialService,
            useVpn: Boolean = WbuSyncEngine.getSavedUseVpn(context) ?: false,
        ): Boolean {
            val jar = getShared(context, useVpn).cookieStore
            fun has(name: String) = jar.any { it.name == name && it.value.isNotBlank() }
            return when (service) {
                CredentialService.JIAOWU -> has("jw_uf") || has("CASTGC") || (useVpn && has("TWFID"))
                CredentialService.LIBRARY -> has("PHPSESSID") || has("CASTGC")
                CredentialService.UNIFIED_AUTH -> has("CASTGC")
                CredentialService.WEBVPN -> has("TWFID")
                else -> true
            }
        }

        fun getTwfid(context: Context): String =
            prefsOf(context).getString(twfidKey(context), "").orEmpty()

        fun setTwfid(context: Context, value: String) {
            prefsOf(context)
                .edit().putString(twfidKey(context), value.trim()).apply()
            _credentialChanges.tryEmit(Unit)
        }

        fun clearTwfid(context: Context) {
            prefsOf(context)
                .edit().remove(twfidKey(context)).apply()
            _credentialChanges.tryEmit(Unit)
        }

        fun getUseHttpsWebVpn(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_USE_HTTPS_WEBVPN, false)

        fun setUseHttpsWebVpn(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USE_HTTPS_WEBVPN, enabled).apply()
        }

        fun hasPersistedSession(context: Context): Boolean {
            val prefs = prefsOf(context)
            if (!prefs.getString(LEGACY_KEY_COOKIES_JSON, null).isNullOrBlank()) return true
            return allCookieScopeIds().any { scope ->
                !prefs.getString(cookieKeyFor(scope, accountForCookieScope(context, scope)), null).isNullOrBlank()
            }
        }

        fun getSavedUseVpn(context: Context): Boolean? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_LAST_USE_VPN_SET, false)) return null
            return prefs.getBoolean(KEY_LAST_USE_VPN, false)
        }

        fun setSavedUseVpn(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LAST_USE_VPN, enabled)
                .putBoolean(KEY_LAST_USE_VPN_SET, true)
                .apply()
        }

        fun getSavedStudentId(context: Context): String =
            prefsOf(context)
                .getString(prefKeyLastStudentId(context), "").orEmpty()

        fun setSavedStudentId(context: Context, studentId: String) {
            prefsOf(context)
                .edit()
                .putString(prefKeyLastStudentId(context), studentId)
                .apply()
        }

        fun shouldUseManualWebViewForVpn(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_USE_WEBVIEW_VPN_MANUAL_MODE, false)

        fun setManualWebViewForVpn(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USE_WEBVIEW_VPN_MANUAL_MODE, enabled).apply()
        }

        /** 「IDS addr not from Jwxt」：为 true 时不从教务登录页发现 CAS 链接，直接用 ids 基址构造。默认关闭。 */
        fun getIdsAddrNotFromJwxt(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_IDS_ADDR_NOT_FROM_JWXT, false)

        fun setIdsAddrNotFromJwxt(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_IDS_ADDR_NOT_FROM_JWXT, enabled).apply()
        }

        /** 「no indexMain verify」：为 true 时不解析/打开 indexMain，仅校验会话 cookie + 拿学号。默认关闭。 */
        fun getNoIndexMainVerify(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_NO_INDEXMAIN_VERIFY, false)

        fun setNoIndexMainVerify(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_NO_INDEXMAIN_VERIFY, enabled).apply()
        }

        /** 「登录WebVPN前必须获取学号」：为 true 时无论输入格式如何均先从 ids 换取学号。默认关闭。 */
        fun getForceFetchStudentIdBeforeVpn(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FORCE_FETCH_STUDENT_ID_BEFORE_VPN, false)

        fun setForceFetchStudentIdBeforeVpn(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FORCE_FETCH_STUDENT_ID_BEFORE_VPN, enabled).apply()
        }

        /** 「使用固定service获取ticket」：为 true 时提取学号使用同源个人中心 service。默认关闭。 */
        fun getUseFixedServiceForTicket(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_USE_FIXED_SERVICE_FOR_TICKET, false)

        fun setUseFixedServiceForTicket(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USE_FIXED_SERVICE_FOR_TICKET, enabled).apply()
        }

        fun isSimplifiedChinese(context: Context): Boolean {
            val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            return if (tags.isBlank()) {
                val loc = Locale.getDefault()
                loc.language.equals("zh", ignoreCase = true) &&
                    !loc.country.equals("TW", ignoreCase = true) &&
                    loc.script != "Hant"
            } else {
                val lower = tags.lowercase()
                lower.startsWith("zh-cn") || lower.startsWith("zh-hans")
            }
        }

        fun getSendEnglishSms(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_SEND_ENGLISH_SMS)) return false
            return prefs.getBoolean(KEY_SEND_ENGLISH_SMS, false)
        }

        fun setSendEnglishSms(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SEND_ENGLISH_SMS, enabled).apply()
        }

        // ── 密码安全加解密存储 (Android KeyStore AES-GCM) ──
        private const val KEYSTORE_ALIAS = "ClassFlowWbuCredentialKey"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"

        private fun getSecretKey(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            keyStore.getKey(KEYSTORE_ALIAS, null)?.let { return it as SecretKey }

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }

        fun isRememberPasswordEnabled(
            context: Context,
            service: CredentialService = CredentialService.UNIFIED_AUTH
        ): Boolean {
            val key = fieldKey(FIELD_REMEMBER_PASSWORD, service, activeAccount(context, service))
            return prefsOf(context).getBoolean(key, false)
        }

        fun setRememberPasswordEnabled(context: Context, service: CredentialService, enabled: Boolean) {
            val key = fieldKey(FIELD_REMEMBER_PASSWORD, service, activeAccount(context, service))
            prefsOf(context).edit().putBoolean(key, enabled).apply()
            if (!enabled) {
                clearSavedPassword(context, service)
            }
        }

        /** 兼容旧调用：等价于统一认证服务。 */
        fun setRememberPasswordEnabled(context: Context, enabled: Boolean) =
            setRememberPasswordEnabled(context, CredentialService.UNIFIED_AUTH, enabled)

        /** 该服务自身是否持有密码槽（不含教务对统一认证的回退）。 */
        fun hasOwnPassword(context: Context, service: CredentialService): Boolean {
            val acc = activeAccount(context, service)
            val prefs = prefsOf(context)
            return !prefs.getString(fieldKey(FIELD_PASSWORD, service, acc), null).isNullOrBlank() &&
                !prefs.getString(fieldKey(FIELD_PASSWORD_IV, service, acc), null).isNullOrBlank()
        }

        fun hasSavedPassword(
            context: Context,
            service: CredentialService = CredentialService.UNIFIED_AUTH
        ): Boolean {
            if (hasOwnPassword(context, service) && isRememberPasswordEnabled(context, service)) return true
            // 教务系统：自身密码槽为空时回退统一认证（两者当前共用同一密码）
            if (service == CredentialService.JIAOWU) return hasSavedPassword(context, CredentialService.UNIFIED_AUTH)
            return false
        }

        fun getSavedPassword(
            context: Context,
            service: CredentialService = CredentialService.UNIFIED_AUTH
        ): String? {
            if (isRememberPasswordEnabled(context, service)) {
                decryptStoredPassword(context, service)?.let { return it }
            }
            if (service == CredentialService.JIAOWU) {
                return getSavedPassword(context, CredentialService.UNIFIED_AUTH)
            }
            return null
        }

        private fun decryptStoredPassword(context: Context, service: CredentialService): String? {
            val acc = activeAccount(context, service)
            val prefs = prefsOf(context)
            val encryptedBase64 = prefs.getString(fieldKey(FIELD_PASSWORD, service, acc), null) ?: return null
            val ivBase64 = prefs.getString(fieldKey(FIELD_PASSWORD_IV, service, acc), null) ?: return null
            return decrypt(encryptedBase64, ivBase64)
        }

        /**
         * 读取已存储的明文密码（不受「记住密码」开关影响），用于高级模式的查看/编辑。
         * 教务系统在自身密码槽为空时回退统一认证。
         */
        fun getStoredPassword(
            context: Context,
            service: CredentialService = CredentialService.UNIFIED_AUTH
        ): String? {
            decryptStoredPassword(context, service)?.let { if (it.isNotBlank()) return it }
            if (service == CredentialService.JIAOWU) {
                return getStoredPassword(context, CredentialService.UNIFIED_AUTH)
            }
            return null
        }

        fun savePassword(context: Context, service: CredentialService, password: String) {
            if (password.isBlank()) return
            val acc = activeAccount(context, service)
            encryptInto(
                context,
                fieldKey(FIELD_PASSWORD, service, acc),
                fieldKey(FIELD_PASSWORD_IV, service, acc),
                password
            )
        }

        /** 兼容旧调用：等价于统一认证服务。 */
        fun savePassword(context: Context, password: String) =
            savePassword(context, CredentialService.UNIFIED_AUTH, password)

        fun clearSavedPassword(
            context: Context,
            service: CredentialService = CredentialService.UNIFIED_AUTH
        ) {
            val acc = activeAccount(context, service)
            val editor = prefsOf(context).edit()
                .remove(fieldKey(FIELD_PASSWORD, service, acc))
                .remove(fieldKey(FIELD_PASSWORD_IV, service, acc))
            if (service == CredentialService.UNIFIED_AUTH) {
                // 保持旧行为：清除统一认证密码时，同时清除教务记住的登录方式与教务密码槽
                val jwAcc = activeAccount(context, CredentialService.JIAOWU)
                editor.remove(fieldKey(FIELD_AUTH_MODE, CredentialService.JIAOWU, jwAcc))
                editor.remove(fieldKey(FIELD_PASSWORD, CredentialService.JIAOWU, jwAcc))
                editor.remove(fieldKey(FIELD_PASSWORD_IV, CredentialService.JIAOWU, jwAcc))
            }
            editor.apply()
        }

        private fun encryptInto(context: Context, dataKey: String, ivKey: String, plain: String) {
            try {
                val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
                val encryptedBase64 = Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
                val ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
                prefsOf(context).edit()
                    .putString(dataKey, encryptedBase64)
                    .putString(ivKey, ivBase64)
                    .apply()
            } catch (e: Exception) {
                Log.w("WbuAuthTransport", "Failed to encrypt and save credential", e)
            }
        }

        private fun decrypt(encryptedBase64: String, ivBase64: String): String? = try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val ivBytes = Base64.decode(ivBase64, Base64.NO_WRAP)
            val gcmSpec = GCMParameterSpec(128, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), gcmSpec)
            val decryptedBytes = cipher.doFinal(Base64.decode(encryptedBase64, Base64.NO_WRAP))
            String(decryptedBytes, Charsets.UTF_8).replace("\u0000", "").trim()
        } catch (e: Exception) {
            Log.w("WbuAuthTransport", "Failed to decrypt saved credential", e)
            null
        }

        fun getSavedAuthMode(context: Context): WbuAuthMode {
            val key = fieldKey(FIELD_AUTH_MODE, CredentialService.JIAOWU, activeAccount(context, CredentialService.JIAOWU))
            val name = prefsOf(context).getString(key, null) ?: return WbuAuthMode.UNIFIED_CAS
            return try {
                WbuAuthMode.valueOf(name)
            } catch (e: Exception) {
                WbuAuthMode.UNIFIED_CAS
            }
        }

        fun setSavedAuthMode(context: Context, authMode: WbuAuthMode) {
            val key = fieldKey(FIELD_AUTH_MODE, CredentialService.JIAOWU, activeAccount(context, CredentialService.JIAOWU))
            prefsOf(context).edit().putString(key, authMode.name).apply()
        }

        fun isRememberVpnPasswordEnabled(context: Context): Boolean =
            isRememberPasswordEnabled(context, CredentialService.WEBVPN)

        fun setRememberVpnPasswordEnabled(context: Context, enabled: Boolean) =
            setRememberPasswordEnabled(context, CredentialService.WEBVPN, enabled)

        fun hasSavedVpnPassword(context: Context): Boolean =
            hasSavedPassword(context, CredentialService.WEBVPN)

        fun getSavedVpnPassword(context: Context): String? =
            getSavedPassword(context, CredentialService.WEBVPN)

        fun saveVpnPassword(context: Context, password: String) =
            savePassword(context, CredentialService.WEBVPN, password)

        fun clearSavedVpnPassword(context: Context) =
            clearSavedPassword(context, CredentialService.WEBVPN)
    }
}
