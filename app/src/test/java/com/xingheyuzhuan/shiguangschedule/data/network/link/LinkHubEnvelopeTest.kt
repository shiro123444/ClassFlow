package com.xingheyuzhuan.shiguangschedule.data.network.link

import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubEnvelope
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubProtocol
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubType
import com.xingheyuzhuan.shiguangschedule.data.model.link.intField
import com.xingheyuzhuan.shiguangschedule.data.model.link.stringField
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkHubEnvelopeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesMinimalEnvelope() {
        val envelope = json.decodeFromString<LinkHubEnvelope>(
            """{"type":"open","payload":{"url":"https://example.com/a"}}"""
        )
        assertEquals(LinkHubProtocol.VERSION, envelope.v)
        assertEquals(LinkHubType.OPEN, envelope.type)
        assertEquals("https://example.com/a", envelope.payload.stringField("url"))
        assertNull(envelope.title)
        assertNull(envelope.expiresAt)
    }

    @Test
    fun parsesFullEnvelope() {
        val envelope = json.decodeFromString<LinkHubEnvelope>(
            """
            {
              "v": 1,
              "type": "proxy",
              "title": "宿舍代理",
              "description": "备用线路",
              "minAppVersion": 18,
              "expiresAt": 4102444800,
              "payload": {"kind":"socks5","host":"1.2.3.4","port":1080}
            }
            """.trimIndent()
        )
        assertEquals("宿舍代理", envelope.title)
        assertEquals("备用线路", envelope.description)
        assertEquals(18, envelope.minAppVersion)
        assertEquals(4102444800L, envelope.expiresAt)
        assertEquals("socks5", envelope.payload.stringField("kind"))
        assertEquals(1080, envelope.payload.intField("port"))
    }

    @Test
    fun ignoresUnknownFields() {
        val envelope = json.decodeFromString<LinkHubEnvelope>(
            """{"v":1,"type":"open","author":"someone","payload":{"url":"https://example.com/a"}}"""
        )
        assertEquals(LinkHubType.OPEN, envelope.type)
    }

    @Test
    fun payloadFieldHelpersAreTypeSafe() {
        val envelope = json.decodeFromString<LinkHubEnvelope>(
            """{"type":"proxy","payload":{"port":"1080","host":123,"flag":true}}"""
        )
        // 数字字符串可解析为 Int
        assertEquals(1080, envelope.payload.intField("port"))
        // 非字符串类型不当作字符串返回
        assertNull(envelope.payload.stringField("host"))
        assertNull(envelope.payload.stringField("flag"))
        assertNull(envelope.payload.stringField("missing"))
        assertNull(envelope.payload.intField("missing"))
    }

    @Test
    fun reservedTypesAreDeclared() {
        assertTrue(LinkHubType.PROXY in LinkHubType.RESERVED)
        assertTrue(LinkHubType.LAYOUT_PLUGIN in LinkHubType.RESERVED)
        assertTrue(LinkHubType.OPEN !in LinkHubType.RESERVED)
    }
}
