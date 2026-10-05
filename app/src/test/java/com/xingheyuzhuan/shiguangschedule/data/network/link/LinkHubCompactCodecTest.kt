package com.xingheyuzhuan.shiguangschedule.data.network.link

import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubEnvelope
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubType
import com.xingheyuzhuan.shiguangschedule.data.model.link.intField
import com.xingheyuzhuan.shiguangschedule.data.model.link.stringField
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkHubCompactCodecTest {

    private val encoder = Base64.getUrlEncoder().withoutPadding()

    private fun openEnvelope(url: String) = LinkHubEnvelope(
        type = LinkHubType.OPEN,
        payload = buildJsonObject { put("url", url) }
    )

    private fun decodeOk(payload: String): LinkHubEnvelope {
        val result = LinkHubCompactCodec.decode(payload)
        assertTrue("期望解码成功，实际 $result", result is LinkHubInlineResult.Ok)
        return (result as LinkHubInlineResult.Ok).envelope
    }

    @Test
    fun roundTripsHttpsOpenNode() {
        val encoded = LinkHubCompactCodec.encode(openEnvelope("https://example.com/a"))
        val envelope = decodeOk(encoded!!)
        assertEquals(LinkHubType.OPEN, envelope.type)
        assertEquals("https://example.com/a", envelope.payload.stringField("url"))
    }

    @Test
    fun roundTripsHttpOpenNode() {
        val encoded = LinkHubCompactCodec.encode(openEnvelope("http://example.com/a"))
        val envelope = decodeOk(encoded!!)
        assertEquals("http://example.com/a", envelope.payload.stringField("url"))
    }

    @Test
    fun omitsHttpsSchemeForCompactness() {
        val encoded = LinkHubCompactCodec.encode(openEnvelope("https://example.com/a"))!!
        // 0x11 首字节 + flags + "example.com/a" = 15 字节
        val raw = Base64.getUrlDecoder().decode(encoded)
        assertEquals(15, raw.size)
        assertEquals(0x11, raw[0].toInt())
        assertEquals(1, raw[1].toInt())
    }

    @Test
    fun roundTripsTextNode() {
        val text = "宿舍代理：1.2.3.4:1080"
        val encoded = LinkHubCompactCodec.encode(
            LinkHubEnvelope(type = LinkHubType.TEXT, payload = buildJsonObject { put("text", text) })
        )
        val envelope = decodeOk(encoded!!)
        assertEquals(LinkHubType.TEXT, envelope.type)
        assertEquals(text, envelope.payload.stringField("text"))
    }

    @Test
    fun roundTripsProxyNode() {
        val encoded = LinkHubCompactCodec.encode(
            LinkHubEnvelope(
                type = LinkHubType.PROXY,
                payload = buildJsonObject {
                    put("kind", "socks5")
                    put("host", "1.2.3.4")
                    put("port", 1080)
                    put("username", "abc")
                    put("password", "xyz")
                }
            )
        )
        val envelope = decodeOk(encoded!!)
        assertEquals(LinkHubType.PROXY, envelope.type)
        assertEquals("socks5", envelope.payload.stringField("kind"))
        assertEquals("1.2.3.4", envelope.payload.stringField("host"))
        assertEquals(1080, envelope.payload.intField("port"))
        assertEquals("abc", envelope.payload.stringField("username"))
        assertEquals("xyz", envelope.payload.stringField("password"))
    }

    @Test
    fun omitsEmptyProxyCredentials() {
        val encoded = LinkHubCompactCodec.encode(
            LinkHubEnvelope(
                type = LinkHubType.PROXY,
                payload = buildJsonObject {
                    put("kind", "http")
                    put("host", "proxy.example.com")
                    put("port", 8080)
                }
            )
        )
        val envelope = decodeOk(encoded!!)
        assertNull(envelope.payload.stringField("username"))
        assertNull(envelope.payload.stringField("password"))
        assertEquals("http", envelope.payload.stringField("kind"))
    }

    @Test
    fun rejectsProxyWithIllegalPort() {
        assertNull(
            LinkHubCompactCodec.encode(
                LinkHubEnvelope(
                    type = LinkHubType.PROXY,
                    payload = buildJsonObject {
                        put("kind", "http")
                        put("host", "1.2.3.4")
                        put("port", 70000)
                    }
                )
            )
        )
    }

    @Test
    fun roundTripsLayoutPluginNode() {
        val encoded = LinkHubCompactCodec.encode(
            LinkHubEnvelope(
                type = LinkHubType.LAYOUT_PLUGIN,
                payload = buildJsonObject { put("manifestUrl", "https://example.com/p.json") }
            )
        )
        val envelope = decodeOk(encoded!!)
        assertEquals(LinkHubType.LAYOUT_PLUGIN, envelope.type)
        assertEquals("https://example.com/p.json", envelope.payload.stringField("manifestUrl"))
    }

    @Test
    fun rejectsPluginWithHttpManifest() {
        assertNull(
            LinkHubCompactCodec.encode(
                LinkHubEnvelope(
                    type = LinkHubType.LAYOUT_PLUGIN,
                    payload = buildJsonObject { put("manifestUrl", "http://example.com/p.json") }
                )
            )
        )
    }

    @Test
    fun rejectsUnregisteredType() {
        assertNull(
            LinkHubCompactCodec.encode(
                LinkHubEnvelope(type = "something_else", payload = buildJsonObject { })
            )
        )
    }

    @Test
    fun rejectsOversizeText() {
        assertNull(
            LinkHubCompactCodec.encode(
                LinkHubEnvelope(
                    type = LinkHubType.TEXT,
                    payload = buildJsonObject { put("text", "a".repeat(2048)) }
                )
            )
        )
    }

    @Test
    fun rejectsGarbagePayload() {
        assertTrue(LinkHubCompactCodec.decode("") is LinkHubInlineResult.Invalid)
        assertTrue(LinkHubCompactCodec.decode("%%%") is LinkHubInlineResult.Invalid)
        assertTrue(LinkHubCompactCodec.decode("A") is LinkHubInlineResult.Invalid)
        // 超出 fragment 长度上限
        assertTrue(LinkHubCompactCodec.decode("A".repeat(4096)) is LinkHubInlineResult.Invalid)
        // 逃生口标记但后面没有 JSON
        assertTrue(LinkHubCompactCodec.decode(encoder.encodeToString(byteArrayOf(0x1F))) is LinkHubInlineResult.Invalid)
    }

    @Test
    fun rejectsProxyWithZeroPortAndTruncatedFields() {
        val zeroPort = byteArrayOf(0x13, 0x00, 0x00, 0x00, 0x07) + "1.2.3.4".toByteArray()
        assertTrue(LinkHubCompactCodec.decode(encoder.encodeToString(zeroPort)) is LinkHubInlineResult.Invalid)

        // host 长度前缀声明 9 字节，实际只剩 3 字节
        val truncated = byteArrayOf(0x13, 0x00, 0x04, 0x38, 0x09) + "abc".toByteArray()
        assertTrue(LinkHubCompactCodec.decode(encoder.encodeToString(truncated)) is LinkHubInlineResult.Invalid)
    }

    @Test
    fun reportsUnsupportedVersion() {
        val raw = byteArrayOf(0x21, 0x01) + "example.com/a".toByteArray()
        assertTrue(LinkHubCompactCodec.decode(encoder.encodeToString(raw)) is LinkHubInlineResult.UnsupportedVersion)
    }

    @Test
    fun reportsUnsupportedType() {
        val raw = byteArrayOf(0x17, 0x00)
        assertTrue(LinkHubCompactCodec.decode(encoder.encodeToString(raw)) is LinkHubInlineResult.UnsupportedType)
    }

    @Test
    fun roundTripsYktXyyyShowerNode() {
        val encoded = LinkHubCompactCodec.encode(
            LinkHubEnvelope(
                type = LinkHubType.CAMPUS_SHOWER,
                payload = buildJsonObject {
                    put("system", "yktxyyy")
                    put("posno", "10101")
                }
            )
        )
        assertEquals("FQAAJ3U", encoded)
        val envelope = decodeOk(encoded!!)
        assertEquals(LinkHubType.CAMPUS_SHOWER, envelope.type)
        assertEquals("yktxyyy", envelope.payload.stringField("system"))
        assertEquals("10101", envelope.payload.stringField("posno"))
    }

    @Test
    fun roundTripsLifeServiceShowerNode() {
        val encoded = LinkHubCompactCodec.encode(
            LinkHubEnvelope(
                type = LinkHubType.CAMPUS_SHOWER,
                payload = buildJsonObject {
                    put("system", "life_service")
                    put("imei", "abc")
                    put("port", "2")
                }
            )
        )
        assertEquals("FQEBAAAAAgNhYmM", encoded)
        val envelope = decodeOk(encoded!!)
        assertEquals(LinkHubType.CAMPUS_SHOWER, envelope.type)
        assertEquals("life_service", envelope.payload.stringField("system"))
        assertEquals("abc", envelope.payload.stringField("imei"))
        assertEquals("2", envelope.payload.stringField("port"))
    }

    @Test
    fun rejectsShowerWithIllegalPosno() {
        assertNull(
            LinkHubCompactCodec.encode(
                LinkHubEnvelope(
                    type = LinkHubType.CAMPUS_SHOWER,
                    payload = buildJsonObject { put("system", "yktxyyy"); put("posno", "0") }
                )
            )
        )
    }

    @Test
    fun rejectsShowerWithTrailingGarbage() {
        val valid = Base64.getUrlDecoder().decode("FQAAJ3U")
        val garbage = valid + byteArrayOf(0x01)
        assertTrue(LinkHubCompactCodec.decode(encoder.encodeToString(garbage)) is LinkHubInlineResult.Invalid)
    }

    @Test
    fun decodesJsonEscapeHatch() {
        val json = """
            {"v":1,"type":"open","title":"示例","payload":{"url":"https://example.com/a"}}
        """.trimIndent()
        val raw = byteArrayOf(0x1F) + json.toByteArray(Charsets.UTF_8)
        val envelope = decodeOk(encoder.encodeToString(raw))
        assertEquals("示例", envelope.title)
        assertEquals("https://example.com/a", envelope.payload.stringField("url"))
    }

    @Test
    fun rejectsJsonEscapeHatchWithWrongVersion() {
        val json = """{"v":2,"type":"open","payload":{"url":"https://example.com/a"}}"""
        val raw = byteArrayOf(0x1F) + json.toByteArray(Charsets.UTF_8)
        assertTrue(LinkHubCompactCodec.decode(encoder.encodeToString(raw)) is LinkHubInlineResult.UnsupportedVersion)
    }

    @Test
    fun rejectsProxyWithTrailingGarbage() {
        val valid = Base64.getUrlDecoder().decode(
            LinkHubCompactCodec.encode(
                LinkHubEnvelope(
                    type = LinkHubType.PROXY,
                    payload = buildJsonObject {
                        put("kind", "http")
                        put("host", "1.2.3.4")
                        put("port", 1080)
                    }
                )
            )!!
        )
        val garbage = valid + byteArrayOf(0x01, 0x02)
        assertTrue(LinkHubCompactCodec.decode(encoder.encodeToString(garbage)) is LinkHubInlineResult.Invalid)
    }
}
