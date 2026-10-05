package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 洗浴二维码解析器单测：覆盖 2-3 栋（水表 51）与 1 栋（智能控水裸码候选）。
 */
class ShowerQrLinkTest {

    @Test
    fun parsesWaterMeterUrl() {
        val result = ShowerQrLink.parse("http://4gsk.shuibiao51.com?id=1234567890")
        assertTrue(result is ShowerQrLink.Result.LifeService)
        result as ShowerQrLink.Result.LifeService
        assertEquals("1234567890", result.imei)
        assertNull(result.port)
    }

    @Test
    fun parsesWaterMeterSubdomainUrl() {
        val result = ShowerQrLink.parse("http://szsk.shuibiao51.com?id=abc-123")
        assertTrue(result is ShowerQrLink.Result.LifeService)
        assertEquals("abc-123", (result as ShowerQrLink.Result.LifeService).imei)
    }

    @Test
    fun parsesImeiAndPortSeparator() {
        val result = ShowerQrLink.parse("861234567890123\$#\$2")
        assertTrue(result is ShowerQrLink.Result.LifeService)
        result as ShowerQrLink.Result.LifeService
        assertEquals("861234567890123", result.imei)
        assertEquals("2", result.port)
    }

    @Test
    fun parsesUrlEncodedImeiPartBeforeSeparator() {
        val raw = "http%3A%2F%2F4gsk.shuibiao51.com%3Fid%3Dabc\$#\$01"
        val result = ShowerQrLink.parse(raw)
        assertTrue(result is ShowerQrLink.Result.LifeService)
        result as ShowerQrLink.Result.LifeService
        assertEquals("http://4gsk.shuibiao51.com?id=abc", result.imei)
        assertEquals("01", result.port)
    }

    @Test
    fun parsesYktXyyyBareCode() {
        val result = ShowerQrLink.parse("ABCDEFGH10101")
        assertTrue(result is ShowerQrLink.Result.YktXyyy)
        result as ShowerQrLink.Result.YktXyyy
        assertEquals("10101", result.posno)
        assertEquals("ABCDEFGH10101", result.raw)
    }

    @Test
    fun normalizesLeadingZeroPosnoLikePage() {
        // 页面 getPosNo 为 Number(substr(8,5))，前导零会被去掉
        val result = ShowerQrLink.parse("ABCDEFGH00101")
        assertEquals("101", (result as ShowerQrLink.Result.YktXyyy).posno)
    }

    @Test
    fun treatsEan13AsCandidateButNotLifeService() {
        // 13 位商品条码也可能命中候选，最终由服务端 CheckKsPos 裁决
        val result = ShowerQrLink.parse("6901234567890")
        assertTrue(result is ShowerQrLink.Result.YktXyyy)
        assertEquals("67890", (result as ShowerQrLink.Result.YktXyyy).posno)
    }

    @Test
    fun rejectsShortCode() {
        assertNull(ShowerQrLink.parse("10101"))
    }

    @Test
    fun rejectsAllZeroPosno() {
        assertNull(ShowerQrLink.parse("ABCDEFGH00000"))
    }

    @Test
    fun rejectsPlainUrlEvenIfDigitsAlign() {
        // URL 一律不按裸码取子串（避免误伤普通网址）
        assertNull(ShowerQrLink.parse("https://a1234567890.com/x"))
    }

    @Test
    fun rejectsCasAndUjingUrls() {
        assertNull(ShowerQrLink.parse("http://cas.wbu.edu.cn/authserver/login?uuid=abc"))
        assertNull(ShowerQrLink.parse("http://q.ujing.com.cn/ed/index.html?cd=123456789"))
    }

    @Test
    fun rejectsBlank() {
        assertNull(ShowerQrLink.parse("   "))
    }

    @Test
    fun syntheticRawRoundTripsPosno() {
        val raw = ShowerQrLink.syntheticYktRaw("10101")
        assertEquals("CFSHOWER10101", raw)
        val parsed = ShowerQrLink.parse(raw)
        assertTrue(parsed is ShowerQrLink.Result.YktXyyy)
        assertEquals("10101", (parsed as ShowerQrLink.Result.YktXyyy).posno)
    }

    @Test
    fun syntheticRawPadsShortPosno() {
        val raw = ShowerQrLink.syntheticYktRaw("101")
        assertEquals("CFSHOWER00101", raw)
        assertEquals("101", (ShowerQrLink.parse(raw) as ShowerQrLink.Result.YktXyyy).posno)
    }
}
