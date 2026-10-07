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
    fun parsesSchoolShowerEntryUrl() {
        // 马影河 3 栋贴纸实测：学校自己的洗浴入口主机（yktxyyy1.wbu.edu.cn:50040），
        // 与厂商域一样只是 ?id= 的载体，生命周期由 lifeService 页面自己取 id 当设备号
        val raw = "http://yktxyyy1.wbu.edu.cn:50040?id=17010737"
        val result = ShowerQrLink.parse(raw)
        assertTrue(result is ShowerQrLink.Result.LifeService)
        result as ShowerQrLink.Result.LifeService
        assertEquals("17010737", result.imei)
        assertNull(result.port)
        // 原文必须原样交给 lifeService 页面：页面 handleImei 自己解析 ?id=
        assertEquals(raw, result.raw)
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
    fun rejectsOtherCampusUrlsWithId() {
        // 只放行洗浴码载体域名：一卡通平台 / CAS / 学校主页上带 ?id= 的链接都不是设备码
        // （分流顺序里洗浴排在通用链接节点之前，放宽就会把这些链接抢走）
        assertNull(ShowerQrLink.parse("http://yktfwpt.wbu.edu.cn/plat/index?id=17010737"))
        assertNull(ShowerQrLink.parse("http://ids.wbu.edu.cn/authserver/login?id=17010737"))
        assertNull(ShowerQrLink.parse("https://www.wbu.edu.cn/news?id=17010737"))
        // 洗浴入口主机前缀的其它主机（精确到一个主机，不做前缀族放行）
        assertNull(ShowerQrLink.parse("http://yktxyyy11.wbu.edu.cn:50040?id=17010737"))
        // 同主机但没有 ?id=：不是设备码载体
        assertNull(ShowerQrLink.parse("http://yktxyyy1.wbu.edu.cn:50040/devices"))
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
