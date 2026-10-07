package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVpnCaptchaDetectionTest {

    @Test
    fun detectsCaptchaFromPortalFlags() {
        // 门户前端 dto.js：enableRandCode = psw_config 的 USE_RAND_CODE；login_auth 的 RndImg 是同义冗余信号。
        // 门户从不回 ENABLE_RANDCODE 响应头，那个判据已经删掉。
        assertTrue(isWebVpnCaptchaEnabled("1", null))
        assertTrue(isWebVpnCaptchaEnabled(null, "1"))
        assertTrue(isWebVpnCaptchaEnabled("1", "1"))
        assertFalse(isWebVpnCaptchaEnabled("0", "0"))
        assertFalse(isWebVpnCaptchaEnabled(null, null))
    }

    @Test
    fun recognizesCaptchaErrorCodesRegardlessOfResultTag() {
        // 判定只看 ErrorCode（对齐门户 dto.js：code 取自 ErrorCode，而不是 Result）
        for (code in listOf("20023", "20041", "20042", "20043", "20053", "20268")) {
            assertTrue("$code 应判为验证码类错误", isWebVpnCaptchaError(code))
        }
        assertFalse(isWebVpnCaptchaError("20004"))
        assertFalse(isWebVpnCaptchaError("20026"))
        assertFalse(isWebVpnCaptchaError("1"))
        assertFalse(isWebVpnCaptchaError(null))
    }

    @Test
    fun normalizesPastedTwfidValues() {
        assertEquals("0123456789abcdef", normalizeTwfid("0123456789abcdef"))
        assertEquals("0123456789abcdef", normalizeTwfid("  0123456789abcdef  "))
        assertEquals("0123456789abcdef", normalizeTwfid("\"0123456789abcdef\""))
        // 直接粘整行 Cookie（门户 Application → Cookies 里复制的就是这种）
        assertEquals(
            "0123456789abcdef",
            normalizeTwfid("TWFID=0123456789abcdef; path=/; domain=webvpn.wbu.edu.cn")
        )
        assertEquals("0123456789abcdef", normalizeTwfid("twfid=0123456789abcdef"))
        assertEquals("", normalizeTwfid("   "))
        assertEquals("", normalizeTwfid(null))
    }

    @Test
    fun flagsSuspiciousTwfidShapes() {
        // 真机上的 TWFID 是 16 位十六进制；形状可疑只提示，不拒绝用户输入
        assertTrue(looksLikeTwfid("0123456789abcdef"))
        assertFalse(looksLikeTwfid("not-a-session"))
        assertFalse(looksLikeTwfid(""))
    }
}
