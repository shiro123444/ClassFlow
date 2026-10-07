package com.xingheyuzhuan.shiguangschedule.data.model.wbu

/**
 * 扫一扫的二维码解码引擎。
 *
 * 两者识别能力等价（把一帧画面解成二维码文本），区别只在实现：
 * [ZXING_CPP] 是 Binary Eye 用的那套原生解码器（zxing-cpp），鲁棒性和速度都更好，默认走它；
 * [ZXING] 是纯 Java 实现，不依赖原生库，留作兜底——万一某台设备的 ABI 上原生库出问题还能扫。
 *
 * （原 ML Kit 引擎已移除：它自带模型与 barhopper 原生库，为了解二维码要多背 6MB 包体。）
 */
enum class QrScanEngine {

    /** ZXing core（纯 Java 实现，不依赖原生库）。 */
    ZXING,

    /** zxing-cpp（Binary Eye 同款原生解码器）。 */
    ZXING_CPP;

    companion object {
        val DEFAULT = ZXING_CPP
    }
}
