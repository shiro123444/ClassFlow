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
 * ZXing 解码所需的灰度处理：按行跨距拷出 Y 平面，再按相机旋转角转正。
 *
 * 只依赖 java.nio，不碰 Android API，可直接单测。
 */
internal object QrLuminance {

    /** 按 [rowStride] / [pixelStride] 把 Y 平面拷成紧密排列的 width×height 数组。 */
    fun copyPlane(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
        val out = ByteArray(width * height)
        val src = buffer.duplicate()
        if (pixelStride == 1 && rowStride == width) {
            src.position(0)
            src.get(out, 0, minOf(out.size, src.remaining()))
            return out
        }
        val row = ByteArray(maxOf(rowStride, width * pixelStride))
        for (y in 0 until height) {
            src.position(y * rowStride)
            val len = minOf(row.size, src.remaining())
            if (len <= 0) break
            src.get(row, 0, len)
            for (x in 0 until width) {
                val idx = x * pixelStride
                out[y * width + x] = if (idx < len) row[idx] else 0
            }
        }
        return out
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
