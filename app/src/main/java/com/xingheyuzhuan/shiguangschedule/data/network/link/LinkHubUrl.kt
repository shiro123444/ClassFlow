package com.xingheyuzhuan.shiguangschedule.data.network.link

import com.xingheyuzhuan.shiguangschedule.BuildConfig
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubProtocol
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 通用链接节点 URL 解析。
 *
 * 规范形式（`/url/` 与短别名 `/u/` 完全等价）：
 * - 服务端短码：`https://<hub>/url/{code}`
 * - 内嵌载荷：`https://<hub>/url/{code}#<紧凑载荷>`，极小标签可省略 code（`https://<hub>/u/#<紧凑载荷>`）
 *
 * 严格匹配：`https` + host 精确等于 [host] + 端口 443 + 无 userinfo；
 * 路径只允许 `/url`、`/url/{1..64 位 [A-Za-z0-9_-]}` 及其 `/u` 别名，
 * 更深路径、非法 code、以及形如 `#k=v` 的 fragment 一律拒绝（交回浏览器）。
 *
 * 域名来自 `BuildConfig.LINK_HUB_HOST`（私有配置注入），仓库代码不写死真实域名。
 */
object LinkHubUrl {

    /** 当前构建的 hub 域名。 */
    val host: String get() = BuildConfig.LINK_HUB_HOST

    /**
     * 仅 debug 构建生效的本地联调地址（`CLASSFLOW_LINK_HUB_DEBUG_BASE`，例如 `http://127.0.0.1:8090`）。
     *
     * 用途：不部署到公网、用 `php -S` 起一个本地服务端就能打通 Stage B（配合 `adb reverse`）。
     * release 构建恒为 null —— 线上永远只认 `https` + hub 域名的严格规则。
     */
    private val debugBase: HttpUrl? = run {
        if (!BuildConfig.DEBUG) return@run null
        val raw = BuildConfig.LINK_HUB_DEBUG_BASE.trim()
        if (raw.isEmpty()) null else raw.toHttpUrlOrNull()
    }

    /** 服务端短码允许的字符集与长度。 */
    private val CODE_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

    /**
     * 解析结果；[code] 与 [inline] 至少有一个非空。
     */
    data class Node(
        /** 服务端短码（内嵌节点可为空）。 */
        val code: String?,
        /** 内嵌紧凑载荷原文（base64url，不含前缀标记）。 */
        val inline: String?,
        /**
         * 链接的来源（`scheme://host[:port]`）。
         *
         * 取服务端短码时按这个来源请求，而不是固定用 hub 域名：
         * 这样 dev 构建扫到本地联调链接（`http://127.0.0.1:8090/...`）就请求本地，
         * 扫到线上链接就请求线上，二者不会互相干扰。
         */
        val origin: String
    )

    /**
     * 解析任意字符串；不是本 App 的通用节点时返回 null。
     */
    fun parse(raw: String): Node? {
        val trimmed = raw.trim()
        val url = trimmed.toHttpUrlOrNull() ?: return null
        if (!matchesOrigin(url)) return null

        val path = url.encodedPath
        val code: String? = when {
            path == "/url" || path == "/url/" || path == "/u" || path == "/u/" -> null
            path.startsWith("/url/") ->
                path.removePrefix("/url/").takeIf(CODE_REGEX::matches) ?: return null
            path.startsWith("/u/") ->
                path.removePrefix("/u/").takeIf(CODE_REGEX::matches) ?: return null
            else -> return null
        }

        val fragment = url.fragment.orEmpty()
        // `#k=v` 形式为将来扩展预留，本版本不识别
        if (fragment.contains('=')) return null
        val inline = fragment.takeIf { it.isNotEmpty() && it.length <= LinkHubProtocol.MAX_INLINE_CHARS }

        if (code == null && inline == null) return null
        return Node(code = code, inline = inline, origin = url.origin())
    }

    /** `scheme://host[:port]`（省略默认端口）。 */
    private fun HttpUrl.origin(): String {
        val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
        return if (defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    /** 该短码在指定来源下的请求地址（来源缺失时回落到默认服务端）。 */
    fun buildCodeUrl(code: String, origin: String? = null, shortAlias: Boolean = false): String {
        val base = origin?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: serverBase()
        return "$base${if (shortAlias) "/u/" else "/url/"}$code"
    }

    /** host 严格匹配：正式规则（https + hub 域名 + 443），或 debug 构建下的本地联调地址。 */
    private fun matchesOrigin(url: HttpUrl): Boolean {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        if (url.isHttps && url.port == 443 && url.host.equals(host, ignoreCase = true)) return true
        val base = debugBase ?: return false
        return url.scheme == base.scheme &&
            url.port == base.port &&
            url.host.equals(base.host, ignoreCase = true)
    }

    /** 取服务端短码时使用的基础地址（debug 联调时指向本地服务端）。 */
    fun serverBase(): String = debugBase?.let { base ->
        base.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
    } ?: "https://$host"

    /**
     * 生成内嵌节点链接；[payload] 为 [LinkHubCompactCodec.encode] 的输出。
     * [code] 为 null 时会省略路径尾段（极小标签可用）。
     *
     * 注意：内嵌链接是**要写进标签/二维码**的对外地址，固定用 hub 域名，
     * 不随 debug 联调地址变化。
     */
    fun buildInlineUrl(
        payload: String,
        code: String? = null,
        shortAlias: Boolean = false
    ): String {
        val prefix = "https://$host${if (shortAlias) "/u/" else "/url/"}"
        return prefix + code.orEmpty() + "#" + payload
    }
}
