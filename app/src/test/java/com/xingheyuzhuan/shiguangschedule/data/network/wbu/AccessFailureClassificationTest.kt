package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判据层单测。
 *
 * 这里钉住的是「静默自动登录」这条链路最贵的两类 bug：
 * 1. 把网络/协议问题翻译成「账号密码不对」→ 用户什么都没做错却被要求重新输密码；
 * 2. 把验证码错翻译成「密码不对」→ 用户正确的密码被清掉，还得重新输一遍。
 *
 * 文案全部取自线上实测响应（中/英两套语言），不是猜的。
 */
class AccessFailureClassificationTest {

    // ---------- 文案 → 被拒的凭据类型 ----------

    @Test
    fun casPasswordErrorTextsInBothLanguages() {
        // 实测：POST /authserver/login → HTTP 401 + 停在登录页 + #showErrorTip
        assertEquals(CredentialKind.Password, classifyCredentialRejection("您提供的用户名或者密码有误"))
        assertEquals(CredentialKind.Password, classifyCredentialRejection("username or password is incorrect"))
    }

    @Test
    fun casCaptchaErrorTextsInBothLanguages() {
        // 实测：同为 401，但提示只出现在 #showErrorTip，且文案点名验证码
        assertEquals(CredentialKind.Captcha, classifyCredentialRejection("验证码错误"))
        assertEquals(CredentialKind.Captcha, classifyCredentialRejection("Verification code error"))
    }

    @Test
    fun jwxtLegacyErrorTextMentionsFrozenAccount() {
        val text = "用户或密码错误, 请重试。当前错误次数为：1次，超过10次后，账号将被冻结15分钟。"
        assertEquals(CredentialKind.Password, classifyCredentialRejection(text))
    }

    @Test
    fun captchaWinsWhenBothWordsAppear() {
        // 同时出现「密码」和「验证码」时，用户要补的是验证码：密码通常上一轮就是对的，不能清
        assertEquals(CredentialKind.Captcha, classifyCredentialRejection("密码正确，请输入验证码"))
    }

    @Test
    fun unrecognizedTextsAreUnknownRatherThanPassword() {
        // 判不出类型就什么都不许动 —— 尤其是「不能清密码」
        assertEquals(CredentialKind.Unknown, classifyCredentialRejection(null))
        assertEquals(CredentialKind.Unknown, classifyCredentialRejection(""))
        assertEquals(CredentialKind.Unknown, classifyCredentialRejection("   "))
        assertEquals(CredentialKind.Unknown, classifyCredentialRejection("系统维护中，请稍后再试"))
        assertEquals(CredentialKind.Unknown, classifyCredentialRejection("maybe attacked"))
        assertEquals(CredentialKind.Unknown, classifyCredentialRejection("登录失败，请重试"))
    }

    @Test
    fun portalEnglishMessageIsRecognizedAsPasswordRejection() {
        // 门户（Sangfor）的 Message 恒为英文，ErrorCode=20004 才是主判据，这里是兜底
        assertEquals(CredentialKind.Password, classifyCredentialRejection("Invalid username or password!"))
    }

    // ---------- CAS 结果 → 结构化失败 ----------

    @Test
    fun casProtocolFailureNeverBecomesCredentialRejection() {
        // 「无法获取登录参数」「跳转异常」「重定向次数过多」都属这一档：只该重试，不该弹登录框
        val failure = casLoginFailure(
            CasPasswordLoginResult(false, "无法获取登录参数", LocalLoginFailure.PROTOCOL),
            AccessLayer.UnifiedAuth
        )
        assertTrue(failure is AccessFailure.Unexpected)
        assertFalse(failure.needsRelogin)
        assertNull(failure.confirmedPasswordRejectionLayer)
    }

    @Test
    fun casNetworkFailureIsUnreachableNotCredentialRejection() {
        val failure = casLoginFailure(
            CasPasswordLoginResult(false, "网络异常: timeout", LocalLoginFailure.NETWORK),
            AccessLayer.UnifiedAuth
        )
        assertTrue(failure is AccessFailure.Unreachable)
        assertFalse(failure.needsRelogin)
    }

    @Test
    fun casLandingOnPortalIsSessionExpiryNotPasswordError() {
        val failure = casLoginFailure(
            CasPasswordLoginResult(false, "WebVPN 未授权或会话已失效", LocalLoginFailure.VPN_SESSION),
            AccessLayer.UnifiedAuth
        )
        assertEquals(AccessFailure.SessionExpired(AccessLayer.WebVpnPortal), failure)
        assertTrue(failure.needsRelogin)
        // 会话失效可以静默重建，但绝不能顺手清掉密码
        assertNull(failure.confirmedPasswordRejectionLayer)
    }

