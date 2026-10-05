package com.xingheyuzhuan.shiguangschedule.data.network.link

import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubEnvelope
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubProtocol
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubType
import com.xingheyuzhuan.shiguangschedule.data.model.link.intField
import com.xingheyuzhuan.shiguangschedule.data.model.link.stringField
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 内嵌载荷解码结果。 */
sealed interface LinkHubInlineResult {

    data class Ok(val envelope: LinkHubEnvelope) : LinkHubInlineResult

    /** 载荷损坏或结构非法。 */
    data object Invalid : LinkHubInlineResult

    /** 编码版本高于本版本 App 能识别的范围。 */
    data object UnsupportedVersion : LinkHubInlineResult

    /** 动作类型本版本不认识（需要更新 App）。 */
    data object UnsupportedType : LinkHubInlineResult
}

/**
 * 通用链接节点的内嵌紧凑线格式（CFN1）。
 *
 * 设计目标：极小 NFC 标签 / 小尺寸二维码。相比「JSON 信封 + deflate + base64」，
 * 紧凑二进制把一条代理节点从 ~157 字节压到 ~61 字节（详见 `LINK_HUB_PROTOCOL.md` 的字节预算表）。
 *
 * 线格式（首字节 = `0x高nibble 版本 | 低nibble 类型`，其后按类型排列，整体再 base64url 无 padding）：
 * - `0x11 open`  ：flags(1B，bit0 = 已省略 `https://`) + URL 剩余字节
 * - `0x12 text`  ：UTF-8 原文
 * - `0x13 proxy` ：kind(1B，0=http/1=socks5) + port(u16 BE) + host(1B 长度前缀) + user + pass
 * - `0x14 plugin`：flags(1B) + 清单 URL（仅清单地址，哈希等元数据由服务端清单提供）
 * - `0x15 campus_shower`：system(1B，0=智能控水/1=lifeService) + 机号或 imei/port
 * - `0x1F`       ：逃生口 —— 其后为 UTF-8 JSON 信封（需要 title / 有效期时就地升级）
 *
 * 不使用 deflate：短文本下 zlib 头 + base64 的 33% 膨胀反而是负收益。
 */
object LinkHubCompactCodec {

    private const val TYPE_OPEN = 0x1
    private const val TYPE_TEXT = 0x2
    private const val TYPE_PROXY = 0x3
    private const val TYPE_PLUGIN = 0x4
    private const val TYPE_CAMPUS_SHOWER = 0x5
    private const val TYPE_JSON = 0xF

    private const val FLAG_HTTPS_OMITTED = 0x1

    private const val KIND_HTTP = 0
    private const val KIND_SOCKS5 = 1

    private const val SHOWER_SYSTEM_YKT = 0
    private const val SHOWER_SYSTEM_LIFE = 1
    private const val SHOWER_FLAG_HAS_PORT = 0x1

    private const val MAX_URL_BYTES = 512
    private const val MAX_TEXT_BYTES = 1024
    private const val MAX_HOST_BYTES = 255
    private const val MAX_CREDENTIAL_BYTES = 255
    private const val MAX_POSNO = 99999
    private const val MAX_PORT = 99999999
    private const val MAX_DEVICE_NO_BYTES = 255

