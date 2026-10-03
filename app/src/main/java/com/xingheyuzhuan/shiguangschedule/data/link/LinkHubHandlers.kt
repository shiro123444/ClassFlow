package com.xingheyuzhuan.shiguangschedule.data.link

import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubActionHandler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通用链接节点的动作处理器注册表。
 *
 * 新增动作类型（如 `proxy`、`layout_plugin`）时，在这里追加一个构造参数 + [listOf] 项即可；
 * 未注册的类型由 UI 走「未知类型 / 需要更新 App」兜底。
 *
 * 说明：当前处理器数量很少，用显式注册表而不是 Hilt multibinding，便于一眼看清支持范围。
 */
@Singleton
class LinkHubHandlers @Inject constructor(
    openUrlHandler: OpenUrlHandler,
    textHandler: TextHandler
) {

    private val handlers: Map<String, LinkHubActionHandler> =
        listOf(openUrlHandler, textHandler).associateBy { it.type }

    /** 按信封 `type` 查找处理器（大小写、首尾空格不敏感）。 */
    fun find(type: String): LinkHubActionHandler? = handlers[type.trim().lowercase()]
}
