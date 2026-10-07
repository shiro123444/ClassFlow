package com.xingheyuzhuan.shiguangschedule.ui.campus.qrscan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.ByteBuffer
import kotlin.math.max
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * 「取景框 → 裁剪 → 解码」这条链路的离线验证。
 *
 * 单测里没有相机，但可以自己合成帧：几何方向用 [QrLuminance.rotate] 来回核对
 * （rotate 自己有逐角度锁定的单测，所以这不是自证），裁剪则交给真的 ZXing 解码器
 * 验证「框内的解得出来、框外的解不出来」。
 */
class QrScanRoiTest {

    private val payload = "https://wbu.pennote.cn/url/ABCD1234"

    private val black = 0x00.toByte()
    private val white = 0xFF.toByte()

    // ── copyRect：只拷指定区域 ──

    @Test
    fun copyRect_copiesOnlyRequestedRegion() {
        // 4x3 帧，行跨距 5（每行 1 字节 padding）
        val src = byteArrayOf(
            1, 2, 3, 4, 99,
            5, 6, 7, 8, 99,
            9, 10, 11, 12, 99
        )
        val luma = QrLuminance.copyRect(ByteBuffer.wrap(src), 4, 3, 5, 1, FrameRect(1, 1, 3, 3))
        assertEquals(2, luma.width)
        assertEquals(2, luma.height)
        assertArrayEquals(byteArrayOf(6, 7, 10, 11), luma.data)
    }

    @Test
    fun copyRect_pixelStridePicksEveryNthByte() {
        // 每 2 字节取第 1 个（交错的 UV 布局式）；行跨距 8 = 4 像素 * 2
        val src = byteArrayOf(
            1, 9, 2, 9, 3, 9, 4, 9,
            5, 9, 6, 9, 7, 9, 8, 9
        )
        val luma = QrLuminance.copyRect(ByteBuffer.wrap(src), 4, 2, 8, 2, FrameRect(1, 0, 3, 2))
        assertEquals(2, luma.width)
        assertEquals(2, luma.height)
        assertArrayEquals(byteArrayOf(2, 3, 6, 7), luma.data)
    }

    @Test
    fun copyRect_emptyRegionReturnsEmpty() {
        val luma = QrLuminance.copyRect(ByteBuffer.wrap(ByteArray(8)), 4, 2, 4, 1, FrameRect(0, 0, 0, 0))
        assertEquals(0, luma.width)
        assertEquals(0, luma.height)
    }

    // ── 映射方向：与 QrLuminance.rotate 互相印证 ──

