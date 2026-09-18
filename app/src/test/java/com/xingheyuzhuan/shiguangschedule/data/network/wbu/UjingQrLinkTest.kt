package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UjingQrLinkTest {

    @Test
    fun parsesWaterDispenserUrl() {
        val raw = "http://q.ujing.com.cn/ed/index.html?cd=0011202004140940"
        val res = UjingQrLink.parse(raw)
        assertTrue(res is UjingQrLink.Result.Water)
        assertEquals("0011202004140940", (res as UjingQrLink.Result.Water).cd)
        assertEquals(raw, res.raw)
    }

    @Test
    fun parsesHairdryerUrl() {
        val raw = "http://q.ujing.com.cn/6d/index.html?cd=0014202206120446"
        val res = UjingQrLink.parse(raw)
        assertTrue(res is UjingQrLink.Result.Hairdryer)
        assertEquals("0014202206120446", (res as UjingQrLink.Result.Hairdryer).cd)
        assertEquals(raw, res.raw)
    }

    @Test
    fun parsesWasherUrl() {
        val raw = "http://app.littleswan.com/u_download.html?type=Ujing&uuid=0000000000000A1234567202208040004678"
        val res = UjingQrLink.parse(raw)
        assertTrue(res is UjingQrLink.Result.Washer)
        assertEquals("0000000000000A1234567202208040004678", (res as UjingQrLink.Result.Washer).uuid)
        assertEquals(raw, res.raw)
    }

    @Test
    fun handlesWhitespaceAndHttps() {
        val raw = "  https://q.ujing.com.cn/ed/index.html?cd=123456  \n"
        val res = UjingQrLink.parse(raw)
        assertTrue(res is UjingQrLink.Result.Water)
        assertEquals("123456", (res as UjingQrLink.Result.Water).cd)
    }

    @Test
    fun rejectsUnrelatedUrl() {
        assertNull(UjingQrLink.parse("https://www.wbu.edu.cn"))
        assertNull(UjingQrLink.parse("http://ids.wbu.edu.cn/authserver/qrCode/qrCodeLogin.do?uuid=QR-12345"))
    }

    @Test
    fun rejectsEmptyOrMissingCd() {
        assertNull(UjingQrLink.parse("http://q.ujing.com.cn/ed/index.html?cd="))
        assertNull(UjingQrLink.parse("http://q.ujing.com.cn/ed/index.html"))
    }

    @Test
    fun parsesCampusNfcWaterUrl() {
        val raw = "https://ujing_test.wbu.edu.cn/w/0011202004140940"
        val res = UjingQrLink.parse(raw)
        assertTrue(res is UjingQrLink.Result.Water)
        assertEquals("0011202004140940", (res as UjingQrLink.Result.Water).cd)
        assertEquals(raw, res.raw)
    }

    @Test
    fun parsesCampusNfcWasherUrl() {
        val raw = "https://ujing_test.wbu.edu.cn/wm/0000000000000A1234567202208040004678"
        val res = UjingQrLink.parse(raw)
        assertTrue(res is UjingQrLink.Result.Washer)
        val washer = res as UjingQrLink.Result.Washer
        assertEquals("0000000000000A1234567202208040004678", washer.uuid)
        assertEquals("http://app.littleswan.com/u_download.html?type=Ujing&uuid=0000000000000A1234567202208040004678", washer.raw)
    }

    @Test
    fun buildsHairdryerAlipaySchemeAndUrl() {
        val cd = "0014202206120446"
        val scheme = UjingQrLink.buildHairdryerAlipayScheme(cd)
        assertTrue(scheme.startsWith("alipays://platformapi/startapp?appId=10000007&actionType=route&codeContent="))
        assertTrue(scheme.contains("0014202206120446"))

        val nfcScheme = UjingQrLink.buildHairdryerNfcScheme(cd)
        assertTrue(nfcScheme.startsWith("alipay://nfc/app?id=10000007&actionType=route&codeContent="))
        assertTrue(nfcScheme.contains("0014202206120446"))

        val ulink = UjingQrLink.buildHairdryerAlipayUrl(cd)
        assertTrue(ulink.startsWith("https://render.alipay.com/p/s/ulink/sn?s=dc&scheme=alipay"))
        assertTrue(ulink.contains("0014202206120446"))

        // 测试生成的链接与 scheme 可被 parse 正确提取
        val resUlink = UjingQrLink.parse(ulink)
        assertTrue(resUlink is UjingQrLink.Result.Hairdryer)
        assertEquals(cd, (resUlink as UjingQrLink.Result.Hairdryer).cd)

        val resScheme = UjingQrLink.parse(scheme)
        assertTrue(resScheme is UjingQrLink.Result.Hairdryer)
        assertEquals(cd, (resScheme as UjingQrLink.Result.Hairdryer).cd)
    }

    @Test
    fun parsesAlipayHairdryerUlinkFromUser() {
        val raw = "https://render.alipay.com/p/s/ulink/sn?s=dc&scheme=alipay%3a%2f%2fnfc%2fapp%3fid%3d10000007%26actionType%3droute%26codeContent%3dhttp%253a%252f%252fq.ujing.com.cn%252f6d%252findex.html%253fcd%253d0014202206120446"
        val res = UjingQrLink.parse(raw)
        assertTrue(res is UjingQrLink.Result.Hairdryer)
        assertEquals("0014202206120446", (res as UjingQrLink.Result.Hairdryer).cd)
    }
}
