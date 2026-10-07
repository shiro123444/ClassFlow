package com.xingheyuzhuan.shiguangschedule.ui.campus.qrscan

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** 取景框在视图坐标下的位置（正方形，单位 px）。 */
internal data class ScanFrameRect(
    val left: Float,
    val top: Float,
    val side: Float
)

/** 解码区域在帧缓冲坐标下的位置（左闭右开，单位 px）。 */
internal data class FrameRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 计算取景框（视图坐标）：
 * 边长 = min(视图宽, 扣掉上下预留后的可用高) * 0.68，且不超过 [maxSide]；
 * 水平居中，在上下预留之间的区域里垂直居中。
 *
 * 取景框同时是解码 ROI 的来源（见 [mapViewRectToFrame]），所以抽成纯函数由「画框」和
 * 「算 ROI」两边共用——两边各算一遍迟早会算歪。
 */
internal fun scanFrameRect(
    viewWidth: Float,
    viewHeight: Float,
    reservedTop: Float,
    reservedBottom: Float,
    maxSide: Float
): ScanFrameRect {
    val freeHeight = (viewHeight - reservedTop - reservedBottom).coerceAtLeast(0f)
    val side = (min(viewWidth, freeHeight) * 0.68f).coerceAtMost(maxSide)
    val left = (viewWidth - side) / 2f
    val top = reservedTop + (freeHeight - side) / 2f
    return ScanFrameRect(left, top, side)
}

/** 帧的宽高比（恒 >= 1；旋转 90/270 只交换长短边，比值不变，所以这里不需要 rotation）。 */
internal fun frameAspect(width: Int, height: Int): Float {
    if (width <= 0 || height <= 0) return 0f
    return max(width, height).toFloat() / min(width, height).toFloat()
}

/** 两个宽高比是否足够接近（相对差 <= [tolerance]）。 */
internal fun aspectClose(a: Float, b: Float, tolerance: Float = 0.05f): Boolean {
    if (a <= 0f || b <= 0f) return false
    return abs(a - b) / max(a, b) <= tolerance
}

/**
 * 把视图坐标下的取景框映射到分析帧的缓冲坐标。
 *
 * PreviewView 用 FILL_CENTER（等比放大铺满、超出部分居中裁掉），所以映射是两步仿射：
 * 1. 视图 → 转正后的图像：减去居中裁剪的偏移，再除以缩放比 scale；
 * 2. 转正后的图像 → 原始缓冲：按 [rotationDegrees]（顺时针转正角）做逆变换。
 *
 * 第二步的旋转方向必须和 [QrLuminance.rotate] 一致（那边有单测逐角度锁定），
 * 这里由 QrScanGeometryTest 用「屏幕上方 = 缓冲左/右侧」的用例锁住方向。
 *
 * [padding] 是额外放宽比例（0.15 = 四边各放 15%），用来吸收手抖，以及预览流与
 * 分析流裁切不完全一致时的偏差。
 *
 * @return 裁剪到帧内、向外取整后的矩形；尺寸非法时返回 null，调用方应退回整帧解码。
 */
internal fun mapViewRectToFrame(
    rect: ScanFrameRect,
    viewWidth: Int,
    viewHeight: Int,
    frameWidth: Int,
    frameHeight: Int,
    rotationDegrees: Int,
    padding: Float = 0f
): FrameRect? {
    if (viewWidth <= 0 || viewHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return null
    if (rect.side <= 0f) return null

    val rotation = ((rotationDegrees % 360) + 360) % 360
    val swapped = rotation % 180 != 0
    val uprightWidth = if (swapped) frameHeight else frameWidth
    val uprightHeight = if (swapped) frameWidth else frameHeight

    // FILL_CENTER：等比放大到铺满视图
    val scale = max(
        viewWidth.toFloat() / uprightWidth,
        viewHeight.toFloat() / uprightHeight
    )
    val offsetX = (viewWidth - uprightWidth * scale) / 2f
    val offsetY = (viewHeight - uprightHeight * scale) / 2f

    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE

    val viewXs = floatArrayOf(rect.left, rect.left + rect.side)
    val viewYs = floatArrayOf(rect.top, rect.top + rect.side)
    for (viewX in viewXs) {
        for (viewY in viewYs) {
            // 1. 视图 → 转正后的图像
            val ux = (viewX - offsetX) / scale
            val uy = (viewY - offsetY) / scale
            // 2. 转正后的图像 → 原始缓冲（QrLuminance.rotate 的逆变换）
            val rawX: Float
            val rawY: Float
            when (rotation) {
                90 -> {
                    rawX = uy
                    rawY = frameHeight - ux
                }

                180 -> {
                    rawX = frameWidth - ux
                    rawY = frameHeight - uy
                }

                270 -> {
                    rawX = frameWidth - uy
                    rawY = ux
                }

                else -> {
                    rawX = ux
                    rawY = uy
                }
            }
            minX = min(minX, rawX)
            maxX = max(maxX, rawX)
            minY = min(minY, rawY)
            maxY = max(maxY, rawY)
        }
    }

    if (padding > 0f) {
        val padX = (maxX - minX) * padding
        val padY = (maxY - minY) * padding
        minX -= padX
        maxX += padX
        minY -= padY
        maxY += padY
    }

    val left = floor(minX).toInt().coerceIn(0, frameWidth - 1)
    val top = floor(minY).toInt().coerceIn(0, frameHeight - 1)
    val right = ceil(maxX).toInt().coerceIn(left + 1, frameWidth)
    val bottom = ceil(maxY).toInt().coerceIn(top + 1, frameHeight)
    return FrameRect(left, top, right, bottom)
}
