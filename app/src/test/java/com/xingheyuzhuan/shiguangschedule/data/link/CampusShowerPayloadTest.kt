package com.xingheyuzhuan.shiguangschedule.data.link

import com.xingheyuzhuan.shiguangschedule.data.network.wbu.CampusShowerEntryResolver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `campus_shower` payload 契约与 2-3 栋原文拼装单测。
 */
class CampusShowerPayloadTest {

    private fun payload(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    @Test
    fun parsesYktXyyyPosno() {
        val parsed = CampusShowerPayload.parse(
            payload("system" to "yktxyyy", "posno" to "10101")
        )
        assertTrue(parsed is CampusShowerPayload.Parsed.YktXyyy)
        assertEquals("10101", (parsed as CampusShowerPayload.Parsed.YktXyyy).posno)
    }

    @Test
    fun normalizesLeadingZeroPosno() {
        val parsed = CampusShowerPayload.parse(
            payload("system" to "YKTXYYY", "posno" to "00101")
        )
        assertEquals("101", (parsed as CampusShowerPayload.Parsed.YktXyyy).posno)
    }

    @Test
    fun rejectsInvalidYktXyyyPosno() {
        assertNull(CampusShowerPayload.parse(payload("system" to "yktxyyy", "posno" to "")))
        assertNull(CampusShowerPayload.parse(payload("system" to "yktxyyy", "posno" to "12a45")))
        assertNull(CampusShowerPayload.parse(payload("system" to "yktxyyy", "posno" to "123456")))
    }

    @Test
    fun parsesLifeServiceImeiAndPort() {
        val parsed = CampusShowerPayload.parse(
            payload("system" to "life_service", "imei" to "1234567890", "port" to "2")
        )
        assertTrue(parsed is CampusShowerPayload.Parsed.LifeService)
        parsed as CampusShowerPayload.Parsed.LifeService
        assertEquals("1234567890", parsed.imei)
        assertEquals("2", parsed.port)
    }

    @Test
    fun parsesLifeServiceWithoutPort() {
        val parsed = CampusShowerPayload.parse(
            payload("system" to "life_service", "imei" to "abc-123")
        )
        assertEquals(null, (parsed as CampusShowerPayload.Parsed.LifeService).port)
    }

    @Test
    fun rejectsInvalidLifeServicePayload() {
        assertNull(CampusShowerPayload.parse(payload("system" to "life_service", "imei" to "")))
        assertNull(CampusShowerPayload.parse(payload("system" to "life_service", "imei" to "a b")))
        assertNull(CampusShowerPayload.parse(payload("system" to "life_service", "imei" to "a", "port" to "12x")))
    }

    @Test
    fun rejectsUnknownSystem() {
        assertNull(CampusShowerPayload.parse(payload("system" to "other", "posno" to "10101")))
        assertNull(CampusShowerPayload.parse(payload("posno" to "10101")))
    }

    @Test
    fun buildsLifeServiceRaw() {
        // 不带端口：页面走 `imei = getRequest(原文).id`，所以原文要补上厂商链接
        assertEquals(
            "http://4gsk.shuibiao51.com?id=abc",
            CampusShowerEntryResolver.buildLifeServiceRaw("abc")
        )
        // 带端口：页面走 `imei = 第一段原文本身`（不再解析 URL），第一段必须是裸设备号
        assertEquals(
            "abc\$#\$2",
            CampusShowerEntryResolver.buildLifeServiceRaw("abc", "2")
        )
    }

    @Test
    fun buildsLifeServiceRawFromDeviceLink() {
        // 设备号本身是链接（协议允许）：不带端口原文透传、带端口先取出 ?id= 再拼端口 ——
        // 否则页面会把整条链接当 imei 交给 getDevicesType，服务端认不出设备
        assertEquals(
            "http://yktxyyy1.wbu.edu.cn:50040?id=17010737",
            CampusShowerEntryResolver.buildLifeServiceRaw("http://yktxyyy1.wbu.edu.cn:50040?id=17010737")
        )
        assertEquals(
            "17010737\$#\$2",
            CampusShowerEntryResolver.buildLifeServiceRaw("http://yktxyyy1.wbu.edu.cn:50040?id=17010737", "2")
        )
        // 链接里没有可用的 ?id=：不是设备码载体
        assertNull(CampusShowerEntryResolver.buildLifeServiceRaw("http://yktxyyy1.wbu.edu.cn:50040/devices"))
        assertNull(CampusShowerEntryResolver.buildLifeServiceRaw("http://4gsk.shuibiao51.com?id="))
    }

    @Test
    fun rejectsUnsafeLifeServiceRawInput() {
        assertNull(CampusShowerEntryResolver.buildLifeServiceRaw(""))
        assertNull(CampusShowerEntryResolver.buildLifeServiceRaw("a b"))
        assertNull(CampusShowerEntryResolver.buildLifeServiceRaw("a#b"))
        assertNull(CampusShowerEntryResolver.buildLifeServiceRaw("a\$b"))
        assertNull(CampusShowerEntryResolver.buildLifeServiceRaw("abc", "1x"))
        assertNull(CampusShowerEntryResolver.buildLifeServiceRaw("abc", "123456789"))
    }
}
