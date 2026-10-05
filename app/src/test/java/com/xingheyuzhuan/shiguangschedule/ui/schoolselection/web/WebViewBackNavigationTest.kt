package com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「WebView 网页导入」按返回先停在 about:blank 白屏、再按一次才能退出 的回归测试。
 *
 * 历史里混进空白页（about:blank / 空串）时，返回应当直接跳到上一条真实页面；
 * 若前面只有空白页，则应交给调用方退出当前页面。
 */
class WebViewBackNavigationTest {

    // ── isBlankWebPageUrl ──

    @Test
    fun isBlankWebPageUrl_treatsNullEmptyAndAboutBlankAsBlank() {
        assertTrue(isBlankWebPageUrl(null))
        assertTrue(isBlankWebPageUrl(""))
        assertTrue(isBlankWebPageUrl("   "))
        assertTrue(isBlankWebPageUrl("about:blank"))
        assertTrue(isBlankWebPageUrl("ABOUT:BLANK"))
    }

    @Test
    fun isBlankWebPageUrl_keepsRealPages() {
        assertFalse(isBlankWebPageUrl("https://jwxt.wbu.edu.cn"))
        assertFalse(isBlankWebPageUrl("https://jwxt.wbu.edu.cn/xsd/pkgl/xskb"))
    }

    // ── previousRealHistoryIndex ──

    @Test
    fun previousRealHistoryIndex_skipsLeadingAboutBlank() {
        // WBU 流程的典型历史：空白页 → 教务首页 → 课表页
        val history = listOf<String?>(
            "about:blank",
            "https://jwxt.wbu.edu.cn",
            "https://jwxt.wbu.edu.cn/xsd/pkgl/xskb",
        )

        // 从课表页返回应直接回到教务首页，而不是白屏
        assertEquals(1, previousRealHistoryIndex(history, currentIndex = 2))
    }

    @Test
    fun previousRealHistoryIndex_returnsNullWhenOnlyBlankEntriesRemain() {
        val history = listOf<String?>("about:blank", "https://jwxt.wbu.edu.cn")

        // 前面只剩空白页：调用方应当直接退出页面
        assertNull(previousRealHistoryIndex(history, currentIndex = 1))
    }

    @Test
    fun previousRealHistoryIndex_skipsConsecutiveBlankEntries() {
        val history = listOf<String?>(
            "about:blank",
            "",
            "about:blank",
            "https://jwxt.wbu.edu.cn",
            "https://jwxt.wbu.edu.cn/admin/index?loginType=xx",
        )

        assertEquals(3, previousRealHistoryIndex(history, currentIndex = 4))
    }

    @Test
    fun previousRealHistoryIndex_handlesEmptyHistoryAndFirstEntry() {
        assertNull(previousRealHistoryIndex(emptyList(), currentIndex = 0))
        assertNull(previousRealHistoryIndex(listOf<String?>("https://jwxt.wbu.edu.cn"), currentIndex = 0))
    }
}
