package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import android.util.Log
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 洗衣机入口解析。
 *
 * `/wm/{uuid}`（NFC 标签 / 网页深链）只带设备码，直接打开一卡通门户并不会自动进入洗衣机流程；
 * 这里复用扫码链路的同一套接口（登录态 → connect → 可用性检测 → 解析 App 启动地址），
 * 得到可直达的洗衣机 H5 地址。
 *
 * 语义与扫码链路保持一致：
 * - [Result.Ready]：拿到直达地址（可下单，或扫码校验被业务规则拒绝但可交由 H5 提示）；
 * - [Result.Unavailable]：服务端明确判定当前不可下单，按 [WasherUnavailableReason]
 *   给出准确原因（离线 / 被占用 / 故障 / 停用 / 码不存在），而不是一律说「设备离线」；
 * - [Result.Failed]：未登录 / 网络异常等，调用方回退到门户自举方案。
 */
object WasherEntryResolver {

    private const val TAG = "WasherEntryResolver"

    sealed interface Result {
        /** 可直接打开的洗衣机 H5 地址（已带上 scanResult）。 */
        data class Ready(val url: String) : Result

        /** 设备当前不可下单，[availability] 里带原因（与商家电话）。 */
        data class Unavailable(val availability: WasherAvailability.Unavailable) : Result

        /** 未登录、网络异常等，需要回退门户。 */
        data object Failed : Result
    }

    suspend fun resolve(context: Context, washerRaw: String, useVpn: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                val encodedRaw = URLEncoder.encode(washerRaw, "UTF-8")
                val cardClient = WbuCampusCardClient(context.applicationContext, useVpn)
                val ujingClient = WbuUjingClient(context.applicationContext, cardClient)
                val token = cardClient.ensureValidAccessToken()
                ujingClient.connect(token, appId = WbuUjingClient.WASHER_APP_ID)

                // 与扫码链路一致：扫码接口抛业务异常（如「不在营业时间」）不阻断直达，交给 H5 提示。
                // 只有服务端给出**明确判定**时才拦下来；异常/字段缺失不再默认「在线」。
                val scan = runCatching { ujingClient.scanWasherCode(washerRaw) }
                    .onFailure { Log.w(TAG, "scanWasherCode 被拒，仍按扫码链路兜底直达 H5", it) }
                    .getOrNull()
                val unavailable = scan?.availability as? WasherAvailability.Unavailable
                if (scan != null && unavailable != null) {
                    Log.i(TAG, "洗衣机不可下单: ${unavailable.reason} (status=${scan.status}, raw=${scan.rawData})")
                    return@runCatching Result.Unavailable(unavailable)
                }

                val launchUrl = cardClient.resolveAppLaunchUrl(WbuUjingClient.WASHER_APP_ID, token)
                Log.i(TAG, "resolveAppLaunchUrl=$launchUrl")
                if (launchUrl.isNullOrBlank()) return@runCatching Result.Failed
                val separator = if (launchUrl.contains("?")) "&" else "?"
                Result.Ready("$launchUrl${separator}scanResult=$encodedRaw")
            }.onFailure {
                Log.w(TAG, "解析洗衣机入口失败: $washerRaw", it)
            }.getOrDefault(Result.Failed)
        }
}
