package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 应用级品牌过场控制器。
 *
 * 由 MainActivity 提供（动画层在 NavHost 之上），任意页面都可以请求播放一次过场，
 * 例如「扫一扫 → 吹风机」需要先播放品牌过场再让支付宝覆盖前台，
 * 此时页面自身会被 pop 掉，动画必须挂在应用层才不会被一起销毁。
 */
class EntryOverlayController(private val showBlock: (durationMillis: Int) -> Unit) {
    fun show(durationMillis: Int = 1800) = showBlock(durationMillis)
}

/** 默认空实现，保证独立预览 / 测试环境下调用不崩溃。 */
val LocalEntryOverlayController = staticCompositionLocalOf { EntryOverlayController {} }
