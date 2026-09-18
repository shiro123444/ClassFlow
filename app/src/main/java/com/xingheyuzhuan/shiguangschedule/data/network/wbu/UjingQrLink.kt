package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import java.net.URLDecoder
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * U净 贴纸与设备二维码解析。
 *
 * 常见形式（基于实测研究笔记 ykt_notes.md 第 10.6 节）：
 * 1. 饮水机：`http://q.ujing.com.cn/ed/index.html?cd=<设备码>`
 * 2. 吹风机：`http://q.ujing.com.cn/6d/index.html?cd=<设备码>`
 *    或支付宝直达：`https://render.alipay.com/p/s/ulink/sn?s=dc&scheme=alipay%3a%2f%2fnfc%2fapp%3fid%3d10000007...`
 * 3. 洗衣机/烘干机：`http://app.littleswan.com/u_download.html?type=Ujing&uuid=<设备码>`
 */
object UjingQrLink {

    /** 支付宝官方客户端包名 */
    const val ALIPAY_PACKAGE_NAME = "com.eg.android.AlipayGphone"

    sealed interface Result {
        val raw: String

        /** 饮水机（ed），带设备码 cd。 */
        data class Water(val cd: String, override val raw: String) : Result

        /** 吹风机（6d），带设备码 cd。可使用支付宝直达打开。 */
        data class Hairdryer(val cd: String, override val raw: String) : Result

        /** 洗衣机/烘干机（小天鹅），带 uuid。 */
        data class Washer(val uuid: String, override val raw: String) : Result
    }

    /**
     * 生成吹风机设备的支付宝内部 Scheme 链接（alipays 协议头，用于应用间直接调起）。
     */
    fun buildHairdryerAlipayScheme(cd: String): String {
        val rawUrl = "http://q.ujing.com.cn/6d/index.html?cd=$cd"
        val encodedRaw = runCatching { URLEncoder.encode(rawUrl, "UTF-8") }.getOrDefault(rawUrl)
        return "alipays://platformapi/startapp?appId=10000007&actionType=route&codeContent=$encodedRaw"
    }

    /**
     * 生成吹风机设备 NFC 标签写入的 NFC Scheme（供 NFC 触碰广播使用）。
     */
    fun buildHairdryerNfcScheme(cd: String): String {
        val rawUrl = "http://q.ujing.com.cn/6d/index.html?cd=$cd"
        val encodedRaw = runCatching { URLEncoder.encode(rawUrl, "UTF-8") }.getOrDefault(rawUrl)
        return "alipay://nfc/app?id=10000007&actionType=route&codeContent=$encodedRaw"
    }

    /**
     * 生成吹风机设备的支付宝 Universal Link（对外网页链接，未安装支付宝或通过外部打开时可自动拉起应用）。
     */
    fun buildHairdryerAlipayUrl(cd: String): String {
        val nfcScheme = buildHairdryerNfcScheme(cd)
        val encodedScheme = runCatching { URLEncoder.encode(nfcScheme, "UTF-8") }.getOrDefault(nfcScheme)
        return "https://render.alipay.com/p/s/ulink/sn?s=dc&scheme=$encodedScheme"
    }

