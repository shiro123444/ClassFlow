package com.xingheyuzhuan.shiguangschedule.data.model.wbu

import androidx.annotation.StringRes
import com.xingheyuzhuan.shiguangschedule.R

/**
 * 网页应用标识枚举。
 */
enum class WebAppId {
    /** 图书馆座位预约系统 (jsq-v)。 */
    LIBRARY_SEAT,

    /** 一卡通移动服务平台（慧新e校）。 */
    CAMPUS_CARD
}

/**
 * 网页应用元数据定义。
 *
 * 适用于以统一身份认证 (CAS) 为主、且页面具有自有完整导航与标题栏的校园全屏网页系统。
 */
data class WebAppDefinition(
    val id: WebAppId,
    @StringRes val titleRes: Int,
    /** 公网真实域名/Origin，如 `https://libseat.wbu.edu.cn`。 */
    val realOrigin: String,
    /** 目标主机的 Host 字段，如 `libseat.wbu.edu.cn`。 */
    val targetHost: String,
    /** 前端网页应用根路径，如 `/jsq-v/`。 */
    val appPath: String,
    /** CAS SSO 入口路径，用于在换取 token 前向业务 SSO 服务初始化会话（如关联 session），可为 null。 */
    val remSsoLoginPath: String? = null,
    /** 业务端在 CAS 注册的真实 Service 回调地址，CAS 在验证通过后回跳到此接口核销 ticket 并签发 JWT。 */
    val casServiceUrl: String,
    /** 默认的首页 Hash 路由，如 `#/main/index`。 */
    val defaultHash: String = "",
    /** 用于判定是否处于主页的 Hash 标记前缀集合。在这些页面触发系统返回键将直接退出容器，而不是在内部回退。 */
    val homeHashMarkers: List<String> = emptyList(),
    /**
     * 平台主页的绝对地址。当页面被第三方 H5（如 U净）接管后，返回键将回到此地址，
     * 避免在其 OAuth 回调链上「回退 → 被重新跳转」形成死循环。
     */
    val homeUrl: String? = null,
    /** 默认在 WebVPN 代理时是否包含 `-s` 单机后缀。 */
    val vpnSingleSuffix: Boolean = true,
    /** 是否属于外网直连服务（无需校园网环境，也不经由 WebVPN）。 */
    val directOnly: Boolean = false
)

/**
 * 网页应用目录注册表。
 */
object WebAppCatalog {
    val LIBRARY_SEAT = WebAppDefinition(
        id = WebAppId.LIBRARY_SEAT,
        titleRes = R.string.item_library_seat,
        realOrigin = "https://libseat.wbu.edu.cn",
        targetHost = "libseat.wbu.edu.cn",
        appPath = "/jsq-v/",
        remSsoLoginPath = "/rem/static/sso/login",
        casServiceUrl = "https://libseat.wbu.edu.cn/rem/static/sso/webOAuthRed",
        defaultHash = "#/main/index",
        homeHashMarkers = listOf("#/main/index", "#/main/home"),
        vpnSingleSuffix = true,
        directOnly = false
    )

    val CAMPUS_CARD = WebAppDefinition(
        id = WebAppId.CAMPUS_CARD,
        titleRes = R.string.service_campus_card,
        realOrigin = "http://yktfwpt.wbu.edu.cn",
        targetHost = "yktfwpt.wbu.edu.cn",
        appPath = "/plat/",
        remSsoLoginPath = null,
        casServiceUrl = "http://yktfwpt.wbu.edu.cn/berserker-auth/cas/oauth2url",
        defaultHash = "",
        homeHashMarkers = listOf("shouyeUser", "/plat/shouyeUser"),
        homeUrl = "http://yktfwpt.wbu.edu.cn/plat/shouyeUser",
        vpnSingleSuffix = false,
        directOnly = true
    )

    private val catalog = listOf(LIBRARY_SEAT, CAMPUS_CARD).associateBy { it.id }

    fun find(id: WebAppId): WebAppDefinition? = catalog[id]

    fun findByIdString(idStr: String): WebAppDefinition? =
        runCatching { WebAppId.valueOf(idStr) }.getOrNull()?.let { find(it) }
}
