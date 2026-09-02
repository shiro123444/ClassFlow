package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.xingheyuzhuan.shiguangschedule.data.db.main.Course
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWithWeeks
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONTokener
import org.json.JSONObject
import org.jsoup.Jsoup
import java.math.BigInteger
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.RSAPublicKeySpec
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

sealed class VpnLoginStep {
    data class SmsRequired(val maskedPhone: String) : VpnLoginStep()
    object VpnAuthenticated : VpnLoginStep()
    data class Error(val message: String) : VpnLoginStep()
}

enum class VpnFullLoginStatus {
    VPN_AUTHENTICATED,
    SMS_REQUIRED,
    SMS_VERIFIED,
    VPN_READY_SKIP_CAS,
    VPN_READY_NEED_CAS,
    CAS_COMPLETED,
    CAS_FAILED
}

/**
 * 登录认证方式：统一身份认证(CAS) 或 教务系统直接表单。
 */
enum class WbuAuthMode {
    UNIFIED_CAS,
    JYXT_LEGACY
}

/**
 * 教务系统直连(含 VPN 镜像)表单登录的失败原因。
 * 用于 UI 判断是否因超星验证码被服务端风控拦截，从而引导用户跳转 WebView 手动登录。
 */
enum class LocalLoginFailure {
    /** 服务端回跳 /admin/login?jcaptchaError=1，疑似需要超星验证码 */
    CAPTCHA,

    /** 停止在 /admin/login 但无验证码标识，通常是账号或密码错误 */
    CREDENTIALS
}

/**
 * 登录方式：密码 / 二维码 / 手机动态码。
 */
enum class WbuLoginMethod {
    PASSWORD,
    DYNAMIC_CODE,
    QR
}

/**
 * 二维码登录轮询状态。
 */
enum class QrStatus {
    WAIT,
    CONFIRM,
    SUCCESS,
    EXPIRED,
    ERROR
}

/**
 * 学期配置：从 /admin/api/getZclistByXnxq 派生。
 * semesterStartDate 为第 1 周开始日期（"yyyy-MM-dd"），semesterTotalWeeks 为总周数。
 */
data class WbuSemesterConfig(
    val semesterStartDate: String?,
    val semesterTotalWeeks: Int
)

/**
 * 二维码登录会话：uuid 用于轮询与提交，content 用于本地生成二维码，execution/lt 用于最终提交。
 */
data class QrSession(
    val uuid: String,
    val content: String,
    val execution: String,
    val lt: String,
    val authBaseUrl: String
)

/**
 * 登录页表单参数（execution/lt），动态码登录最终提交需要。
 */
data class AuthForm(
    val execution: String,
    val lt: String
)

/**
 * 发送动态码的结果。[Failure.waitSeconds] > 0 表示发送过于频繁的剩余冷却秒数。
 */
sealed class DynamicCodeSendResult {
    data class Success(val prep: AuthForm) : DynamicCodeSendResult()
    data class Failure(val message: String, val waitSeconds: Int = 0) : DynamicCodeSendResult()
}

/**
 * 动态码登录结果。失败时 [message] 尽可能给出服务端真实文案。
 */
data class DynamicCodeLoginResult(
    val success: Boolean,
    val message: String = ""
)

/**
 * 统一身份认证滑块验证码数据。
 * 图片为服务端返回的 base64 编码。
 */
data class SliderCaptchaData(
    val smallImageBase64: String,
    val bigImageBase64: String,
    val tagWidth: Int,
    val canvasLength: Int = 280
)

/**
 * 用户针对滑块验证码的操作结果。
 */
sealed class SliderCaptchaResult {
    /** 用户拖拽完成，moveLength 为服务端坐标系下的移动距离 (0..canvasLength) */
    data class Move(val moveLength: Int) : SliderCaptchaResult()

    /** 用户要求换一张验证码 */
    object Refresh : SliderCaptchaResult()

    /** 用户取消登录 */
    object Cancel : SliderCaptchaResult()
}

/**
 * 滑块验证码回调。接收验证码数据，返回用户的操作结果（挂起等待 UI 交互）。
 */
typealias SliderCaptchaProvider = suspend (SliderCaptchaData) -> SliderCaptchaResult