    /**
     * 解析扫码得到的原文；如果不是 U净 设备码，返回 null。
     */
    fun parse(raw: String): Result? {
        val trimmed = raw.trim()
        val url = trimmed.toHttpUrlOrNull()

        if (url != null) {
            val host = url.host.lowercase()
            val rawPath = url.encodedPath
            val pathLower = rawPath.lowercase()

            // 1. q.ujing.com.cn / 类似域名的 ed / 6d 贴纸
            if (host.contains("ujing")) {
                val cd = url.queryParameter("cd")?.trim().orEmpty()
                if (pathLower.contains("/ed/") && cd.isNotBlank()) {
                    return Result.Water(cd = cd, raw = trimmed)
                }
                if (pathLower.contains("/6d/") && cd.isNotBlank()) {
                    return Result.Hairdryer(cd = cd, raw = trimmed)
                }
            }

            // 2. 支付宝包装链接 (render.alipay.com / alipay scheme)
            if (host.contains("alipay.com")) {
                val scheme = url.queryParameter("scheme")
                if (!scheme.isNullOrBlank()) {
                    val nestedResult = parseAlipayScheme(scheme, trimmed)
                    if (nestedResult != null) return nestedResult
                }
            }

            // 3. NFC / 校园直达格式: /w/{cd} (饮水机) 与 /wm/{uuid} (洗衣机)
            val waterPathMatch = Regex("^/w/([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE).find(rawPath)
            if (waterPathMatch != null) {
                val cd = waterPathMatch.groupValues[1]
                if (cd.isNotBlank()) return Result.Water(cd = cd, raw = trimmed)
            }
            val washerPathMatch = Regex("^/wm/([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE).find(rawPath)
            if (washerPathMatch != null) {
                val uuid = washerPathMatch.groupValues[1]
                if (uuid.isNotBlank()) {
                    val rawUrl = "http://app.littleswan.com/u_download.html?type=Ujing&uuid=$uuid"
                    return Result.Washer(uuid = uuid, raw = rawUrl)
                }
            }

            // 4. 小天鹅洗衣机下载 / 设备二维码
            if (host.contains("littleswan.com") || host.contains("ujing")) {
                val uuid = url.queryParameter("uuid")?.trim().orEmpty()
                if (uuid.isNotBlank()) {
                    return Result.Washer(uuid = uuid, raw = trimmed)
                }
            }
        }

        // 5. 直接传 alipay:// 或 alipays:// 开头的 Scheme
        if (trimmed.startsWith("alipay://", ignoreCase = true) || trimmed.startsWith("alipays://", ignoreCase = true)) {
            val nestedResult = parseAlipayScheme(trimmed, trimmed)
            if (nestedResult != null) return nestedResult
        }

        // 兜底正则提取：部分二维码可能经过特殊包装
        if (trimmed.contains("/ed/") && trimmed.contains("cd=")) {
            val cd = extractParam(trimmed, "cd")
            if (cd.isNotBlank()) return Result.Water(cd = cd, raw = trimmed)
        }
        if (trimmed.contains("/6d/") && trimmed.contains("cd=")) {
            val cd = extractParam(trimmed, "cd")
            if (cd.isNotBlank()) return Result.Hairdryer(cd = cd, raw = trimmed)
        }
        // 嵌套 URL Decode 兜底（处理经多层转义的吹风机链接）
        val decodedOnce = runCatching { URLDecoder.decode(trimmed, "UTF-8") }.getOrNull()
        if (decodedOnce != null && decodedOnce != trimmed) {
            val decodedTwice = runCatching { URLDecoder.decode(decodedOnce, "UTF-8") }.getOrDefault(decodedOnce)
            if (decodedTwice.contains("/6d/") && decodedTwice.contains("cd=")) {
                val cd = extractParam(decodedTwice, "cd")
                if (cd.isNotBlank()) return Result.Hairdryer(cd = cd, raw = trimmed)
            }
            if (decodedTwice.contains("/ed/") && decodedTwice.contains("cd=")) {
                val cd = extractParam(decodedTwice, "cd")
                if (cd.isNotBlank()) return Result.Water(cd = cd, raw = trimmed)
            }
        }
        if (trimmed.contains("uuid=") && (trimmed.contains("littleswan") || trimmed.contains("Ujing"))) {
            val uuid = extractParam(trimmed, "uuid")
            if (uuid.isNotBlank()) return Result.Washer(uuid = uuid, raw = trimmed)
        }

        return null
    }

    private fun parseAlipayScheme(scheme: String, originalRaw: String): Result? {
        val codeContent = extractParam(scheme, "codeContent")
        if (codeContent.isNotBlank()) {
            val parsedNested = parse(codeContent)
            if (parsedNested != null) {
                return when (parsedNested) {
                    is Result.Hairdryer -> Result.Hairdryer(cd = parsedNested.cd, raw = originalRaw)
                    is Result.Water -> Result.Water(cd = parsedNested.cd, raw = originalRaw)
                    is Result.Washer -> Result.Washer(uuid = parsedNested.uuid, raw = originalRaw)
                }
            }
        }
        return null
    }

    private fun extractParam(raw: String, param: String): String {
        val regex = Regex("[?&]${Regex.escape(param)}=([^&#]+)")
        val match = regex.find(raw) ?: return ""
        return runCatching { URLDecoder.decode(match.groupValues[1], "UTF-8") }.getOrDefault(match.groupValues[1])
    }
}
