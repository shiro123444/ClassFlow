package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * U净 贴纸与设备二维码解析。
 *
 * 常见形式（基于实测研究笔记 ykt_notes.md 第 10.6 节）：
 * 1. 饮水机：`http://q.ujing.com.cn/ed/index.html?cd=<设备码>`
 * 2. 吹风机：`http://q.ujing.com.cn/6d/index.html?cd=<设备码>`
 * 3. 洗衣机/烘干机：`http://app.littleswan.com/u_download.html?type=Ujing&uuid=<设备码>`
 */
object UjingQrLink {

    sealed interface Result {
        val raw: String

        /** 饮水机（ed），带设备码 cd。 */
        data class Water(val cd: String, override val raw: String) : Result

        /** 吹风机（6d），带设备码 cd。注意：一卡通平台暂不支持此设备类型。 */
        data class Hairdryer(val cd: String, override val raw: String) : Result

        /** 洗衣机/烘干机（小天鹅），带 uuid。 */
        data class Washer(val uuid: String, override val raw: String) : Result
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

            // 2. NFC / 校园直达格式: /w/{cd} (饮水机) 与 /wm/{uuid} (洗衣机)
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

            // 3. 小天鹅洗衣机下载 / 设备二维码
            if (host.contains("littleswan.com") || host.contains("ujing")) {
                val uuid = url.queryParameter("uuid")?.trim().orEmpty()
                if (uuid.isNotBlank()) {
                    return Result.Washer(uuid = uuid, raw = trimmed)
                }
            }
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
        if (trimmed.contains("uuid=") && (trimmed.contains("littleswan") || trimmed.contains("Ujing"))) {
            val uuid = extractParam(trimmed, "uuid")
            if (uuid.isNotBlank()) return Result.Washer(uuid = uuid, raw = trimmed)
        }

        return null
    }

    private fun extractParam(raw: String, param: String): String {
        val regex = Regex("[?&]${Regex.escape(param)}=([^&#]+)")
        val match = regex.find(raw) ?: return ""
        return runCatching { java.net.URLDecoder.decode(match.groupValues[1], "UTF-8") }.getOrDefault(match.groupValues[1])
    }
}
