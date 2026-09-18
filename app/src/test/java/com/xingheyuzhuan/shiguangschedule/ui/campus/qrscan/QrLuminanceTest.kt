package com.xingheyuzhuan.shiguangschedule.ui.campus.qrscan

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ZXing 备用引擎的灰度处理：Y 平面拷贝与旋转。
 * 旋转方向错了会导致二维码永远解不出来，因此逐个角度锁定。
 */
class QrLuminanceTest {

    // ── copyPlane ──

    @Test
    fun copyPlane_tightlyPacked_copiesAll() {
        val src = byteArrayOf(1, 2, 3, 4, 5, 6)
        val out = QrLuminance.copyPlane(ByteBuffer.wrap(src), width = 3, height = 2, rowStride = 3, pixelStride = 1)
        assertArrayEquals(src, out)
    }

    @Test
    fun copyPlane_skipsRowPadding() {
        // 每行 4 字节，真实像素 3 个：第 4 字节是 padding
        val src = byteArrayOf(1, 2, 3, 99, 4, 5, 6, 99)
        val out = QrLuminance.copyPlane(ByteBuffer.wrap(src), width = 3, height = 2, rowStride = 4, pixelStride = 1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), out)
    }

    @Test
    fun copyPlane_picksEveryPixelStrideByte() {
        // YUV 交错的 UV 平面式布局：每 2 字节取第 1 个
        val src = byteArrayOf(1, 9, 2, 9, 3, 9, 4, 9)
        val out = QrLuminance.copyPlane(ByteBuffer.wrap(src), width = 2, height = 2, rowStride = 4, pixelStride = 2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), out)
    }

    // ── rotate ──

    @Test
    fun rotate_zero_isIdentity() {
        val src = byteArrayOf(1, 2, 3, 4, 5, 6)
        val out = QrLuminance.rotate(src, width = 3, height = 2, degrees = 0)
        assertArrayEquals(src, out.data)
        assertEquals(3, out.width)
        assertEquals(2, out.height)
    }

    @Test
    fun rotate_90_clockwise_swapsDimensions() {
        // 2x2: (1 2 / 3 4) 顺时针 → (3 1 / 4 2)
        val src = byteArrayOf(1, 2, 3, 4)
        val out = QrLuminance.rotate(src, width = 2, height = 2, degrees = 90)
        assertArrayEquals(byteArrayOf(3, 1, 4, 2), out.data)
        assertEquals(2, out.width)
        assertEquals(2, out.height)
    }

    @Test
    fun rotate_90_clockwise_nonSquare() {
        // 3x2: (1 2 3 / 4 5 6) 顺时针 → 2x3: (4 1 / 5 2 / 6 3)
        val src = byteArrayOf(1, 2, 3, 4, 5, 6)
        val out = QrLuminance.rotate(src, width = 3, height = 2, degrees = 90)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), out.data)
        assertEquals(2, out.width)
        assertEquals(3, out.height)
    }

    @Test
    fun rotate_270_isCounterClockwise() {
        // 3x2: (1 2 3 / 4 5 6) 逆时针 → 2x3: (3 6 / 2 5 / 1 4)
        val src = byteArrayOf(1, 2, 3, 4, 5, 6)
        val out = QrLuminance.rotate(src, width = 3, height = 2, degrees = 270)
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), out.data)
        assertEquals(2, out.width)
        assertEquals(3, out.height)
    }

    @Test
    fun rotate_180_reverses() {
        val src = byteArrayOf(1, 2, 3, 4)
        val out = QrLuminance.rotate(src, width = 2, height = 2, degrees = 180)
        assertArrayEquals(byteArrayOf(4, 3, 2, 1), out.data)
        assertEquals(2, out.width)
        assertEquals(2, out.height)
    }

    @Test
    fun rotate_degreesWrapAround() {
        val src = byteArrayOf(1, 2, 3, 4)
        val asRotation = QrLuminance.rotate(src, width = 2, height = 2, degrees = 450)
        val expected = QrLuminance.rotate(src, width = 2, height = 2, degrees = 90)
        assertArrayEquals(expected.data, asRotation.data)
        assertEquals(expected.width, asRotation.width)
        assertEquals(expected.height, asRotation.height)
    }

    @Test
    fun rotate_unsupportedAngle_isIdentity() {
        val src = byteArrayOf(1, 2, 3, 4, 5, 6)
        val out = QrLuminance.rotate(src, width = 3, height = 2, degrees = 45)
        assertArrayEquals(src, out.data)
        assertEquals(3, out.width)
        assertEquals(2, out.height)
    }
}
