package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.util.Log
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Request

/**
 * TWFID 探活结论。
 *
 * 做三态是刻意的：探活本身可能因为网络/门户不可达而失败，那时**不能**说用户的会话失效 ——
 * 调用方一旦按「无效」处理，就会把本地凭据清掉（用户被迫重新输密码，甚至白烧一次登录机会）。
 */
enum class TwfidState {
    /** 服务端确认该会话已认证（`/por/conf.csp` 放行）。 */
    VALID,

    /** 服务端明确说没有会话/未认证（20026 NOSESSION）。 */
    NOT_AUTHENTICATED,

    /** 这次没问出来（网络异常、响应无法解析）。保留本地会话，交由调用方按「暂时说不准」处理。 */
    UNKNOWN,
}

/**
 * WebVPN 门户登录结果。
 */
sealed class PortalLoginStep {
    data class SmsRequired(
        val maskedPhone: String,
        val isStillValid: Boolean = false,
        /**
         * 服务端要求的下一次可重发倒计时（**秒**）。
         * `0` = 服务端不限制重发，界面不要自己补 60（门户前端：`disableTime = SmsSendInterval || SmsIsStillValid || 0`）。
         */
        val sendInterval: Int = 0,
        val promptText: String = ""
    ) : PortalLoginStep()
    object PortalAuthenticated : PortalLoginStep()
    data class Error(val message: String) : PortalLoginStep()
}

/** 图形验证码图片由门户会话下载，交给 UI 供用户手动辨认。 */
data class PortalCaptchaData(
    val imageBytes: ByteArray,
    val attempt: Int = 1,
    val previousErrorCode: String = ""
)

/** 用户对 WebVPN 图形验证码弹窗的操作。 */
sealed class PortalCaptchaResult {
    data class Submit(val code: String) : PortalCaptchaResult()
    object Refresh : PortalCaptchaResult()
    object Cancel : PortalCaptchaResult()
}

typealias PortalCaptchaProvider = suspend (PortalCaptchaData) -> PortalCaptchaResult

/**
 * 规范化用户粘贴的 TWFID。
 *
 * 支持直接粘贴整行 Cookie：`TWFID=xxxx; path=/; domain=webvpn.wbu.edu.cn`、
 * 带引号或前后空白；也支持只粘 16 位十六进制值本身（门户 Application → Cookies 里的取值）。
 */
internal fun normalizeTwfid(raw: String?): String {
    var value = raw.orEmpty().trim().trim('"', '\'')
    if (value.contains('=')) {
        Regex("(?i)twfid\\s*=\\s*([^;\\s]+)").find(value)?.let { value = it.groupValues[1] }
    }
    return value.substringBefore(';').trim().trim('"', '\'')
}

/** TWFID 的形状：真机上是 16 位十六进制（64 bit）。 */
internal fun looksLikeTwfid(value: String): Boolean = value.matches(Regex("(?i)[0-9a-f]{8,64}"))

/**
 * 是否要求图形验证码。
 *
 * 门户自己的判定读的是 `/public/psw_config` 的 `USE_RAND_CODE`
 * （jssdk/common/dto.js：`enableRandCode = USE_RAND_CODE`），`login_auth` 的 `RndImg` 是同义冗余信号，两者取或。
 * 注意：门户从不回 `ENABLE_RANDCODE` 响应头，不要拿它当判据。
 */
internal fun isWebVpnCaptchaEnabled(rndImg: String?, useRandCode: String?): Boolean =
    rndImg == "1" || useRandCode == "1"

/**
 * 图形验证码类错误码（对齐门户 jssdk/common/errcode.js）：
 * 20023 ERRCODE_EAUTH_RAND_CODE、20041 RANDCODE_ON、20042 CRACK_RETURN、
 * 20043 RANDCODE_ON_RETURN、20053 RANDCODE_ON_PWDERR、20268 VERIFYCODE_TIMEOUT。
 *
 * 判定只看 ErrorCode —— 官方前端就是这么判的（dto.js::authData 的 code 取自 ErrorCode，不是 Result）。
 */
internal val WEBVPN_CAPTCHA_ERROR_CODES: Set<String> =
    setOf("20023", "20041", "20042", "20043", "20053", "20268")

