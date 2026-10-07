package com.xingheyuzhuan.shiguangschedule.ui.campus.qrscan

import java.nio.ByteBuffer

/** 已转正的亮度数据。 */
internal data class Luminance(val data: ByteArray, val width: Int, val height: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Luminance) return false
        return data.contentEquals(other.data) && width == other.width && height == other.height
    }

    override fun hashCode(): Int = (data.contentHashCode() * 31 + width) * 31 + height
}

/**
 * ZXing 解码所需的灰度处理：按行跨距从 Y 平面里拷出指定的一块。
 *
 * [rotate] 当前解码路径已经不调用（二维码四个方向都能解，转正只是白拷一份内存），
 * 保留它是因为 ROI 的方向约定需要一个独立的参照：它自己有逐角度锁定的单测，
 * QrScanRoiTest 用它来交叉验证 [mapViewRectToFrame] 的旋转方向。
 *
 * 只依赖 java.nio，不碰 Android API，可直接单测。
 */
internal object QrLuminance {

    /** 按 [rowStride] / [pixelStride] 把整帧 Y 平面拷成紧密排列的 width×height 数组。 */
    fun copyPlane(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray =
        copyRect(buffer, width, height, rowStride, pixelStride, FrameRect(0, 0, width, height)).data

    /**
     * 只拷 [rect] 这一块，并按 [rowStride] / [pixelStride] 压成紧密排列的数组。
     *
     * 取景框 ROI 解码靠它：分辨率再高也只读取景框内的像素，代价与帧大小无关。
     * 行跨距异常导致读越界时提前收尾（剩下的保持 0），不抛异常——分析线程不该因为坏帧挂掉。
     */
    fun copyRect(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        rect: FrameRect
    ): Luminance {
        val outWidth = rect.width
        val outHeight = rect.height
        if (outWidth <= 0 || outHeight <= 0 || width <= 0 || height <= 0) {
            return Luminance(ByteArray(0), 0, 0)
        }
        val stride = if (pixelStride > 0) pixelStride else 1
        val rowStrideSafe = if (rowStride > 0) rowStride else width * stride
        val out = ByteArray(outWidth * outHeight)
        val src = buffer.duplicate()

        if (stride == 1) {
            for (y in 0 until outHeight) {
                val start = (rect.top + y) * rowStrideSafe + rect.left
                if (start < 0 || start + outWidth > src.limit()) break
                src.position(start)
                src.get(out, y * outWidth, outWidth)
            }
        } else {
            val row = ByteArray(outWidth * stride)
            for (y in 0 until outHeight) {
                val start = (rect.top + y) * rowStrideSafe + rect.left * stride
                if (start < 0 || start + row.size > src.limit()) break
                src.position(start)
                src.get(row, 0, row.size)
                val base = y * outWidth
                for (x in 0 until outWidth) out[base + x] = row[x * stride]
            }
        }
        return Luminance(out, outWidth, outHeight)
    }

    /**
     * 按相机上报的旋转角把亮度数据转正（顺时针）。
     * 只处理 90 的整数倍，其余角度原样返回。90/270 会交换宽高。
     */
    fun rotate(data: ByteArray, width: Int, height: Int, degrees: Int): Luminance {
        return when (((degrees % 360) + 360) % 360) {
            90 -> {
                val out = ByteArray(data.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[x * height + (height - 1 - y)] = data[y * width + x]
                    }
                }
                Luminance(out, height, width)
            }

            180 -> {
                val out = ByteArray(data.size)
                for (i in data.indices) out[i] = data[data.size - 1 - i]
                Luminance(out, width, height)
            }

            270 -> {
                val out = ByteArray(data.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[(width - 1 - x) * height + y] = data[y * width + x]
                    }
                }
                Luminance(out, height, width)
            }

            else -> Luminance(data, width, height)
        }
    }
}
