package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import com.xingheyuzhuan.shiguangschedule.Destination
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 校园 NFC 标签触碰、网页链接与外部 Intent 的集中路由分发器。
 *
 * 规范（方案 A）：
 * - `/w/{cd}` -> 饮水机原生出水页面 `Destination.UjingWater(cd)`
 * - `/wm/{uuid}` -> 洗衣机 H5 页面 `Destination.WebApp`
 */
object CampusLinkRouter {

    /**
     * 解析任意 String 或 Uri，若符合校园直达规范，返回目标 Destination。
     */
    fun parse(uriString: String, scanId: Long = 0L): Destination? {
        val trimmed = uriString.trim()
        val okhttpUrl = trimmed.toHttpUrlOrNull()
        val path = okhttpUrl?.encodedPath ?: run {
            val qIdx = trimmed.indexOfAny(charArrayOf('?', '#'))
            val noQuery = if (qIdx >= 0) trimmed.substring(0, qIdx) else trimmed
            val schemeIdx = noQuery.indexOf("://")
            val afterScheme = if (schemeIdx >= 0) noQuery.substring(schemeIdx + 3) else noQuery
            val slashIdx = afterScheme.indexOf('/')
            if (slashIdx >= 0) afterScheme.substring(slashIdx) else "/"
        }

        // 1. 饮水机直达: /w/{cd}
        val waterMatch = Regex("^/w/([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE).find(path)
        if (waterMatch != null) {
            val cd = waterMatch.groupValues[1]
            if (cd.isNotBlank()) {
                return Destination.UjingWater(cd = cd, scanId = scanId)
            }
        }

        // 2. 洗衣机直达: /wm/{uuid}
        val washerMatch = Regex("^/wm/([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE).find(path)
        if (washerMatch != null) {
            val uuid = washerMatch.groupValues[1]
            if (uuid.isNotBlank()) {
                val washerRaw = "http://app.littleswan.com/u_download.html?type=Ujing&uuid=$uuid"
                return Destination.WebApp(
                    appId = com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppId.CAMPUS_CARD.name,
                    pendingAutoScan = washerRaw
                )
            }
        }

        // 3. 通用 U 净/小天鹅链接兜底解析
        val ujingRes = UjingQrLink.parse(trimmed)
        if (ujingRes is UjingQrLink.Result.Water) {
            return Destination.UjingWater(cd = ujingRes.cd, scanId = scanId)
        }
        if (ujingRes is UjingQrLink.Result.Washer) {
            return Destination.WebApp(
                appId = com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppId.CAMPUS_CARD.name,
                pendingAutoScan = ujingRes.raw
            )
        }

        return null
    }

    /**
     * 解析任意 Uri，若符合校园直达规范，返回目标 Destination。
     */
    fun parse(uri: Uri, scanId: Long = 0L): Destination? = parse(uri.toString(), scanId)

    /**
     * 从接收到的 Intent 中安全提取 URI 并路由。
     * 支持标准 data、NdefMessage URI 记录。
     */
    fun extractDestination(intent: Intent?): Destination? {
        if (intent == null) return null
        val scanId = System.currentTimeMillis()

        // 1. 优先使用 intent.data
        intent.data?.let { uri ->
            parse(uri, scanId)?.let { return it }
        }

        // 2. NFC NDEF_DISCOVERED 时解析 NdefMessage
        @Suppress("DEPRECATION")
        val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        if (rawMessages != null) {
            for (raw in rawMessages) {
                val msg = raw as? NdefMessage ?: continue
                for (record in msg.records) {
                    val uri = record.toUri() ?: continue
                    parse(uri, scanId)?.let { return it }
                }
            }
        }

        return null
    }
}