    private val json = Json { ignoreUnknownKeys = true }
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /**
     * 编码为可放入 URL fragment 的字符串。
     * 类型未登记或载荷超限时返回 null（调用方应回退到服务端短码形态）。
     */
    fun encode(envelope: LinkHubEnvelope): String? {
        val body = when (envelope.type.trim().lowercase()) {
            LinkHubType.OPEN -> {
                val url = envelope.payload.stringField("url")?.trim().orEmpty()
                encodeUrlPayload(TYPE_OPEN, url, requireHttps = false) ?: return null
            }

            LinkHubType.TEXT -> {
                val bytes = envelope.payload.stringField("text").orEmpty().toByteArray(Charsets.UTF_8)
                if (bytes.isEmpty() || bytes.size > MAX_TEXT_BYTES) return null
                byteArrayOf(typeByte(TYPE_TEXT)) + bytes
            }

            LinkHubType.PROXY -> encodeProxy(envelope.payload) ?: return null

            LinkHubType.LAYOUT_PLUGIN -> {
                val manifestUrl = envelope.payload.stringField("manifestUrl")?.trim().orEmpty()
                encodeUrlPayload(TYPE_PLUGIN, manifestUrl, requireHttps = true) ?: return null
            }

            LinkHubType.CAMPUS_SHOWER -> encodeCampusShower(envelope.payload) ?: return null

            else -> return null
        }
        return encoder.encodeToString(body)
    }

    /**
     * 解码 URL fragment 里的紧凑载荷。
     *
     * 输入必须是 [LinkHubUrl.parse] 判定过的 fragment 原文（不含 `#`、不含 `k=` 前缀）。
     */
    fun decode(payload: String): LinkHubInlineResult {
        if (payload.isEmpty() || payload.length > LinkHubProtocol.MAX_INLINE_CHARS) {
            return LinkHubInlineResult.Invalid
        }
        val bytes = runCatching { decoder.decode(payload) }.getOrNull()
            ?: return LinkHubInlineResult.Invalid
        if (bytes.isEmpty() || bytes.size > LinkHubProtocol.MAX_INLINE_BYTES) {
            return LinkHubInlineResult.Invalid
        }

        val version = (bytes[0].toInt() ushr 4) and 0x0F
        if (version != LinkHubProtocol.VERSION) return LinkHubInlineResult.UnsupportedVersion

        return when (bytes[0].toInt() and 0x0F) {
            TYPE_OPEN -> {
                val url = decodeUrlPayload(bytes, requireHttps = false) ?: return LinkHubInlineResult.Invalid
                ok(LinkHubType.OPEN, buildJsonObject { put("url", url) })
            }

            TYPE_TEXT -> {
                if (bytes.size - 1 > MAX_TEXT_BYTES) return LinkHubInlineResult.Invalid
                val text = bytes.copyOfRange(1, bytes.size).toString(Charsets.UTF_8)
                if (text.isEmpty()) return LinkHubInlineResult.Invalid
                ok(LinkHubType.TEXT, buildJsonObject { put("text", text) })
            }

            TYPE_PROXY -> decodeProxy(bytes) ?: return LinkHubInlineResult.Invalid

            TYPE_PLUGIN -> {
                val manifestUrl = decodeUrlPayload(bytes, requireHttps = true)
                    ?: return LinkHubInlineResult.Invalid
                ok(LinkHubType.LAYOUT_PLUGIN, buildJsonObject { put("manifestUrl", manifestUrl) })
            }

            TYPE_CAMPUS_SHOWER -> decodeCampusShower(bytes) ?: return LinkHubInlineResult.Invalid

            TYPE_JSON -> {
                val text = bytes.copyOfRange(1, bytes.size).toString(Charsets.UTF_8)
                val envelope = runCatching { json.decodeFromString<LinkHubEnvelope>(text) }.getOrNull()
                    ?: return LinkHubInlineResult.Invalid
                if (envelope.v != LinkHubProtocol.VERSION) return LinkHubInlineResult.UnsupportedVersion
                if (envelope.type.isBlank()) return LinkHubInlineResult.Invalid
                LinkHubInlineResult.Ok(envelope)
            }

            // 首字节版本号已通过校验，说明是「本版本不认识的类型」
            else -> LinkHubInlineResult.UnsupportedType
        }
    }

    // --- 编码辅助 ---

    private fun typeByte(type: Int): Byte =
        ((LinkHubProtocol.VERSION shl 4) or (type and 0x0F)).toByte()

