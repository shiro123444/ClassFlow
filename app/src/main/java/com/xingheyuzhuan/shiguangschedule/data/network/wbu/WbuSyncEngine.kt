package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import android.util.Base64
import android.util.Log
import com.xingheyuzhuan.shiguangschedule.data.db.main.Course
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWithWeeks
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.math.BigInteger
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 登录认证方式：统一身份认证(CAS) 或 教务系统直接表单。
 */
enum class WbuAuthMode {
    UNIFIED_CAS,
    JYXT_LEGACY
}

/**
 * 完整 WebVPN 登录全过程的状态（供 UI 显示文案）。
 */
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
 * 教务系统直连(含 VPN 镜像)表单登录的失败原因。
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
 */
data class WbuSemesterConfig(
    val semesterStartDate: String?,
    val semesterTotalWeeks: Int
)

/**
 * 二维码登录会话。
 */
data class QrSession(
    val uuid: String,
    val content: String,
    val execution: String,
    val lt: String,
    val authBaseUrl: String
)

/**
 * 登录页表单参数（execution/lt）。
 */
data class AuthForm(
    val execution: String,
    val lt: String
)

/**
 * 发送动态码的结果。
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
    data class Move(val moveLength: Int) : SliderCaptchaResult()
    object Refresh : SliderCaptchaResult()
    object Cancel : SliderCaptchaResult()
}

typealias SliderCaptchaProvider = suspend (SliderCaptchaData) -> SliderCaptchaResult

/**
 * 教务引擎：负责 /admin 教务登录、课程/学期抓取，以及登录编排。
 *
 * WebVPN 门户与 ids 统一认证分别由 [WebVpnClient] / [IdsCasClient] 承担；
 * 三者共享同一个 [WbuAuthTransport]（客户端、Cookie、基址、TLS）。
 */
