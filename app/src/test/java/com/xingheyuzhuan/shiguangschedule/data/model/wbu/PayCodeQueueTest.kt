package com.xingheyuzhuan.shiguangschedule.data.model.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 付款码轮换队列单测：翻码节奏与「过期跳码」必须与平台 H5 的 `campusCode` 页面一致，
 * 否则会出现「屏幕上摆着一个已经失效的码」这种事故。
 */
class PayCodeQueueTest {

    /** 假时钟：所有时间推进都由测试显式驱动。 */
    private class FakeClock(var now: Long = 0L) {
        fun advanceSeconds(seconds: Int) {
            now += seconds * 1000L
        }
    }

    private fun batch(count: Int) = (1..count).map { "CODE_$it" }

    @Test
    fun `翻码按顺序逐个消费`() {
        val clock = FakeClock()
        val queue = PayCodeQueue(
            codes = batch(3),
            expiresSeconds = 30,
            fetchedAtMs = clock.now,
            nowMs = { clock.now }
        )

        assertEquals("CODE_1", queue.current)
        assertEquals("CODE_2", queue.advance())
        assertEquals("CODE_3", queue.advance())
        assertNull(queue.advance())
    }

    @Test
    fun `翻码间隔取服务端有效期与默认节奏中更短者`() {
        // 有效期比默认 60 秒短：必须按有效期翻，不能把失效码继续摆着
        val shortLived = PayCodeQueue(batch(3), 20, 0L, nowMs = { 0L })
        assertEquals(20, shortLived.effectiveSlotSeconds)

        // 有效期比默认长：仍按默认 60 秒翻（H5 的 codeRefreshTime）
        val longLived = PayCodeQueue(batch(3), 120, 0L, nowMs = { 0L })
        assertEquals(PayCodeQueue.DEFAULT_SLOT_SECONDS, longLived.effectiveSlotSeconds)

        // 服务端没给有效期（0）时不能退化成「每 0 秒翻一次」
        val degenerate = PayCodeQueue(batch(3), 0, 0L, nowMs = { 0L })
        assertEquals(1, degenerate.effectiveSlotSeconds)
    }

    @Test
    fun `倒计时按当前码的展示窗口递减`() {
        val clock = FakeClock()
        val queue = PayCodeQueue(batch(10), 30, clock.now, nowMs = { clock.now })

        assertEquals(30, queue.secondsLeft())
        clock.advanceSeconds(5)
        assertEquals(25, queue.secondsLeft())
        clock.advanceSeconds(25)
        assertEquals(0, queue.secondsLeft())
    }

    @Test
    fun `空闲太久后翻码会跳过已过期的码`() {
        val clock = FakeClock()
        val queue = PayCodeQueue(batch(10), 20, clock.now, nowMs = { clock.now })

        // 第 i 个码（自取码时刻算起）的失效时刻 = 20 × (i + 1 + pad)，10 个码时 pad = 1：
        // CODE_1 → 20s、CODE_2 → 40s、CODE_3 → 60s …
        clock.advanceSeconds(65)
        assertEquals("CODE_4", queue.advance())
    }

    @Test
    fun `整批用尽后重置窗口`() {
        val clock = FakeClock()
        val queue = PayCodeQueue(batch(2), 30, clock.now, nowMs = { clock.now })

        assertEquals("CODE_2", queue.advance())
        assertNull(queue.advance())
        assertEquals(true, queue.isExhausted)
    }
}