internal fun isWebVpnCaptchaError(errorCode: String?): Boolean =
    errorCode != null && WEBVPN_CAPTCHA_ERROR_CODES.contains(errorCode)

/** WebVPN 短信重发结果（包含服务端下发的最新重发冷却秒数，0 = 服务端不限制）。 */
data class PortalResendSmsResult(
    val success: Boolean,
    val cooldownSeconds: Int = 0
)

/**
 * WebVPN 门户客户端：只负责 Sangfor 门户认证（密码 + 短信 + TWFID），
 * 以及门户 RSA 加密、TWFID 探活。不触碰教务(ids/www)与统一认证。
 *
 * 协议要点（对齐门户前端 jssdk 与线上实测）：
 * - 会话探活走网关接口 `/por/conf.csp`：放行 = 已认证，20026 = 没有可用会话。
 *   **不要**用 `/por/login_psw.csp` 探活 —— 它是登录接口，空凭据访问会被服务端当一次
 *   失败登录记账（实测返回 20004 "Invalid username or password!"，反复调用会把整个出口 IP
 *   推进「需要图形验证码」的风控态）。
 * - 登录成功的判据是 `ErrorCode == 1`（或 20021 已登录），而不是 `Result`；并且
 *   `ErrorCode == 1` 但只要还有 `NextService` 就说明门户还差一步，不能算登录完成。
 */
