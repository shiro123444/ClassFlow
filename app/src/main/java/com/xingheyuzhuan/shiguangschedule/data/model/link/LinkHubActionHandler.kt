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

    /** 需要跳转到指定网页应用容器（如 CAMPUS_CARD）打开。 */
    data class OpenWebApp(
        val appId: String,
        val initialUrl: String? = null,
        val pendingAutoScan: String? = null
    ) : LinkHubApplyResult

    /**
     * 需要跳转到 2-3 栋淋浴**原生**用水页（已正证 `devicesType` = 淋浴）。
     *
     * 与 [OpenWebApp] 并列而不是塞进它：原生页不再是「某个网页应用容器」，
     * 参数也不是 appId + URL，而是设备身份加平台给的计费上下文。
     */
    data class OpenShowerWater(
        val deviceId: String,
        val port: String?,
        val implid: String,
        val feeitemid: String,
        /** 平台生活服务页面地址（逃生口，不带一次性自启载体），见 `Destination.ShowerWater`。 */
        val webFallbackUrl: String? = null
    ) : LinkHubApplyResult

    /** 落地失败。 */
    data class Failed(
        @StringRes val messageRes: Int,
        /** 是否值得让用户点「重试」（网络类、登录态类失败为 true）。 */
        val retryable: Boolean = false,
        /**
         * 失败原因是「本机没有统一认证会话」。
         *
         * 上层据此用保存的密码静默登一次再重来 —— 登录态是用户**自己动手就能解决**的那类问题，
         * 网络类失败重试即可，不该顺手把用户推进登录面板。
         */
        val needsLogin: Boolean = false
    ) : LinkHubApplyResult
}

/**
 * 通用链接节点的动作处理器。
 *
 * 约定：
 * - [type] 与信封 `type` 字段一一对应（小写）；
 * - [summarize] 只在确认卡片上展示，不允许有副作用；
 * - 默认 [apply] **仅在用户点击「应用」后**被调用；
 * - [autoApply] 为 true 的处理器在**服务端短码**（[LinkHubOrigin.SERVER]）节点上免确认直接 [apply]；
 *   内嵌载荷（[LinkHubOrigin.INLINE]）需处理器再声明 [autoApplyInline] 才免确认，否则仍走确认卡片。
 *
 * 新增动作类型（如 `proxy`、`layout_plugin`）时：
 * 1. 在 `LINK_HUB_PROTOCOL.md` 冻结 payload 字段；
 * 2. 实现本接口；
 * 3. 注册进 [com.xingheyuzhuan.shiguangschedule.data.link.LinkHubHandlers]。
 */
interface LinkHubActionHandler {

    val type: String

    /**
     * 是否允许服务端节点免确认直接落地。
     *
     * 只对「动作本身安全、可由服务端随时撤销」的功能开放（如 `campus_shower`）。
     */
    val autoApply: Boolean get() = false

    /**
     * 是否允许**内嵌载荷**（`/u/#载荷`）免确认直接落地。
     *
     * 默认 false：内嵌内容不经服务端、无法撤销，必须先经用户确认。
     * 仅对「动作边界明确、与直接扫原设备二维码等价」的处理器开放（如 `campus_shower`）。
     */
    val autoApplyInline: Boolean get() = false

    /** 确认卡片上的摘要键值对（默认无）。 */
    fun summarize(envelope: LinkHubEnvelope): List<LinkHubFact> = emptyList()

    /** 执行落地。 */
    suspend fun apply(envelope: LinkHubEnvelope): LinkHubApplyResult
}