    @Test
    fun casCaptchaFailureNeverClearsPassword() {
        val failure = casLoginFailure(
            CasPasswordLoginResult(false, "滑块验证未通过", LocalLoginFailure.CAPTCHA),
            AccessLayer.UnifiedAuth
        )
        assertEquals(CredentialKind.Captcha, (failure as AccessFailure.CredentialRejected).kind)
        assertTrue(failure.needsRelogin)
        assertNull(failure.confirmedPasswordRejectionLayer)
    }

    @Test
    fun casConfirmedPasswordRejectionClearsUnifiedAuthPassword() {
        val failure = casLoginFailure(
            CasPasswordLoginResult(
                success = false,
                message = "您提供的用户名或者密码有误",
                failure = LocalLoginFailure.CREDENTIALS,
                rejectedKind = CredentialKind.Password
            ),
            AccessLayer.UnifiedAuth
        )
        assertEquals(AccessLayer.UnifiedAuth, failure.confirmedPasswordRejectionLayer)
    }

    @Test
    fun casUnspecifiedRejectionDoesNotClearPassword() {
        // 服务端拒绝了但没说清是哪个输入框：仍然弹登录框，但不许清密码
        val failure = casLoginFailure(
            CasPasswordLoginResult(false, "登录失败，请重试", LocalLoginFailure.CREDENTIALS),
            AccessLayer.UnifiedAuth
        )
        assertTrue(failure.needsRelogin)
        assertNull(failure.confirmedPasswordRejectionLayer)
    }

    // ---------- 门户结果 → 结构化失败 ----------

    @Test
    fun portalRejectedPasswordIsConfirmedButOthersAreNot() {
        val rejected = portalLoginFailure("Invalid username or password!", PortalFailureKind.Rejected)
        assertEquals(AccessLayer.WebVpnPortal, rejected.confirmedPasswordRejectionLayer)

        for (kind in listOf(
            PortalFailureKind.Captcha,
            PortalFailureKind.Network,
            PortalFailureKind.Protocol,
            PortalFailureKind.Cancelled
        )) {
            assertNull(
                "门户 $kind 不得被判成密码错",
                portalLoginFailure("whatever", kind).confirmedPasswordRejectionLayer
            )
        }
    }

    @Test
    fun portalFailureKindsMapToDistinctNextSteps() {
        // 图形验证码 → 就地小窗（仍是 CredentialRejected，但 kind 不是 Password）
        assertEquals(
            CredentialKind.Captcha,
            (portalLoginFailure("randcode", PortalFailureKind.Captcha) as AccessFailure.CredentialRejected).kind
        )
        // 网络 / 协议 → 只重试，不弹登录框
        assertTrue(portalLoginFailure("timeout", PortalFailureKind.Network) is AccessFailure.Unreachable)
        assertTrue(portalLoginFailure("no rsa key", PortalFailureKind.Protocol) is AccessFailure.Unexpected)
        // 用户关掉图形验证码弹窗 → 安静回落
        assertEquals(AccessFailure.Cancelled, portalLoginFailure("cancelled", PortalFailureKind.Cancelled))
    }

    @Test
    fun suggestedActionsFollowTheContract() {
        // 契约：只有「必须用户补新凭据」才提示重新登录
        assertEquals(
            AccessAction.Relogin,
            AccessFailure.CredentialRejected(AccessLayer.UnifiedAuth, CredentialKind.Password).suggestedAction(false)
        )
        assertEquals(
            AccessAction.Relogin,
            AccessFailure.SessionExpired(AccessLayer.UnifiedAuth).suggestedAction(false)
        )
        assertEquals(
            AccessAction.Retry,
            AccessFailure.Unexpected(AccessLayer.UnifiedAuth, "x").suggestedAction(false)
        )
        assertEquals(
            AccessAction.UseWebVpnOnce,
            AccessFailure.Unreachable(AccessLayer.CampusDirect).suggestedAction(webVpnEnabled = false)
        )
        assertEquals(
            AccessAction.Retry,
            AccessFailure.Unreachable(AccessLayer.WebVpnPortal).suggestedAction(webVpnEnabled = true)
        )
        assertEquals(AccessAction.None, AccessFailure.Cancelled.suggestedAction(false))
    }
}