    private fun encodeUrlPayload(type: Int, url: String, requireHttps: Boolean): ByteArray? {
        val httpsOmitted = url.startsWith("https://")
        if (requireHttps && !httpsOmitted) return null
        if (!httpsOmitted && !url.startsWith("http://")) return null
        val rest = if (httpsOmitted) url.removePrefix("https://") else url
        val restBytes = rest.toByteArray(Charsets.UTF_8)
        if (restBytes.isEmpty() || restBytes.size > MAX_URL_BYTES) return null
        val flags: Byte = if (httpsOmitted) FLAG_HTTPS_OMITTED.toByte() else 0
        return byteArrayOf(typeByte(type), flags) + restBytes
    }

    private fun encodeProxy(payload: JsonObject): ByteArray? {
        val kind = when (payload.stringField("kind")?.trim()?.lowercase()) {
            "http" -> KIND_HTTP
            "socks5" -> KIND_SOCKS5
            else -> return null
        }
        val host = payload.stringField("host")?.trim().orEmpty()
        val hostBytes = host.toByteArray(Charsets.UTF_8)
        if (hostBytes.isEmpty() || hostBytes.size > MAX_HOST_BYTES) return null

        val port = payload.intField("port") ?: return null
        if (port !in 1..65535) return null

        val userBytes = payload.stringField("username").orEmpty().toByteArray(Charsets.UTF_8)
        val passBytes = payload.stringField("password").orEmpty().toByteArray(Charsets.UTF_8)
        if (userBytes.size > MAX_CREDENTIAL_BYTES || passBytes.size > MAX_CREDENTIAL_BYTES) return null

        val out = ByteArrayOutputStream()
        out.write(typeByte(TYPE_PROXY).toInt())
        out.write(kind)
        out.write((port shr 8) and 0xFF)
        out.write(port and 0xFF)
        out.write(hostBytes.size)
        out.write(hostBytes)
        out.write(userBytes.size)
        out.write(userBytes)
        out.write(passBytes.size)
        out.write(passBytes)
        return out.toByteArray()
    }

    private fun encodeCampusShower(payload: JsonObject): ByteArray? {
        return when (payload.stringField("system")?.trim()?.lowercase()) {
            "yktxyyy" -> {
                val posno = payload.stringField("posno")?.trim()?.toIntOrNull() ?: return null
                if (posno !in 1..MAX_POSNO) return null
                byteArrayOf(
                    typeByte(TYPE_CAMPUS_SHOWER),
                    SHOWER_SYSTEM_YKT.toByte(),
                    ((posno shr 16) and 0xFF).toByte(),
                    ((posno shr 8) and 0xFF).toByte(),
                    (posno and 0xFF).toByte()
                )
            }

            "life_service" -> {
                val imeiBytes = payload.stringField("imei")?.trim().orEmpty().toByteArray(Charsets.UTF_8)
                if (imeiBytes.isEmpty() || imeiBytes.size > MAX_DEVICE_NO_BYTES) return null
                val portText = payload.stringField("port")?.trim().orEmpty()
                val port = if (portText.isEmpty()) null else portText.toIntOrNull() ?: return null
                if (port != null && port !in 1..MAX_PORT) return null

                val out = ByteArrayOutputStream()
                out.write(typeByte(TYPE_CAMPUS_SHOWER).toInt())
                out.write(SHOWER_SYSTEM_LIFE)
                out.write(if (port == null) 0 else SHOWER_FLAG_HAS_PORT)
                if (port != null) {
                    out.write((port ushr 24) and 0xFF)
                    out.write((port ushr 16) and 0xFF)
                    out.write((port ushr 8) and 0xFF)
                    out.write(port and 0xFF)
                }
                out.write(imeiBytes.size)
                out.write(imeiBytes)
                out.toByteArray()
            }

            else -> null
        }
    }

