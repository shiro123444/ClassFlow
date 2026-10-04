package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 访问失败发生在哪一层。文案与「下一步该点哪个按钮」都由它决定。
 *
 * 判据永远只看「请求实际走到了哪」：
 * - 一个响应都没拿到 → [Unreachable]（网络问题，重试即可，**不要**弹登录框）；
 * - 拿到了响应，但被重定向/接管到登录页 → [SessionExpired]（凭据问题，**直接弹登录框**）；
 * - 拿到了响应且服务端明确说凭据不对 → [CredentialRejected]；
 * - 拿到了响应但内容不是业务页 → [Unexpected]。
 */
enum class AccessLayer {
    /** 校园网直连（不经 WebVPN）。 */
    CampusDirect,

    /** WebVPN 门户 / 隧道（TWFID）。 */
    WebVpnPortal,

    /** 统一身份认证（CASTGC / 票据）。 */
    UnifiedAuth,

    /** 业务系统本身（教务、图书馆、一卡通、U净…）的会话。 */
    Service
}

/** 被拒的凭据类型，决定文案点名哪一个输入框。 */
enum class CredentialKind {
    Password,
    SmsCode,
    Captcha,
    Unknown
}

/**
 * 结构化的访问失败原因。
 *
 * 取代过去散落在 [WbuSyncEngine] 上的 `lastLocalLoginError: String?` /
 * `lastLocalLoginNetworkError: Boolean` / `lastLocalLoginFailure` 三个可变字段 —— 那些字段依赖
 * 每个失败分支「记得」写入原因，任何一处 `return false` 漏写，UI 就只剩一句看不懂的兜底文案
 * （典型事故：用户取消短信验证码，界面却提示「请检查账号密码或校外VPN开关」）。
 */
sealed interface AccessFailure {

    /** 一个 HTTP 响应都没拿到：超时、DNS 失败、连接被拒、TLS 失败。属于网络问题。 */
    data class Unreachable(
        val layer: AccessLayer,
        val cause: Throwable? = null
    ) : AccessFailure

    /**
     * 拿到了响应，但被网关 / 认证中心接管到了登录页 —— 即「登录状态已过期」。
     * 这是**唯一**应当弹登录框的失败类型，也是最容易被误判成「没有数据」的一类。
     */
    data class SessionExpired(
        val layer: AccessLayer
    ) : AccessFailure

    /** 服务端明确拒绝了凭据（密码错、验证码错、需要图形验证码）。 */
    data class CredentialRejected(
        val layer: AccessLayer,
        val kind: CredentialKind = CredentialKind.Unknown,
        val serverMessage: String? = null
    ) : AccessFailure

    /** 用户主动取消输入（WebVPN 密码 / 短信验证码 / 图形验证码 / 校园网确认）。不应报错。 */
    data object Cancelled : AccessFailure

    /** 有响应但内容不符合预期：接口调整、解析失败、服务端维护页。 */
    data class Unexpected(
        val layer: AccessLayer,
        val detail: String? = null
    ) : AccessFailure
}

/** 失败后给用户的可执行动作，UI 用它决定展示哪个按钮。 */
enum class AccessAction {
    /** 什么都不用做（用户取消）。 */
    None,

    /** 就地重试即可。 */
    Retry,

    /** 打开登录框重新登录。 */
    Relogin,

    /** 本次改用 WebVPN 走一遍（单次生效，不改全局开关）。 */
    UseWebVpnOnce
}

/** 失败类型 → 建议动作。 */
fun AccessFailure.suggestedAction(webVpnEnabled: Boolean): AccessAction = when (this) {
    is AccessFailure.Cancelled -> AccessAction.None
    is AccessFailure.Unreachable ->
        if (layer == AccessLayer.CampusDirect && !webVpnEnabled) AccessAction.UseWebVpnOnce else AccessAction.Retry
    is AccessFailure.SessionExpired -> AccessAction.Relogin
    is AccessFailure.CredentialRejected -> AccessAction.Relogin
    is AccessFailure.Unexpected -> AccessAction.Retry
}