class WbuSyncEngine(
    private val context: Context,
    val useVpn: Boolean = false,
) {
    private val transport = WbuAuthTransport(context, useVpn)
    private val portal = WebVpnClient(transport)
    private val cas = IdsCasClient(transport)

    private val client = transport.client
    private val baseUrl = transport.jwxtBase
    private val cookieStore = transport.cookieStore
    private val prefs = transport.prefs

    /**
     * 最近一次解析出的学生学号。
     */
    @Volatile
    var lastResolvedStudentId: String? = null

    /**
     * 最近一次教务系统直连/镜像表单登录的失败原因（供 UI 判断）。
     */
    @Volatile
    var lastLocalLoginFailure: LocalLoginFailure? = null

    /**
     * 教务系统直连/镜像表单登录失败时，从服务端返回页提取的真实错误文案。成功时为 null。
     */
    @Volatile
    var lastLocalLoginError: String? = null

    /**
     * 教务系统直连/镜像登录是否因**网络异常**而失败。
     */
    @Volatile
    var lastLocalLoginNetworkError: Boolean = false

    /**
     * WebVPN TLS 证书校验异常回调（转发到共享 transport）。
     */
    var sslIssueHandler: (suspend (message: String) -> Boolean)?
        get() = transport.sslIssueHandler
        set(v) { transport.sslIssueHandler = v }

    /**
     * 教务服务端通过 getCurrentXnxq 真实返回的当前学期（不会因用户手动挑选其它学期而被覆盖）。
     */
    @Volatile
    var systemCurrentXnxq: String? = null

    /**
     * 最近一次 fetchCourseData 解析出的当前学期，供 fetchSemesterConfig 复用。
     */
    @Volatile
    var lastResolvedXnxq: String? = null

    /**
     * 最近一次 fetchCourseData 解析出的校区，供 fetchSemesterConfig 复用。
     */
    @Volatile
    var lastResolvedXqdm: String? = null

    init {
        transport.restoreCookieStore()
        // 手动 TWFID：构造时即注入（内存），使后续所有请求自动携带。
        val initialTwfid = transport.currentTwfid().trim()
        if (initialTwfid.isNotEmpty()) portal.injectTwfid(initialTwfid)
    }

    // ------------------- 登录编排入口（UI 可见，签名不变） -------------------

    suspend fun login(
        studentId: String,
        password: String,
        captchaProvider: SliderCaptchaProvider? = null,
        authMode: WbuAuthMode = WbuAuthMode.UNIFIED_CAS
    ): Boolean = withContext(Dispatchers.IO) {
        lastLocalLoginNetworkError = false
        // 每次启动全新登录前，清理旧的历史会话凭据（保留手动 TWFID），确保必须重新认证一次且不受残留干扰
        transport.startNewLoginSession()
        try {
            val success = if (useVpn) {
                loginViaVpnCas(studentId, password, captchaProvider, authMode)
            } else {
                loginDirect(studentId, password, captchaProvider, authMode)
            }
            if (success) {
                prefs.edit()
                    .putString(WbuAuthTransport.prefKeyLastStudentId(), studentId)
                    .putBoolean(KEY_LAST_USE_VPN, useVpn)
                    .putBoolean(KEY_LAST_USE_VPN_SET, true)
                    .apply()
                transport.persistCookieStore()
            }
            return@withContext success
        } catch (e: Exception) {
            Log.e("WbuSyncEngine", "Login failed", e)
            lastLocalLoginNetworkError = true
            false
        }
    }

    suspend fun hasActiveSession(): Boolean = false

    fun clearPersistedSession() = transport.clearPersistedSession()

    fun importCookiesFromWebView(cookieManager: android.webkit.CookieManager) =
        transport.importCookiesFromWebView(cookieManager)

    // ------------------- 对外透传（ids CAS 原语，UI 直接调用；教务 bootstrap 由引擎完成） -------------------

    /** 发送动态码。 */
    suspend fun sendDynamicCode(
        studentId: String,
        flowTag: String,
        captchaProvider: SliderCaptchaProvider?
    ): DynamicCodeSendResult = cas.sendDynamicCode(studentId, flowTag, captchaProvider)

    /**
     * 确保 WebVPN 网关隧道处于放行状态：
     * 1. 优先校验 TWFID（手动配置或已存在的会话凭据）；
     * 2. 若 TWFID 有效，直接放行（免密）；
     * 3. 若 TWFID 无效或缺失，直接调用 [vpnPasswordProvider] 弹窗索取 WebVPN/统一认证密码打通门禁（支持短信二次验证）。
     */
    suspend fun ensureVpnTunnelReady(
        studentId: String,
        vpnPasswordProvider: suspend () -> String?,
        smsCodeProvider: (suspend (maskedPhone: String, isStillValid: Boolean, sendInterval: Int, promptText: String) -> String?)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!useVpn) return@withContext true

        // 1. 优先校验手动/已有 TWFID
        val currentTwfid = transport.currentTwfid().trim().ifEmpty {
            cookieStore.firstOrNull { it.name == "TWFID" && it.value.isNotBlank() }?.value.orEmpty()
        }
        if (currentTwfid.isNotEmpty()) {
            if (portal.validateTwfid(currentTwfid)) {
                Log.i("WbuSyncEngine", "ensureVpnTunnelReady: Valid TWFID present, skip WebVPN portal login")
                portal.injectTwfid(currentTwfid)
                return@withContext true
            } else {
                Log.w("WbuSyncEngine", "ensureVpnTunnelReady: Existing TWFID invalid, clearing and prompting for password")
                WbuAuthTransport.clearTwfid(context)
                portal.removeTwfidCookie()
            }
        }

        // 2. 无有效 TWFID，直接弹窗请求密码
        val vpnPassword = vpnPasswordProvider()
        if (vpnPassword.isNullOrBlank()) {
            lastLocalLoginError = "已取消 WebVPN 密码输入"
            return@withContext false
        }

        val effectiveSid = studentId.ifBlank { lastResolvedStudentId ?: getSavedStudentId(context) }
        val portalStep = portal.portalPasswordLogin(effectiveSid, vpnPassword)
        when (portalStep) {
            is PortalLoginStep.Error -> {
                Log.w("WbuSyncEngine", "WebVPN portal login failed: ${portalStep.message}")
                lastLocalLoginError = "WebVPN 登录失败: ${portalStep.message}"
                false
            }
            is PortalLoginStep.SmsRequired -> {
                if (smsCodeProvider == null) {
                    lastLocalLoginError = "WebVPN 需要短信验证码"
                    return@withContext false
                }
                val code = smsCodeProvider(portalStep.maskedPhone, portalStep.isStillValid, portalStep.sendInterval, portalStep.promptText)
                if (code.isNullOrBlank()) {
                    lastLocalLoginError = "已取消 WebVPN 短信验证码输入"
                    return@withContext false
                }
                if (!portal.portalSubmitSms(code)) {
                    lastLocalLoginError = "WebVPN 短信验证码错误"
                    return@withContext false
                }
                true
            }
            is PortalLoginStep.PortalAuthenticated -> {
                Log.i("WbuSyncEngine", "WebVPN portal authenticated successfully")
                true
            }
        }
    }

    /**
     * 凭当前会话中已有的 CASTGC 向 CAS 请求教务系统 Ticket 并跟随重定向完成换票。
     * WebVPN 模式下会将 302 Location 改写为代理宿主，由 WebVPN 隧道代理至教务系统确立会话。
     */
    suspend fun exchangeCastgcForJwxtSession(flowTag: String): Boolean = withContext(Dispatchers.IO) {
        val serviceTarget = if (WbuAuthTransport.getUseFixedServiceForTicket(context)) {
            WbuAuthTransport.IDS_PERSON_CENTER_SERVICE
        } else {
            "https://jwxt.wbu.edu.cn/admin/caslogin"
        }
        val encodedService = URLEncoder.encode(serviceTarget, "UTF-8")
        val idsLoginUrl = "${transport.idsBase()}/authserver/login?service=$encodedService"
        Log.i("WbuSyncEngine", "$flowTag 开始使用 CASTGC 换取教务 Ticket: $idsLoginUrl")

        // 仅在已有 CASTGC 时换票；clearAuthCookies = false 保留凭据，consumeTicket = true 跟随并核销 ticket
        val casResult = cas.casPasswordLogin(
            studentId = "",
            password = "",
            idsLoginUrl = idsLoginUrl,
            flowTag = flowTag,
            clearAuthCookies = false,
            consumeTicket = true
        )
        if (!casResult.success) {
            Log.w("WbuSyncEngine", "$flowTag CASTGC 换票失败: ${casResult.message}")
            lastLocalLoginFailure = casResult.failure
            lastLocalLoginError = casResult.message
            return@withContext false
        }
        bootstrapJwxtSession(casResult.landingHtml)
    }

    /** 仅获取动态码登录表单参数。 */
    suspend fun obtainDynamicCodeForm(flowTag: String): AuthForm? =
        cas.obtainDynamicCodeForm(flowTag, if (useVpn) WbuAuthTransport.IDS_PERSON_CENTER_SERVICE else null)

    /** 用动态码完成登录（含教务会话引导与 WebVPN 门禁就绪检测）。 */
    suspend fun dynamicCodeLogin(
        studentId: String,
        code: String,
        prep: AuthForm,
        flowTag: String,
        vpnPasswordProvider: (suspend () -> String?)? = null,
        smsCodeProvider: (suspend (maskedPhone: String, isStillValid: Boolean, sendInterval: Int, promptText: String) -> String?)? = null
    ): DynamicCodeLoginResult = withContext(Dispatchers.IO) {
        // WebVPN 模式下：登录 IDS 时严格使用同源个人中心 service 且不跳转消费，防止未建网关时提前 302 撞入教务
        val idsTarget = if (useVpn) WbuAuthTransport.IDS_PERSON_CENTER_SERVICE else null
        val result = cas.casDynamicCodeLogin(
            studentId = studentId,
            code = code,
            prep = prep,
            flowTag = flowTag,
            serviceTarget = idsTarget,
            consumeTicket = !useVpn
        )
        if (result.success) {
            prefs.edit()
                .putString(WbuAuthTransport.prefKeyLastStudentId(), studentId)
                .putBoolean(KEY_LAST_USE_VPN, useVpn)
                .putBoolean(KEY_LAST_USE_VPN_SET, true)
                .apply()
            transport.persistCookieStore()

            if (useVpn) {
                if (vpnPasswordProvider != null) {
                    val vpnReady = ensureVpnTunnelReady(studentId, vpnPasswordProvider, smsCodeProvider)
                    if (!vpnReady) {
                        return@withContext DynamicCodeLoginResult(false, lastLocalLoginError ?: "WebVPN 门禁连接失败")
                    }
                }
                // WebVPN 网关打通后，凭新鲜的 CASTGC 请求 jwxt service 换票并建立教务会话
                val exchangeOk = exchangeCastgcForJwxtSession("$flowTag-EXCHANGE")
                DynamicCodeLoginResult(exchangeOk, if (exchangeOk) "" else (lastLocalLoginError ?: "换取教务会话失败"))
            } else {
                val boot = bootstrapJwxtSession(result.landingHtml)
                DynamicCodeLoginResult(boot, if (boot) "" else "登录成功但教务系统会话未就绪")
            }
        } else {
            DynamicCodeLoginResult(false, result.message)
        }
    }

    /** 开始二维码登录。 */
    suspend fun startQrLogin(flowTag: String): QrSession? =
        cas.startQrLogin(flowTag, if (useVpn) WbuAuthTransport.IDS_PERSON_CENTER_SERVICE else null)

    /** 轮询二维码状态。 */
    suspend fun pollQrStatus(session: QrSession): QrStatus = cas.pollQrStatus(session)

    /** 扫码确认后完成登录（含教务会话引导与 WebVPN 门禁就绪检测）。 */
    suspend fun completeQrLogin(
        session: QrSession,
        flowTag: String,
        vpnPasswordProvider: (suspend () -> String?)? = null,
        smsCodeProvider: (suspend (maskedPhone: String, isStillValid: Boolean, sendInterval: Int, promptText: String) -> String?)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        // WebVPN 模式下：登录 IDS 时严格使用同源个人中心 service 且不跳转消费
        val idsTarget = if (useVpn) WbuAuthTransport.IDS_PERSON_CENTER_SERVICE else null
        val result = cas.casCompleteQrLogin(session, serviceTarget = idsTarget, consumeTicket = !useVpn)
        if (result.success) {
            // 获取真实学号始终使用个人中心同源 service
            val resolvedSid = cas.fetchStudentIdFromCas()
            if (!resolvedSid.isNullOrBlank()) {
                lastResolvedStudentId = resolvedSid
                prefs.edit().putString(WbuAuthTransport.prefKeyLastStudentId(), resolvedSid).apply()
            }
            prefs.edit()
                .putBoolean(KEY_LAST_USE_VPN, useVpn)
                .putBoolean(KEY_LAST_USE_VPN_SET, true)
                .apply()
            transport.persistCookieStore()

            if (useVpn) {
                if (vpnPasswordProvider != null) {
                    val vpnReady = ensureVpnTunnelReady(resolvedSid.orEmpty(), vpnPasswordProvider, smsCodeProvider)
                    if (!vpnReady) {
                        return@withContext false
                    }
                }
                // WebVPN 网关打通后，凭新鲜的 CASTGC 请求 jwxt service 换票并建立教务会话
                exchangeCastgcForJwxtSession("$flowTag-EXCHANGE")
            } else {
                bootstrapJwxtSession(result.landingHtml)
            }
        } else {
            false
        }
    }

    /** 重发 WebVPN 门户短信验证码（返回成功状态及冷却秒数）。 */
    suspend fun resendVpnSmsCode(): PortalResendSmsResult = portal.portalResendSms()

    /**
     * 完整 WebVPN 登录流程：门户登录（密码/SMS/手动 TWFID）→ 登录教务。
     * @param vpnPassword 当 authMode 为 JYXT_LEGACY 且未配置 TWFID 时，用于连接 WebVPN 的统一认证密码；若为 null 则默认尝试使用 password。
     */
    suspend fun loginVpnFull(
        studentId: String,
        password: String,
        smsCodeProvider: suspend (maskedPhone: String, isStillValid: Boolean, sendInterval: Int, promptText: String) -> String?,
        captchaProvider: SliderCaptchaProvider? = null,
        statusCallback: ((VpnFullLoginStatus) -> Unit)? = null,
        authMode: WbuAuthMode = WbuAuthMode.UNIFIED_CAS,
        vpnPassword: String? = null
    ): Boolean {
        // 每次启动全新登录前，清理旧的历史会话凭据（保留手动 TWFID），确保必须重新认证一次且不受残留干扰
        transport.startNewLoginSession()

        // 手动 TWFID：已认证则跳过 WebVPN 门户登录，直接进入教务
        val manualTwfid = transport.currentTwfid().trim()
        if (manualTwfid.isNotEmpty()) {
            if (portal.validateTwfid(manualTwfid)) {
                Log.i("WbuSyncEngine", "Manual TWFID validated; skip WebVPN credential login")
                portal.injectTwfid(manualTwfid)
                return vpnLoginTail(studentId, password, captchaProvider, authMode, statusCallback)
            } else {
                Log.w("WbuSyncEngine", "Manual TWFID invalid/expired; clear and fall back to WebVPN login")
                WbuAuthTransport.clearTwfid(context)
                portal.removeTwfidCookie()
            }
        }

        // 解析登录 WebVPN 门户所需的账号与密码：
        var vpnStudentId = studentId
        val effectiveVpnPassword = vpnPassword ?: password
        var didPreIdsAuth = false

        // 判断是否需要先走公网 IDS 换取真实学号：
        // 规则：当前学号为9位纯数字（年份后两位+专业代码+学生号，如260593099）。
        // 1. 若开启「登录WebVPN前必须获取学号」，强制先获取；
        // 2. 若当前输入的账号不符合9位纯数字（包含字母别名等），自动先去公网 IDS 获取真实学号；
        // 3. 纯数字学号默认直接登录 WebVPN，不在前期多发无谓请求。
        val forceFetch = WbuAuthTransport.getForceFetchStudentIdBeforeVpn(context)
        val isStandardStudentId = isLikelyStudentId(studentId)
        val needPreIds = authMode == WbuAuthMode.UNIFIED_CAS && (forceFetch || !isStandardStudentId)

        if (needPreIds) {
            val preSid = performPreIdsAuthAndGetStudentId(studentId, effectiveVpnPassword, captchaProvider)
            if (preSid != null) {
                vpnStudentId = preSid
                didPreIdsAuth = true
            } else {
                return false
            }
        }

        var portalStep = portal.portalPasswordLogin(vpnStudentId, effectiveVpnPassword)

        // 回退机制：如果用户输入的用户名巧合符合学号模样，导致直接登录 WebVPN 失败，且此前未进行 IDS 预认证：
        // 自动回退走一次 IDS 预认证换取真实学号，再重试 WebVPN 门户登录。
        if (portalStep is PortalLoginStep.Error && !didPreIdsAuth && authMode == WbuAuthMode.UNIFIED_CAS) {
            Log.w("WbuSyncEngine", "WebVPN 直接登录失败（${portalStep.message}），尝试回退走 IDS 解析真实学号...")
            val fallbackSid = performPreIdsAuthAndGetStudentId(studentId, effectiveVpnPassword, captchaProvider)
            if (fallbackSid != null && fallbackSid != vpnStudentId) {
                Log.d("WbuSyncEngine", "回退成功解析出真实学号，正在重试登录 WebVPN...")
                vpnStudentId = fallbackSid
                didPreIdsAuth = true
                portalStep = portal.portalPasswordLogin(vpnStudentId, effectiveVpnPassword)
            }
        }

        when (portalStep) {
            is PortalLoginStep.Error -> {
                Log.w("WbuSyncEngine", "VPN login error: ${portalStep.message}")
                lastLocalLoginError = "WebVPN登录失败: ${portalStep.message}"
                return false
            }
            is PortalLoginStep.SmsRequired -> {
                statusCallback?.invoke(VpnFullLoginStatus.SMS_REQUIRED)
                val code = smsCodeProvider(portalStep.maskedPhone, portalStep.isStillValid, portalStep.sendInterval, portalStep.promptText) ?: return false
                if (!portal.portalSubmitSms(code)) {
                    Log.w("WbuSyncEngine", "SMS code verification failed")
                    lastLocalLoginError = "WebVPN 短信验证码错误"
                    return false
                }
                statusCallback?.invoke(VpnFullLoginStatus.SMS_VERIFIED)
            }
            is PortalLoginStep.PortalAuthenticated -> {
                statusCallback?.invoke(VpnFullLoginStatus.VPN_AUTHENTICATED)
            }
        }
        val ok = vpnLoginTail(vpnStudentId, password, captchaProvider, authMode, statusCallback)
        if (ok) {
            prefs.edit()
                .putString(WbuAuthTransport.prefKeyLastStudentId(), vpnStudentId)
                .putBoolean(KEY_LAST_USE_VPN, true)
                .putBoolean(KEY_LAST_USE_VPN_SET, true)
                .apply()
            transport.persistCookieStore()
        }
        return ok
    }

    /**
     * 判断字符串是否符合当前 9 位纯数字学号规范（年份后两位 + 专业代码4位 + 学生号3位，如 260593099）。
     */
    private fun isLikelyStudentId(input: String): Boolean =
        input.trim().matches(Regex("""^\d{9}$"""))

    /**
     * 在公网 IDS 上完成预认证并提取标准学号。
     * 注意：这里验证成功后会完整保留 CASTGC 会话，后续教务单点登录可直接复用，绝不清除重复提交。
     */
    private suspend fun performPreIdsAuthAndGetStudentId(
        usernameInput: String,
        passwordInput: String,
        captchaProvider: SliderCaptchaProvider?
    ): String? {
        // 获取学号始终使用同源个人中心 service，不提前消耗教务系统的 service ticket
        val serviceTarget = WbuAuthTransport.IDS_PERSON_CENTER_SERVICE
        val idsLoginUrl = "${transport.idsBase()}/authserver/login?service=${URLEncoder.encode(serviceTarget, "UTF-8")}"
        // 预认证时只提交到公网 IDS 拿到 CASTGC 与真实学号。
        // consumeTicket = false: 302 拦截 ST 即止，绝不跟随去 GET 个人中心落地页，省去无谓请求！
        val casResult = cas.casPasswordLogin(
            studentId = usernameInput,
            password = passwordInput,
            idsLoginUrl = idsLoginUrl,
            flowTag = "PRE-IDS",
            captchaProvider = captchaProvider,
            clearAuthCookies = false,
            consumeTicket = false
        )
        if (casResult.success) {
            // 若 302 响应头中直接提取出了 ST，直接调 serviceValidate；否则回退 fetchStudentIdFromCas
            val realSid = casResult.stTicket?.let { st ->
                cas.validateTicketForStudentId(transport.idsBase(), st, serviceTarget)
            } ?: cas.fetchStudentIdFromCas(transport.idsBase(), serviceTarget)

            return if (!realSid.isNullOrBlank()) {
                Log.d("WbuSyncEngine", "Resolved student ID from input")
                realSid
            } else {
                usernameInput
            }
        } else {
            Log.w("WbuSyncEngine", "Pre-IDS authentication failed: ${casResult.message}")
            lastLocalLoginFailure = casResult.failure
            lastLocalLoginError = casResult.message
            return null
        }
    }

    /** 已通过 WebVPN 鉴权后的收尾：登录到教务系统。 */
    private suspend fun vpnLoginTail(
        studentId: String,
        password: String,
        captchaProvider: SliderCaptchaProvider?,
        authMode: WbuAuthMode,
        statusCallback: ((VpnFullLoginStatus) -> Unit)?
    ): Boolean {
        statusCallback?.invoke(VpnFullLoginStatus.VPN_READY_NEED_CAS)
        val ok = withContext(Dispatchers.IO) {
            if (authMode == WbuAuthMode.JYXT_LEGACY) {
                loginDirectLegacy(studentId, password)
            } else {
                loginViaVpnCas(studentId, password, captchaProvider, authMode)
            }
        }
        statusCallback?.invoke(if (ok) VpnFullLoginStatus.CAS_COMPLETED else VpnFullLoginStatus.CAS_FAILED)
        return ok
    }

    // ------------------- 教务登录：直连 -------------------

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

        val directCasEntryUrl = "$baseUrl/admin/caslogin"
        val serviceTarget = if (WbuAuthTransport.getUseFixedServiceForTicket(context)) {
            WbuAuthTransport.IDS_PERSON_CENTER_SERVICE
        } else {
            "https://jwxt.wbu.edu.cn/admin/caslogin"
        }
        val encodedService = URLEncoder.encode(serviceTarget, "UTF-8")
        val fixedIdsLoginUrl = "${transport.idsBase()}/authserver/login?service=$encodedService"

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

        val casOk = loginViaCas(
            studentId = studentId,
            password = password,
            idsLoginUrl = discoveredCasUrl,
            flowTag = "DIRECT-CAS",
            captchaProvider = captchaProvider
        )
        if (casOk) return true

        Log.w("WbuSyncEngine", "Direct CAS flow failed and no legacy fallback per selected auth mode")
        return false
    }

    // ------------------- 教务登录：经 VPN + CAS -------------------

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

        val jwxtLoginUrl = "$baseUrl/admin/login"
        val serviceTarget = if (WbuAuthTransport.getUseFixedServiceForTicket(context)) {
            WbuAuthTransport.IDS_PERSON_CENTER_SERVICE
        } else {
            "https://jwxt.wbu.edu.cn/admin/caslogin"
        }
        val encodedService = URLEncoder.encode(serviceTarget, "UTF-8")
        val fallbackIdsLoginUrl = "${transport.idsBase()}/authserver/login?service=$encodedService"

        // WebVPN 模式下：禁止提前向代理宿主发起 GET /admin/login 爬取 CAS 链接（避免未授权引发网关 302 跌落）；
        // 除非显式需要从页面爬取，否则直接使用标准构造的 ids 登录地址。
        val idsLoginUrl = fallbackIdsLoginUrl
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

    // ------------------- 教务登录：CAS 桥接（调 IdsCasClient，成功后 bootstrap） -------------------

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

        // 如果 cookieStore 中已有有效 CASTGC，无需清空已有的认证凭据，直接利用 SSO 换票
        val hasTgc = cookieStore.any { it.name == "CASTGC" && !it.value.isBlank() }
        val result = cas.casPasswordLogin(studentId, password, idsLoginUrl, flowTag, captchaProvider, clearAuthCookies = !hasTgc)
        if (!result.success) {
            lastLocalLoginFailure = result.failure
            lastLocalLoginError = result.message
            return false
        }
        return bootstrapJwxtSession(result.landingHtml)
    }

    // ------------------- 教务登录：legacy 表单 -------------------

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
                    if (name.isNotBlank()) hiddenFields[name] = input.attr("value")
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

                resolveAndPersistStudentId(studentId)
                return canAccessTermApi()
            }
        } catch (e: Exception) {
            Log.w("WbuSyncEngine", "Legacy login network error", e)
            lastLocalLoginNetworkError = true
            return false
        }
    }

    /**
     * 登录成功后的 JWXT 会话引导。
     * 默认：解析落地页 indexMain 并打开，最后校验课表接口。
     * 「no indexMain verify」开启时：跳过 indexMain，仅校验会话 cookie + 拿学号。
     *
     * @param landingHtml CAS 落地页 HTML（ticket 消费 auto-follow 已取到），非空则复用，避免重复 GET /admin/?loginType=1；
     *                    为空时才回退重新 GET。
     */
    private suspend fun bootstrapJwxtSession(landingHtml: String? = null): Boolean {
        // 「no indexMain verify」：不解析/打开 indexMain，仅验证会话 cookie 是否就绪 + 拿学号。
        if (WbuAuthTransport.getNoIndexMainVerify(context)) {
            val ready = hasJwxtSessionCookie()
            if (ready) {
                Log.i("WbuSyncEngine", "no indexMain verify: session cookie present, resolve student id")
                resolveAndPersistStudentId(WbuSyncEngine.getSavedStudentId(context))
            }
            return ready
        }

        val loginTypeHtml: String = landingHtml?.takeIf { it.isNotBlank() } ?: run {
            client.newCall(
                Request.Builder().url("$baseUrl/admin/?loginType=1").get().build()
            ).execute().use { it.body?.string().orEmpty() }
        }

        val indexMainUrl = runCatching {
            val doc = Jsoup.parse(loginTypeHtml, "$baseUrl/admin/?loginType=1")
            // 仅在明确带有 indexMain 属性的 a, frame, iframe 标签中查找，严禁宽泛匹配 script 标签
            val candidate = doc.select("a[href*=indexMain], frame[src*=indexMain], iframe[src*=indexMain]")
                .firstOrNull()
                ?.let { el -> el.attr("href").ifBlank { el.attr("src") } }
                ?.takeIf { it.isNotBlank() }

            if (candidate != null) {
                resolveAbsoluteUrl("$baseUrl/admin/?loginType=1", candidate)
            } else {
                extractIndexMainUrlFromScript(loginTypeHtml)
            }
        }.getOrNull()

        if (!indexMainUrl.isNullOrBlank()) {
            runCatching {
                client.newCall(Request.Builder().url(indexMainUrl).get().build()).execute().close()
            }.onFailure { Log.w("WbuSyncEngine", "bootstrapJwxtSession open indexMain failed: ${it.message}") }
        }

        return canAccessTermApi()
    }

    /** 判断教务会话 cookie 是否已就绪（jw_uf / JSESSIONID 任一存在即视为有会话）。 */
    private fun hasJwxtSessionCookie(): Boolean {
        val hasJwUf = cookieStore.any { it.name == "jw_uf" }
        val hasSession = cookieStore.any { it.name == "JSESSIONID" }
        return hasJwUf || hasSession
    }

    // ------------------- 学期 / 课表抓取 -------------------

    data class WbuSemesterOption(val value: String, val text: String)

    suspend fun fetchSemesterOptions(): List<WbuSemesterOption> = withContext(Dispatchers.IO) {
        runCatching {
            val currentXnxq = systemCurrentXnxq?.takeIf { it.isNotBlank() } ?: run {
                val termReq = Request.Builder()
                    .url("$baseUrl/admin/xsd/xsdcjcx/getCurrentXnxq?sf_request_type=ajax")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .get()
                    .build()
                val termRaw = client.newCall(termReq).execute().use { it.body?.string().orEmpty() }
                if (!transport.looksLikeHtml(termRaw) && termRaw.isNotBlank()) {
                    JSONObject(termRaw).optString("data", "").also {
                        if (it.isNotBlank()) {
                            systemCurrentXnxq = it
                            if (lastResolvedXnxq.isNullOrBlank()) lastResolvedXnxq = it
                        }
                    }
                } else null
            } ?: lastResolvedXnxq

            val queryUrl = if (!currentXnxq.isNullOrBlank()) {
                "$baseUrl/admin/xsd/pkgl/xskb/queryKbForXsd?xnxq=${URLEncoder.encode(currentXnxq, "UTF-8")}"
            } else {
                "$baseUrl/admin/xsd/pkgl/xskb/queryKbForXsd"
            }

            val pageHtml = client.newCall(
                Request.Builder().url(queryUrl).get().build()
            ).execute().use { it.body?.string().orEmpty() }

            if (pageHtml.isBlank() || transport.looksLikeHtml(pageHtml).not() && !pageHtml.contains("xnxq1")) {
                // 如果没有返回期望页面，至少返回当前已知的学期
                return@withContext if (!currentXnxq.isNullOrBlank()) {
                    listOf(WbuSemesterOption(value = currentXnxq, text = currentXnxq))
                } else emptyList()
            }

            val doc = Jsoup.parse(pageHtml)
            val options = mutableListOf<WbuSemesterOption>()
            doc.select("#xnxq1 option").forEach { opt ->
                val v = opt.attr("value").trim()
                val t = opt.text().trim()
                if (v.isNotBlank()) {
                    options.add(WbuSemesterOption(value = v, text = t.ifBlank { v }))
                }
            }

            if (options.isEmpty() && !currentXnxq.isNullOrBlank()) {
                options.add(WbuSemesterOption(value = currentXnxq, text = currentXnxq))
            }
            options
        }.getOrElse { e ->
            Log.w("WbuSyncEngine", "fetchSemesterOptions failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun fetchCourseData(tableId: String, targetXnxq: String? = null): List<CourseWithWeeks>? = withContext(Dispatchers.IO) {
        try {
            Log.i("WbuSyncEngine", "Fetch course data start. tableId=$tableId baseUrl=$baseUrl targetXnxq=$targetXnxq")
            // 优先使用传入的 targetXnxq，其次复用已解析的学期
            val xnxq = targetXnxq?.takeIf { it.isNotBlank() } ?: systemCurrentXnxq?.takeIf { it.isNotBlank() } ?: lastResolvedXnxq?.takeIf { it.isNotBlank() } ?: run {
                val termReq = Request.Builder()
                    .url("$baseUrl/admin/xsd/xsdcjcx/getCurrentXnxq?sf_request_type=ajax")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .get()
                    .build()
                val termRaw = client.newCall(termReq).execute().use { it.body?.string().orEmpty() }
                Log.d("WbuSyncEngine", "Term API response len=${termRaw.length}")
                if (transport.looksLikeHtml(termRaw)) {
                    Log.w("WbuSyncEngine", "Term API returned HTML; auth/session likely invalid.")
                    return@withContext null
                }
                JSONObject(termRaw).optString("data", "").also { resolved ->
                    if (resolved.isNotBlank()) {
                        systemCurrentXnxq = resolved
                        lastResolvedXnxq = resolved
                    }
                }
            }
            if (xnxq.isBlank()) {
                Log.w("WbuSyncEngine", "Term API has empty xnxq.")
                return@withContext null
            }
            lastResolvedXnxq = xnxq

            val pkglHtml = client.newCall(
                Request.Builder()
                    .url("$baseUrl/admin/xsd/pkgl/xskb/queryKbForXsd?xnxq=$xnxq")
                    .get()
                    .build()
            ).execute().use { it.body?.string() ?: run { Log.w("WbuSyncEngine", "queryKbForXsd null body"); return@withContext null } }

            val document = Jsoup.parse(pkglHtml)
            var xhid = document.select("#xhid").first()?.attr("value").orEmpty()
            if (xhid.isBlank()) xhid = document.select("input[name=xhid]").first()?.attr("value").orEmpty()
            var xqdm = document.select("#xqdm").first()?.attr("value").orEmpty()
            if (xqdm.isBlank()) xqdm = document.select("input[name=xqdm]").first()?.attr("value").orEmpty()
            if (xhid.isBlank()) xhid = extractFieldFromHtml(pkglHtml, "xhid").orEmpty()
            if (xqdm.isBlank()) xqdm = extractFieldFromHtml(pkglHtml, "xqdm").orEmpty()
            lastResolvedXqdm = xqdm.takeIf { it.isNotBlank() } ?: lastResolvedXqdm

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
                val listRaw = client.newCall(
                    Request.Builder()
                        .url(candidate)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .get()
                        .build()
                ).execute().use { it.body?.string().orEmpty() }
                val data = runCatching { JSONObject(listRaw.ifBlank { "{}" }) }
                    .getOrNull()?.optJSONArray("data")
                if (data != null) { jsonArray = data; break }
            }
            if (jsonArray == null) {
                Log.w("WbuSyncEngine", "All sdpkkbList attempts failed to return data array")
                return@withContext null
            }

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
            Log.i("WbuSyncEngine", "Fetch course data done. merged=${courses.size}")
            return@withContext courses
        } catch (e: Exception) {
            Log.e("WbuSyncEngine", "Fetch failed", e)
            null
        }
    }

    suspend fun fetchSemesterConfig(xnxq: String? = null, xqdm: String? = null): WbuSemesterConfig? =
        withContext(Dispatchers.IO) {
            runCatching {
                // 优先使用传入参数，其次复用先前在 fetchCourseData/canAccessTermApi 中已成功解析的学期与校区代码，避免重复请求
                var termXnxq = xnxq?.takeIf { it.isNotBlank() } ?: lastResolvedXnxq?.takeIf { it.isNotBlank() }
                var campusXqdm = xqdm?.takeIf { it.isNotBlank() } ?: lastResolvedXqdm?.takeIf { it.isNotBlank() }
                if (termXnxq == null) {
                    val termRaw = client.newCall(
                        Request.Builder()
                            .url("$baseUrl/admin/xsd/xsdcjcx/getCurrentXnxq?sf_request_type=ajax")
                            .header("X-Requested-With", "XMLHttpRequest")
                            .get()
                            .build()
                    ).execute().use { it.body?.string().orEmpty() }
                    if (transport.looksLikeHtml(termRaw) || termRaw.isBlank()) return@withContext null
                    termXnxq = JSONObject(termRaw).optString("data", "")
                }
                if (termXnxq.isBlank()) return@withContext null

                if (campusXqdm.isNullOrBlank()) {
                    val pageHtml = client.newCall(
                        Request.Builder()
                            .url("$baseUrl/admin/xsd/pkgl/xskb/queryKbForXsd?xnxq=${URLEncoder.encode(termXnxq, "UTF-8")}")
                            .get()
                            .build()
                    ).execute().use { it.body?.string().orEmpty() }
                    campusXqdm = runCatching { Jsoup.parse(pageHtml).select("#xqdm").first()?.attr("value") }
                        .getOrNull()?.trim().orEmpty()
                }

                val cfgRaw = client.newCall(
                    Request.Builder()
                        .url("$baseUrl/admin/api/getZclistByXnxq?xnxq=${URLEncoder.encode(termXnxq, "UTF-8")}&role=&userId=&xqid=${URLEncoder.encode(campusXqdm, "UTF-8")}")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .get()
                        .build()
                ).execute().use { it.body?.string().orEmpty() }
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

    // ------------------- 教务登录后的学号解析 -------------------

    private fun resolveAndPersistStudentId(fallbackStudentId: String) {
        val username = cookieStore.firstOrNull { it.name == "username" }?.value
        val sid = username?.takeIf { it.isNotBlank() } ?: fallbackStudentId
        if (username.isNullOrBlank()) {
            runCatching {
                client.newCall(Request.Builder().url("$baseUrl/admin").get().build()).execute().use { resp -> }
                cookieStore.firstOrNull { it.name == "username" }?.value
                    ?.takeIf { it.isNotBlank() }
                    ?.let { prefs.edit().putString(WbuAuthTransport.prefKeyLastStudentId(), it).apply() }
            }
            return
        }
        prefs.edit().putString(WbuAuthTransport.prefKeyLastStudentId(), sid).apply()
        lastResolvedStudentId = sid
        Log.d("WbuSyncEngine", "Resolved student id from username cookie: $sid")
    }

    // ------------------- 会话/连接状态 -------------------

    private fun clearJwxtSessionCookies() {
        runCatching {
            val loginUrl = "$baseUrl/admin/login".toHttpUrlOrNull() ?: return
            cookieStore.removeAll { it.matches(loginUrl) && it.name != "TWFID" }
            transport.persistCookieStore()
        }.onFailure { Log.w("WbuSyncEngine", "clearJwxtSessionCookies failed", it) }
    }

    private fun canAccessTermApi(): Boolean {
        return runCatching {
            val termReq = Request.Builder()
                .url("$baseUrl/admin/xsd/xsdcjcx/getCurrentXnxq?sf_request_type=ajax")
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()
            // 使用不自动重定向的客户端探测，防止 302 自动跳入 WebVPN /por/ 门户产生多余请求
            val manualClient = client.newBuilder().followRedirects(false).build()
            manualClient.newCall(termReq).execute().use { resp ->
                if (resp.code in 300..399) {
                    Log.w("WbuSyncEngine", "Session check failed: term API returned 30x redirect (location=${resp.header("Location")})")
                    return@use false
                }
                val body = resp.body?.string().orEmpty()
                if (transport.looksLikeHtml(body)) {
                    Log.w("WbuSyncEngine", "Session check failed: term API returned HTML login page.")
                    return@use false
                }
                val term = JSONObject(body).optString("data", "")
                // 顺手记录学期，供 fetchCourseData 复用，避免重复 getCurrentXnxq。
                if (term.isNotBlank()) {
                    systemCurrentXnxq = term
                    lastResolvedXnxq = term
                }
                term.isNotBlank()
            }
        }.getOrElse { e ->
            Log.w("WbuSyncEngine", "Session check failed: ${e.message}", e)
            false
        }
    }

    // ------------------- 密码加密 -------------------

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

    // ------------------- 文案/URL 解析基元 -------------------

    private fun extractLoginErrorMessage(html: String): String? {
        val m = Regex("""var\s+error\s*=\s*"([^"]*)"\s*;?""", RegexOption.IGNORE_CASE).find(html) ?: return null
        val raw = m.groupValues.getOrNull(1)?.trim().orEmpty()
        if (raw.isBlank()) return null
        return raw.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
            .replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\").trim()
    }

    private fun extractFieldFromHtml(html: String, field: String): String? {
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

    private fun extractIndexMainUrlFromScript(html: String): String? {
        val match = Regex("(/admin/indexMain[^\"'\\s]*)", RegexOption.IGNORE_CASE).find(html) ?: return null
        val path = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (path.isBlank()) return null
        return if (path.startsWith("http://") || path.startsWith("https://")) path else "$baseUrl$path"
    }

    private fun extractCasLoginUrlFromHtml(html: String): String? {
        val match = Regex("""([\"'])([^\"']*authserver/login[^\"']*service=[^\"']+)\1""", RegexOption.IGNORE_CASE)
            .find(html) ?: return null
        return match.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun resolveAbsoluteUrl(baseUrl: String, maybeRelative: String): String =
        transport.resolveAbsoluteUrl(baseUrl, maybeRelative)

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

    private fun stripTeacherId(teacher: String): String = cleanTeacherId(teacher)
    private fun keepTeacherId(): Boolean = WbuAuthTransport.getKeepTeacherId(context)
    private fun keepBuilding(): Boolean = WbuAuthTransport.getKeepBuilding(context)

    private fun pickColorIndexForCourse(courseName: String, poolSize: Int = 12): Int {
        if (poolSize <= 0) return 0
        return kotlin.math.abs(courseName.hashCode()) % poolSize
    }

    // ------------------- 课表解析 -------------------

    private data class DraftCourse(
        val name: String,
        val teacher: String,
        val position: String,
        val day: Int,
        val startSection: Int,
        val endSection: Int,
        val weeks: List<Int>
    )

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
            val weeks = parseWeeks(cleanImportedText(item.optString("zc", "")), cleanImportedText(item.optString("zcstr", "")))
            if (name.isBlank() || weeks.isEmpty()) continue

            maxSection = maxOf(maxSection, startSection)
            val sig = "$name\u0001$teacher\u0001$position"
            byCell.getOrPut(Cell(day, startSection)) { LinkedHashMap() }
                .getOrPut(sig) { LinkedHashSet() }.addAll(weeks)
        }

        val drafts = mutableListOf<DraftCourse>()
        for (day in 1..7) {
            var runStart: Int? = null
            var runSig = ""
            fun flushRun(endSection: Int) {
                val start = runStart ?: return
                if (start == 0 || runSig.isEmpty()) { runStart = null; runSig = ""; return }
                byCell[Cell(day, start)]?.forEach { (sig, weeks) ->
                    val parts = sig.split('\u0001')
                    if (parts.size == 3) drafts.add(DraftCourse(parts[0], parts[1], parts[2], day, start, endSection, weeks.sorted()))
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
                if (runStart != null && (ended || sig.isEmpty() || sig != runSig)) flushRun(s - 1)
                if (!ended && sig.isNotEmpty() && runStart == null) { runStart = s; runSig = sig }
            }
        }
        return drafts
    }

    private fun mergeAndDistinctCourses(list: List<DraftCourse>): List<DraftCourse> {
        if (list.size <= 1) return list
        val norm = list.map { it.copy(weeks = it.weeks.sorted().distinct()) }

        val sorted1 = norm.sortedWith(compareBy(
            { it.name }, { it.teacher }, { it.position }, { it.day },
            { it.weeks.joinToString(",") }, { it.startSection }
        ))
        val step1 = mutableListOf<DraftCourse>()
        var cur = sorted1[0]
        for (i in 1 until sorted1.size) {
            val nxt = sorted1[i]
            val same = cur.name == nxt.name && cur.teacher == nxt.teacher &&
                cur.position == nxt.position && cur.day == nxt.day && cur.weeks == nxt.weeks
            val continuous = cur.endSection + 1 == nxt.startSection
            val duplicate = cur.startSection == nxt.startSection && cur.endSection == nxt.endSection
            when {
                same && continuous -> cur = cur.copy(endSection = nxt.endSection)
                same && duplicate -> { /* skip */ }
                else -> { step1.add(cur); cur = nxt }
            }
        }
        step1.add(cur)

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

    private fun parseWeeks(zc: String, zcstr: String): List<Int> {
        val fromZcstr = zcstr.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
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
            if (single != null) { baseWeeks.add(single); continue }
            val rangeMatch = Regex("^(\\d+)\\s*[-\\~]\\s*(\\d+)$").find(t)
            if (rangeMatch != null) {
                val start = Math.min(rangeMatch.groupValues[1].toInt(), rangeMatch.groupValues[2].toInt())
                val end = Math.max(rangeMatch.groupValues[1].toInt(), rangeMatch.groupValues[2].toInt())
                for (w in start..end) baseWeeks.add(w)
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

    // ------------------- 静态偏好访问（转发，UI 接口不变） -------------------

    /**
     * 重复课程冲突组信息（用于 UI 弹窗）。
     */
    data class DuplicateGroupInfo(
        val sampleCourseName: String,
        val sampleTeacherSummary: String,
        val totalConflictCourses: Int,
        val groupCount: Int,
        val hasIdentical: Boolean,
        val hasMultiTeacher: Boolean
    )

    enum class DuplicateResolveStrategy {
        KEEP_ALL,
        MERGE_TEACHERS,
        KEEP_ONE
    }

    companion object {
        /**
         * 分析课程列表中是否存在同时间、同地点、同名但多教师或完全相同的重复课程。
         */
        fun analyzeDuplicateCourses(courses: List<CourseWithWeeks>): DuplicateGroupInfo? {
            if (courses.size <= 1) return null
            val groups = courses.groupBy { cw ->
                val c = cw.course
                val weeksStr = cw.weeks.map { it.weekNumber }.sorted().joinToString(",")
                "${c.name}|${c.position}|${c.day}|${c.startSection}|${c.endSection}|$weeksStr"
            }.values.filter { it.size > 1 }

            if (groups.isEmpty()) return null

            var hasIdentical = false
            var hasMultiTeacher = false
            var sampleMultiGroup: List<CourseWithWeeks>? = null

            for (g in groups) {
                val teachers = g.map { it.course.teacher.trim() }.filter { it.isNotEmpty() }.distinct()
                if (teachers.size > 1) {
                    hasMultiTeacher = true
                    if (sampleMultiGroup == null) sampleMultiGroup = g
                } else {
                    hasIdentical = true
                }
            }

            val targetSampleGroup = sampleMultiGroup ?: groups.first()
            val sampleCourse = targetSampleGroup.first().course
            val teachersCombined = targetSampleGroup.map { it.course.teacher.trim() }
                .filter { it.isNotEmpty() }.distinct().joinToString("、")

            val totalCoursesInConflicts = groups.sumOf { it.size }
            return DuplicateGroupInfo(
                sampleCourseName = sampleCourse.name,
                sampleTeacherSummary = teachersCombined.ifBlank { "多位教师" },
                totalConflictCourses = totalCoursesInConflicts,
                groupCount = groups.size,
                hasIdentical = hasIdentical,
                hasMultiTeacher = hasMultiTeacher
            )
        }

        /**
         * 根据策略解决重复课程。
         */
        fun resolveDuplicateCourses(
            courses: List<CourseWithWeeks>,
            strategy: DuplicateResolveStrategy
        ): List<CourseWithWeeks> {
            if (strategy == DuplicateResolveStrategy.KEEP_ALL || courses.size <= 1) return courses

            val grouped = courses.groupBy { cw ->
                val c = cw.course
                val weeksStr = cw.weeks.map { it.weekNumber }.sorted().joinToString(",")
                "${c.name}|${c.position}|${c.day}|${c.startSection}|${c.endSection}|$weeksStr"
            }

            val result = mutableListOf<CourseWithWeeks>()
            for ((_, group) in grouped) {
                if (group.size == 1) {
                    result.add(group.first())
                } else {
                    val first = group.first()
                    when (strategy) {
                        DuplicateResolveStrategy.MERGE_TEACHERS -> {
                            val combinedTeachers = group.map { it.course.teacher.trim() }
                                .filter { it.isNotEmpty() }.distinct().joinToString("、")
                            val mergedCourse = first.course.copy(
                                teacher = if (combinedTeachers.isNotBlank()) combinedTeachers else first.course.teacher
                            )
                            result.add(first.copy(course = mergedCourse))
                        }
                        DuplicateResolveStrategy.KEEP_ONE -> {
                            result.add(first)
                        }
                        DuplicateResolveStrategy.KEEP_ALL -> {
                            result.addAll(group)
                        }
                    }
                }
            }
            return result
        }

        private const val KEY_LAST_USE_VPN = "last_use_vpn"
        private const val KEY_LAST_USE_VPN_SET = "last_use_vpn_set"

        /** 教务系统直连登录（/admin/login）JSEncrypt 硬编码公钥（1024 位 PKCS#1）。 */
        private const val JWXT_RSA_MODULUS = "B3B58F37A7A94BF018359A825981DE8C39E1B41A55602A5D134EBC7C612CB8C9897E0F907FC1E12B40AF2A39E472860E0FBB8F336FBACD0104E84FDFF1E223ACB70C0EC4DD1B2935D884FE0AAC74B5FDB69B757FCDA04A89DF4AD5C2997517C89563B64C303DCE97A1DA3D4A989927A753ECBFC49D2D6EB889CBC1B71F9AF501"
        private const val JWXT_RSA_EXPONENT = "010001"

        /** 「使用 PC User-Agent」：默认关闭。 */
        fun getUsePcUserAgent(context: Context): Boolean = WbuAuthTransport.getUsePcUserAgent(context)
        fun setUsePcUserAgent(context: Context, enabled: Boolean) = WbuAuthTransport.setUsePcUserAgent(context, enabled)

        /** 「不检测校园网环境」：默认关闭。 */
        fun getSkipCampusCheck(context: Context): Boolean = WbuAuthTransport.getSkipCampusCheck(context)
        fun setSkipCampusCheck(context: Context, enabled: Boolean) = WbuAuthTransport.setSkipCampusCheck(context, enabled)

        /** 「保留教师工号」：默认关闭。 */
        fun getKeepTeacherId(context: Context): Boolean = WbuAuthTransport.getKeepTeacherId(context)
        fun setKeepTeacherId(context: Context, enabled: Boolean) = WbuAuthTransport.setKeepTeacherId(context, enabled)

        /** 「保留建筑名称」：默认关闭。 */
        fun getKeepBuilding(context: Context): Boolean = WbuAuthTransport.getKeepBuilding(context)
        fun setKeepBuilding(context: Context, enabled: Boolean) = WbuAuthTransport.setKeepBuilding(context, enabled)
        fun getSelectSemesterOnImport(context: Context): Boolean = WbuAuthTransport.getSelectSemesterOnImport(context)
        fun setSelectSemesterOnImport(context: Context, enabled: Boolean) = WbuAuthTransport.setSelectSemesterOnImport(context, enabled)

        /** 去除教师名末尾工号。 */
        fun cleanTeacherId(teacher: String): String {
            val m = Regex("^(.+?)\\s*[（(](\\d{4,})[）)]$").find(teacher.trim()) ?: return teacher
            return m.groupValues[1].trim()
        }

        fun hasPersistedSession(context: Context): Boolean = WbuAuthTransport.hasPersistedSession(context)
        fun getSavedUseVpn(context: Context): Boolean? = WbuAuthTransport.getSavedUseVpn(context)
        fun setSavedUseVpn(context: Context, enabled: Boolean) = WbuAuthTransport.setSavedUseVpn(context, enabled)
        fun getSavedStudentId(context: Context): String = WbuAuthTransport.getSavedStudentId(context)

        fun isSimplifiedChinese(context: Context): Boolean = WbuAuthTransport.isSimplifiedChinese(context)

        /** 「IDS addr not from Jwxt」：为 true 时不从教务登录页发现 CAS 链接，直接用 ids 基址构造。默认关闭。 */
        fun getIdsAddrNotFromJwxt(context: Context): Boolean = WbuAuthTransport.getIdsAddrNotFromJwxt(context)
        fun setIdsAddrNotFromJwxt(context: Context, enabled: Boolean) =
            WbuAuthTransport.setIdsAddrNotFromJwxt(context, enabled)

        /** 「no indexMain verify」：为 true 时不解析/打开 indexMain，仅校验会话 cookie + 拿学号。默认关闭。 */
        fun getNoIndexMainVerify(context: Context): Boolean = WbuAuthTransport.getNoIndexMainVerify(context)
        fun setNoIndexMainVerify(context: Context, enabled: Boolean) =
            WbuAuthTransport.setNoIndexMainVerify(context, enabled)

        /** 「登录WebVPN前必须获取学号」：为 true 时无论输入格式如何均先从 ids 换取学号。默认关闭。 */
        fun getForceFetchStudentIdBeforeVpn(context: Context): Boolean =
            WbuAuthTransport.getForceFetchStudentIdBeforeVpn(context)
        fun setForceFetchStudentIdBeforeVpn(context: Context, enabled: Boolean) =
            WbuAuthTransport.setForceFetchStudentIdBeforeVpn(context, enabled)

        /** 「使用固定service获取ticket」：为 true 时提取学号使用同源个人中心 service。默认关闭。 */
        fun getUseFixedServiceForTicket(context: Context): Boolean =
            WbuAuthTransport.getUseFixedServiceForTicket(context)
        fun setUseFixedServiceForTicket(context: Context, enabled: Boolean) =
            WbuAuthTransport.setUseFixedServiceForTicket(context, enabled)
    }
}
