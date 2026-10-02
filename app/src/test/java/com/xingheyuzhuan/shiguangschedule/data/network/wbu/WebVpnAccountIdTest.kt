package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVpnAccountIdTest {
    @Test
    fun acceptsStudentAndStaffIdFormatsWithoutPreLookup() {
        assertTrue(isDirectWebVpnAccountId("260593099"))
        assertTrue(isDirectWebVpnAccountId("20170111"))
        assertTrue(isDirectWebVpnAccountId(" 20170111 "))
    }

    @Test
    fun aliasesAndOtherLengthsRequireIdResolution() {
        assertFalse(isDirectWebVpnAccountId("student01"))
        assertFalse(isDirectWebVpnAccountId("1234567"))
        assertFalse(isDirectWebVpnAccountId("1234567890"))
    }
}
