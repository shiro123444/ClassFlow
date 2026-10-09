package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerCodeFormat

/**
 * 吹风机设备码的**印刷格式**判据。
 *
 * 这是纯本地的第一层判据：不联网、不耗平台票据、瞬时出结果，用来把绝大多数扫码
 * 直接分流（蓝牙 → 支付宝，云端 → 一卡通页面），省掉一次「扫码 + 探测」往返。
 *
 * 实测（2026-10-08，武汉商学院）：
 * | 印刷体 | 样例 | `moduleType` | 实际类型 |
 * | --- | --- | --- | --- |
 * | 16 位纯数字 | `0014202206120446` | 1 | 手机蓝牙 |
 * | 32 位十六进制 | `00006D11488800030263110002870000` | 7 | 云端（控制盒 / 4G） |
 *
 * 判据只是「猜测」：用户可以按格式一次性否掉（见 `HairdryerHubRepository.setFormatVerdict`），
 * 否掉之后这类码一律改走接口探测。所以判错只影响一次体验，不会把人堵死。
 */
object UjingHairdryerCode {

    /** 16 位纯数字（老校区蓝牙机的印刷体）。 */
    private val LEGACY_16 = Regex("^\\d{16}$")

    /** 32 位十六进制（云端 / 4G 机的印刷体，实测为大写）。 */
    private val CLOUD_32 = Regex("^[0-9A-Fa-f]{32}$")

    /**
     * 判断设备码 [cd] 的印刷格式。
     *
     * 只认「整串就是设备码」的情形：`cd` 里带了别的字符（例如旧版带前缀的编码）
     * 一律算 [HairdryerCodeFormat.UNKNOWN]，交给接口探测。
     */
    fun formatOf(cd: String): HairdryerCodeFormat {
        val value = cd.trim()
        return when {
            LEGACY_16.matches(value) -> HairdryerCodeFormat.LEGACY_16
            CLOUD_32.matches(value) -> HairdryerCodeFormat.CLOUD_32
            else -> HairdryerCodeFormat.UNKNOWN
        }
    }
}
