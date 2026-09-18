package com.xingheyuzhuan.shiguangschedule.data.model.wbu

/**
 * 扫一扫的二维码解码引擎。
 *
 * 两者识别能力等价（把一帧画面解成二维码文本），区别在成本与鲁棒性：
 * [ML_KIT] 自带检测与畸变矫正、复杂光照/斜角更稳，代价是打进包体的原生库与模型；
 * [ZXING] 纯 Java、包体开销极小，作为识别不理想时的备用。
 */
enum class QrScanEngine {

    /** ML Kit（bundled 模型）。 */
    ML_KIT,

    /** ZXing core（项目已有的纯 Java 实现）。 */
    ZXING;

    companion object {
        val DEFAULT = ML_KIT
    }
}