class WbuSyncEngine(
    private val context: Context,
    private val useVpn: Boolean = false,
) {

    private val casRandom = SecureRandom()

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val cookieStore = CopyOnWriteArrayList<Cookie>()

    /**
     * 最近一次教务系统直连/镜像表单登录的失败原因（供 UI 判断是否因超星验证码被拦截）。
     * 只在 [loginDirectLegacy] 流程完成后更新；成功或网络异常时保持为 null。
     */
    @Volatile
    var lastLocalLoginFailure: LocalLoginFailure? = null

    /**
     * 教务系统直连/镜像表单登录失败时，从服务端返回页提取的真实错误文案
     * （如"用户或密码错误, 请重试。当前错误次数为：N次…"）。成功时为 null。
     */
    @Volatile
    var lastLocalLoginError: String? = null

    /**
     * 教务系统直连/镜像登录是否因**网络异常**（断网/超时/无法解析）而失败。
     * 与 [lastLocalLoginFailure]/[lastLocalLoginError] 区分：网络问题请勿误标成"密码错误"。
     */
    @Volatile
    var lastLocalLoginNetworkError: Boolean = false

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

    private val client: OkHttpClient

    init {
        restoreCookieStore()

        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", authUserAgent())
                    .header("Accept-Language", authAcceptLanguage)
                    .build()
                chain.proceed(req)
            }

        // WebVPN uses a certificate that may not be in Android's trust store
        if (useVpn) {
            val trustAllManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAllManager)
            builder.hostnameVerifier { hostname, _ ->
                hostname.endsWith(".wbu.edu.cn") || hostname == "wbu.edu.cn"
            }
        }

        client = builder.build()
    }

    // Base URL is different if using VPN but for WBU specific JWXT.
    private val baseUrl = if (useVpn) "http://jwxt-wbu-edu-cn-s.webvpn.wbu.edu.cn:8118" else "https://jwxt.wbu.edu.cn"
    private val vpnBaseUrl = "https://webvpn.wbu.edu.cn"
    private val idsPublicBaseUrl = "http://ids.wbu.edu.cn"
    private val idsVpnBaseUrl = "http://ids-wbu-edu-cn.webvpn.wbu.edu.cn:8118"
    private val casServiceTarget = "https://jwxt.wbu.edu.cn/admin/caslogin"

    /** ids 认证基址：密码/动态码，受「ids 走 WebVPN」开关控制（默认公网）。 */
    private fun idsBaseUrl(): String =
        if (getIdsViaWebVpn(context)) idsVpnBaseUrl else idsPublicBaseUrl

    /** 二维码认证基址：受「二维码走 WebVPN」开关控制（默认公网）。 */
    private fun qrBaseUrl(): String =
        if (getQrViaWebVpn(context)) idsVpnBaseUrl else idsPublicBaseUrl

    /** 认证请求统一使用中文语言偏好（短信语言由 locale cookie 决定）。 */
    private val authAcceptLanguage = "zh-CN,zh;q=0.9,en;q=0.5"

    /**
     * 无头登录使用的 User-Agent。
     * 默认移动端（服务端返回移动版登录页）；开启「使用 PC User-Agent」后用桌面 UA，
     * 使服务端返回 PC 版登录页（其错误提示在 #showErrorTip，表单为 #pwdFromId）。
     */
    private fun authUserAgent(): String =
        if (getUsePcUserAgent(context)) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

    /** Spring CookieLocaleResolver 会话 locale cookie 名，决定短信文案/通道语言。 */
    private val authLocaleCookieName = "org.springframework.web.servlet.i18n.CookieLocaleResolver.LOCALE"

    /**
     * 在发送验证码请求前，向当前认证域注入 locale cookie：
     * 仅当「发送英语验证码」开启且非简体中文时用 en，否则 zh_CN。
     */
    private fun injectAuthLocaleCookie(authBase: String) {
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

    /**
     * 第一步：模拟登录获取 Session 和内部校验信息
     *
     * @param captchaProvider 当教务统一认证需要滑块验证码时调用，返回用户操作结果；为 null 时遇验证码直接失败
     */
    suspend fun login(
        studentId: String,
        password: String,
        captchaProvider: SliderCaptchaProvider? = null,
        authMode: WbuAuthMode = WbuAuthMode.UNIFIED_CAS
    ): Boolean = withContext(Dispatchers.IO) {
        lastLocalLoginNetworkError = false
        try {
            val success = if (useVpn) {
                loginViaVpnCas(studentId, password, captchaProvider, authMode)
            } else {
                loginDirect(studentId, password, captchaProvider, authMode)
            }
            if (success) {
                prefs.edit()
                    .putString(KEY_LAST_STUDENT_ID, studentId)
                    .putBoolean(KEY_LAST_USE_VPN, useVpn)
                    .putBoolean(KEY_LAST_USE_VPN_SET, true)
                    .apply()
                persistCookieStore()
            }
            return@withContext success
        } catch (e: Exception) {
            Log.e("WbuSyncEngine", "Login failed", e)
            lastLocalLoginNetworkError = true
            false
        }
    }

    suspend fun hasActiveSession(): Boolean = withContext(Dispatchers.IO) {
        try {
            return@withContext canAccessTermApi()
        } catch (e: Exception) {
            Log.w("WbuSyncEngine", "Session probe failed", e)
            false
        }
    }

    fun clearPersistedSession() {
        cookieStore.clear()
        prefs.edit().remove(KEY_COOKIES_JSON).apply()
    }

    /**
     * 清除与当前 jwxt baseUrl（直连或 VPN 镜像）匹配的旧会话 Cookie。
     * 登录前调用，避免残留的 JSESSIONID 使错误密码被"已认证"短接、或被旧账号会话假成功。
     * 只清 jwxt host，不影响 ids / webvpn 的凭证 Cookie。
     */
    private fun clearJwxtSessionCookies() {
        runCatching {
            val loginUrl = "$baseUrl/admin/login".toHttpUrlOrNull() ?: return
            cookieStore.removeAll { it.matches(loginUrl) }
            persistCookieStore()
        }.onFailure { Log.w("WbuSyncEngine", "clearJwxtSessionCookies failed", it) }
    }

    /**
     * 从 Android WebView CookieManager 导入 cookies 到 OkHttp cookie jar。
     * 用于 WebView 手动登录 WebVPN 后桥接 session。
     */
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
            // CookieManager returns "name1=value1; name2=value2" format
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

    /**
     * WebVPN 密码登录（第一步：RSA 加密密码 → 提交 → 检测是否需要 SMS）
     */
    suspend fun loginVpnPassword(studentId: String, password: String): VpnLoginStep = withContext(Dispatchers.IO) {
        try {
            // 获取 RSA 公钥和表单字段名
            val authReq = Request.Builder()
                .url("$vpnBaseUrl/por/login_auth.csp?apiversion=1")
                .get().build()
            val authXml = client.newCall(authReq).execute().use { it.body?.string().orEmpty() }

            val rsaKey = extractXmlTag(authXml, "RSA_ENCRYPT_KEY")
                ?: return@withContext VpnLoginStep.Error("无法获取加密密钥")
            val rsaExp = extractXmlTag(authXml, "RSA_ENCRYPT_EXP") ?: "65537"
            val csrfCode = extractXmlTag(authXml, "CSRF_RAND_CODE") ?: ""
            val nameField = extractXmlTag(authXml, "N_INPUTNAME") ?: "svpn_name"
            val passField = extractXmlTag(authXml, "N_INPUTPASS") ?: "svpn_password"

            // Sangfor JS encryptID() logic for password mode is: password + "_" + csrfRandCode.
            val plainForEncrypt = if (csrfCode.isNotBlank()) "${password}_$csrfCode" else password
            val encryptedPassword = rsaEncryptSangfor(plainForEncrypt, rsaKey, rsaExp)

            // 提交密码
            val form = FormBody.Builder()
                .add("mitm_result", "")
                .add("svpn_req_randcode", csrfCode)
                .add(nameField, studentId)
                .add(passField, encryptedPassword)
                .add("svpn_rand_code", "")
                .build()

            val pswReq = Request.Builder()
                .url("$vpnBaseUrl/por/login_psw.csp?anti_replay=1&encrypt=1&apiversion=1")
                .post(form)
                .build()
            val pswXml = client.newCall(pswReq).execute().use { it.body?.string().orEmpty() }
            Log.d("WbuSyncEngine", "VPN login_psw response: ${pswXml.take(500)}")

            val errorCode = extractXmlTag(pswXml, "ErrorCode")
            if (errorCode == "20021") {
                // Sangfor may return "user had logged in" when VPN session already exists.
                // Treat as authenticated instead of hard failure.
                Log.i("WbuSyncEngine", "VPN login_psw indicates existing logged-in session; continue flow")
                persistCookieStore()
                return@withContext VpnLoginStep.VpnAuthenticated
            }
            if (errorCode != "1") {
                val msg = extractXmlTag(pswXml, "Message") ?: "密码验证失败"
                Log.w("WbuSyncEngine", "VPN login_psw failed: code=$errorCode msg=$msg")
                return@withContext VpnLoginStep.Error(msg)
            }

            val nextAuth = extractXmlTag(pswXml, "NextAuth")
            if (nextAuth == "2") {
                // 需要短信验证 → 获取手机号并发送验证码
                val smsInfoReq = Request.Builder()
                    .url("$vpnBaseUrl/por/login_sms.csp?apiversion=1")
                    .post(FormBody.Builder().build())
                    .build()
                val smsInfoXml = client.newCall(smsInfoReq).execute().use { it.body?.string().orEmpty() }
                val maskedPhone = extractXmlTag(smsInfoXml, "USER_PHONE") ?: "未知号码"

                // 触发发送验证码
                val sendReq = Request.Builder()
                    .url("$vpnBaseUrl/por/post_sms.csp?apiversion=1")
                    .post(FormBody.Builder()
                        .add("phone_number", "")
                        .add("phone_index", "0")
                        .build())
                    .build()
                client.newCall(sendReq).execute().close()

                return@withContext VpnLoginStep.SmsRequired(maskedPhone)
            }

            // 不需要 SMS（少见）
            persistCookieStore()
            VpnLoginStep.VpnAuthenticated
        } catch (e: Exception) {
            Log.e("WbuSyncEngine", "VPN password login failed", e)
            VpnLoginStep.Error("网络错误: ${e.message}")
        }
    }

    /**
     * 提交 WebVPN 短信验证码
     */
    suspend fun submitVpnSmsCode(smsCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val form = FormBody.Builder()
                .add("svpn_inputsms", smsCode)
                .build()
            val req = Request.Builder()
                .url("$vpnBaseUrl/por/login_sms1.csp?apiversion=1")
                .post(form)
                .build()
            val xml = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            val ok = extractXmlTag(xml, "ErrorCode") == "1" && extractXmlTag(xml, "Result") == "1"
            if (ok) persistCookieStore()
            ok
        } catch (e: Exception) {
            Log.e("WbuSyncEngine", "SMS verification failed", e)
            false
        }
    }

    /**
     * 重新发送 WebVPN 短信验证码
     */
    suspend fun resendVpnSmsCode(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$vpnBaseUrl/por/post_sms.csp?apiversion=1")
                .post(FormBody.Builder()
                    .add("phone_number", "")
                    .add("phone_index", "0")
                    .build())
                .build()
            val xml = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            extractXmlTag(xml, "ErrorCode") == "1"
        } catch (e: Exception) {
            Log.e("WbuSyncEngine", "Resend SMS failed", e)
            false
        }
    }

    /**
     * 完整 WebVPN 登录流程：密码加密 → SMS 验证 → CAS 登录 → JWXT
     * @param smsCodeProvider 挂起函数，UI 层弹出对话框让用户输入验证码，返回 null 表示取消
     * @param captchaProvider 当教务统一认证需要滑块验证码时调用，返回用户操作结果；为 null 时遇验证码直接失败
     */
    suspend fun loginVpnFull(
        studentId: String,
        password: String,
        smsCodeProvider: suspend (maskedPhone: String) -> String?,
        captchaProvider: SliderCaptchaProvider? = null,
        statusCallback: ((VpnFullLoginStatus) -> Unit)? = null,
        authMode: WbuAuthMode = WbuAuthMode.UNIFIED_CAS
    ): Boolean {
        val step = loginVpnPassword(studentId, password)
        when (step) {
            is VpnLoginStep.Error -> {
                Log.w("WbuSyncEngine", "VPN login error: ${step.message}")
                return false
            }
            is VpnLoginStep.SmsRequired -> {
                statusCallback?.invoke(VpnFullLoginStatus.SMS_REQUIRED)
                val code = smsCodeProvider(step.maskedPhone) ?: return false
                if (!submitVpnSmsCode(code)) {
                    Log.w("WbuSyncEngine", "SMS code verification failed")
                    return false
                }
                statusCallback?.invoke(VpnFullLoginStatus.SMS_VERIFIED)
            }
            is VpnLoginStep.VpnAuthenticated -> {
                statusCallback?.invoke(VpnFullLoginStatus.VPN_AUTHENTICATED)
            }
        }

        // Some environments can access JWXT immediately after VPN+SMS auth.
        // Probe first to avoid unnecessary CAS captcha challenges.
        val vpnSessionReady = withContext(Dispatchers.IO) { canAccessTermApi() }
        if (vpnSessionReady) {
            Log.i("WbuSyncEngine", "VPN session is already valid for JWXT; skip CAS login")
            statusCallback?.invoke(VpnFullLoginStatus.VPN_READY_SKIP_CAS)
            return true
        }

        statusCallback?.invoke(VpnFullLoginStatus.VPN_READY_NEED_CAS)

        // WebVPN 已认证，接下来按选中认证方式登录到教务系统
        val casOk = withContext(Dispatchers.IO) {
            if (authMode == WbuAuthMode.JYXT_LEGACY) {
                loginDirectLegacy(studentId, password)
            } else {
                loginViaVpnCas(studentId, password, captchaProvider, authMode)
            }
        }
        statusCallback?.invoke(if (casOk) VpnFullLoginStatus.CAS_COMPLETED else VpnFullLoginStatus.CAS_FAILED)
        return casOk
    }

    private fun rsaEncryptSangfor(password: String, modulusHex: String, exponentStr: String): String {
        val modulus = BigInteger(modulusHex, 16)
        val exponent = parseSangforExponent(exponentStr)
        val spec = RSAPublicKeySpec(modulus, exponent)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return encrypted.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
    }

    /**
     * 正方/超星教务系统直连登录的密码加密。
     * 线上 /admin/login 使用 JSEncrypt（RSA/ECB/PKCS1）把密码加密后放入 password 字段，输出为 Base64。
     * 公钥为硬编码的 1024 位 PKCS#1（模数 + 指数 65537）。
     */
    private fun rsaEncryptJwxtPassword(password: String): String {
        return runCatching {
            val modulus = BigInteger(JWXT_RSA_MODULUS, 16)
            val exponent = BigInteger(JWXT_RSA_EXPONENT, 16)
            val spec = RSAPublicKeySpec(modulus, exponent)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }.getOrElse {
            Log.w("WbuSyncEngine", "JWXT RSA password encryption failed, fallback to plaintext", it)
            password
        }
    }

    /**
     * 从教务系统登录页 JS 提取真实错误文案，形如：
     * var error = "用户或密码错误, 请重试。当前错误次数为：1次，超过10次后，账号将被冻结15分钟。";
     */
    private fun extractLoginErrorMessage(html: String): String? {
        val m = Regex("""var\s+error\s*=\s*"([^"]*)"\s*;?""", RegexOption.IGNORE_CASE).find(html)
            ?: return null
        val raw = m.groupValues.getOrNull(1)?.trim().orEmpty()
        if (raw.isBlank()) return null
        return raw
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\\\", "\\")
            .trim()
    }

    /**
     * 从统一身份认证(CAS)登录页提取真实错误文案。
     * 优先取 #showErrorTip 的文本；若为空再按常见错误关键词识别。
     * 提取不到时返回 null（UI 走中性兜底），不会误标成网络故障。
     */
    private fun extractCasErrorMessage(html: String): String? {
        if (html.isBlank()) return null
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null
        // 从服务端渲染的错误提示元素提取：
        //  移动端：#formErrorTip2 / #formErrorTip（本例为「您提供的用户名或者密码有误」）
        //  PC 端：#showErrorTip
        // 不要对原始 HTML 做关键词扫描，避免命中验证码图片 alt="验证码错误" 等误报。
        val selectors = listOf("#formErrorTip2", "#formErrorTip", "#showErrorTip")
        for (sel in selectors) {
            val text = doc.selectFirst(sel)?.text()?.trim()?.takeIf { it.isNotBlank() }
            if (text != null) return text
        }
        return null
    }

    private fun parseSangforExponent(raw: String): BigInteger {
        val exp = raw.trim()
        if (exp.startsWith("0x", ignoreCase = true)) {
            return BigInteger(exp.substring(2), 16)
        }
        if (exp.matches(Regex("0*10001", RegexOption.IGNORE_CASE))) {
            // Common Sangfor response uses hex exponent 0x10001 (65537).
            return BigInteger(exp, 16)
        }
        if (exp.any { it in 'A'..'F' || it in 'a'..'f' }) {
            return BigInteger(exp, 16)
        }
        return BigInteger(exp)
    }

    private fun extractXmlTag(xml: String, tag: String): String? {
        // Handle both plain text and CDATA: <Tag>value</Tag> or <Tag><![CDATA[value]]></Tag>
        val pattern = Regex("<$tag>(?:<!\\[CDATA\\[(.+?)]]>|([^<]*))</$tag>", RegexOption.IGNORE_CASE)
        val match = pattern.find(xml) ?: return null
        return (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
    }

    /**
     * 第二步：提取最新学期和学期ID (xhid/xqdm)
     */
    suspend fun fetchCourseData(tableId: String): List<CourseWithWeeks>? = withContext(Dispatchers.IO) {
        try {
            Log.i("WbuSyncEngine", "Fetch course data start. tableId=$tableId baseUrl=$baseUrl")
            // 获取当前学年学期
            val termReq = Request.Builder()
                .url("$baseUrl/admin/xsd/xsdcjcx/getCurrentXnxq?sf_request_type=ajax")
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()

            val termResp = client.newCall(termReq).execute()
            val termRaw = termResp.body?.string().orEmpty()
            Log.d("WbuSyncEngine", "Term API response code=${termResp.code} len=${termRaw.length}")
            if (looksLikeHtml(termRaw)) {
                Log.w("WbuSyncEngine", "Term API returned HTML; auth/session likely invalid.")
                return@withContext null
            }

            val termJson = JSONObject(termRaw)
            val xnxq = termJson.optString("data", "")
            if (xnxq.isEmpty()) {
                Log.w("WbuSyncEngine", "Term API has empty xnxq. raw=${termRaw.take(300)}")
                return@withContext null
            }
            Log.d("WbuSyncEngine", "Resolved term xnxq=$xnxq")

            // 获取页面并提取隐藏域 xhid 和 xqdm
            val pkglReq = Request.Builder()
                .url("$baseUrl/admin/xsd/pkgl/xskb/queryKbForXsd?xnxq=$xnxq")
                .get()
                .build()

            val pkglResp = client.newCall(pkglReq).execute()
            val pkglHtml = pkglResp.body?.string() ?: run {
                Log.w("WbuSyncEngine", "queryKbForXsd returned null body. code=${pkglResp.code}")
                return@withContext null
            }
            val document = Jsoup.parse(pkglHtml)

            // Keep extraction order consistent with web script: id first, then name fallback.
            var xhid = document.select("#xhid").first()?.attr("value").orEmpty()
            if (xhid.isBlank()) {
                xhid = document.select("input[name=xhid]").first()?.attr("value").orEmpty()
            }
            var xqdm = document.select("#xqdm").first()?.attr("value").orEmpty()
            if (xqdm.isBlank()) {
                xqdm = document.select("input[name=xqdm]").first()?.attr("value").orEmpty()
            }

            // Some pages no longer expose hidden inputs; try extracting values from inline scripts.
            if (xhid.isBlank()) {
                xhid = extractFieldFromHtml(pkglHtml, "xhid").orEmpty()
            }
            if (xqdm.isBlank()) {
                xqdm = extractFieldFromHtml(pkglHtml, "xqdm").orEmpty()
            }

            if (xhid.isBlank() || xqdm.isBlank()) {
                Log.w("WbuSyncEngine", "Missing xhid/xqdm from queryKbForXsd after fallback. xhid=$xhid xqdm=$xqdm")
                Log.d("WbuSyncEngine", "queryKbForXsd snippet=${pkglHtml.take(400)}")
            } else {
                Log.d("WbuSyncEngine", "Resolved xhid=$xhid xqdm=$xqdm")
            }

            // 抓取并解析课表列表。先走完整参数，缺字段时再尝试兜底 URL。
            val listUrlCandidates = linkedSetOf<String>().apply {
                if (xhid.isNotBlank() && xqdm.isNotBlank()) {
                    add("$baseUrl/admin/xsd/pkgl/xskb/sdpkkbList?xnxq=$xnxq&xhid=$xhid&xqdm=$xqdm&zdzc=&zxzc=&xskbxslx=0&sf_request_type=ajax")
                }
                add("$baseUrl/admin/xsd/pkgl/xskb/sdpkkbList?xnxq=$xnxq&zdzc=&zxzc=&xskbxslx=0&sf_request_type=ajax")
                if (xhid.isNotBlank()) {
                    add("$baseUrl/admin/xsd/pkgl/xskb/sdpkkbList?xnxq=$xnxq&xhid=$xhid&zdzc=&zxzc=&xskbxslx=0&sf_request_type=ajax")
                }
                if (xqdm.isNotBlank()) {
                    add("$baseUrl/admin/xsd/pkgl/xskb/sdpkkbList?xnxq=$xnxq&xqdm=$xqdm&zdzc=&zxzc=&xskbxslx=0&sf_request_type=ajax")
                }
            }

            var jsonArray: JSONArray? = null
            for (candidate in listUrlCandidates) {
                val listReq = Request.Builder()
                    .url(candidate)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .get()
                    .build()

                val listResp = client.newCall(listReq).execute()
                val listRaw = listResp.body?.string().orEmpty()
                Log.d("WbuSyncEngine", "sdpkkbList try url=$candidate code=${listResp.code} len=${listRaw.length}")

                val listJson = runCatching { JSONObject(listRaw.ifBlank { "{}" }) }.getOrNull()
                val data = listJson?.optJSONArray("data")
                if (data == null) {
                    Log.w("WbuSyncEngine", "sdpkkbList missing data array for url=$candidate raw=${listRaw.take(300)}")
                    continue
                }

                Log.d("WbuSyncEngine", "sdpkkbList item count=${data.length()} for url=$candidate")
                jsonArray = data
                break
            }

            if (jsonArray == null) {
                Log.w("WbuSyncEngine", "All sdpkkbList attempts failed to return data array")
                return@withContext null
            }

            // 复用 js（school.js）的解析：按 天+起始节次 单元格分组，同课程周次求并集，
            // 再合并连续节次、同节次不同周次、并去重。
            val draftCourses = buildDraftCourses(jsonArray, keepTeacherId())
            if (draftCourses.isEmpty()) {
                Log.w("WbuSyncEngine", "No parseable courses from sdpkkbList")
                return@withContext emptyList()
            }
            val mergedDrafts = mergeAndDistinctCourses(draftCourses)

            val courses = mergedDrafts.map { draft ->
                val courseId = java.util.UUID.randomUUID().toString()
                val course = Course(
                    id = courseId,
                    courseTableId = tableId,
                    name = draft.name,
                    day = draft.day,
                    startSection = draft.startSection,
                    endSection = draft.endSection,
                    teacher = draft.teacher,
                    position = draft.position,
                    isCustomTime = false,
                    customStartTime = null,
                    customEndTime = null,
                    colorInt = pickColorIndexForCourse(draft.name)
                )
                val weeks = draft.weeks.map { CourseWeek(courseId = courseId, weekNumber = it) }
                CourseWithWeeks(course, weeks)
            }

            Log.i("WbuSyncEngine", "Fetch course data done. parsed=${draftCourses.size} merged=${courses.size}")
            return@withContext courses

        } catch (e: Exception) {
            Log.e("WbuSyncEngine", "Fetch failed", e)
            null
        }
    }

    /** 中间课程草稿（解析期用，周次为去重排序列表）。 */
    private data class DraftCourse(
        val name: String,
        val teacher: String,
        val position: String,
        val day: Int,
        val startSection: Int,
        val endSection: Int,
        val weeks: List<Int>
    )

    /**
     * 获取当前学期配置（开学日期、总周数），用于写入课表配置（周次⇄日期对齐）。
     * 数据源：/admin/api/getZclistByXnxq。失败或未发布返回 null。
     */
    suspend fun fetchSemesterConfig(): WbuSemesterConfig? = withContext(Dispatchers.IO) {
        runCatching {
            val termReq = Request.Builder()
                .url("$baseUrl/admin/xsd/xsdcjcx/getCurrentXnxq?sf_request_type=ajax")
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()
            val termRaw = client.newCall(termReq).execute().use { it.body?.string().orEmpty() }
            if (looksLikeHtml(termRaw) || termRaw.isBlank()) return@withContext null
            val xnxq = JSONObject(termRaw).optString("data", "")
            if (xnxq.isEmpty()) return@withContext null

            // 校区(xqdm)来自课表页默认校区，保证配置与所抓课程同一校区
            val pageReq = Request.Builder()
                .url("$baseUrl/admin/xsd/pkgl/xskb/queryKbForXsd?xnxq=${URLEncoder.encode(xnxq, "UTF-8")}")
                .get()
                .build()
            val pageHtml = client.newCall(pageReq).execute().use { it.body?.string().orEmpty() }
            val xqdm = runCatching { Jsoup.parse(pageHtml).select("#xqdm").first()?.attr("value") }
                .getOrNull()?.trim().orEmpty()

            val cfgReq = Request.Builder()
                .url("$baseUrl/admin/api/getZclistByXnxq?xnxq=${URLEncoder.encode(xnxq, "UTF-8")}&role=&userId=&xqid=${URLEncoder.encode(xqdm, "UTF-8")}")
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()
            val cfgRaw = client.newCall(cfgReq).execute().use { it.body?.string().orEmpty() }
            if (cfgRaw.isBlank()) return@withContext null
            val data = JSONObject(cfgRaw).optJSONObject("data") ?: return@withContext null
            val zclist = data.optJSONArray("zclist") ?: return@withContext null

            val items = mutableListOf<Pair<Int, String>>()
            for (i in 0 until zclist.length()) {
                val z = zclist.optJSONObject(i) ?: continue
                val zc = z.optInt("zc", 0)
                val minrq = z.optString("minrq", "")
                if (zc > 0 && minrq.isNotBlank()) items.add(zc to minrq)
            }
            if (items.isEmpty()) return@withContext null
            val sorted = items.sortedBy { it.first }
            WbuSemesterConfig(
                semesterStartDate = sorted.first().second.take(10),
                semesterTotalWeeks = sorted.maxOf { it.first }
            )
        }.getOrNull()
    }

    /**
     * 参考 school.js parseCoursesFromRows：
     * 按 (day, startSection) 单元格分组，同一课程（名称/教师/教室）的周次求并集；
     * 再按"同一格内课程签名一致"的连续节次合并（rowspan），生成一条课程。
     */
    private fun buildDraftCourses(jsonArray: JSONArray, keepTeacherId: Boolean): List<DraftCourse> {
        data class Cell(val day: Int, val section: Int)

        val byCell = LinkedHashMap<Cell, LinkedHashMap<String, LinkedHashSet<Int>>>()
        var maxSection = 0

        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(i) ?: continue
            val fromKcmc = cleanImportedText(item.optString("kcmc", ""))
            val fromJxbmc = cleanImportedText(item.optString("jxbmc", ""))
            val name = when {
                fromKcmc.isNotBlank() -> fromKcmc
                fromJxbmc.isNotBlank() -> fromJxbmc
                else -> "未命名课程"
            }
            val rawTeacher = cleanImportedText(item.optString("tmc", ""))
            val teacher = if (keepTeacherId) rawTeacher else stripTeacherId(rawTeacher)
            val building = cleanImportedText(item.optString("jxlmc", ""))
            val room = cleanImportedText(item.optString("croommc", ""))
            // 默认只用教室(croommc)，对齐 school.js；开启「保留建筑名称」才拼接教学楼名
            val position = if (keepBuilding() && building.isNotEmpty() && room.isNotEmpty() && !room.contains(building)) {
                "$building $room"
            } else {
                room.ifEmpty { building }
            }
            val day = item.optInt("xingqi", 1).coerceIn(1..7)
            val startSection = run {
                val rqxl = item.optString("rqxl", "")
                val fromRqxl = if (rqxl.matches(Regex("^\\d{3,4}$"))) (rqxl.toIntOrNull() ?: 100) % 100 else null
                (fromRqxl ?: item.optInt("djc", 1)).coerceIn(1..30)
            }
            val weeks = parseWeeks(
                cleanImportedText(item.optString("zc", "")),
                cleanImportedText(item.optString("zcstr", ""))
            )
            if (name.isBlank() || weeks.isEmpty()) continue

            maxSection = maxOf(maxSection, startSection)
            val sig = "$name\u0001$teacher\u0001$position"
            byCell.getOrPut(Cell(day, startSection)) { LinkedHashMap() }
                .getOrPut(sig) { LinkedHashSet() }
                .addAll(weeks)
        }

        val drafts = mutableListOf<DraftCourse>()

        // 按天推进：仅当相邻节次的单元格"签名"完全一致且非空时视作同一 rowspan 合并。
        for (day in 1..7) {
            var runStart: Int? = null
            var runSig = ""
            fun flushRun(endSection: Int) {
                val start = runStart ?: return
                if (start == 0 || runSig.isEmpty()) { runStart = null; runSig = ""; return }
                byCell[Cell(day, start)]?.forEach { (sig, weeks) ->
                    val parts = sig.split('\u0001')
                    if (parts.size == 3) {
                        drafts.add(DraftCourse(parts[0], parts[1], parts[2], day, start, endSection, weeks.sorted()))
                    }
                }
                runStart = null
                runSig = ""
            }
            for (s in 1..maxSection + 1) {
                val cell = byCell[Cell(day, s)]
                val sig = cell?.entries
                    ?.sortedBy { it.key }
                    ?.joinToString("\u0001") { (sk, ws) -> "$sk|${ws.sorted().joinToString(",")}" }
                    .orEmpty()
                val ended = s == maxSection + 1
                if (runStart != null && (ended || sig.isEmpty() || sig != runSig)) {
                    flushRun(s - 1)
                }
                if (!ended && sig.isNotEmpty() && runStart == null) {
                    runStart = s
                    runSig = sig
                }
            }
        }
        return drafts
    }

    /**
     * 参考 school.js mergeAndDistinctCourses：
     * 1) 合并连续节次（名称/教师/地点/星期/周次一致且节次相邻）并延展结束节次；
     * 2) 合并同节次不同周次（名称/教师/地点/星期/起止节次一致时周次并集）；
     * 3) 完全重复跳过。
     */
    private fun mergeAndDistinctCourses(list: List<DraftCourse>): List<DraftCourse> {
        if (list.size <= 1) return list

        val norm = list.map { it.copy(weeks = it.weeks.sorted().distinct()) }

        // 阶段1：合并连续节次 + 去重
        val sorted1 = norm.sortedWith(compareBy(
            { it.name }, { it.teacher }, { it.position }, { it.day },
            { it.weeks.joinToString(",") }, { it.startSection }
        ))
        val step1 = mutableListOf<DraftCourse>()
        var cur = sorted1[0]
        for (i in 1 until sorted1.size) {
            val nxt = sorted1[i]
            val same = cur.name == nxt.name && cur.teacher == nxt.teacher &&
                cur.position == nxt.position && cur.day == nxt.day &&
                cur.weeks == nxt.weeks
            val continuous = cur.endSection + 1 == nxt.startSection
            val duplicate = cur.startSection == nxt.startSection && cur.endSection == nxt.endSection
            when {
                same && continuous -> cur = cur.copy(endSection = nxt.endSection)
                same && duplicate -> { /* skip */ }
                else -> { step1.add(cur); cur = nxt }
            }
        }
        step1.add(cur)

        // 阶段2：合并同节次不同周次（周次并集）
        val sorted2 = step1.sortedWith(compareBy(
            { it.name }, { it.teacher }, { it.position }, { it.day },
            { it.startSection }, { it.endSection }
        ))
        val step2 = mutableListOf<DraftCourse>()
        var c = sorted2[0]
        for (i in 1 until sorted2.size) {
            val n = sorted2[i]
            val sameSection = c.name == n.name && c.teacher == n.teacher &&
                c.position == n.position && c.day == n.day &&
                c.startSection == n.startSection && c.endSection == n.endSection
            if (sameSection) {
                c = c.copy(weeks = (c.weeks + n.weeks).distinct().sorted())
            } else {
                step2.add(c)
                c = n
            }
        }
        step2.add(c)
        return step2
    }

    private fun extractFieldFromHtml(html: String, field: String): String? {
        // Matches forms like: xhid='123', "xhid":"123", xhid = 123
        val escapedField = Regex.escape(field)
        val patterns = listOf(
            Regex("""$escapedField\s*[:=]\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE),
            Regex("""$escapedField\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val m = pattern.find(html) ?: continue
            val v = m.groupValues.getOrNull(1).orEmpty().trim()
            if (v.isNotBlank()) return v
        }
        return null
    }

    private fun cleanImportedText(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** 去除教师名末尾的工号，如"王老师（20240999）" -> "王老师"；无工号则原样返回。 */
    private fun stripTeacherId(teacher: String): String = cleanTeacherId(teacher)

    /** 是否保留教师工号（读取设置，默认去除）。 */
    private fun keepTeacherId(): Boolean = getKeepTeacherId(context)

    /** 是否保留建筑名称（读取设置，默认不保留）。 */
    private fun keepBuilding(): Boolean = getKeepBuilding(context)

    private suspend fun loginDirect(
        studentId: String,
        password: String,
        captchaProvider: SliderCaptchaProvider? = null,
        authMode: WbuAuthMode = WbuAuthMode.UNIFIED_CAS
    ): Boolean {
        if (authMode == WbuAuthMode.JYXT_LEGACY) {
            Log.i("WbuSyncEngine", "Direct campus login uses legacy /admin/login form")
            return loginDirectLegacy(studentId, password)
        }

        // Direct campus flow deterministic:
        // jwxt /admin/caslogin -> ids /authserver/login?service=... -> jwxt /admin/?loginType=1
        val directCasEntryUrl = "$baseUrl/admin/caslogin"
        val serviceTarget = "https://jwxt.wbu.edu.cn/admin/caslogin"
        val encodedService = URLEncoder.encode(serviceTarget, "UTF-8")
        val fixedIdsLoginUrl = "http://ids.wbu.edu.cn/authserver/login?service=$encodedService"

        val discoveredCasUrl = runCatching {
            client.newCall(Request.Builder().url(directCasEntryUrl).get().build()).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                val finalUrl = resp.request.url.toString()
                when {
                    finalUrl.contains("/authserver/login") -> finalUrl
                    html.contains("/authserver/login", ignoreCase = true) -> {
                        extractCasLoginUrlFromHtml(html)?.let { resolveAbsoluteUrl(directCasEntryUrl, it) } ?: fixedIdsLoginUrl
                    }
                    else -> fixedIdsLoginUrl
                }
            }
        }.getOrDefault(fixedIdsLoginUrl)

        val directCasOk = loginViaCas(
            studentId = studentId,
            password = password,
            idsLoginUrl = discoveredCasUrl,
            flowTag = "DIRECT-CAS",
            captchaProvider = captchaProvider
        )
        if (directCasOk) return true

        Log.w("WbuSyncEngine", "Direct CAS flow failed and no legacy fallback per selected auth mode")
        return false
    }

    /**
     * 教务系统直接表单登录（loginType=1）。baseUrl 依 useVpn 而定，直连与 VPN 镜像均可复用。
     */
    private suspend fun loginDirectLegacy(
        studentId: String,
        password: String
    ): Boolean {
        clearJwxtSessionCookies()
        lastLocalLoginFailure = null
        lastLocalLoginError = null
        lastLocalLoginNetworkError = false
        try {
            val loginPageReq = Request.Builder()
                .url("$baseUrl/admin/login")
                .get()
                .build()

            val hiddenFields = mutableMapOf<String, String>()
            client.newCall(loginPageReq).execute().use { loginPageResp ->
                val html = loginPageResp.body?.string().orEmpty()
                if (html.isBlank()) return false
                val document = Jsoup.parse(html, "$baseUrl/admin/login")
                document.select("input[type=hidden][name]").forEach { input ->
                    val name = input.attr("name")
                    if (name.isNotBlank()) {
                        hiddenFields[name] = input.attr("value")
                    }
                }
            }

            val formBuilder = FormBody.Builder()
            hiddenFields.forEach { (k, v) -> formBuilder.add(k, v) }
            formBuilder.add("username", studentId)
            formBuilder.add("password", rsaEncryptJwxtPassword(password))

            val loginPostReq = Request.Builder()
                .url("$baseUrl/admin/login")
                .post(formBuilder.build())
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build()

            client.newCall(loginPostReq).execute().use { postResp ->
                val postRespString = postResp.body?.string().orEmpty()
                val finalUrl = postResp.request.url.toString()
                // 成功判定：最终 URL 已跳离登录页即视为登录被接受
                // （无论落到 /admin、/admin/index 还是选课页 /xsd/... 均可）。
                val success = !finalUrl.contains("/admin/login")
                if (!success) {
                    Log.d("WbuSyncEngine", "Legacy login response URL=$finalUrl")
                    Log.d("WbuSyncEngine", "Legacy login response body snippet=${postRespString.take(500)}")
                    lastLocalLoginFailure = if (finalUrl.contains("jcaptchaError")) {
                        LocalLoginFailure.CAPTCHA
                    } else {
                        lastLocalLoginError = extractLoginErrorMessage(postRespString)
                        LocalLoginFailure.CREDENTIALS
                    }
                    return false
                }

                return canAccessTermApi()
            }
        } catch (e: Exception) {
            Log.w("WbuSyncEngine", "Legacy login network error", e)
            lastLocalLoginNetworkError = true
            return false
        }
    }

    private data class CasLoginPage(
        val hiddenFields: Map<String, String>,
        val pwdEncryptSalt: String,
        val needCaptcha: Boolean
    )

    private suspend fun fetchCasLoginPage(idsLoginUrl: String, flowTag: String): CasLoginPage? {
        return withContext(Dispatchers.IO) {
            runCatching {
                clearAuthCookies(idsLoginUrl.toHttpUrlOrNull()?.host ?: return@runCatching null)
                client.newCall(
                    Request.Builder().url(idsLoginUrl).get().build()
                ).execute().use { pageResp ->
                    val loginHtml = pageResp.body?.string().orEmpty()
                    if (loginHtml.isBlank()) {
                        Log.w("WbuSyncEngine", "$flowTag CAS login page is blank. idsLoginUrl=$idsLoginUrl")
                        return@use null
                    }

                    val doc = Jsoup.parse(loginHtml)
                    val pwdForm = doc.selectFirst("form#pwdFromId") ?: run {
                        val candidates = doc.select("form").filter { form ->
                            val hasUsername = form.select("input[name=username]").isNotEmpty()
                            val hasPassword = form.select("input[name=password], input#password").isNotEmpty()
                            val looksPwdForm = form.id().contains("pwd", ignoreCase = true) ||
                                form.attr("class").contains("pwd", ignoreCase = true)
                            hasUsername && (hasPassword || looksPwdForm)
                        }

                        if (candidates.size == 1) {
                            Log.w(
                                "WbuSyncEngine",
                                "$flowTag CAS missing #pwdFromId; using strict fallback form id='${candidates[0].id()}'"
                            )
                            candidates[0]
                        } else {
                            val formSummary = doc.select("form").joinToString(" | ") { form ->
                                val id = form.id().ifBlank { "<no-id>" }
                                val hasUser = form.select("input[name=username]").isNotEmpty()
                                val hasPwd = form.select("input[name=password], input#password").isNotEmpty()
                                "id=$id user=$hasUser pwd=$hasPwd"
                            }
                            Log.w("WbuSyncEngine", "$flowTag CAS form is ambiguous. forms=[$formSummary]")
                            Log.d("WbuSyncEngine", "$flowTag CAS snippet=${loginHtml.take(800)}")
                            return@use null
                        }
                    }

                    val hiddenFields = mutableMapOf<String, String>()
                    pwdForm.select("input[type=hidden][name]").forEach { input ->
                        val key = input.attr("name")
                        if (key.isNotBlank()) {
                            hiddenFields[key] = input.attr("value")
                        }
                    }

                    val pwdEncryptSalt = pwdForm.selectFirst("#pwdEncryptSalt")?.attr("value").orEmpty()
                    val needCaptcha = Regex("""needCaptcha\s*=\s*['\"]?true['\"]?""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(loginHtml)

                    CasLoginPage(
                        hiddenFields = hiddenFields,
                        pwdEncryptSalt = pwdEncryptSalt,
                        needCaptcha = needCaptcha
                    )
                }
            }.getOrNull()
        }
    }

    private suspend fun loginViaCas(
        studentId: String,
        password: String,
        idsLoginUrl: String,
        flowTag: String,
        captchaProvider: SliderCaptchaProvider? = null
    ): Boolean {
        clearJwxtSessionCookies()
        lastLocalLoginFailure = null
        lastLocalLoginError = null
        val origin = idsLoginUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }

        // 首次获取登录页面（解析表单隐藏域、盐值，并检测是否需要验证码）
        val page = fetchCasLoginPage(idsLoginUrl, flowTag) ?: return false

        // HTML 检测可能失效，再通过服务端接口二次确认是否需要验证码
        var captchaNeeded = page.needCaptcha
        if (!captchaNeeded && !origin.isNullOrBlank() && captchaProvider != null) {
            captchaNeeded = checkNeedCaptcha(studentId, origin)
        }

        if (captchaNeeded) {
            if (captchaProvider == null || origin.isNullOrBlank()) {
                Log.w("WbuSyncEngine", "$flowTag CAS requires captcha but no provider available; skip auto submit")
                return false
            }
            val solved = solveSliderCaptcha(origin, flowTag, captchaProvider)
            if (!solved) {
                Log.w("WbuSyncEngine", "$flowTag CAS captcha not solved")
                return false
            }
            // 注意：不重新获取登录页。滑块 verifySliderCaptcha.htl 设置的是服务端会话标记，
            // 复用最初拉取的 execution 提交，才与该"已验证"标记绑定一致（与站点行为一致）。
            // 若此处再 fetchCasLoginPage 拿全新 execution，新 execution 无已验证标记会被判"验证码错误"。
            Log.i("WbuSyncEngine", "$flowTag CAS captcha verified, submit with original execution")
        }

        val hiddenFields = page.hiddenFields
        val pwdEncryptSalt = page.pwdEncryptSalt
        val encryptedCasPassword = encryptCasPassword(password, pwdEncryptSalt)
        val formBuilder = FormBody.Builder()
        hiddenFields
            .filterKeys { it != "password" && it != "passwordText" && it != "username" }
            .forEach { (k, v) -> formBuilder.add(k, v) }
        formBuilder.add("username", studentId)
        formBuilder.add("password", encryptedCasPassword)
        if (!hiddenFields.containsKey("_eventId")) formBuilder.add("_eventId", "submit")
        if (!hiddenFields.containsKey("cllt")) formBuilder.add("cllt", "userNameLogin")
        if (!hiddenFields.containsKey("dllt")) formBuilder.add("dllt", "generalLogin")

        val loginPostReq = Request.Builder()
            .url(idsLoginUrl)
            .post(formBuilder.build())
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .addHeader("Referer", idsLoginUrl)
            .apply {
                if (!origin.isNullOrBlank()) addHeader("Origin", origin)
            }
            .build()

        var casPostStaysOnLogin = false
        client.newCall(loginPostReq).execute().use { postResp ->
            val body = postResp.body?.string().orEmpty()
            val finalUrl = postResp.request.url.toString()
            casPostStaysOnLogin = finalUrl.contains("/authserver/login")
            if (casPostStaysOnLogin) {
                Log.w("WbuSyncEngine", "$flowTag CAS stayed on login page. URL=$finalUrl")
                Log.d("WbuSyncEngine", "$flowTag CAS stay snippet=${body.take(500)}")
                // CAS 密码错误：提取真实报错上屏。注意 CAS 不涉及超星验证码，
                // 故标为 CREDENTIALS（不是 CAPTCHA），避免误触 jwxt 验证码弹窗。
                lastLocalLoginFailure = LocalLoginFailure.CREDENTIALS
                lastLocalLoginError = extractCasErrorMessage(body)
            }
        }
        if (casPostStaysOnLogin) return false

        return bootstrapJwxtSession()
    }

    /**
     * 登录成功后的 JWXT 会话引导：打开落地页、解析 indexMain，最后校验课表接口。
     * 密码/动态码/二维码登录成功后共用此方法。
     */
    private suspend fun bootstrapJwxtSession(): Boolean {
        runCatching {
            client.newCall(Request.Builder().url("$baseUrl/admin/login").get().build()).execute().close()
        }
        val loginTypeHtml = runCatching {
            client.newCall(
                Request.Builder().url("$baseUrl/admin/?loginType=1").get().build()
            ).execute().use { it.body?.string().orEmpty() }
        }.getOrDefault("")

        val indexMainUrl = runCatching {
            val doc = Jsoup.parse(loginTypeHtml, "$baseUrl/admin/?loginType=1")
            doc.select("a[href*=indexMain], frame[src*=indexMain], iframe[src*=indexMain], script")
                .firstOrNull()
                ?.let { el ->
                    val candidate = el.attr("href").ifBlank { el.attr("src") }
                    if (candidate.isBlank()) extractIndexMainUrlFromScript(loginTypeHtml)
                    else resolveAbsoluteUrl("$baseUrl/admin/?loginType=1", candidate)
                } ?: extractIndexMainUrlFromScript(loginTypeHtml)
        }.getOrNull()

        if (!indexMainUrl.isNullOrBlank()) {
            runCatching {
                client.newCall(Request.Builder().url(indexMainUrl).get().build()).execute().close()
            }.onFailure {
                Log.w("WbuSyncEngine", "bootstrapJwxtSession open indexMain failed: ${it.message}")
            }
        }

        return canAccessTermApi()
    }

    private suspend fun loginViaVpnCas(
        studentId: String,
        password: String,
        captchaProvider: SliderCaptchaProvider? = null,
        authMode: WbuAuthMode = WbuAuthMode.UNIFIED_CAS
    ): Boolean {
        if (authMode == WbuAuthMode.JYXT_LEGACY) {
            Log.i("WbuSyncEngine", "VPN login uses legacy /admin/login form via mirror")
            return loginDirectLegacy(studentId, password)
        }

        // Follow the same order as manual login:
        // 1) Open JWXT login page behind VPN
        // 2) Click unified-auth link (CAS)
        // 3) Submit CAS credentials
        // 4) Open admin landing pages to finish session bootstrap
        val jwxtLoginUrl = "$baseUrl/admin/login"

        val serviceTarget = "https://jwxt.wbu.edu.cn/admin/caslogin"
        val encodedService = URLEncoder.encode(serviceTarget, "UTF-8")
        val fallbackIdsLoginUrl = "http://ids-wbu-edu-cn.webvpn.wbu.edu.cn:8118/authserver/login?service=$encodedService"

        val discoveredCasUrl = runCatching {
            client.newCall(Request.Builder().url(jwxtLoginUrl).get().build()).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                if (html.isBlank()) return@use null
                val doc = Jsoup.parse(html, jwxtLoginUrl)
                val href = doc.select("a[href*=authserver/login][href*=service=]").firstOrNull()?.attr("href")
                href?.let { resolveAbsoluteUrl(jwxtLoginUrl, it) }
            }
        }.getOrNull()

        val idsLoginUrl = discoveredCasUrl ?: fallbackIdsLoginUrl
        Log.d("WbuSyncEngine", "Using CAS url: $idsLoginUrl")

        val ready = loginViaCas(
            studentId = studentId,
            password = password,
            idsLoginUrl = idsLoginUrl,
            flowTag = "VPN-CAS",
            captchaProvider = captchaProvider
        )
        if (!ready) {
            Log.w("WbuSyncEngine", "VPN CAS flow completed but JWXT term API is still unavailable. baseUrl=$baseUrl")
        }
        return ready
    }

    // ------------------- 登录页表单解析（execution/lt） -------------------

    /**
     * 取登录表单前，先清除该认证域的历史 cookie（尤其 CASTGC）。
     * 否则 CAS 会因有效 CASTGC 自动登录并跳转到 service，返回的是教务落地页而非登录表单。
     */
    private fun clearAuthCookies(host: String) {
        val removed = cookieStore.removeAll { it.domain == host }
        if (removed) {
            Log.d("WbuSyncEngine", "Cleared persisted auth cookies for host=$host")
            persistCookieStore()
        }
    }

    /**
     * 解析指定 form id 的登录页，提取 execution / lt。
     * 用于动态码（phoneFromId）与二维码（qrLoginForm）。
     */
    private suspend fun fetchLoginForm(idsLoginUrl: String, formId: String): AuthForm? {
        return withContext(Dispatchers.IO) {
            runCatching {
                clearAuthCookies(idsLoginUrl.toHttpUrlOrNull()?.host ?: return@runCatching null)
                client.newCall(Request.Builder().url(idsLoginUrl).get().build()).execute().use { pageResp ->
                    val html = pageResp.body?.string().orEmpty()
                    if (html.isBlank()) {
                        Log.w("WbuSyncEngine", "CAS login page blank for form=$formId")
                        return@use null
                    }
                    val doc = Jsoup.parse(html)

                    // 优先从指定表单取 hidden 字段；表单未命中则整页 Jsoup 兜底
                    val scope: org.jsoup.nodes.Element = doc.selectFirst("form#$formId")
                        ?: doc.let { Log.w("WbuSyncEngine", "CAS form $formId not found; fallback to whole-page inputs"); it }
                    val hidden = mutableMapOf<String, String>()
                    scope.select("input[name]").forEach { input ->
                        val key = input.attr("name")
                        if (key.isNotBlank() && !hidden.containsKey(key)) {
                            hidden[key] = input.attr("value")
                        }
                    }

                    val execution = hidden["execution"]
                        ?: extractInputValue(html, "execution")
                        ?: extractScriptVar(html, "execution")
                    if (execution.isNullOrBlank()) {
                        Log.w("WbuSyncEngine", "CAS form $formId no execution. url=$idsLoginUrl")
                        return@use null
                    }
                    val ltValue = hidden["lt"]
                        ?: extractInputValue(html, "lt")
                        ?: extractScriptVar(html, "lt")
                        ?: ""
                    AuthForm(execution, ltValue)
                }
            }.getOrNull()
        }
    }

    private fun extractInputValue(html: String, name: String): String? {
        val m = Regex("""(?:name|id)\s*=\s*["']${Regex.escape(name)}["'][^>]*value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)
        return m?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 从页面脚本变量中提取（如 var execution="..." / execution:'...'），部分 JS 渲染表单适用。 */
    private fun extractScriptVar(html: String, name: String): String? {
        val m = Regex("""${Regex.escape(name)}\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)
        return m?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    // ------------------- 手机动态码登录 -------------------

    /**
     * 发送动态码：解析登录页 → 滑块验证 → 发送短信。返回 prep 供最终登录使用。
     * 失败时返回 null，同时通过 [outMessage] 回传错误信息（如需 UI 展示可自行处理）。
     */
    suspend fun sendDynamicCode(
        studentId: String,
        flowTag: String,
        captchaProvider: SliderCaptchaProvider?
    ): DynamicCodeSendResult = withContext(Dispatchers.IO) {
        val authBase = idsBaseUrl()
        val service = URLEncoder.encode(casServiceTarget, "UTF-8")
        val loginUrl = "$authBase/authserver/login?service=$service"
        val form = fetchLoginForm(loginUrl, "phoneFromId") ?: run {
            Log.w("WbuSyncEngine", "$flowTag dynamicCode: cannot parse login form")
            return@withContext DynamicCodeSendResult.Failure("无法获取登录参数，请重试")
        }

        val origin = authBase
        if (captchaProvider != null) {
            val ok = solveSliderCaptcha(origin, "$flowTag-DYNAMIC-SLIDER", captchaProvider)
            if (!ok) {
                Log.w("WbuSyncEngine", "$flowTag dynamicCode: slider captcha not solved")
                return@withContext DynamicCodeSendResult.Failure("滑块验证未通过")
            }
        }

        val url = "$authBase/authserver/dynamicCode/getDynamicCode.htl"
        injectAuthLocaleCookie(authBase)
        val req = Request.Builder()
            .url(url)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Referer", loginUrl)
            .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
            .post(FormBody.Builder()
                .add("mobile", studentId)
                .add("captcha", "")
                .build())
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                val code = json?.optString("code").orEmpty()
                when (code) {
                    "success" -> {
                        Log.i("WbuSyncEngine", "$flowTag dynamicCode sent")
                        DynamicCodeSendResult.Success(form)
                    }
                    "captchaError" -> {
                        val msg = json?.optString("message").orEmpty()
                        Log.w("WbuSyncEngine", "$flowTag dynamicCode captcha error: $msg")
                        DynamicCodeSendResult.Failure(if (msg.isBlank()) "验证码错误" else msg)
                    }
                    "timeExpire" -> {
                        val wait = parseWaitSeconds(json?.opt("time"))
                        Log.w("WbuSyncEngine", "$flowTag dynamicCode too frequent, wait=$wait")
                        DynamicCodeSendResult.Failure("发送过于频繁", waitSeconds = wait)
                    }
                    else -> {
                        val msg = json?.optString("message").orEmpty()
                        Log.w("WbuSyncEngine", "$flowTag dynamicCode send failed: code=$code msg=$msg")
                        DynamicCodeSendResult.Failure(if (msg.isBlank()) "发送验证码失败，请重试" else msg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WbuSyncEngine", "$flowTag dynamicCode send exception", e)
            DynamicCodeSendResult.Failure("发送验证码失败，请重试")
        }
    }

    private fun parseWaitSeconds(raw: Any?): Int {
        return when (raw) {
            is Number -> raw.toInt().coerceAtLeast(1)
            is String -> raw.toIntOrNull()?.coerceAtLeast(1) ?: 120
            else -> 120
        }
    }

    /**
     * 仅获取动态码登录表单参数（execution/lt），不发短信。
     * 用于用户已有可用的验证码、直接填写登录的场景。
     */
    suspend fun obtainDynamicCodeForm(flowTag: String): AuthForm? = withContext(Dispatchers.IO) {
        val authBase = idsBaseUrl()
        val service = URLEncoder.encode(casServiceTarget, "UTF-8")
        fetchLoginForm("$authBase/authserver/login?service=$service", "phoneFromId")
    }

    /**
     * 用动态码完成登录：提交 dynamicCode → 302 票据 → JWXT 会话引导。
     */
    suspend fun dynamicCodeLogin(
        studentId: String,
        code: String,
        prep: AuthForm,
        flowTag: String
    ): DynamicCodeLoginResult = withContext(Dispatchers.IO) {
        val authBase = idsBaseUrl()
        val service = URLEncoder.encode(casServiceTarget, "UTF-8")
        val postUrl = "$authBase/authserver/login?service=$service"
        val formBuilder = FormBody.Builder()
            .add("username", studentId)
            .add("dynamicCode", code)
            .add("captcha", "")
            .add("_eventId", "submit")
            .add("cllt", "dynamicLogin")
            .add("dllt", "generalLogin")
            .add("lt", prep.lt)
            .add("execution", prep.execution)

        val loginReq = Request.Builder()
            .url(postUrl)
            .post(formBuilder.build())
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .addHeader("Referer", postUrl)
            .addHeader("Origin", authBase)
            .build()

        val outcome = runCatching {
            // 客户端 followRedirects=true：POST 302 会自动跳转并消费票据，最终落到教务页。
            // 因此不看 resp.code，改为判断最终地址是否还停在登录页。
            client.newCall(loginReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val finalUrl = resp.request.url.toString()
                if (finalUrl.contains("/authserver/login")) {
                    val err = extractCasError(body)
                    Log.w("WbuSyncEngine", "$flowTag dynamicCode stayed on login. url=$finalUrl err=$err")
                    DynamicCodeLoginResult(false, err.ifBlank { "动态码登录失败，请检查验证码是否有效或已过期" })
                } else {
                    Log.i("WbuSyncEngine", "$flowTag dynamicCode login ok, finalUrl=$finalUrl")
                    DynamicCodeLoginResult(success = true)
                }
            }
        }.getOrElse { e ->
            Log.e("WbuSyncEngine", "$flowTag dynamicCode login exception", e)
            DynamicCodeLoginResult(false, "网络异常: ${e.message}")
        }

        if (outcome.success) {
            prefs.edit()
                .putString(KEY_LAST_STUDENT_ID, studentId)
                .putBoolean(KEY_LAST_USE_VPN, useVpn)
                .putBoolean(KEY_LAST_USE_VPN_SET, true)
                .apply()
            persistCookieStore()
            val boot = bootstrapJwxtSession()
            return@withContext DynamicCodeLoginResult(boot, if (boot) "" else "登录成功但教务系统会话未就绪")
        }
        outcome
    }

    private fun extractCasError(html: String): String {
        // 与 extractCasErrorMessage 同源（动态码/二维码共用）：
        // 只从服务端渲染的错误提示元素取文本，避免命中验证码图片 alt="验证码错误" 等误报。
        // 移动端：#formErrorTip2 / #formErrorTip；PC 端：#showErrorTip。
        if (html.isBlank()) return ""
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return ""
        val selectors = listOf("#formErrorTip2", "#formErrorTip", "#showErrorTip")
        for (sel in selectors) {
            val text = doc.selectFirst(sel)?.text()?.trim()?.takeIf { it.isNotBlank() }
            if (text != null) return text
        }
        return ""
    }

    // ------------------- 二维码登录 -------------------

    /**
     * 开始二维码登录：解析 qr 登录页 → 获取 uuid → 生成二维码内容。
     */
    suspend fun startQrLogin(flowTag: String): QrSession? = withContext(Dispatchers.IO) {
        runCatching {
            val authBase = qrBaseUrl()
            val service = URLEncoder.encode(casServiceTarget, "UTF-8")
            val qrPageUrl = "$authBase/authserver/login?type=qrcode&service=$service"
            val form = fetchLoginForm(qrPageUrl, "qrLoginForm") ?: run {
                Log.w("WbuSyncEngine", "$flowTag qr: cannot parse login form")
                return@runCatching null
            }

            val tokenUrl = "$authBase/authserver/qrCode/getToken?ts=${System.currentTimeMillis()}"
            val tokenReq = Request.Builder()
                .url(tokenUrl)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", qrPageUrl)
                .get()
                .build()
            val uuid = client.newCall(tokenReq).execute().use { it.body?.string().orEmpty().trim() }
            if (uuid.isBlank()) {
                Log.w("WbuSyncEngine", "$flowTag qr uuid blank")
                return@runCatching null
            }

            val content = "$authBase/authserver/qrCode/qrCodeLogin.do?uuid=$uuid"
            Log.i("WbuSyncEngine", "$flowTag qr started uuid=$uuid")
            QrSession(uuid, content, form.execution, form.lt, authBase)
        }.getOrNull()
    }

    /**
     * 轮询二维码状态。
     */
    suspend fun pollQrStatus(session: QrSession): QrStatus = withContext(Dispatchers.IO) {
        try {
            val statusUrl = "${session.authBaseUrl}/authserver/qrCode/getStatus.htl?ts=${System.currentTimeMillis()}&uuid=${session.uuid}"
            val req = Request.Builder()
                .url(statusUrl)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", "${session.authBaseUrl}/authserver/login?type=qrcode")
                .get()
                .build()
            val raw = client.newCall(req).execute().use { it.body?.string().orEmpty().trim() }
            when (raw.toIntOrNull()) {
                0 -> QrStatus.WAIT
                1 -> QrStatus.SUCCESS
                2 -> QrStatus.CONFIRM
                3 -> QrStatus.EXPIRED
                else -> QrStatus.ERROR
            }
        } catch (e: Exception) {
            Log.w("WbuSyncEngine", "pollQrStatus failed", e)
            QrStatus.ERROR
        }
    }

    /**
     * 手机扫码确认后提交登录。
     */
    suspend fun completeQrLogin(session: QrSession, flowTag: String): Boolean = withContext(Dispatchers.IO) {
        val service = URLEncoder.encode(casServiceTarget, "UTF-8")
        val postUrl = "${session.authBaseUrl}/authserver/login?display=qrLogin&service=$service"
        val form = FormBody.Builder()
            .add("lt", session.lt)
            .add("uuid", session.uuid)
            .add("cllt", "qrLogin")
            .add("dllt", "generalLogin")
            .add("execution", session.execution)
            .add("_eventId", "submit")
            .add("rmShown", "1")
            .build()
        val loginReq = Request.Builder()
            .url(postUrl)
            .post(form)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .addHeader("Referer", "${session.authBaseUrl}/authserver/login?type=qrcode")
            .addHeader("Origin", session.authBaseUrl)
            .build()

        // 客户端 followRedirects=true：POST 302 会自动跳转并消费票据，因此看最终地址是否停在登录页。
        val ok = runCatching {
            client.newCall(loginReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val finalUrl = resp.request.url.toString()
                if (finalUrl.contains("/authserver/login")) {
                    Log.w("WbuSyncEngine", "$flowTag qr login stayed on login. url=$finalUrl err=${extractCasError(body)}")
                    false
                } else {
                    Log.i("WbuSyncEngine", "$flowTag qr login ok, finalUrl=$finalUrl")
                    true
                }
            }
        }.getOrDefault(false)

        if (ok) {
            prefs.edit()
                .putString(KEY_LAST_STUDENT_ID, "")
                .putBoolean(KEY_LAST_USE_VPN, useVpn)
                .putBoolean(KEY_LAST_USE_VPN_SET, true)
                .apply()
            persistCookieStore()
            return@withContext bootstrapJwxtSession()
        }
        false
    }

    /**
     * 查询指定学号是否需要滑块验证码。
     */
    private suspend fun checkNeedCaptcha(username: String, origin: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$origin/authserver/checkNeedCaptcha.htl?username=${URLEncoder.encode(username, "UTF-8")}&_=${System.currentTimeMillis()}"
            val req = Request.Builder()
                .url(url)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", "$origin/authserver/login")
                .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                .get()
                .build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            runCatching { JSONObject(body).optBoolean("isNeed", false) }.getOrDefault(false)
        } catch (e: Exception) {
            Log.w("WbuSyncEngine", "checkNeedCaptcha failed", e)
            false
        }
    }

    /**
     * 获取滑块验证码图片数据。
     */
    private suspend fun fetchSliderCaptcha(origin: String): SliderCaptchaData? = withContext(Dispatchers.IO) {
        try {
            val url = "$origin/authserver/common/openSliderCaptcha.htl?_=${System.currentTimeMillis()}"
            val req = Request.Builder()
                .url(url)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", "$origin/authserver/login")
                .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                .get()
                .build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            val json = JSONObject(body)
            SliderCaptchaData(
                smallImageBase64 = json.optString("smallImage"),
                bigImageBase64 = json.optString("bigImage"),
                tagWidth = json.optInt("tagWidth", 50)
            )
        } catch (e: Exception) {
            Log.w("WbuSyncEngine", "fetchSliderCaptcha failed", e)
            null
        }
    }

    /**
     * 提交滑块位置，返回是否验证通过。
     */
    private suspend fun verifySliderCaptcha(origin: String, moveLength: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$origin/authserver/common/verifySliderCaptcha.htl"
            val form = FormBody.Builder()
                .add("canvasLength", "280")
                .add("moveLength", moveLength.toString())
                .build()
            val req = Request.Builder()
                .url(url)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", "$origin/authserver/login")
                .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                .post(form)
                .build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            runCatching { JSONObject(body).optInt("errorCode") == 1 }.getOrDefault(false)
        } catch (e: Exception) {
            Log.w("WbuSyncEngine", "verifySliderCaptcha failed", e)
            false
        }
    }

    /**
     * 滑块验证码完整流程：获取图片 → 用户操作 → 校验。
     * 支持"换一张"（Refresh）与取消（Cancel），校验失败会自动重试拉取新图。
     */
    private suspend fun solveSliderCaptcha(
        origin: String,
        flowTag: String,
        captchaProvider: SliderCaptchaProvider
    ): Boolean {
        var attempts = 0
        while (attempts < MAX_CAPTCHA_ATTEMPTS) {
            attempts++
            val captcha = fetchSliderCaptcha(origin)
            if (captcha == null) {
                Log.w("WbuSyncEngine", "$flowTag fetch slider captcha failed (attempt $attempts)")
                continue
            }

            when (val result = captchaProvider(captcha)) {
                is SliderCaptchaResult.Cancel -> {
                    Log.w("WbuSyncEngine", "$flowTag captcha cancelled by user")
                    return false
                }
                is SliderCaptchaResult.Refresh -> {
                    Log.i("WbuSyncEngine", "$flowTag captcha refresh requested")
                    continue
                }
                is SliderCaptchaResult.Move -> {
                    val ok = verifySliderCaptcha(origin, result.moveLength)
                    if (ok) {
                        Log.i("WbuSyncEngine", "$flowTag captcha verified on attempt $attempts")
                        return true
                    }
                    Log.w("WbuSyncEngine", "$flowTag captcha verify failed (attempt $attempts)")
                }
            }
        }
        Log.w("WbuSyncEngine", "$flowTag captcha attempts exhausted")
        return false
    }

    private fun encryptCasPassword(password: String, salt: String): String {
        val trimmedSalt = salt.trim()
        if (trimmedSalt.isEmpty()) return password
        return runCatching {
            val keyBytes = trimmedSalt.toByteArray(Charsets.UTF_8)
            val ivText = randomAesString(16)
            val ivBytes = ivText.toByteArray(Charsets.UTF_8)
            val plain = randomAesString(64) + password

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }.getOrElse {
            Log.w("WbuSyncEngine", "CAS password encryption failed, fallback to plain password", it)
            password
        }
    }

    private fun randomAesString(len: Int): String {
        val chars = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
        val out = StringBuilder(len)
        repeat(len) {
            val idx = casRandom.nextInt(chars.length)
            out.append(chars[idx])
        }
        return out.toString()
    }

    private fun resolveAbsoluteUrl(baseUrl: String, maybeRelative: String): String {
        val raw = maybeRelative.trim()
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("//")) {
            val scheme = if (baseUrl.startsWith("https://")) "https:" else "http:"
            return "$scheme$raw"
        }
        val base = baseUrl.toHttpUrlOrNull() ?: return raw
        return base.resolve(raw)?.toString() ?: raw
    }

    private fun extractIndexMainUrlFromScript(html: String): String? {
        val match = Regex("(/admin/indexMain[^\"'\\s]*)", RegexOption.IGNORE_CASE).find(html) ?: return null
        val path = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (path.isBlank()) return null
        return if (path.startsWith("http://") || path.startsWith("https://")) path else "$baseUrl$path"
    }

    private fun extractCasLoginUrlFromHtml(html: String): String? {
        val match = Regex("""([\"'])([^\"']*authserver/login[^\"']*service=[^\"']+)\1""", RegexOption.IGNORE_CASE)
            .find(html)
            ?: return null
        return match.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun pickColorIndexForCourse(courseName: String, poolSize: Int = 12): Int {
        if (poolSize <= 0) return 0
        return kotlin.math.abs(courseName.hashCode()) % poolSize
    }

    private fun canAccessTermApi(): Boolean {
        return runCatching {
            val termReq = Request.Builder()
                .url("$baseUrl/admin/xsd/xsdcjcx/getCurrentXnxq?sf_request_type=ajax")
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()

            client.newCall(termReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (looksLikeHtml(body)) {
                    Log.w("WbuSyncEngine", "Session check failed: term API returned HTML login page.")
                    return@use false
                }

                val json = JSONObject(body)
                val term = json.optString("data", "")
                term.isNotBlank()
            }
        }.getOrElse { e ->
            Log.w("WbuSyncEngine", "Session check failed: ${e.message}", e)
            false
        }
    }

    private fun looksLikeHtml(content: String): Boolean {
        val trimmed = content.trimStart()
        return trimmed.startsWith("<html", ignoreCase = true) ||
            trimmed.startsWith("<!doctype html", ignoreCase = true)
    }

    private fun persistCookieStore() {
        val array = JSONArray()
        cookieStore.forEach { cookie ->
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

    private fun restoreCookieStore() {
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

                val hostOnly = obj.optBoolean("hostOnly", false)
                if (hostOnly) {
                    builder.hostOnlyDomain(domain)
                } else {
                    builder.domain(domain)
                }

                if (obj.optBoolean("persistent", false)) {
                    val expiresAt = obj.optLong("expiresAt", 0L)
                    if (expiresAt > System.currentTimeMillis()) {
                        builder.expiresAt(expiresAt)
                    }
                }

                if (obj.optBoolean("secure", false)) {
                    builder.secure()
                }
                if (obj.optBoolean("httpOnly", false)) {
                    builder.httpOnly()
                }

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

    companion object {
        private const val PREFS_NAME = "wbu_sync_auth"
        private const val KEY_COOKIES_JSON = "cookies_json"
        private const val KEY_LAST_USE_VPN = "last_use_vpn"
        private const val KEY_LAST_USE_VPN_SET = "last_use_vpn_set"
        private const val KEY_LAST_STUDENT_ID = "last_student_id"
        private const val KEY_USE_WEBVIEW_VPN_MANUAL_MODE = "use_webview_vpn_manual_mode"
        private const val KEY_IDS_VIA_WEBVPN = "ids_via_webvpn"
        private const val KEY_QR_VIA_WEBVPN = "qr_via_webvpn"
        private const val KEY_SEND_ENGLISH_SMS = "send_english_sms"
        private const val KEY_USE_PC_USER_AGENT = "use_pc_user_agent"
        private const val KEY_SKIP_CAMPUS_CHECK = "skip_campus_check"
        private const val KEY_KEEP_TEACHER_ID = "keep_teacher_id"
        private const val KEY_KEEP_BUILDING = "keep_building"
        private const val MAX_CAPTCHA_ATTEMPTS = 5

        // 教务系统直连登录（/admin/login）JSEncrypt 硬编码公钥（1024 位 PKCS#1）
        private const val JWXT_RSA_MODULUS = "B3B58F37A7A94BF018359A825981DE8C39E1B41A55602A5D134EBC7C612CB8C9897E0F907FC1E12B40AF2A39E472860E0FBB8F336FBACD0104E84FDFF1E223ACB70C0EC4DD1B2935D884FE0AAC74B5FDB69B757FCDA04A89DF4AD5C2997517C89563B64C303DCE97A1DA3D4A989927A753ECBFC49D2D6EB889CBC1B71F9AF501"
        private const val JWXT_RSA_EXPONENT = "010001"

        /** 「ids 走 WebVPN」：默认关闭（走公网 ids）。 */
        fun getIdsViaWebVpn(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_IDS_VIA_WEBVPN, false)
        }

        fun setIdsViaWebVpn(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_IDS_VIA_WEBVPN, enabled).apply()
        }

        /** 「二维码走 WebVPN」：默认关闭（走公网 ids）。 */
        fun getQrViaWebVpn(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_QR_VIA_WEBVPN, false)
        }

        fun setQrViaWebVpn(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_QR_VIA_WEBVPN, enabled).apply()
        }

        /** 「使用 PC User-Agent」：默认关闭（移动端 UA，服务端返回移动版登录页）。 */
        fun getUsePcUserAgent(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_USE_PC_USER_AGENT, false)
        }

        fun setUsePcUserAgent(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_USE_PC_USER_AGENT, enabled).apply()
        }

        /** 「不检测校园网环境」：为 true 时跳过校园网探测与「未检测到是否继续」确认。默认关闭。 */
        fun getSkipCampusCheck(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_SKIP_CAMPUS_CHECK, false)
        }

        fun setSkipCampusCheck(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_SKIP_CAMPUS_CHECK, enabled).apply()
        }

        /** 「保留教师工号」：为 true 时保留教师名中的工号（如"王老师（20240999）"），false 则去除。默认去除。 */
        fun getKeepTeacherId(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_KEEP_TEACHER_ID, false)
        }

        fun setKeepTeacherId(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_KEEP_TEACHER_ID, enabled).apply()
        }

        /** 「保留建筑名称」：为 true 时教室位置前拼接教学楼名（如"1号楼 101"），false 只显示教室。默认不保留。 */
        fun getKeepBuilding(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_KEEP_BUILDING, false)
        }

        fun setKeepBuilding(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_KEEP_BUILDING, enabled).apply()
        }

        /** 去除教师名末尾工号（如"王老师（20240999）"->"王老师"）；无则原样返回。供安卓端各处复用。 */
        fun cleanTeacherId(teacher: String): String {
            val m = Regex("^(.+?)\\s*[（(](\\d{4,})[）)]$").find(teacher.trim()) ?: return teacher
            return m.groupValues[1].trim()
        }

        fun hasPersistedSession(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return !prefs.getString(KEY_COOKIES_JSON, null).isNullOrBlank()
        }

        fun getSavedUseVpn(context: Context): Boolean? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_LAST_USE_VPN_SET, false)) return null
            return prefs.getBoolean(KEY_LAST_USE_VPN, false)
        }

        fun getSavedStudentId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_LAST_STUDENT_ID, "").orEmpty()
        }

        fun shouldUseManualWebViewForVpn(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_USE_WEBVIEW_VPN_MANUAL_MODE, false)
        }

        fun setManualWebViewForVpn(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_USE_WEBVIEW_VPN_MANUAL_MODE, enabled).apply()
        }

        /** 当前软件语言是否为简体中文（App 语言或跟随系统）。 */
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

        /**
         * 是否发送英语验证码（可能更慢）。
         * 默认：中文（false）；仅当用户显式开启时发英文。简体中文始终强制中文（结合 [isSimplifiedChinese]）。
         */
        fun getSendEnglishSms(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_SEND_ENGLISH_SMS)) {
                return false
            }
            return prefs.getBoolean(KEY_SEND_ENGLISH_SMS, false)
        }

        fun setSendEnglishSms(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_SEND_ENGLISH_SMS, enabled).apply()
        }
    }

    private fun parseWeeks(zc: String, zcstr: String): List<Int> {
        val fromZcstr = zcstr.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
            
        if (fromZcstr.isNotEmpty()) return fromZcstr.sorted().distinct()

        if (zc.isEmpty()) return emptyList()

        val isOddOnly = zc.contains("单")
        val isEvenOnly = zc.contains("双")
        val normalized = zc.replace(Regex("[^\\d,\\-~]"), "")
        
        val baseWeeks = mutableListOf<Int>()
        for (token in normalized.split(",")) {
            val t = token.trim()
            if (t.isEmpty()) continue
            
            val single = t.toIntOrNull()
            if (single != null) {
                baseWeeks.add(single)
                continue
            }
            
            val rangeMatch = Regex("^(\\d+)\\s*[-\\~]\\s*(\\d+)$").find(t)
            if (rangeMatch != null) {
                val start = Math.min(rangeMatch.groupValues[1].toInt(), rangeMatch.groupValues[2].toInt())
                val end = Math.max(rangeMatch.groupValues[1].toInt(), rangeMatch.groupValues[2].toInt())
                for (w in start..end) {
                    baseWeeks.add(w)
                }
            }
        }

        return baseWeeks.filter {
            when {
                isOddOnly -> it % 2 != 0
                isEvenOnly -> it % 2 == 0
                else -> true
            }
        }.sorted().distinct()
    }
}

