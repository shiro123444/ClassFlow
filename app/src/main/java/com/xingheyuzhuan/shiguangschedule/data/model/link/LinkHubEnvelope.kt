package com.xingheyuzhuan.shiguangschedule.data.model.link

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 通用链接节点（`https://<hub>/url/{code}`、短别名 `/u/{code}`）的协议常量。
 *
 * 完整协议见仓库根目录 `LINK_HUB_PROTOCOL.md`。
 */
object LinkHubProtocol {

    /** 协议版本：信封的 `v` 字段与紧凑线格式首字节高 nibble 共用。 */
    const val VERSION = 1

    /** 内嵌载荷（URL fragment 部分）的最大字符数。 */
    const val MAX_INLINE_CHARS = 2048

    /** 内嵌载荷解码后的最大字节数（防解压/解码炸弹）。 */
    const val MAX_INLINE_BYTES = 1024
}

/**
 * 动作类型（信封的 `type` 字段）。
 *
 * 新增类型时：在协议文档登记字段契约，再由对应功能注册
 * [LinkHubActionHandler][com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubActionHandler]。
 */
object LinkHubType {

    /** 在内置 WebView 打开 `payload.url`。 */
    const val OPEN = "open"

    /** 复制 `payload.text` 到剪贴板。 */
    const val TEXT = "text"

    /** 网络代理配置（字段契约已冻结，功能本体待实现）。 */
    const val PROXY = "proxy"

    /** 布局插件（字段契约已冻结，功能本体待实现）。 */
    const val LAYOUT_PLUGIN = "layout_plugin"

    /** 已冻结契约、但当前版本尚未实现的动作类型（UI 提示更新 App 而非「未知类型」）。 */
    val RESERVED = setOf(PROXY, LAYOUT_PLUGIN)
}

/** 节点载荷来源：服务端 JSON 或链接内嵌紧凑二进制。 */
enum class LinkHubOrigin {
    /** 服务端返回的 JSON 信封（可撤销、可校验）。 */
    SERVER,

    /** 直接内嵌在链接 fragment 里（离线可用、不经服务端，也无法被撤销）。 */
    INLINE
}

/**
 * 通用链接节点的动作信封。
 *
 * 两条线格式共用同一个模型：
 * - 服务端 JSON（`Accept: application/json`）直接反序列化本类；
 * - 内嵌紧凑二进制经 [com.xingheyuzhuan.shiguangschedule.data.network.link.LinkHubCompactCodec]
 *   解码后构造本类（此时 [title]、[description]、[minAppVersion]、[expiresAt] 均为 null）。
 */
@Serializable
data class LinkHubEnvelope(
    val v: Int = LinkHubProtocol.VERSION,
    val type: String,
    val title: String? = null,
    val description: String? = null,
    /** 要求的最低 App `versionCode`；低于它的客户端只提示更新，不落地。 */
    val minAppVersion: Int? = null,
    /** 失效时间（Unix 秒）；过期后不落地。 */
    val expiresAt: Long? = null,
    /** 各动作类型自定义的载荷（字段契约见协议文档）。 */
    val payload: JsonObject = JsonObject(emptyMap())
)

/** 信封 + 来源（来源由本地判定，不来自服务端）。 */
data class LinkHubNode(
    val envelope: LinkHubEnvelope,
    val origin: LinkHubOrigin
)

/** 读取字符串字段（非字符串返回 null）。 */
fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** 读取整数字段（兼容 JSON 数字与数字字符串）。 */
fun JsonObject.intField(name: String): Int? =
    (this[name] as? JsonPrimitive)?.content?.toIntOrNull()
