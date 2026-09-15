package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 统一认证登录二维码内容解析（实测样本来自 WBUCas/qr_scan_notes.md 第二节）。
 */
class CasQrLinkTest {

    @Test
    fun parsesUuidFromDirectIdsUrl() {
        val raw = "http://ids.wbu.edu.cn/authserver/qrCode/qrCodeLogin.do?uuid=QR-wpxKUtmDr9HKixOcBLe"
        assertEquals("QR-wpxKUtmDr9HKixOcBLe", CasQrLink.parseUuid(raw))
    }

    @Test
    fun parsesUuidFromWebVpnProxyUrl() {
        val raw = "https://ids-wbu-edu-cn.webvpn.wbu.edu.cn:8118/authserver/qrCode/qrCodeLogin.do?uuid=QR-abc12345678"
        assertEquals("QR-abc12345678", CasQrLink.parseUuid(raw))
    }

    @Test
    fun ignoresTrailingQueryParameters() {
        val raw = "http://ids.wbu.edu.cn/authserver/qrCode/qrCodeLogin.do?uuid=QR-abc12345678&ts=1"
        assertEquals("QR-abc12345678", CasQrLink.parseUuid(raw))
    }

    @Test
    fun ignoresSurroundingWhitespace() {
        val raw = "  http://ids.wbu.edu.cn/authserver/qrCode/qrCodeLogin.do?uuid=QR-abc12345678\n"
        assertEquals("QR-abc12345678", CasQrLink.parseUuid(raw))
    }

    @Test
    fun rejectsUnrelatedUrl() {
        assertNull(CasQrLink.parseUuid("https://www.baidu.com"))
    }

    @Test
    fun rejectsOtherCasEndpoint() {
        val raw = "http://ids.wbu.edu.cn/authserver/login?type=qrcode&service=x"
        assertNull(CasQrLink.parseUuid(raw))
    }

    @Test
    fun rejectsMissingUuid() {
        assertNull(CasQrLink.parseUuid("http://ids.wbu.edu.cn/authserver/qrCode/qrCodeLogin.do"))
    }

    @Test
    fun rejectsForeignUuidPrefix() {
        val raw = "http://ids.wbu.edu.cn/authserver/qrCode/qrCodeLogin.do?uuid=ABC-abc12345678"
        assertNull(CasQrLink.parseUuid(raw))
    }

    @Test
    fun rejectsEmptyUuid() {
        val raw = "http://ids.wbu.edu.cn/authserver/qrCode/qrCodeLogin.do?uuid="
        assertNull(CasQrLink.parseUuid(raw))
    }

    @Test
    fun rejectsPlainText() {
        assertNull(CasQrLink.parseUuid("hello world"))
    }
}
