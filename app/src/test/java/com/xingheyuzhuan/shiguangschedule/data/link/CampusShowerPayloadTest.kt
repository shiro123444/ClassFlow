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
        assertEquals(
            "http://4gsk.shuibiao51.com?id=abc",
            CampusShowerEntryResolver.buildLifeServiceRaw("abc")
        )
        assertEquals(
            "http://4gsk.shuibiao51.com?id=abc\$#\$2",
            CampusShowerEntryResolver.buildLifeServiceRaw("abc", "2")
        )
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