/** 是否是「需要重新登录」类失败（区别于网络不通）。 */
val AccessFailure.needsRelogin: Boolean
    get() = this is AccessFailure.SessionExpired || this is AccessFailure.CredentialRejected

/**
 * 统一的失败判据：把「响应特征」与「异常类型」翻译成 [AccessFailure]。
 *
 * 过去这些判断散落在各个 client 里（`Location.contains("login")`、`contains("/por/")`、
 * host 判定、`looksLikeHtml`…），任何一处漏判就会把「没登录」显示成「没有数据」。
 */
object WbuFailureDetector {

    /** WebVPN 门户（Sangfor）根域名：门禁失效时，代理网关会把所有子域请求接管到这里。 */
    const val WEBVPN_GATEWAY_HOST = "webvpn.wbu.edu.cn"

    /**
     * 网络异常 → [AccessFailure]。只有「连一个响应都没拿到」才算网络问题（[AccessFailure.Unreachable]）；
     * 其余异常（解析失败、状态异常等）归为 [AccessFailure.Unexpected]，两者给用户的下一步完全不同。
     */
    fun fromThrowable(e: Throwable, layer: AccessLayer): AccessFailure = when (e) {
        is WbuSessionExpiredException -> AccessFailure.SessionExpired(e.layer)
        is UnknownHostException, is SocketTimeoutException, is SSLException, is IOException ->
            AccessFailure.Unreachable(layer, e)
        else -> AccessFailure.Unexpected(layer, e.message)
    }

    /** 某个 host 是否是 WebVPN 门户本身（代理子域如 `opac-xxx.webvpn.wbu.edu.cn` **不算**）。 */
    fun isGatewayHost(host: String?): Boolean =
        host?.equals(WEBVPN_GATEWAY_HOST, ignoreCase = true) == true

    /**
     * 从 URL 文本判断是否指向 WebVPN 门户。注意必须**按 host 精确比较**：
     * 正常经 WebVPN 访问时落点本身就是代理子域，用 substring 判断会把合法请求误判成门禁接管。
     */
    fun isGatewayUrl(url: String?): Boolean =
        !url.isNullOrBlank() && isGatewayHost(url.toHttpUrlOrNull()?.host)

    /**
     * 判断一段响应是否属于「被接管到登录页」：
     * @param finalHost 跟随重定向后最终落地的 host（OkHttp 的 `response.request.url.host`）
     * @param location  响应里的 Location 头（若有）
     * @param body      响应体（可选，用于识别门户/登录页特征）
     */
    fun sessionExpiredOrNull(
        finalHost: String?,
        location: String?,
        body: String? = null,
        serviceLayer: AccessLayer = AccessLayer.Service
    ): AccessFailure.SessionExpired? {
        if (isGatewayHost(finalHost) || isGatewayUrl(location)) {
            return AccessFailure.SessionExpired(AccessLayer.WebVpnPortal)
        }
        if (location.orEmpty().contains("/por/", ignoreCase = true)) {
            return AccessFailure.SessionExpired(AccessLayer.WebVpnPortal)
        }
        if (location.orEmpty().contains("authserver/login", ignoreCase = true) &&
            !location.orEmpty().contains("ticket=", ignoreCase = true)
        ) {
            return AccessFailure.SessionExpired(AccessLayer.UnifiedAuth)
        }
        if (location.orEmpty().contains("login", ignoreCase = true)) {
            return AccessFailure.SessionExpired(serviceLayer)
        }
        if (body != null && looksLikeWebVpnPortal(body)) {
            return AccessFailure.SessionExpired(AccessLayer.WebVpnPortal)
        }
        return null
    }

    /** 响应体特征：Portal 接管页。仅用于「本来要当作空数据返回」的兜底分支。 */
    fun looksLikeWebVpnPortal(body: String): Boolean =
        body.contains("redirect_uri=", ignoreCase = true) || body.contains("login_psw.csp", ignoreCase = true)
}