internal class WebVpnClient(
    private val transport: WbuAuthTransport,
) {
    // 门户认证专用客户端：连接池独立、强制 Host: webvpn.wbu.edu.cn、禁用自动重定向
    private val client = transport.portalClient
    // 强制锁死 WebVPN 门户真实基址，绝不受任何代理镜像或重定向污染
    private val vpnBase = "https://webvpn.wbu.edu.cn"
    private val cookieStore = transport.cookieStore

    // ------------------- TWFID 探活 -------------------

    /**
     * TWFID 探活（三态）。持 `Cookie: TWFID=<id>` 请求 `/por/conf.csp`：
     * 返回 `<Conf` 即已认证；20026 表示服务端没有可用会话；其余（含网络异常）一律 UNKNOWN。
     *
     * 这个接口只读、不消耗登录尝试，可以放心在启动/进入页面时调用。
     */
    suspend fun probeTwfid(twfid: String): TwfidState = withContext(Dispatchers.IO) {
        probeTwfidBlocking(twfid)
    }

    /**
     * 兼容入口：只有服务端确认「已认证」才返回 true。
     * 需要区分「明确未认证」与「这次没问出来」时请直接用 [probeTwfid] ——
     * 只有前者才允许清掉本地会话。
     */
    suspend fun validateTwfid(twfid: String): Boolean = probeTwfid(twfid) == TwfidState.VALID

    private fun probeTwfidBlocking(rawTwfid: String?): TwfidState {
        val twfid = normalizeTwfid(rawTwfid)
        if (twfid.isBlank()) return TwfidState.NOT_AUTHENTICATED
        return try {
            val req = Request.Builder()
                .url("$vpnBase/por/conf.csp?apiversion=1")
                .header("Cookie", "TWFID=$twfid")
                .get()
                .build()
            val body = transport.twfidProbeClient.newCall(req).execute().use { it.body?.string().orEmpty() }
            val errorCode = extractXmlTag(body, "ErrorCode")
            when {
                body.contains("<Conf") -> TwfidState.VALID
                errorCode == "20026" || errorCode == "20035" -> TwfidState.NOT_AUTHENTICATED
                body.contains("session timedout", ignoreCase = true) -> TwfidState.NOT_AUTHENTICATED
                else -> {
                    Log.w(TAG, "TWFID 探活无法判定: ${summarizeAuthXml(body)}")
                    TwfidState.UNKNOWN
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TWFID 探活未能完成（网络/门户不可达）", e)
            TwfidState.UNKNOWN
        }
    }

    // ------------------- 会话读写 -------------------

    /** 当前 Cookie 罐里的 TWFID（优先取未过期、门户域名的那个）。 */
    private fun currentJarTwfid(): String? {
        val now = System.currentTimeMillis()
        val portalOwned = cookieStore.lastOrNull {
            it.name == "TWFID" && it.value.isNotBlank() && it.expiresAt > now &&
                (it.domain == PORTAL_HOST || it.domain.endsWith(".$PORTAL_HOST"))
        }?.value
        return portalOwned ?: cookieStore.lastOrNull { it.name == "TWFID" && it.value.isNotBlank() }?.value
    }

    /**
     * 登录失败/取消时的收尾：丢掉本次尝试中新发的（未认证）TWFID，还原登录前那个。
     *
     * 不这么做的话，共享 Cookie 罐里会留下一个「有 TWFID 但没认证」的会话槽位 ——
     * 而 TWFID 只是会话槽位、不是凭证（未认证槽位访问受保护接口会被 20026 拒绝），
     * 本地那些「只看 Cookie 名字」的会话判断（[WbuAuthTransport.hasLocalSession]）就会被骗过去，
     * 后续请求带着它被网关接管到门户页，表现为「明明没登录却以为登录了」。
     */
    private fun discardFreshTwfid(previousTwfid: String?) {
        val previous = normalizeTwfid(previousTwfid)
        val removed = cookieStore.removeAll {
            it.name == "TWFID" && normalizeTwfid(it.value) != previous
        }
        if (previous.isNotBlank() &&
            cookieStore.none { it.name == "TWFID" && normalizeTwfid(it.value) == previous }
        ) {
            cookieStore.add(
                Cookie.Builder()
                    .name("TWFID")
                    .value(previous)
                    .domain(PORTAL_HOST)
                    .path("/")
                    .build()
            )
        }
        if (removed) Log.i(TAG, "已丢弃本次登录尝试产生的未认证 TWFID")
    }

    private fun failure(previousTwfid: String?, message: String): PortalLoginStep {
        discardFreshTwfid(previousTwfid)
        return PortalLoginStep.Error(message)
    }

    // ------------------- 门户密码登录 -------------------

    /** 门户密码登录（第一步：按需人工输入图形验证码，RSA 加密 → 提交 → 检测是否需要短信）。 */
    suspend fun portalPasswordLogin(
        studentId: String,
        password: String,
        captchaProvider: PortalCaptchaProvider? = null
    ): PortalLoginStep =
        withContext(Dispatchers.IO) {
            // 登录前先把手上那个 TWFID 记下来：失败时要把共享 Cookie 罐还原成这个样子
            val previousTwfid = currentJarTwfid()
            try {
                var captchaRequired = false
                var captchaErrorCode = ""
                var attackSignals = 0
                var pswXml = ""

                for (attempt in 1..MAX_PASSWORD_ATTEMPTS) {
                    val authXml = getText("$vpnBase/por/login_auth.csp?apiversion=1")
                        ?: return@withContext failure(previousTwfid, "无法连接 WebVPN 门户，请检查网络")
                    // 门户前端同样先拉 psw_config：RSA key / CSRF_RAND_CODE / 验证码开关 / 表单字段名都在这里
                    val pswConfigXml = getText("$vpnBase/public/psw_config").orEmpty()

                    val rsaKey = extractXmlTag(pswConfigXml, "RSA_ENCRYPT_KEY")
                        ?: extractXmlTag(authXml, "RSA_ENCRYPT_KEY")
                        ?: return@withContext failure(previousTwfid, "无法获取加密密钥")
                    val rsaExp = extractXmlTag(pswConfigXml, "RSA_ENCRYPT_EXP")
                        ?: extractXmlTag(authXml, "RSA_ENCRYPT_EXP")
                        ?: "65537"
                    val csrfCode = extractXmlTag(pswConfigXml, "CSRF_RAND_CODE")
                        ?: extractXmlTag(authXml, "CSRF_RAND_CODE").orEmpty()
                    val nameField = extractXmlTag(pswConfigXml, "N_INPUTNAME") ?: "svpn_name"
                    val passField = extractXmlTag(pswConfigXml, "N_INPUTPASS") ?: "svpn_password"
                    val randCodeUrl = extractXmlTag(pswConfigXml, "RAND_CODE_URL")

                    if (!captchaRequired &&
                        isWebVpnCaptchaEnabled(
                            extractXmlTag(authXml, "RndImg"),
                            extractXmlTag(pswConfigXml, "USE_RAND_CODE")
                        )
                    ) {
                        captchaRequired = true
                        Log.i(TAG, "服务端要求图形验证码（RndImg/USE_RAND_CODE）")
                    }

                    var captchaCode = ""
                    if (captchaRequired) {
                        val provider = captchaProvider
                            ?: return@withContext failure(
                                previousTwfid,
                                "WebVPN 需要图形验证码，但当前没有可用的输入界面"
                            )
                        while (true) {
                            val image = fetchPortalCaptcha(randCodeUrl)
                                ?: return@withContext failure(previousTwfid, "获取 WebVPN 图形验证码失败，请重试")
                            when (val input = provider(PortalCaptchaData(image, attempt, captchaErrorCode))) {
                                is PortalCaptchaResult.Submit -> {
                                    captchaCode = input.code.trim()
                                    if (captchaCode.length != CAPTCHA_LENGTH) {
                                        return@withContext failure(previousTwfid, "图形验证码应为 4 位")
                                    }
                                    break
                                }
                                PortalCaptchaResult.Refresh -> continue
                                PortalCaptchaResult.Cancel -> return@withContext failure(
                                    previousTwfid,
                                    "已取消 WebVPN 图形验证码输入"
                                )
                            }
                        }
                    }

                    val plainForEncrypt = if (csrfCode.isNotBlank()) "${password}_$csrfCode" else password
                    val form = FormBody.Builder()
                        .add("mitm_result", "")
                        .add("svpn_req_randcode", csrfCode)
                        .add(nameField, studentId)
                        .add(passField, rsaEncryptSangfor(plainForEncrypt, rsaKey, rsaExp))
                        .add("svpn_rand_code", captchaCode)
                        .build()
                    pswXml = postText("$vpnBase/por/login_psw.csp?anti_replay=1&encrypt=1&apiversion=1", form).orEmpty()
                    Log.d(TAG, "login_psw -> ${summarizeAuthXml(pswXml)}")

                    val errorCode = extractXmlTag(pswXml, "ErrorCode")
                    if (isWebVpnCaptchaError(errorCode)) {
                        if (errorCode == "20041" && ++attackSignals >= MAX_ATTACK_SIGNALS) {
                            return@withContext failure(
                                previousTwfid,
                                "WebVPN 连续触发登录风控，已停止重试；请稍后再登录"
                            )
                        }
                        captchaRequired = true
                        captchaErrorCode = errorCode.orEmpty()
                        Log.i(TAG, "门户要求图形验证码 (ErrorCode=$errorCode)，重试 $attempt/$MAX_PASSWORD_ATTEMPTS")
                        if (attempt < MAX_PASSWORD_ATTEMPTS) continue
                        return@withContext failure(previousTwfid, "图形验证码多次未通过，请稍后再试")
                    }
                    break
                }

                if (pswXml.isBlank()) {
                    return@withContext failure(previousTwfid, "WebVPN 登录尝试次数已达上限")
                }

                val errorCode = extractXmlTag(pswXml, "ErrorCode")
                val resultCode = extractXmlTag(pswXml, "Result")
                val nextService = extractXmlTag(pswXml, "NextService")
                val nextAuth = extractXmlTag(pswXml, "NextAuth")

                // 门户前端（dto.js::authData）的判定：code 取自 ErrorCode；code==1 但只要还有
                // NextService 就改记成「还有下一步」(2)。所以「有下一步」时绝不能判成功。
                if (!nextService.isNullOrBlank() || nextAuth == "2" || errorCode == "20011") {
                    if (isSmsNextService(errorCode, nextService, nextAuth)) {
                        return@withContext smsStep(pswXml)
                    }
                    // 动态口令/令牌、挑战-应答、证书、硬件特征码等：本应用没有对应输入界面，
                    // 明确报出来，不要拿短信框去凑（更不要当成登录成功）。
                    return@withContext failure(
                        previousTwfid,
                        "该账号还需要${describeSecondFactor(nextService, nextAuth)}，" +
                            "请先在浏览器里打开门户完成登录，再回到本应用"
                    )
                }

                val authenticated = errorCode == "1" || errorCode == "20021" ||
                    (errorCode.isNullOrBlank() && resultCode == "1")
                if (!authenticated) {
                    return@withContext failure(
                        previousTwfid,
                        extractXmlTag(pswXml, "Message")
                            ?: extractXmlTag(pswXml, "Note")
                            ?: "WebVPN 密码验证失败"
                    )
                }

                // 服务端说成功还不算数：用网关接口确认这个会话真能过，再把 TWFID 交出去
                when (probeTwfidBlocking(currentJarTwfid())) {
                    TwfidState.VALID -> {
                        transport.persistCookieStore()
                        PortalLoginStep.PortalAuthenticated
                    }
                    TwfidState.NOT_AUTHENTICATED -> {
                        Log.w(TAG, "门户报告登录成功，但会话过不了 conf.csp")
                        failure(previousTwfid, "WebVPN 登录流程走完了，但拿到的会话没生效，请重试")
                    }
                    TwfidState.UNKNOWN -> {
                        // 复核本身没做成（网络抖动）时不推翻服务端明确的成功，也不清会话
                        Log.w(TAG, "门户报告登录成功，但会话复核未能完成，先接受该会话")
                        transport.persistCookieStore()
                        PortalLoginStep.PortalAuthenticated
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebVPN password login failed", e)
                failure(previousTwfid, "网络错误: ${e.message}")
            }
        }

    /**
     * 短信二次认证阶段。
     *
     * 秒数全部以服务端为准：
     * - `SmsIsStillValid` 是**剩余秒数**（门户 dto.js 用 parseInt 解析它，auth_sms.js 还把它当作
     *   disableTime 的兜底值），不是 0/1 开关 —— 之前按 `== "1"` 判会把「还有几十秒有效」误判成
     *   「旧码已失效」，从而重复请求发码；
     * - `SmsSendInterval` 是下一次可重发的倒计时秒数，**0 = 服务端不限制**，界面不要自己补 60。
     */
    private suspend fun smsStep(pswXml: String): PortalLoginStep {
        val stillValidSecondsFromLogin = extractXmlTag(pswXml, "SmsIsStillValid")?.toIntOrNull() ?: 0
        var stillValidSeconds = stillValidSecondsFromLogin
        var sendInterval = extractXmlTag(pswXml, "SmsSendInterval")?.toIntOrNull() ?: 0
        var maskedPhone = extractXmlTag(pswXml, "Phone")
            ?: extractXmlTag(pswXml, "USER_PHONE")
            ?: ""
        var promptText = if (stillValidSeconds > 0) SMS_STILL_VALID_TEXT else ""

        // 进入短信阶段时问一次短信通道（门户前端进入短信页也是这么做的，服务端可能据此下发）
        val smsInfoXml = postText("$vpnBase/por/login_sms.csp?apiversion=1", FormBody.Builder().build())
        if (smsInfoXml != null) {
            extractXmlTag(smsInfoXml, "USER_PHONE")?.takeIf { it.isNotBlank() }?.let { maskedPhone = it }
            extractXmlTag(smsInfoXml, "T_SMSINFOR")?.takeIf { it.isNotBlank() }?.let { promptText = it }
            val inPeriod = extractXmlTag(smsInfoXml, "IS_IN_PERIOD") == "1"
            extractXmlTag(smsInfoXml, "SmsIsStillValid")?.toIntOrNull()?.let { if (it > 0) stillValidSeconds = it }
            extractXmlTag(smsInfoXml, "SmsSendInterval")?.toIntOrNull()?.let { sendInterval = it }
            if ((inPeriod || stillValidSeconds > 0) && promptText.isBlank()) {
                promptText = SMS_STILL_VALID_TEXT
            }
        } else {
            // login_sms.csp 没通：退回 post_sms.csp，至少拿服务端要求的重发间隔
            val resend = portalResendSms()
            if (resend.success) sendInterval = resend.cooldownSeconds
        }

        return PortalLoginStep.SmsRequired(
            maskedPhone = maskedPhone,
            isStillValid = stillValidSeconds > 0,
            sendInterval = resendCooldownSeconds(sendInterval, stillValidSeconds),
            promptText = promptText
        )
    }

    /**
     * 重发冷却收口：门户口径是 `disableTime = SmsSendInterval || SmsIsStillValid || 0`。
     *
     * 但 `SmsIsStillValid` 有两种可能含义 —— 「剩余秒数」（例如 42）或布尔式的 `1`；
     * 而门户 UI 的 `countDown()` 只在 `1 < disableTime` 时才真的计时，`1` 与 `0` 都等于「不锁」。
     * 所以这里把 <=1 秒一律收成 0：既不会闪出「重新获取(1)」这种 1 秒倒计时，
     * 也不影响服务端真的要你等一会儿（>1 秒）的场景。
     */
    private fun resendCooldownSeconds(sendInterval: Int, stillValidSeconds: Int): Int = when {
        sendInterval > 1 -> sendInterval
        stillValidSeconds > 1 -> stillValidSeconds
        else -> 0
    }

    private fun isSmsNextService(errorCode: String?, nextService: String?, nextAuth: String?): Boolean =
        nextAuth == "2" || errorCode == "20011" ||
            nextService?.contains("sms", ignoreCase = true) == true

    private fun describeSecondFactor(nextService: String?, nextAuth: String?): String {
        val name = nextService.orEmpty().lowercase()
        return when {
            "token" in name || "totp" in name -> "动态口令（令牌）二次验证"
            "radius" in name || "challenge" in name -> "挑战-应答二次验证"
            "cert" in name -> "证书二次验证"
            "hid" in name -> "硬件特征码绑定"
            nextAuth == "1" -> "额外一步认证"
            else -> "额外一步认证（${nextService ?: "未知步骤"}）"
        }
    }

    /** 提交门户短信验证码。 */
    suspend fun portalSubmitSms(smsCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val xml = postText(
                "$vpnBase/por/login_sms1.csp?apiversion=1",
                FormBody.Builder().add("svpn_inputsms", smsCode).build()
            ).orEmpty()
            Log.i(TAG, "login_sms1 -> ${summarizeAuthXml(xml)}")
            val errorCode = extractXmlTag(xml, "ErrorCode")
            // 官方前端只看 ErrorCode（1 = 成功）：Result 不是成功判据，不能要求它也必须等于 1
            val ok = errorCode == "1" ||
                (errorCode.isNullOrBlank() && extractXmlTag(xml, "Result") == "1")
            if (!ok) return@withContext false
            when (probeTwfidBlocking(currentJarTwfid())) {
                TwfidState.NOT_AUTHENTICATED -> {
                    Log.w(TAG, "短信验证通过，但会话仍未认证")
                    false
                }
                else -> {
                    transport.persistCookieStore()
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS verification failed", e)
            false
        }
    }

    /** 取验证码图片；服务端给了 `RAND_CODE_URL` 就按它取，否则退回 `/por/rand_code.csp`。 */
    private fun fetchPortalCaptcha(randCodeUrl: String?): ByteArray? = try {
        val path = randCodeUrl?.takeIf { it.isNotBlank() } ?: "/por/rand_code.csp"
        val base = if (path.startsWith("http", ignoreCase = true)) path else "$vpnBase$path"
        val url = if (base.contains("rnd=")) {
            base
        } else {
            base + (if (base.contains('?')) "&" else "?") + "rnd=" + Random.nextDouble()
        }
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val contentType = response.header("Content-Type").orEmpty()
            val bytes = if (response.isSuccessful) response.body?.bytes() else null
            val looksLikeImage = contentType.startsWith("image/", ignoreCase = true) ||
                bytes?.let {
                    it.size >= 3 && (
                        it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() ||
                            it.size >= 8 && it.copyOfRange(0, 8).contentEquals(
                                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                            ) ||
                            it[0] == 'G'.code.toByte() && it[1] == 'I'.code.toByte() && it[2] == 'F'.code.toByte()
                        )
                } == true
            if (bytes != null && bytes.isNotEmpty() && looksLikeImage) {
                bytes
            } else {
                Log.w(TAG, "Unexpected captcha response: ${response.code}, $contentType")
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to fetch portal captcha", e)
        null
    }

    /** 重发门户短信验证码，返回服务端要求的下一次可重发秒数（0 = 不限制）。 */
    suspend fun portalResendSms(): PortalResendSmsResult = withContext(Dispatchers.IO) {
        try {
            val xml = postText(
                "$vpnBase/por/post_sms.csp?apiversion=1",
                FormBody.Builder()
                    .add("phone_number", "")
                    .add("phone_index", "0")
                    .build()
            ).orEmpty()
            Log.i(TAG, "post_sms -> ${summarizeAuthXml(xml)}")
            val ok = extractXmlTag(xml, "ErrorCode") == "1"
            // 官方口径：disableTime = SmsSendInterval || SmsIsStillValid || 0；0 就是「不限制」，
            // 不要自己补一个 60 秒（那只是 UI 猜测，会把用户可以立刻重发的窗口白白锁上）。
            val rawCooldown = extractXmlTag(xml, "SmsSendInterval")?.toIntOrNull()
                ?: extractXmlTag(xml, "SmsIsStillValid")?.toIntOrNull()
                ?: extractXmlTag(xml, "disableTime")?.toIntOrNull()
                ?: 0
            // 同上：<=1 秒按「不锁」处理（SmsIsStillValid 可能是布尔 1）
            PortalResendSmsResult(ok, if (rawCooldown > 1) rawCooldown else 0)
        } catch (e: Exception) {
            Log.e(TAG, "Resend SMS failed", e)
            PortalResendSmsResult(false, 0)
        }
    }

    /** 把 TWFID 以 `webvpn.wbu.edu.cn` domain Cookie 注入 cookieStore（兼容整行 Cookie 粘贴）。 */
    fun injectTwfid(twfid: String) {
        val value = normalizeTwfid(twfid)
        if (value.isBlank()) return
        if (!looksLikeTwfid(value)) {
            Log.w(TAG, "TWFID 形状可疑（期望 16 位十六进制，实际长度 ${value.length}）")
        }
        val remove = cookieStore.removeAll { it.name == "TWFID" && it.domain == PORTAL_HOST }
        val cookie = Cookie.Builder()
            .name("TWFID")
            .value(value)
            .domain(PORTAL_HOST)
            .path("/")
            .build()
        cookieStore.add(cookie)
        Log.d(TAG, "Injected manual TWFID (removed=$remove)")
    }

    /** 移除已注入的 TWFID Cookie。 */
    fun removeTwfidCookie() {
        val removed = cookieStore.removeAll { it.name == "TWFID" }
        if (removed) Log.d(TAG, "Removed injected TWFID cookie")
    }

    // ------------------- 低层请求 / 编解码 -------------------

    private fun getText(url: String): String? = try {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { it.body?.string() }
    } catch (e: Exception) {
        Log.w(TAG, "GET $url 失败", e)
        null
    }

    private fun postText(url: String, body: FormBody): String? = try {
        client.newCall(Request.Builder().url(url).post(body).build()).execute().use { it.body?.string() }
    } catch (e: Exception) {
        Log.w(TAG, "POST $url 失败", e)
        null
    }

    /**
     * 只把关键字段写进日志。
     * 整个响应体里有 `TwfID` / `CSRF_RAND_CODE` / `AuthInfo`，不该原样进 logcat。
     */
    private fun summarizeAuthXml(xml: String): String = listOf(
        "ErrorCode" to extractXmlTag(xml, "ErrorCode"),
        "Result" to extractXmlTag(xml, "Result"),
        "NextService" to extractXmlTag(xml, "NextService"),
        "NextAuth" to extractXmlTag(xml, "NextAuth"),
        "Message" to (extractXmlTag(xml, "Message") ?: extractXmlTag(xml, "Note"))
    ).joinToString(", ") { (key, value) -> "$key=${value.orEmpty()}" }

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
        private const val TAG = "WebVpnClient"
        private const val PORTAL_HOST = "webvpn.wbu.edu.cn"
        private const val CAPTCHA_LENGTH = 4
        private const val MAX_PASSWORD_ATTEMPTS = 4
        private const val MAX_ATTACK_SIGNALS = 2
        private const val SMS_STILL_VALID_TEXT = "您的验证码仍在有效期内"

        /** 手动填写的 WebVPN TWFID（跨重启保留）。 */
        fun getTwfid(context: android.content.Context): String = WbuAuthTransport.getTwfid(context)
        fun setTwfid(context: android.content.Context, value: String) = WbuAuthTransport.setTwfid(context, value)
        fun clearTwfid(context: android.content.Context) = WbuAuthTransport.clearTwfid(context)

        /** 凭据变更信号（TWFID / Cookie），供界面同步刷新。 */
        val credentialChanges get() = WbuAuthTransport.credentialChanges

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
