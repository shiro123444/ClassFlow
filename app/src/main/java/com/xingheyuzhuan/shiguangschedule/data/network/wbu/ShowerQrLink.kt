package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import java.net.URLDecoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 马影河校区宿舍洗浴控水设备二维码解析（全局扫码分流用）。
 *
 * 两套洗浴系统（均在一卡通「洗浴」入口下）：
 * 1. 马影河 2-3 栋：新中新 lifeService（水表 51 厂商）。
 *    实测解析规则（`applications/js/chunk-b88c7b1e.f1981e59.js` 的 `handleImei`）：
 *    - 原文含 `$#$`：`imei$#$port`，首段可能是 URL 编码的整条厂商链接；
 *    - 否则按 URL 取 `?id=` 作为 imei。
 *    页面手输入口生成的原文为 `http://4gsk.shuibiao51.com?id=<imei>`。
 * 2. 马影河 1 栋：独立控水「智能控水」（yktxyyy，TjtcApp 系）。
 *    实测解析规则（`js/chunk-7726.6ba84dda.js` 的 `getPosNo`）：裸码第 8~12 位（0 基）
 *    为 5 位机号，页面按 `Number(substr(8, 5))` 使用（前导零会被 Number 去掉）。
 *    裸码仅凭格式无法与商品条码（EAN-13）等区分，是否为本校设备由
 *    [WbuYktXyyyClient.checkPosNo] 服务端校验裁决，本解析器只负责候选提取。
 */
object ShowerQrLink {

    /** 平台为「马2-3栋洗浴」分配的 appId（lifeService 实例）。 */
    const val LIFE_SERVICE_APP_ID = 65

    /** 水表 51 厂商域名。 */
    private const val WATER_METER_HOST = "shuibiao51.com"

    /** 分隔符：`imei$#$port`（多路控水器）。 */
    private const val IMEI_PORT_SEPARATOR = "\$#\$"

    sealed interface Result {
        val raw: String

        /** 马影河 2-3 栋：lifeService（水表 51）。 */
        data class LifeService(
            val imei: String,
            val port: String?,
            override val raw: String
        ) : Result

        /** 马影河 1 栋候选：控水器裸码（机号已按页面规则归一）。 */
        data class YktXyyy(
            val posno: String,
            override val raw: String
        ) : Result
    }

    /**
     * 1 栋 H5 的 `window.scanCallback` 只认 `substr(8,5)`：
     * 服务端节点一般只存 5 位机号，这里生成一段与真实裸码等价的合成原文，
     * 交给 WebAppScreen 的自动执行脚本使用。
     */
    fun syntheticYktRaw(posno: String): String = "CFSHOWER" + posno.trim().padStart(5, '0')

    /**
     * 解析扫码原文；不是洗浴控水设备码时返回 null。
     *
     * 注意：URL 一律只认水表 51 厂商链接，避免把普通网址误当裸码取子串。
     */
    fun parse(raw: String): Result? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // 1. 2-3 栋：imei$#$port（首段可能被 URL 编码）
        if (trimmed.contains(IMEI_PORT_SEPARATOR)) {
            val parts = trimmed.split(IMEI_PORT_SEPARATOR)
            val first = parts.firstOrNull()?.trim().orEmpty()
            if (first.isNotBlank()) {
                val imei = runCatching { URLDecoder.decode(first, "UTF-8") }.getOrDefault(first)
                if (imei.isNotBlank()) {
                    return Result.LifeService(
                        imei = imei,
                        port = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() },
                        raw = trimmed
                    )
                }
            }
        }

        // 2. 2-3 栋：水表 51 链接（?id=<imei>）
        trimmed.toHttpUrlOrNull()?.let { url ->
            val host = url.host.lowercase()
            if (host == WATER_METER_HOST || host.endsWith(".$WATER_METER_HOST")) {
                val id = url.queryParameter("id")?.trim().orEmpty()
                if (id.isNotBlank()) {
                    return Result.LifeService(imei = id, port = null, raw = trimmed)
                }
            }
            // 普通 URL（含 U净/一卡通/Hub 链接）不按裸码取子串
            return null
        }

        // 3. 1 栋候选：非 URL、长度 ≥13、第 8~12 位为 5 位机号（复刻页面 getPosNo）
        if (trimmed.length >= 13) {
            val digits = trimmed.substring(8, 13)
            if (digits.all { it.isDigit() }) {
                val posno = digits.toIntOrNull()
                if (posno != null && posno > 0) {
                    return Result.YktXyyy(posno = posno.toString(), raw = trimmed)
                }
            }
        }

        return null
    }
}