    private fun decodeCampusShower(bytes: ByteArray): LinkHubInlineResult? {
        if (bytes.size < 3) return null
        return when (bytes[1].toInt() and 0xFF) {
            SHOWER_SYSTEM_YKT -> {
                if (bytes.size != 5) return null
                val posno = ((bytes[2].toInt() and 0xFF) shl 16) or
                    ((bytes[3].toInt() and 0xFF) shl 8) or
                    (bytes[4].toInt() and 0xFF)
                if (posno !in 1..MAX_POSNO) return null
                ok(
                    LinkHubType.CAMPUS_SHOWER,
                    buildJsonObject {
                        put("system", "yktxyyy")
                        put("posno", posno.toString())
                    }
                )
            }

            SHOWER_SYSTEM_LIFE -> {
                var index = 2
                val flags = bytes[index++].toInt() and 0xFF
                val hasPort = flags and SHOWER_FLAG_HAS_PORT != 0
                var port: Int? = null
                if (hasPort) {
                    if (index + 4 > bytes.size) return null
                    port = ((bytes[index].toInt() and 0xFF) shl 24) or
                        ((bytes[index + 1].toInt() and 0xFF) shl 16) or
                        ((bytes[index + 2].toInt() and 0xFF) shl 8) or
                        (bytes[index + 3].toInt() and 0xFF)
                    index += 4
                    if (port !in 1..MAX_PORT) return null
                }
                val (imei, next) = readSizedString(bytes, index) ?: return null
                // 严格：不允许尾随垃圾字节
                if (imei.isEmpty() || next != bytes.size) return null
                ok(
                    LinkHubType.CAMPUS_SHOWER,
                    buildJsonObject {
                        put("system", "life_service")
                        put("imei", imei)
                        if (port != null) put("port", port.toString())
                    }
                )
            }

            else -> null
        }
    }

    // --- 解码辅助 ---

    private fun ok(type: String, payload: JsonObject): LinkHubInlineResult =
        LinkHubInlineResult.Ok(LinkHubEnvelope(v = LinkHubProtocol.VERSION, type = type, payload = payload))

    private fun decodeUrlPayload(bytes: ByteArray, requireHttps: Boolean): String? {
        if (bytes.size < 2) return null
        val httpsOmitted = (bytes[1].toInt() and FLAG_HTTPS_OMITTED) != 0
        val rest = bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_8)
        if (rest.isEmpty()) return null
        val url = if (httpsOmitted) "https://$rest" else rest
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        if (requireHttps && !url.startsWith("https://")) return null
        return url
    }

    private fun decodeProxy(bytes: ByteArray): LinkHubInlineResult? {
        if (bytes.size < 5) return null
        var index = 1
        val kind = when (bytes[index++].toInt() and 0xFF) {
            KIND_HTTP -> "http"
            KIND_SOCKS5 -> "socks5"
            else -> return null
        }
        val port = ((bytes[index++].toInt() and 0xFF) shl 8) or (bytes[index++].toInt() and 0xFF)
        if (port !in 1..65535) return null

        val (host, afterHost) = readSizedString(bytes, index) ?: return null
        if (host.isEmpty()) return null
        val (user, afterUser) = readSizedString(bytes, afterHost) ?: return null
        val (pass, afterPass) = readSizedString(bytes, afterUser) ?: return null
        // 严格：不允许尾随垃圾字节
        if (afterPass != bytes.size) return null

        return ok(
            LinkHubType.PROXY,
            buildJsonObject {
                put("kind", kind)
                put("host", host)
                put("port", port)
                if (user.isNotEmpty()) put("username", user)
                if (pass.isNotEmpty()) put("password", pass)
            }
        )
    }

    /** 读取 1 字节长度前缀的 UTF-8 字符串，返回（值, 下一个索引）。 */
    private fun readSizedString(bytes: ByteArray, start: Int): Pair<String, Int>? {
        if (start >= bytes.size) return null
        val length = bytes[start].toInt() and 0xFF
        val from = start + 1
        val to = from + length
        if (to > bytes.size) return null
        return bytes.copyOfRange(from, to).toString(Charsets.UTF_8) to to
    }
}
