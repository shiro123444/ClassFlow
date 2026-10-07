package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 淋浴结算推送（裸 WebSocket，主题在 URL 路径上）与平台令牌里学号的解析。
 *
 * 帧的两种形态都来自页面封装 `js/chunk-b88c7b1e.f1981e59.js`：`onmessage` 里
 * `code/data/msg` 齐全且 `code==200` 时把 `data` 交给业务，否则原样回调整帧。
 */
class WbuShowerWaterSocketTest {

    private fun socket() = WbuShowerWaterSocket(onSettlement = {})

    @Test
    fun parse_readsContentFromBareFrame() {
        val settlement = socket().parse("""{"content":"{\"msg\":\"成功\",\"tranamt\":1.5,\"minutes\":12}"}""")
        assertEquals("成功", settlement?.message)
        assertEquals(1.5, settlement?.amount!!, 0.0001)
        assertEquals(12, settlement?.minutes)
    }

    @Test
    fun parse_readsContentFromCodeWrappedFrame() {
        val settlement = socket().parse(
            """{"code":200,"data":{"content":"{\"msg\":\"成功\",\"tranamt\":\"2.30\",\"minutes\":\"8\"}"}}"""
        )
        assertEquals("成功", settlement?.message)
        assertEquals(2.3, settlement?.amount!!, 0.0001)
        assertEquals(8, settlement?.minutes)
    }

    @Test
    fun parse_keepsPlainTextContentAsMessage() {
        val settlement = socket().parse("""{"content":"用水已结束"}""")
        assertEquals("用水已结束", settlement?.message)
        assertNull(settlement?.amount)
        assertNull(settlement?.minutes)
    }

    @Test
    fun parse_ignoresFramesWithoutContent() {
        assertNull(socket().parse("""{"code":200,"data":{"foo":1}}"""))
        assertNull(socket().parse("ping"))
        assertNull(socket().parse(""))
    }

    @Test
    fun parseSno_readsSnoFromTokenPayload() {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sno":"250594036","name":"测试","account":"250594036"}""".toByteArray())
        val token = "header.$payload.signature"
        assertEquals("250594036", WbuShowerWaterClient.parseSno(token))
    }

    @Test
    fun parseSno_fallsBackToAccountAndRejectsGarbage() {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"account":"250594036"}""".toByteArray())
        assertEquals("250594036", WbuShowerWaterClient.parseSno("h.$payload.s"))
        assertNull(WbuShowerWaterClient.parseSno("not-a-jwt"))
        assertNull(WbuShowerWaterClient.parseSno(null))
        assertNull(WbuShowerWaterClient.parseSno(""))
    }

    @Test
    fun wsBasePointsAtThePlatformWebsocketPath() {
        assertEquals("ws://yktfwpt.wbu.edu.cn/websocket/", WbuShowerWaterSocket.WS_BASE)
    }
}