    /**
     * 在原始帧的某点放一个标记，用 rotate 找出它在「转正后的画面」里的位置，
     * 再按 FILL_CENTER 换算成屏幕坐标；把这个屏幕点映射回帧、裁剪，必须命中同一个像素。
     *
     * 方向约定一旦写反（90 和 270 互换），这个用例在竖屏下就会偏到另一侧。
     */
    @Test
    fun map_roundTripsMarkedPixelAtEveryRotation() {
        val frameW = 1920
        val frameH = 1080
        val viewW = 1080
        val viewH = 2400
        val marker = 77.toByte()
        val rawX = 300
        val rawY = 700

        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val raw = ByteArray(frameW * frameH)
            raw[rawY * frameW + rawX] = marker

            val upright = QrLuminance.rotate(raw, frameW, frameH, rotation)
            val index = upright.data.indexOfFirst { it == marker }
            if (index < 0) fail("rotation=$rotation 标记丢了")
            val ux = index % upright.width
            val uy = index / upright.width

            val scale = max(
                viewW.toFloat() / upright.width,
                viewH.toFloat() / upright.height
            )
            val offsetX = (viewW - upright.width * scale) / 2f
            val offsetY = (viewH - upright.height * scale) / 2f
            val viewX = offsetX + ux * scale
            val viewY = offsetY + uy * scale

            val mapped = mapViewRectToFrame(
                rect = ScanFrameRect(viewX - 30f, viewY - 30f, 60f),
                viewWidth = viewW,
                viewHeight = viewH,
                frameWidth = frameW,
                frameHeight = frameH,
                rotationDegrees = rotation
            ) ?: error("rotation=$rotation 映射为 null")

            val luma = QrLuminance.copyRect(
                buffer = ByteBuffer.wrap(raw),
                width = frameW,
                height = frameH,
                rowStride = frameW,
                pixelStride = 1,
                rect = mapped
            )
            val hit = luma.data.indexOfFirst { it == marker }
            if (hit < 0) fail("rotation=$rotation 裁出来的区域没盖住标记：$mapped")
            // 逆变换里 frameHeight - ux 与 rotate 的 height - 1 - y 最多差 1px
            assertEquals("rotation=$rotation", rawX.toFloat(), (mapped.left + hit % luma.width).toFloat(), 1f)
            assertEquals("rotation=$rotation", rawY.toFloat(), (mapped.top + hit / luma.width).toFloat(), 1f)
        }
    }

    // ── 裁剪 + 真解码：框内的能解，框外的不解 ──

    @Test
    fun zxing_decodesQrInsideCrop() {
        val frame = frameWithQr(1920, 1080, qrRect = FrameRect(1300, 400, 1700, 800))
        val luma = crop(frame, 1920, 1080, 1920, FrameRect(1250, 350, 1750, 850))
        assertEquals(payload, decode(luma))
    }

    @Test
    fun zxing_decodesWithRowPadding() {
        // 相机给的 Y 平面行跨距通常大于宽度
        val frame = frameWithQr(1920, 1080, qrRect = FrameRect(1300, 400, 1700, 800), rowStride = 2048)
        val luma = crop(frame, 1920, 1080, 2048, FrameRect(1250, 350, 1750, 850))
        assertEquals(payload, decode(luma))
    }

    @Test
    fun zxing_doesNotDecodeQrOutsideCrop() {
        val frame = frameWithQr(1920, 1080, qrRect = FrameRect(1300, 400, 1700, 800))
        // 取景框在左上角：裁出来的这一块里没有二维码
        val luma = crop(frame, 1920, 1080, 1920, FrameRect(0, 0, 600, 600))
        try {
            val text = decode(luma)
            fail("框外不该解出内容，却解出了：$text")
        } catch (expected: NotFoundException) {
            // 预期
        }
    }

    @Test
    fun zxing_decodesQrAfterRotationMappedCrop() {
        // 端到端：屏幕中心放着的码，按取景框映射裁出来也能解
        val frameW = 1920
        val frameH = 1080
        val viewW = 1080
        val viewH = 2400
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            // 码放在帧中心附近：取景框（居中）一定盖得住它，四个旋转角都能断言
            val frame = frameWithQr(frameW, frameH, qrRect = FrameRect(860, 440, 1060, 640))
            val scanFrame = scanFrameRect(viewW.toFloat(), viewH.toFloat(), 210f, 420f, viewW * 0.68f)
            val mapped = mapViewRectToFrame(
                rect = scanFrame,
                viewWidth = viewW,
                viewHeight = viewH,
                frameWidth = frameW,
                frameHeight = frameH,
                rotationDegrees = rotation,
                padding = 0.15f
            ) ?: error("rotation=$rotation 映射为 null")
            assertEquals(
                "rotation=$rotation roi=$mapped",
                payload,
                decode(crop(frame, frameW, frameH, frameW, mapped))
            )
        }
    }

    // ── 工具 ──

    private fun crop(
        frame: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        rect: FrameRect
    ): Luminance = QrLuminance.copyRect(
        buffer = ByteBuffer.wrap(frame),
        width = width,
        height = height,
        rowStride = rowStride,
        pixelStride = 1,
        rect = rect
    )

    /** 合成一帧：白底 + 指定位置贴一个二维码。 */
    private fun frameWithQr(
        width: Int,
        height: Int,
        qrRect: FrameRect,
        rowStride: Int = width
    ): ByteArray {
        val out = ByteArray(rowStride * height) { white }
        val side = minOf(qrRect.width, qrRect.height)
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, side, side)
        for (row in 0 until matrix.height) {
            for (col in 0 until matrix.width) {
                if (matrix[col, row]) {
                    out[(qrRect.top + row) * rowStride + qrRect.left + col] = black
                }
            }
        }
        return out
    }

    private fun decode(luma: Luminance): String {
        val source = PlanarYUVLuminanceSource(
            luma.data, luma.width, luma.height, 0, 0, luma.width, luma.height, false
        )
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.ALSO_INVERTED to true
        )
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    }
}
