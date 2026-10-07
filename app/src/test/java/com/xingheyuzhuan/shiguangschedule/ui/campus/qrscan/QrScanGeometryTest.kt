package com.xingheyuzhuan.shiguangschedule.ui.campus.qrscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取景框 → 分析帧 ROI 的映射。
 *
 * 这块算错了不会崩，只会「扫不到」——最难查的那种问题，所以逐个旋转角度把方向锁死：
 * 视图上方对应缓冲的哪一侧，是旋转方向对不对的唯一判据。
 */
class QrScanGeometryTest {

    // ── scanFrameRect ──

    @Test
    fun scanFrameRect_centersHorizontallyAndInsideReservedArea() {
        val rect = scanFrameRect(
            viewWidth = 1000f,
            viewHeight = 2000f,
            reservedTop = 100f,
            reservedBottom = 200f,
            maxSide = 1000f
        )
        // 可用高 1700，边长 = 1000 * 0.68
        assertEquals(680f, rect.side, 0.01f)
        assertEquals(160f, rect.left, 0.01f)
        // 顶部预留 100 + 可用区居中 (1700-680)/2
        assertEquals(610f, rect.top, 0.01f)
    }

    @Test
    fun scanFrameRect_clampsToMaxSide() {
        val rect = scanFrameRect(
            viewWidth = 2000f,
            viewHeight = 3000f,
            reservedTop = 100f,
            reservedBottom = 100f,
            maxSide = 720f
        )
        assertEquals(720f, rect.side, 0.01f)
        assertEquals(640f, rect.left, 0.01f)
        assertEquals(1140f, rect.top, 0.01f)
    }

    // ── mapViewRectToFrame ──

    @Test
    fun map_sameSizeNoRotation_isIdentity() {
        val mapped = mapViewRectToFrame(
            rect = ScanFrameRect(left = 250f, top = 250f, side = 500f),
            viewWidth = 1000,
            viewHeight = 1000,
            frameWidth = 1000,
            frameHeight = 1000,
            rotationDegrees = 0
        )
        assertEquals(FrameRect(250, 250, 750, 750), mapped)
    }

    @Test
    fun map_fillCenterCrop_staysCenteredHorizontally() {
        // 方形帧铺满竖屏：左右各被裁掉 500px（放大 2 倍）
        val mapped = mapViewRectToFrame(
            rect = ScanFrameRect(left = 250f, top = 250f, side = 500f),
            viewWidth = 1000,
            viewHeight = 2000,
            frameWidth = 1000,
            frameHeight = 1000,
            rotationDegrees = 0
        )
        assertEquals(FrameRect(375, 125, 625, 375), mapped)
    }

    @Test
    fun map_rotation90_centerOfScreenIsCenterOfFrame() {
        val mapped = mapViewRectToFrame(
            rect = ScanFrameRect(left = 440f, top = 1100f, side = 200f),
            viewWidth = 1080,
            viewHeight = 2400,
            frameWidth = 1920,
            frameHeight = 1080,
            rotationDegrees = 90
        )
        assertEquals(FrameRect(880, 460, 1040, 620), mapped)
        // 仍然是正方形（旋转不该改变形状）
        assertEquals(mapped!!.width, mapped.height)
    }

    @Test
    fun map_rotation90_topOfScreenIsLeftOfBuffer() {
        val mapped = mapViewRectToFrame(
            rect = ScanFrameRect(left = 490f, top = 0f, side = 100f),
            viewWidth = 1080,
            viewHeight = 2400,
            frameWidth = 1920,
            frameHeight = 1080,
            rotationDegrees = 90
        )
        assertEquals(FrameRect(0, 500, 80, 580), mapped)
    }

    @Test
    fun map_rotation270_topOfScreenIsRightOfBuffer() {
        val mapped = mapViewRectToFrame(
            rect = ScanFrameRect(left = 490f, top = 0f, side = 100f),
            viewWidth = 1080,
            viewHeight = 2400,
            frameWidth = 1920,
            frameHeight = 1080,
            rotationDegrees = 270
        )
        assertEquals(FrameRect(1840, 500, 1920, 580), mapped)
    }

    @Test
    fun map_rotation180_flipsBothAxes() {
        val mapped = mapViewRectToFrame(
            rect = ScanFrameRect(left = 100f, top = 200f, side = 300f),
            viewWidth = 1000,
            viewHeight = 1000,
            frameWidth = 1000,
            frameHeight = 1000,
            rotationDegrees = 180
        )
        assertEquals(FrameRect(600, 500, 900, 800), mapped)
    }

    @Test
    fun map_clipsToFrameBounds() {
        val mapped = mapViewRectToFrame(
            rect = ScanFrameRect(left = -100f, top = -100f, side = 300f),
            viewWidth = 1000,
            viewHeight = 1000,
            frameWidth = 1000,
            frameHeight = 1000,
            rotationDegrees = 0
        )
        assertEquals(FrameRect(0, 0, 200, 200), mapped)
    }

    @Test
    fun map_paddingGrowsRectOnEverySide() {
        val mapped = mapViewRectToFrame(
            rect = ScanFrameRect(left = 300f, top = 300f, side = 400f),
            viewWidth = 1000,
            viewHeight = 1000,
            frameWidth = 1000,
            frameHeight = 1000,
            rotationDegrees = 0,
            padding = 0.15f
        )
        // 400 * 0.15 = 60
        assertEquals(FrameRect(240, 240, 760, 760), mapped)
    }

    @Test
    fun map_allRotationsStayInsideFrame() {
        val rect = ScanFrameRect(left = 0f, top = 0f, side = 5000f)
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val mapped = mapViewRectToFrame(
                rect = rect,
                viewWidth = 1080,
                viewHeight = 2400,
                frameWidth = 1920,
                frameHeight = 1080,
                rotationDegrees = rotation,
                padding = 0.15f
            ) ?: continue
            assertTrue("rotation=$rotation 越界: $mapped", mapped.left >= 0 && mapped.top >= 0)
            assertTrue("rotation=$rotation 越界: $mapped", mapped.right <= 1920 && mapped.bottom <= 1080)
            assertTrue("rotation=$rotation 空矩形: $mapped", mapped.width > 0 && mapped.height > 0)
        }
    }

    @Test
    fun map_invalidSizes_returnsNull() {
        assertNull(
            mapViewRectToFrame(ScanFrameRect(0f, 0f, 100f), 0, 1000, 1000, 1000, 0)
        )
        assertNull(
            mapViewRectToFrame(ScanFrameRect(0f, 0f, 100f), 1000, 1000, 0, 1000, 0)
        )
        assertNull(
            mapViewRectToFrame(ScanFrameRect(0f, 0f, 0f), 1000, 1000, 1000, 1000, 0)
        )
    }

    // ── 宽高比守卫 ──

    @Test
    fun frameAspect_ignoresOrientation() {
        assertEquals(16f / 9f, frameAspect(1920, 1080), 0.0001f)
        assertEquals(16f / 9f, frameAspect(1080, 1920), 0.0001f)
        assertEquals(0f, frameAspect(0, 1080), 0.0001f)
    }

    @Test
    fun aspectClose_toleratesSmallMismatchOnly() {
        assertTrue(aspectClose(16f / 9f, 16f / 9f))
        assertTrue(aspectClose(16f / 9f, 1.7778f))
        // 4:3 与 16:9 差太多：此时不能按「所见即所得」映射
        assertFalse(aspectClose(16f / 9f, 4f / 3f))
        assertFalse(aspectClose(16f / 9f, 0f))
    }
}
