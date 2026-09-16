package com.xingheyuzhuan.shiguangschedule.data.model.wbu

import androidx.annotation.StringRes
import com.xingheyuzhuan.shiguangschedule.R

/**
 * 网页应用标识枚举。
 */
enum class WebAppId {
    /** 图书馆座位预约系统 (jsq-v)。 */
    LIBRARY_SEAT
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
    /** 默认在 WebVPN 代理时是否包含 `-s` 单机后缀。 */
    val vpnSingleSuffix: Boolean = true
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
        vpnSingleSuffix = true
    )

    private val catalog = listOf(LIBRARY_SEAT).associateBy { it.id }

    fun find(id: WebAppId): WebAppDefinition? = catalog[id]

    fun findByIdString(idStr: String): WebAppDefinition? =
        runCatching { WebAppId.valueOf(idStr) }.getOrNull()?.let { find(it) }
}
