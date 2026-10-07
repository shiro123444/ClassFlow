package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import java.net.URLDecoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 马影河校区宿舍洗浴控水设备二维码解析（全局扫码分流用）。
 *
 * 两套洗浴系统（均在一卡通「洗浴」入口下）：
 * 1. 马影河 2-3 栋：新中新 lifeService（水表 51 厂商）。
 *    实测解析规则（`applications/js/chunk-b88c7b1e.f1981e59.js` 的 `handleImei`）：
 *    - 原文含 `$#$`：`imei$#$port`，首段被**原样**当作 imei（不再按 URL 解析）；
 *    - 否则按 URL 取 `?id=` 作为 imei（`getRequest(原文).id`，只看查询参数、不看域名）。
 *    页面手输入口生成的原文为 `http://4gsk.shuibiao51.com?id=<imei>`；
 *    学校自己印的贴纸用的是学校的洗浴入口主机（3 栋实测
 *    `http://yktxyyy1.wbu.edu.cn:50040?id=17010737`，见 [isShowerLinkHost]），
 *    与厂商域名一样只是 `?id=` 的载体，原文原样交给 lifeService 页面即可。
 * 2. 马影河 1 栋：独立控水「智能控水」（yktxyyy，TjtcApp 系）。
 *    实测解析规则（`js/chunk-7726.6ba84dda.js` 的 `getPosNo`）：裸码第 8~12 位（0 基）
 *    为 5 位机号，页面按 `Number(substr(8, 5))` 使用（前导零会被 Number 去掉）；
 *    该页面**只认裸码子串、完全不解析 URL**，所以带 `?id=` 的网址一律归 lifeService。
 *    裸码仅凭格式无法与商品条码（EAN-13）等区分，是否为本校设备由
 *    [WbuYktXyyyClient.checkPosNo] 服务端校验裁决，本解析器只负责候选提取。
 */
object ShowerQrLink {

    /** 平台为「马2-3栋洗浴」分配的 appId（lifeService 实例）。 */
    const val LIFE_SERVICE_APP_ID = 65

    /** 水表 51 厂商域名。 */
    private const val WATER_METER_HOST = "shuibiao51.com"

    /** 学校自己的洗浴入口主机（马影河 3 栋贴纸实测，`?id=` 的另一种载体）。 */
    private const val SCHOOL_SHOWER_HOST = "yktxyyy1.wbu.edu.cn"

    /** 分隔符：`imei$#$port`（多路控水器）；解析与生成（[CampusShowerEntryResolver]）两侧共用。 */
    const val IMEI_PORT_SEPARATOR = "\$#\$"

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
     * 注意：URL 只认洗浴码载体域名（见 [isShowerLinkHost]）上的 `?id=` 链接，
     * 其余网址**一律不按裸码取子串**，避免把普通网址误当控水器机号。
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

        // 2. 2-3 栋：洗浴链接（?id=<imei>）
        trimmed.toHttpUrlOrNull()?.let { url ->
            if (isShowerLinkHost(url.host)) {
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

    /**
     * 洗浴码载体域名，只放行两类：
     * - 水表 51 厂商域（`shuibiao51.com` 及其子域）；
     * - 学校自己的洗浴入口主机 [SCHOOL_SHOWER_HOST]（3 栋贴纸实测，
     *   `http://yktxyyy1.wbu.edu.cn:50040?id=17010737`，与厂商域是同一套 `?id=` 载体）。
     *
     * 为什么不放宽成「任何带 `?id=` 的网址」：lifeService 页面取 `?id=` 时确实不看域名，
     * 但**扫码分流必须看** —— 否则一卡通平台页、CAS、通用链接节点等任何带 `id=` 的校园链接
     * 都会被当成洗浴设备（分流顺序里洗浴还在通用链接节点之前）。
     */
    private fun isShowerLinkHost(host: String): Boolean {
        val lower = host.lowercase()
        return lower == WATER_METER_HOST ||
            lower.endsWith(".$WATER_METER_HOST") ||
            lower == SCHOOL_SHOWER_HOST
    }
}
