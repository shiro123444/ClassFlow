package com.xingheyuzhuan.shiguangschedule.ui.components

import android.content.Context
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessFailure
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessLayer
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.CredentialKind
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuFailureDetector
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.needsRelogin

/**
 * 把结构化的 [AccessFailure] 渲染成用户看得懂的文案。
 *
 * 设计约定：**用户可见文案只在这里出现一次**，数据层（网络/同步引擎）只传「在哪一层、为什么失败」，
 * 不再持有任何中文/英文字面量。这样既能翻译，也避免了「用户取消 → 提示检查账号密码」这类
 * 由兜底文案编造原因的事故。
 *
 * 返回 null 表示「不需要展示错误」（例如用户自己取消了输入）。
 */
fun accessFailureText(context: Context, failure: AccessFailure): String? = when (failure) {
    // 用户主动取消：不报错，静默回到原状态即可
    is AccessFailure.Cancelled -> null

    // 登录态过期：明确告诉用户「重新登录就能继续」
    is AccessFailure.SessionExpired -> when (failure.layer) {
        AccessLayer.WebVpnPortal -> context.getString(R.string.fail_webvpn_session_expired)
        AccessLayer.UnifiedAuth -> context.getString(R.string.fail_unified_auth_session_expired)
        else -> context.getString(R.string.fail_session_expired)
    }

    // 凭据被拒：点名是哪一个输入框不对；只有类型未知时才退回学校服务端原话
    is AccessFailure.CredentialRejected -> when (failure.kind) {
        CredentialKind.SmsCode -> context.getString(R.string.fail_sms_code_rejected)
        CredentialKind.Captcha -> context.getString(R.string.fail_captcha_rejected)
        CredentialKind.Password -> webVpnOrGeneralCredentialText(context, failure.layer)
        CredentialKind.Unknown ->
            failure.serverMessage?.takeIf { it.isNotBlank() }
                ?: webVpnOrGeneralCredentialText(context, failure.layer)
    }

    // 网络不通：区分「不在校园网」与「学校服务器连不上」，两者下一步动作不同
    is AccessFailure.Unreachable -> when (failure.layer) {
        AccessLayer.CampusDirect -> context.getString(R.string.fail_not_on_campus)
        AccessLayer.WebVpnPortal -> context.getString(R.string.fail_webvpn_unreachable)
        AccessLayer.UnifiedAuth -> context.getString(R.string.fail_unified_auth_unreachable)
        AccessLayer.Service -> context.getString(R.string.fail_network_unreachable)
    }

    // 有响应但内容不对：不把技术细节甩给用户，只给「重试」。
    // 措辞刻意只说「返回的内容不符预期」，不替服务端编原因 —— 曾经这里写的是「可能是学校系统有变动」，
    // 结果代理 / VPN 出问题时也弹这一句，用户照着「等学校修好」去等，永远等不到。
    is AccessFailure.Unexpected -> context.getString(R.string.fail_unexpected)
}

/**
 * 异常 → 文案。会把异常归类成 [AccessFailure] 后再渲染，因此任何抛出点都不必自带文案。
 * 传入的 [layer] 只是「不知道具体层次时」的兜底归属。
 */
fun accessFailureText(
    context: Context,
    throwable: Throwable,
    layer: AccessLayer = AccessLayer.Service
): String? = accessFailureText(context, WbuFailureDetector.fromThrowable(throwable, layer))

/** WebVPN 门户的凭据与其它服务的凭据分开措辞：前者是「校外通道」，后者是「学号密码」。 */
private fun webVpnOrGeneralCredentialText(context: Context, layer: AccessLayer): String =
    if (layer == AccessLayer.WebVpnPortal) context.getString(R.string.fail_webvpn_credential_rejected)
    else context.getString(R.string.fail_credential_rejected)

/**
 * 「需要重新登录」的提示文案：在失败原因后面附一句「长按按钮可打开登录面板」。
 *
 * 单击同步 / 单击导入**不再弹登录面板**，所以必须在提示里说清楚面板的入口挪到了长按上，
 * 否则用户只会看到「登录已过期」，却不知道该去哪里登录。
 *
 * 但这句话只对**[AccessFailure.needsRelogin]**（登录态失效 / 凭据被拒）成立：
 * 网络不通、学校返回的内容不对、用户自己取消这些情况，把人引到登录面板前解决不了任何问题
 * （还会顺手再走一遍没用的登录）。这类失败只报原因，不附「长按」的指引。
 *
 * @param failure null 表示「只知道需要登录、不知道原因」→ 用兜底文案 + 长按指引；
 *   用户自己取消（[accessFailureText] 返回 null）时同样返回 null，让调用方安静收场、不报错。
 */
fun needLoginHintText(context: Context, failure: AccessFailure?): String? {
    if (failure == null) {
        return context.getString(
            R.string.format_need_login_long_press,
            context.getString(R.string.err_need_unified_auth_session)
        )
    }
    val reason = accessFailureText(context, failure) ?: return null
    return if (failure.needsRelogin) {
        context.getString(R.string.format_need_login_long_press, reason)
    } else {
        reason
    }
}
