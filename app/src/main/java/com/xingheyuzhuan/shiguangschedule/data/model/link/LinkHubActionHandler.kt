package com.xingheyuzhuan.shiguangschedule.data.model.link

import androidx.annotation.StringRes

/**
 * 确认卡片上的一行摘要（label 用字符串资源，value 由 handler 生成）。
 */
data class LinkHubFact(
    @StringRes val labelRes: Int,
    val value: String
)

/**
 * handler 落地结果。
 */
sealed interface LinkHubApplyResult {

    /** 就地完成（如已复制到剪贴板）。 */
    data class Done(@StringRes val messageRes: Int) : LinkHubApplyResult

    /** 需要跳转到内置 WebView 打开该地址。 */
    data class OpenUrl(val url: String) : LinkHubApplyResult

    /** 落地失败。 */
    data class Failed(@StringRes val messageRes: Int) : LinkHubApplyResult
}

/**
 * 通用链接节点的动作处理器。
 *
 * 约定：
 * - [type] 与信封 `type` 字段一一对应（小写）；
 * - [summarize] 只在确认卡片上展示，不允许有副作用；
 * - [apply] **仅在用户点击「应用」后**被调用，不允许自动执行。
 *
 * 新增动作类型（如 `proxy`、`layout_plugin`）时：
 * 1. 在 `LINK_HUB_PROTOCOL.md` 冻结 payload 字段；
 * 2. 实现本接口；
 * 3. 注册进 [com.xingheyuzhuan.shiguangschedule.data.link.LinkHubHandlers]。
 */
interface LinkHubActionHandler {

    val type: String

    /** 确认卡片上的摘要键值对（默认无）。 */
    fun summarize(envelope: LinkHubEnvelope): List<LinkHubFact> = emptyList()

    /** 执行落地。 */
    suspend fun apply(envelope: LinkHubEnvelope): LinkHubApplyResult
}
