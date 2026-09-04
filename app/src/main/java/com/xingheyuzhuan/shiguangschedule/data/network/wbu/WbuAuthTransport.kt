package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
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

    val cookieStore = CopyOnWriteArrayList<Cookie>()

    /** WebVPN TLS 证书校验异常回调（见 WbuSyncEngine 同名属性说明）。 */
    @Volatile
    var sslIssueHandler: (suspend (message: String) -> Boolean)? = null

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                cookieStore.removeAll {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                if (!cookie.expiresAt.let { expiresAt -> expiresAt <= System.currentTimeMillis() }) {
                    cookieStore.add(cookie)
                }
            }
            persistCookieStore()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val validCookies = cookieStore.filter { it.expiresAt > now }
            cookieStore.removeAll { it.expiresAt <= now }
            return validCookies.filter { it.matches(url) }
        }
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
                Log.d("WbuSyncEngine", "REQ ${req.method} ${req.url}")
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

    fun isWebVpnCookie(cookie: Cookie): Boolean =
        cookie.domain == "webvpn.wbu.edu.cn" || cookie.domain.endsWith(".webvpn.wbu.edu.cn")

    fun persistCookieStore() {
        val array = JSONArray()
        cookieStore.forEach { cookie ->
            // 不持久化 WebVPN 会话 Cookie，只保留手动 TWFID(prefs KEY_TWFID)
            if (isWebVpnCookie(cookie)) return@forEach
            val obj = JSONObject()
                .put("name", cookie.name)
                .put("value", cookie.value)
                .put("domain", cookie.domain)
                .put("path", cookie.path)
                .put("expiresAt", cookie.expiresAt)
                .put("secure", cookie.secure)
                .put("httpOnly", cookie.httpOnly)
                .put("hostOnly", cookie.hostOnly)
                .put("persistent", cookie.persistent)
            array.put(obj)
        }
        prefs.edit().putString(KEY_COOKIES_JSON, array.toString()).apply()
    }

    fun restoreCookieStore() {
        val raw = prefs.getString(KEY_COOKIES_JSON, null) ?: return
        try {
            val token = JSONTokener(raw).nextValue()
            val arr = when (token) {
                is JSONArray -> token
                else -> JSONArray()
            }
            val restored = mutableListOf<Cookie>()
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

                if (obj.optBoolean("persistent", false)) {
                    val expiresAt = obj.optLong("expiresAt", 0L)
                    if (expiresAt > System.currentTimeMillis()) builder.expiresAt(expiresAt)
                }
                if (obj.optBoolean("secure", false)) builder.secure()
                if (obj.optBoolean("httpOnly", false)) builder.httpOnly()

                restored.add(builder.build())
            }
            cookieStore.clear()
            cookieStore.addAll(restored)
        } catch (e: JSONException) {
            Log.w("WbuSyncEngine", "Failed to restore cookies; clearing persisted session", e)
            prefs.edit().remove(KEY_COOKIES_JSON).apply()
            cookieStore.clear()
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

    fun clearPersistedSession() {
        cookieStore.clear()
        prefs.edit().remove(KEY_COOKIES_JSON).apply()
    }

    /**
     * 在登录事务启动时彻底清空上一次的历史会话 Cookie（CASTGC、JSESSIONID、jw_uf 等），
     * 但保留全局配置（例如手动配置的 TWFID）。确保本次登录不受旧会话干扰，必须重新认证一次。
     */
    fun startNewLoginSession() {
        val manualTwfid = currentTwfid().trim()
        cookieStore.clear()
        prefs.edit().remove(KEY_COOKIES_JSON).apply()
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

    /** 当前手动 TWFID（prefs）。 */
    fun currentTwfid(): String = prefs.getString(KEY_TWFID, "").orEmpty()

    fun clearTwfidPref() {
        prefs.edit().remove(KEY_TWFID).apply()
    }

    // ====================== 静态偏好访问器（供各业务模块共用） ======================

    companion object {
        const val PREFS_NAME = "wbu_sync_auth"

        private const val KEY_COOKIES_JSON = "cookies_json"
        private const val KEY_LAST_USE_VPN = "last_use_vpn"
        private const val KEY_LAST_USE_VPN_SET = "last_use_vpn_set"
        private const val KEY_REMEMBER_PASSWORD = "remember_password"
        private const val KEY_ENCRYPTED_PASSWORD = "encrypted_password"
        private const val KEY_PASSWORD_CRYPTO_IV = "password_crypto_iv"
        private const val KEY_LAST_STUDENT_ID = "last_student_id"
        private const val KEY_USE_WEBVIEW_VPN_MANUAL_MODE = "use_webview_vpn_manual_mode"
        private const val KEY_IDS_VIA_WEBVPN = "ids_via_webvpn"
        private const val KEY_QR_VIA_WEBVPN = "qr_via_webvpn"
        private const val KEY_SEND_ENGLISH_SMS = "send_english_sms"
        private const val KEY_USE_PC_USER_AGENT = "use_pc_user_agent"
        private const val KEY_SKIP_CAMPUS_CHECK = "skip_campus_check"
        private const val KEY_KEEP_TEACHER_ID = "keep_teacher_id"
        private const val KEY_KEEP_BUILDING = "keep_building"
        private const val KEY_SELECT_SEMESTER_ON_IMPORT = "select_semester_on_import"
        private const val KEY_TWFID = "twfid"
        private const val KEY_USE_HTTPS_WEBVPN = "use_https_webvpn"
        private const val KEY_IDS_ADDR_NOT_FROM_JWXT = "ids_addr_not_from_jwxt"
        private const val KEY_NO_INDEXMAIN_VERIFY = "no_indexmain_verify"
        private const val KEY_FORCE_FETCH_STUDENT_ID_BEFORE_VPN = "force_fetch_student_id_before_vpn"
        private const val KEY_USE_FIXED_SERVICE_FOR_TICKET = "use_fixed_service_for_ticket"
        const val IDS_PERSON_CENTER_SERVICE = "http://ids.wbu.edu.cn/personalInfo/personCenter/index.html"
        const val MAX_CAPTCHA_ATTEMPTS = 5

        fun prefKeyLastStudentId(): String = KEY_LAST_STUDENT_ID

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

        fun getTwfid(context: Context): String =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_TWFID, "").orEmpty()

        fun setTwfid(context: Context, value: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_TWFID, value.trim()).apply()
        }

        fun clearTwfid(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_TWFID).apply()
        }

        fun getUseHttpsWebVpn(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_USE_HTTPS_WEBVPN, false)

        fun setUseHttpsWebVpn(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USE_HTTPS_WEBVPN, enabled).apply()
        }

        fun hasPersistedSession(context: Context): Boolean =
            !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_COOKIES_JSON, null).isNullOrBlank()

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
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_STUDENT_ID, "").orEmpty()

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

        fun isRememberPasswordEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_REMEMBER_PASSWORD, false)
        }

        fun setRememberPasswordEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_REMEMBER_PASSWORD, enabled).apply()
            if (!enabled) {
                clearSavedPassword(context)
            }
        }

        fun hasSavedPassword(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return isRememberPasswordEnabled(context) &&
                !prefs.getString(KEY_ENCRYPTED_PASSWORD, null).isNullOrBlank() &&
                !prefs.getString(KEY_PASSWORD_CRYPTO_IV, null).isNullOrBlank()
        }

        fun getSavedPassword(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!isRememberPasswordEnabled(context)) return null
            val encryptedBase64 = prefs.getString(KEY_ENCRYPTED_PASSWORD, null) ?: return null
            val ivBase64 = prefs.getString(KEY_PASSWORD_CRYPTO_IV, null) ?: return null
            return try {
                val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                val ivBytes = Base64.decode(ivBase64, Base64.NO_WRAP)
                val gcmSpec = GCMParameterSpec(128, ivBytes)
                cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), gcmSpec)
                val decryptedBytes = cipher.doFinal(Base64.decode(encryptedBase64, Base64.NO_WRAP))
                String(decryptedBytes, Charsets.UTF_8).replace("\u0000", "").trim()
            } catch (e: Exception) {
                Log.w("WbuAuthTransport", "Failed to decrypt saved password", e)
                null
            }
        }

        fun savePassword(context: Context, password: String) {
            if (password.isBlank()) return
            try {
                val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
                val encryptedBytes = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
                val encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
                val ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)

                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_ENCRYPTED_PASSWORD, encryptedBase64)
                    .putString(KEY_PASSWORD_CRYPTO_IV, ivBase64)
                    .apply()
            } catch (e: Exception) {
                Log.w("WbuAuthTransport", "Failed to encrypt and save password", e)
            }
        }

        fun clearSavedPassword(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ENCRYPTED_PASSWORD)
                .remove(KEY_PASSWORD_CRYPTO_IV)
                .apply()
        }
    }
}
