package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVpnCaptchaDetectionTest {
    @Test
    fun detectsCaptchaFromAnyPortalFlag() {
        assertTrue(isWebVpnCaptchaEnabled("1", null, null))
        assertTrue(isWebVpnCaptchaEnabled(null, "1", null))
        assertTrue(isWebVpnCaptchaEnabled(null, null, "1"))
        assertFalse(isWebVpnCaptchaEnabled("0", "0", "0"))
        assertFalse(isWebVpnCaptchaEnabled(null, null, null))
    }

    @Test
    fun recognizesCaptchaErrorsButNotSuccessfulAuthResults() {
        assertTrue(isWebVpnCaptchaError("0", "20023"))
        assertTrue(isWebVpnCaptchaError("0", "20041"))
        assertTrue(isWebVpnCaptchaError("0", "20043"))
        assertFalse(isWebVpnCaptchaError("1", "20023"))
        assertFalse(isWebVpnCaptchaError("2", "20041"))
        assertFalse(isWebVpnCaptchaError("0", "20004"))
    }
}
