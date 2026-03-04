package com.xingheyuzhuan.classflow.data.network.wbu

import android.content.Context
import android.util.Base64
import android.util.Log
import com.xingheyuzhuan.classflow.data.db.main.Course
import com.xingheyuzhuan.classflow.data.db.main.CourseWithWeeks
import com.xingheyuzhuan.classflow.data.db.main.CourseWeek
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

class WbuSyncEngine(
    private val context: Context,
    private val useVpn: Boolean = false,
) {

    private val casRandom = SecureRandom()

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val cookieStore = CopyOnWriteArrayList<Cookie>()

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
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
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

    /**
     * 第一步：模拟登录获取 Session 和内部校验信息
     */
    suspend fun login(studentId: String, password: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val success = if (useVpn) {
                loginViaVpnCas(studentId, password)
            } else {
                loginDirect(studentId, password)
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
     * 从 Android WebView CookieManager 导入 cookies 到 OkHttp cookie jar。
     * 用于 WebView 手动登录 WebVPN 后桥接 session。
     */
    fun importCookiesFromWebView(cookieManager: android.webkit.CookieManager) {
        val urls = listOf(
            "https://webvpn.wbu.edu.cn",
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
     */
    suspend fun loginVpnFull(
        studentId: String,
        password: String,
        smsCodeProvider: suspend (maskedPhone: String) -> String?,
        statusCallback: ((VpnFullLoginStatus) -> Unit)? = null
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

        // WebVPN 已认证，接下来走 CAS 登录到教务系统
        val casOk = withContext(Dispatchers.IO) {
            loginViaVpnCas(studentId, password)
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

            val courses = mutableListOf<CourseWithWeeks>()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(i) ?: continue

                // 使用和 js 相同的逻辑进行字段提取
                val fromKcmc = cleanImportedText(item.optString("kcmc", ""))
                val fromJxbmc = cleanImportedText(item.optString("jxbmc", ""))
                val name = when {
                    fromKcmc.isNotBlank() -> fromKcmc
                    fromJxbmc.isNotBlank() -> fromJxbmc
                    else -> "未命名课程"
                }

                val teacher = cleanImportedText(item.optString("tmc", ""))
                val building = cleanImportedText(item.optString("jxlmc", ""))
                val room = cleanImportedText(item.optString("croommc", ""))
                val position = if (building.isNotEmpty() && room.isNotEmpty() && !room.contains(building)) {
                    "$building $room"
                } else {
                    room.ifEmpty { building }
                }

                val day = item.optInt("xingqi", 1).coerceIn(1..7)
                
                // 节次解析
                var startSection = 1
                val rqxl = item.optString("rqxl", "")
                if (rqxl.matches(Regex("^\\d{3,4}$"))) {
                    startSection = (rqxl.toIntOrNull() ?: 100) % 100
                } else {
                    startSection = item.optInt("djc", 1)
                }
                startSection = startSection.coerceIn(1..30)

                val duration = item.optInt("djs", 1).coerceIn(1..8)
                val endSection = startSection + duration - 1

                // 周次解析
                val weekNumbers = parseWeeks(
                    cleanImportedText(item.optString("zc", "")),
                    cleanImportedText(item.optString("zcstr", ""))
                )

                val courseId = java.util.UUID.randomUUID().toString()
                
                val course = Course(
                    id = courseId,
                    courseTableId = tableId,
                    name = name,
                    day = day,
                    startSection = startSection,
                    endSection = endSection,
                    teacher = teacher,
                    position = position,
                    isCustomTime = false,
                    customStartTime = null,
                    customEndTime = null,
                    colorInt = pickColorIndexForCourse(name)
                )

                val courseWeeks = weekNumbers.map {
                    CourseWeek(courseId = courseId, weekNumber = it)
                }

                if (courseWeeks.isNotEmpty()) {
                    courses.add(CourseWithWeeks(course, courseWeeks))
                }
            }

            // 合并连续节次（同一天、同一名字老师地点和周次）
            val merged = mergeContinuousSections(courses)
            Log.i("WbuSyncEngine", "Fetch course data done. parsed=${courses.size} merged=${merged.size}")
            return@withContext merged

        } catch (e: Exception) {
            Log.e("WbuSyncEngine", "Fetch failed", e)
            null
        }
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

    private fun loginDirect(studentId: String, password: String): Boolean {
        // Keep direct campus flow deterministic:
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
            flowTag = "DIRECT-CAS"
        )
        if (directCasOk) return true

        Log.w("WbuSyncEngine", "Direct fixed CAS flow failed, fallback to legacy /admin/login form")

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
        formBuilder.add("login_name", studentId)
        formBuilder.add("password", password)
        if (!hiddenFields.containsKey("loginType")) {
            formBuilder.add("loginType", "1")
        }

        val loginPostReq = Request.Builder()
            .url("$baseUrl/admin/login")
            .post(formBuilder.build())
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()

        client.newCall(loginPostReq).execute().use { postResp ->
            val postRespString = postResp.body?.string().orEmpty()
            val finalUrl = postResp.request.url.toString()
            val success = finalUrl.contains("/admin/index") ||
                finalUrl.contains("/admin/?loginType=1") ||
                postRespString.contains("退出") ||
                postRespString.contains("我的课表")
            if (!success) {
                Log.d("WbuSyncEngine", "Direct login response URL=$finalUrl")
                Log.d("WbuSyncEngine", "Direct login response body snippet=${postRespString.take(500)}")
            }
            if (!success) {
                return false
            }

            return canAccessTermApi()
        }
    }

    private fun loginViaCas(
        studentId: String,
        password: String,
        idsLoginUrl: String,
        flowTag: String
    ): Boolean {
        val hiddenFields = mutableMapOf<String, String>()
        var pwdEncryptSalt = ""

        client.newCall(
            Request.Builder().url(idsLoginUrl).get().build()
        ).execute().use { pageResp ->
            val loginHtml = pageResp.body?.string().orEmpty()
            if (loginHtml.isBlank()) {
                Log.w("WbuSyncEngine", "$flowTag CAS login page is blank. idsLoginUrl=$idsLoginUrl")
                return false
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
                    return false
                }
            }

            pwdForm.select("input[type=hidden][name]").forEach { input ->
                val key = input.attr("name")
                if (key.isNotBlank()) {
                    hiddenFields[key] = input.attr("value")
                }
            }

            pwdEncryptSalt = pwdForm.selectFirst("#pwdEncryptSalt")?.attr("value").orEmpty()
            val scriptNeedCaptcha = Regex("""needCaptcha\s*=\s*['\"]?true['\"]?""", RegexOption.IGNORE_CASE)
                .containsMatchIn(loginHtml)
            if (scriptNeedCaptcha) {
                Log.w("WbuSyncEngine", "$flowTag CAS requires captcha; skip auto submit")
                return false
            }
        }

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

        val origin = idsLoginUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }
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
            }
        }
        if (casPostStaysOnLogin) return false

        client.newCall(Request.Builder().url("$baseUrl/admin/login").get().build()).execute().close()
        val loginTypeHtml = client.newCall(
            Request.Builder().url("$baseUrl/admin/?loginType=1").get().build()
        ).execute().use { it.body?.string().orEmpty() }

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
                Log.w("WbuSyncEngine", "$flowTag open indexMain failed: ${it.message}")
            }
        }

        return canAccessTermApi()
    }

    private fun loginViaVpnCas(studentId: String, password: String): Boolean {
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
            flowTag = "VPN-CAS"
        )
        if (!ready) {
            Log.w("WbuSyncEngine", "VPN CAS flow completed but JWXT term API is still unavailable. baseUrl=$baseUrl")
        }
        return ready
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

    private fun mergeContinuousSections(courses: List<CourseWithWeeks>): List<CourseWithWeeks> {
        if (courses.isEmpty()) return emptyList()

        val sorted = courses.sortedWith(compareBy(
            { it.course.day },
            { it.weeks.joinToString { w -> w.weekNumber.toString() } },
            { it.course.name },
            { it.course.teacher },
            { it.course.position },
            { it.course.startSection ?: 0 }
        ))

        val merged = mutableListOf<CourseWithWeeks>()
        var i = 0

        while (i < sorted.size) {
            val current = sorted[i]
            var currentEnd = current.course.endSection
            var j = i + 1

            while (j < sorted.size) {
                val next = sorted[j]
                val currentWeeksStr = current.weeks.map { it.weekNumber }.joinToString()
                val nextWeeksStr = next.weeks.map { it.weekNumber }.joinToString()

                if (next.course.day == current.course.day &&
                    next.course.name == current.course.name &&
                    next.course.teacher == current.course.teacher &&
                    next.course.position == current.course.position &&
                    nextWeeksStr == currentWeeksStr &&
                    (next.course.startSection ?: 0) == (currentEnd ?: 0) + 1
                ) {
                    currentEnd = next.course.endSection
                    j++
                } else {
                    break
                }
            }

            val mergedCourse = current.course.copy(endSection = currentEnd)
            val mergedWeeks = current.weeks.map { it.copy(courseId = mergedCourse.id) }
            merged.add(CourseWithWeeks(mergedCourse, mergedWeeks))
            i = j
        }
        return merged
    }
}

