package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.util.Log
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request

/**
 * WebVPN 门户登录结果。
 */
sealed class PortalLoginStep {
    data class SmsRequired(
        val maskedPhone: String,
        val isStillValid: Boolean = false,
        val sendInterval: Int = 60,
        val promptText: String = ""
    ) : PortalLoginStep()
    object PortalAuthenticated : PortalLoginStep()
    data class Error(val message: String) : PortalLoginStep()
}

/** WebVPN 短信重发结果（包含服务端下发的最新重发冷却时间）。 */
data class PortalResendSmsResult(
    val success: Boolean,
    val cooldownSeconds: Int = 60
)

/**
 * WebVPN 门户客户端：只负责 Sangfor 门户认证（密码 + 短信 + TWFID），
 * 以及门户 RSA 加密、TWFID 探活。不触碰教务(ids/www)与统一认证。
 */
internal class WebVpnClient(
    private val transport: WbuAuthTransport,
) {
    // 门户认证专用客户端：连接池独立、强制 Host: webvpn.wbu.edu.cn、禁用自动重定向
    private val client = transport.portalClient
    // 强制锁死 WebVPN 门户真实基址，绝不受任何代理镜像或重定向污染
    private val vpnBase = "https://webvpn.wbu.edu.cn"
    private val cookieStore = transport.cookieStore

    /** 手动 TWFID 探活：/por/login_psw.csp 返回 <Result>1</Result> 视为已认证。 */
    suspend fun validateTwfid(twfid: String): Boolean = withContext(Dispatchers.IO) {
        if (twfid.isBlank()) return@withContext false
        try {
            val req = Request.Builder()
                .url("$vpnBase/por/login_psw.csp")
                .header("Cookie", "TWFID=$twfid")
                .get()
                .build()
            val xml = transport.twfidProbeClient.newCall(req).execute().use { it.body?.string().orEmpty() }
            val result = Regex("<Result>\\s*(\\d+)", RegexOption.IGNORE_CASE).find(xml)
                ?.groupValues?.getOrNull(1)
            result == "1"
        } catch (e: Exception) {
            Log.w("WebVpnClient", "validateTwfid failed", e)
            false
        }
    }

    /** 门户密码登录（第一步：RSA 加密 → 提交 → 检测是否需要 SMS）。 */
    suspend fun portalPasswordLogin(studentId: String, password: String): PortalLoginStep =
        withContext(Dispatchers.IO) {
            try {
                val targetUrl = "$vpnBase/por/login_auth.csp?apiversion=1"
                val authXml = client.newCall(
                    Request.Builder()
                        .url(targetUrl)
                        .get()
                        .build()
                ).execute().use { it.body?.string().orEmpty() }

                val rsaKey = extractXmlTag(authXml, "RSA_ENCRYPT_KEY")
                    ?: return@withContext PortalLoginStep.Error("无法获取加密密钥")
                val rsaExp = extractXmlTag(authXml, "RSA_ENCRYPT_EXP") ?: "65537"
                val csrfCode = extractXmlTag(authXml, "CSRF_RAND_CODE") ?: ""
                val nameField = extractXmlTag(authXml, "N_INPUTNAME") ?: "svpn_name"
                val passField = extractXmlTag(authXml, "N_INPUTPASS") ?: "svpn_password"

                val plainForEncrypt = if (csrfCode.isNotBlank()) "${password}_$csrfCode" else password
                val encryptedPassword = rsaEncryptSangfor(plainForEncrypt, rsaKey, rsaExp)

                val form = FormBody.Builder()
                    .add("mitm_result", "")
                    .add("svpn_req_randcode", csrfCode)
                    .add(nameField, studentId)
                    .add(passField, encryptedPassword)
                    .add("svpn_rand_code", "")
                    .build()
                val pswXml = client.newCall(
                    Request.Builder()
                        .url("$vpnBase/por/login_psw.csp?anti_replay=1&encrypt=1&apiversion=1")
                        .post(form)
                        .build()
                ).execute().use { it.body?.string().orEmpty() }
                Log.d("WebVpnClient", "VPN login_psw response: ${pswXml.take(500)}")

                val resultCode = extractXmlTag(pswXml, "Result")
                val errorCode = extractXmlTag(pswXml, "ErrorCode")
                val nextService = extractXmlTag(pswXml, "NextService")
                val nextAuth = extractXmlTag(pswXml, "NextAuth")

                if (errorCode == "20021" || (resultCode == "1" && pswXml.contains("user had logged in", ignoreCase = true))) {
                    // Sangfor 可能返回 "user had logged in"，需用门户探活复核 TWFID 是否真实有效
                    val sessionTwfid = cookieStore.firstOrNull { it.name == "TWFID" }?.value
                    if (sessionTwfid.isNullOrBlank()) {
                        Log.w("WebVpnClient", "VPN login_psw returned 20021 but no TWFID cookie found")
                        return@withContext PortalLoginStep.Error("WebVPN 会话异常（未获取到 TWFID），请重试")
                    }
                    if (validateTwfid(sessionTwfid)) {
                        Log.i("WebVpnClient", "VPN 20021 confirmed with valid TWFID session")
                        transport.persistCookieStore()
                        return@withContext PortalLoginStep.PortalAuthenticated
                    }
                    Log.w("WebVpnClient", "VPN login_psw returned 20021 but TWFID failed validation")
                    return@withContext PortalLoginStep.Error("WebVPN 会话已失效，请重新登录")
                }

                if (resultCode == "2" || nextService?.contains("sms", ignoreCase = true) == true || nextAuth == "2") {
                    // 多阶段认证：短信验证码阶段（严格对齐 webvpn_notes.md §十）
                    val smsIsStillValid = extractXmlTag(pswXml, "SmsIsStillValid") == "1"
                    var sendInterval = extractXmlTag(pswXml, "SmsSendInterval")?.toIntOrNull() ?: 60
                    var maskedPhone = ""
                    var promptText = if (smsIsStillValid) "您的验证码仍在有效期内" else ""

                    if (smsIsStillValid) {
                        Log.i("WebVpnClient", "WebVPN 短信验证码仍在有效期内，无需调用 login_sms 触发发码")
                    } else {
                        // 验证码不在有效期（或首次认证）：调用 /por/login_sms.csp 初始化短信流程，服务端自动向手机下发短信
                        Log.i("WebVpnClient", "WebVPN 短信验证码不在有效期，请求 /por/login_sms.csp 初始化短信流程...")
                        var smsInitSuccess = false
                        try {
                            val smsInfoXml = client.newCall(
                                Request.Builder()
                                    .url("$vpnBase/por/login_sms.csp?apiversion=1")
                                    .post(FormBody.Builder().build())
                                    .build()
                            ).execute().use { it.body?.string().orEmpty() }

                            maskedPhone = extractXmlTag(smsInfoXml, "USER_PHONE").orEmpty()
                            val serverInfor = extractXmlTag(smsInfoXml, "T_SMSINFOR")
                            val interval = extractXmlTag(smsInfoXml, "SmsSendInterval")?.toIntOrNull()
                            if (interval != null && interval > 0) sendInterval = interval
                            if (!serverInfor.isNullOrBlank()) promptText = serverInfor
                            smsInitSuccess = true
                        } catch (e: Exception) {
                            Log.w("WebVpnClient", "请求 login_sms.csp 异常，回退触发 post_sms.csp", e)
                        }

                        if (!smsInitSuccess) {
                            val resendRes = portalResendSms()
                            if (resendRes.success) sendInterval = resendRes.cooldownSeconds
                        }
                    }

                    return@withContext PortalLoginStep.SmsRequired(
                        maskedPhone = maskedPhone,
                        isStillValid = smsIsStillValid,
                        sendInterval = sendInterval,
                        promptText = promptText
                    )
                }

                if (resultCode == "1") {
                    transport.persistCookieStore()
                    return@withContext PortalLoginStep.PortalAuthenticated
                }

                val msg = extractXmlTag(pswXml, "Message") ?: "密码验证失败"
                Log.w("WebVpnClient", "VPN login_psw failed: code=$errorCode result=$resultCode msg=$msg")
                PortalLoginStep.Error(msg)
            } catch (e: Exception) {
                Log.e("WebVpnClient", "VPN password login failed", e)
                PortalLoginStep.Error("网络错误: ${e.message}")
            }
        }

    /** 提交门户短信验证码。 */
    suspend fun portalSubmitSms(smsCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val form = FormBody.Builder()
                .add("svpn_inputsms", smsCode)
                .build()
            val xml = client.newCall(
                Request.Builder()
                    .url("$vpnBase/por/login_sms1.csp?apiversion=1")
                    .post(form)
                    .build()
            ).execute().use { it.body?.string().orEmpty() }
            val ok = extractXmlTag(xml, "ErrorCode") == "1" && extractXmlTag(xml, "Result") == "1"
            if (ok) transport.persistCookieStore()
            ok
        } catch (e: Exception) {
            Log.e("WebVpnClient", "SMS verification failed", e)
            false
        }
    }

    /** 重发门户短信验证码，返回包含冷却秒数的结果。 */
    suspend fun portalResendSms(): PortalResendSmsResult = withContext(Dispatchers.IO) {
        try {
            val xml = client.newCall(
                Request.Builder()
                    .url("$vpnBase/por/post_sms.csp?apiversion=1")
                    .post(FormBody.Builder()
                        .add("phone_number", "")
                        .add("phone_index", "0")
                        .build())
                    .build()
            ).execute().use { it.body?.string().orEmpty() }
            val ok = extractXmlTag(xml, "ErrorCode") == "1"
            val waitSec = extractXmlTag(xml, "SmsSendInterval")?.toIntOrNull()
                ?: extractXmlTag(xml, "disableTime")?.toIntOrNull()
                ?: 60
            PortalResendSmsResult(ok, waitSec)
        } catch (e: Exception) {
            Log.e("WebVpnClient", "Resend SMS failed", e)
            PortalResendSmsResult(false, 60)
        }
    }

    /** 把 TWFID 以 `.webvpn.wbu.edu.cn` domain Cookie 注入 cookieStore。 */
    fun injectTwfid(twfid: String) {
        if (twfid.isBlank()) return
        val remove = cookieStore.removeAll { it.name == "TWFID" && it.domain == "webvpn.wbu.edu.cn" }
        val cookie = okhttp3.Cookie.Builder()
            .name("TWFID")
            .value(twfid.trim())
            .domain("webvpn.wbu.edu.cn")
            .path("/")
            .build()
        cookieStore.add(cookie)
        Log.d("WebVpnClient", "Injected manual TWFID (removed=$remove)")
    }

    /** 移除已注入的 TWFID Cookie。 */
    fun removeTwfidCookie() {
        val removed = cookieStore.removeAll { it.name == "TWFID" }
        if (removed) Log.d("WebVpnClient", "Removed injected TWFID cookie")
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
        if (exp.startsWith("0x", ignoreCase = true)) return BigInteger(exp.substring(2), 16)
        if (exp.matches(Regex("0*10001", RegexOption.IGNORE_CASE))) return BigInteger(exp, 16)
        if (exp.any { it in 'A'..'F' || it in 'a'..'f' }) return BigInteger(exp, 16)
        return BigInteger(exp)
    }

    private fun extractXmlTag(xml: String, tag: String): String? {
        val pattern = Regex("<$tag>(?:<!\\[CDATA\\[(.+?)]]>|([^<]*))</$tag>", RegexOption.IGNORE_CASE)
        val match = pattern.find(xml) ?: return null
        return (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
    }

    companion object {
        /** 手动填写的 WebVPN TWFID（跨重启保留）。 */
        fun getTwfid(context: android.content.Context): String = WbuAuthTransport.getTwfid(context)
        fun setTwfid(context: android.content.Context, value: String) = WbuAuthTransport.setTwfid(context, value)
        fun clearTwfid(context: android.content.Context) = WbuAuthTransport.clearTwfid(context)

        /** 「使用 https 访问 WebVPN」：默认关闭。 */
        fun getUseHttpsWebVpn(context: android.content.Context): Boolean =
            WbuAuthTransport.getUseHttpsWebVpn(context)
        fun setUseHttpsWebVpn(context: android.content.Context, enabled: Boolean) =
            WbuAuthTransport.setUseHttpsWebVpn(context, enabled)

        /** 是否强制用 WebView 手动登录 WebVPN。 */
        fun shouldUseManualWebViewForVpn(context: android.content.Context): Boolean =
            WbuAuthTransport.shouldUseManualWebViewForVpn(context)
        fun setManualWebViewForVpn(context: android.content.Context, enabled: Boolean) =
            WbuAuthTransport.setManualWebViewForVpn(context, enabled)
    }
}
