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
}
