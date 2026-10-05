package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 马影河宿舍洗浴的**设备直达链接**解析：`https://<域名>/s/{系统}/{设备号}[/{端口}]`。
 *
 * 为什么不用通用链接节点的内嵌载荷（`/u/#base64`）：洗浴只有「系统 + 设备号」两个字段，
 * 而 base64 会把每个字节膨胀成 1.33 个字符，1 栋机号（5 位数字）反而比二进制更长，
 * 2-3 栋的设备号更是凭空多出 8 个字符。写成路径段既更短，也**人可读**——
 * 贴纸上的链接一眼能看懂，任何扫码工具都能直接解析，不必自带 base64 解码器。
 *
 * 与 `/w/`（饮水机）、`/wm/`（洗衣机）、`/hd/`（吹风机）同级，都属于「设备直达命名空间」：
 * 链接内容即设备身份，离线可用、不经服务端、无需用户确认。
 *
 * 两种系统：
 * - `y`（yktxyyy）：马影河 1 栋「智能控水」，设备号是 5 位机号，是否存在由服务端 `CheckKsPos` 正证；
 * - `l`（lifeService）：马影河 2-3 栋（水表 51），设备号是水表 imei，可带多路控水器端口。
 *
 * 设备号一律按 URI 段编码（`encodeURIComponent` / `Uri.encode`），因此可能含 `%3F` 这类转义；
 * 本解析器用 [okhttp3.HttpUrl.pathSegments]（已解码）取值，天然还原原文。
 */
object CampusShowerLink {

    /** 1 栋：智能控水（yktxyyy）。 */
    const val SYSTEM_YKT = "y"

    /** 2-3 栋：lifeService（水表 51）。 */
    const val SYSTEM_LIFE = "l"

    /** 路径命名空间：设备直达。 */
    private const val NAMESPACE = "s"

    /** 设备号长度上限（与通用链接节点一致）。 */
    private const val MAX_CODE_BYTES = 256

    /** 端口位数上限。 */
    private const val MAX_PORT_DIGITS = 8

    /** 机号上限（u24 可表达范围，与 [CampusShowerPayload] 一致）。 */
    private const val MAX_POSNO = 99999

    sealed interface Direct {
        /** `y` 或 `l`；用于回填 [com.xingheyuzhuan.shiguangschedule.Destination.ShowerDirect]。 */
        val system: String

        /** 1 栋：智能控水机号。 */
        data class Ykt(val posno: String) : Direct {
            override val system: String get() = SYSTEM_YKT
        }

        /** 2-3 栋：lifeService 水表设备号（+ 可选端口）。 */
        data class Life(val imei: String, val port: String?) : Direct {
            override val system: String get() = SYSTEM_LIFE
        }
    }

    /**
     * 解析任意字符串；不是本 App 的洗浴直达链接时返回 null。
     *
     * 不校验域名：能进 App 的链接已由 `AndroidManifest.xml` 的 intent-filter 限定在站点域名上，
     * 这与同为设备直达的 `/w/`、`/wm/`、`/hd/` 保持一致（[CampusLinkRouter] 也不校验它们）。
     * 好处是站点迁移域名、或本地联调换地址时，链接不用重写。
     */
    fun parse(raw: String): Direct? {
        val trimmed = raw.trim()
        val url = trimmed.toHttpUrlOrNull() ?: return null
        val segments = url.pathSegments
        // pathSegments 对 "/s" 会给出 ["s"]，对 "/s/" 给出 ["s", ""]
        if (segments.size < 3) return null
        if (!segments[0].equals(NAMESPACE, ignoreCase = true)) return null

        val system = segments[1].lowercase()
        val code = segments[2].trim()
        if (code.isEmpty() || code.toByteArray(Charsets.UTF_8).size > MAX_CODE_BYTES) return null
        // 保留段（如官网下载页 /s/download）不是设备号，不做深链路由
        if (UjingQrLink.isReservedSegment(code)) return null

        // 多一个路径段只能是端口；再多就是垃圾
        val extra = segments.drop(3).filter { it.isNotEmpty() }
        if (extra.size > 1) return null
        val portSegment = extra.firstOrNull()?.trim()

        return when (system) {
            SYSTEM_YKT -> {
                if (portSegment != null) return null
                if (code.length > 5 || !code.all { it.isDigit() }) return null
                val posno = code.toIntOrNull() ?: return null
                if (posno <= 0 || posno > MAX_POSNO) return null
                // 与页面一致：机号按数字值使用，前导零会被去掉
                Direct.Ykt(posno = posno.toString())
            }

            SYSTEM_LIFE -> {
                if (!isValidImei(code)) return null
                val port = portSegment?.takeIf { it.isNotEmpty() }
                if (port != null && (port.length > MAX_PORT_DIGITS || !port.all { it.isDigit() })) return null
                Direct.Life(imei = code, port = port)
            }

            else -> null
        }
    }

    /**
     * 生成设备直达链接；字段不合法时返回 null。
     *
     * 供本地工具（生成器 / 自检）与服务端落地页复用，保证与 [parse] 严格互逆。
     */
    fun build(host: String, system: String, code: String, port: String? = null): String? {
        val trimmedHost = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (trimmedHost.isEmpty()) return null
        val path = when (system.trim().lowercase()) {
            SYSTEM_YKT -> {
                val posno = code.trim().toIntOrNull() ?: return null
                if (posno <= 0 || posno > MAX_POSNO) return null
                "$NAMESPACE/$SYSTEM_YKT/$posno"
            }

            SYSTEM_LIFE -> {
                val imei = code.trim()
                if (!isValidImei(imei)) return null
                val portPart = port?.trim()?.takeIf { it.isNotEmpty() }
                if (portPart != null && (portPart.length > MAX_PORT_DIGITS || !portPart.all { it.isDigit() })) {
                    return null
                }
                buildString {
                    append(NAMESPACE).append('/').append(SYSTEM_LIFE).append('/').append(encodeSegment(imei))
                    if (portPart != null) append('/').append(portPart)
                }
            }

            else -> return null
        }
        return "https://$trimmedHost/$path"
    }

    /** 设备号合法性：非空、无空白、不含 `$` / `#`（与 [CampusShowerPayload] 一致）。 */
    private fun isValidImei(imei: String): Boolean =
        imei.isNotEmpty() && imei.none { it.isWhitespace() || it == '$' || it == '#' }

    /**
     * URI 段编码：只保留 ASCII 的 unreserved 字符（`A-Z a-z 0-9 - _ . ~`），其余按 UTF-8 逐字节百分号转义。
     * 与 JS 的 `encodeURIComponent` 等价（它同样不转义 `-_.!~*'()`，多转义几个字符不影响往返）。
     */
    private fun encodeSegment(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val code = byte.toInt() and 0xFF
            val c = code.toChar()
            val unreserved = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '_' || c == '.' || c == '~'
            if (unreserved) append(c) else append('%').append("%02X".format(code))
        }
    }
}
