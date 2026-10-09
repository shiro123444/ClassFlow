package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import android.util.Log
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerDeviceType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * 吹风机**设备信息探测**：把「这台吹风机是手机蓝牙还是云端（控制盒 / 4G）」这件事问到底。
 *
 * 判据来自一卡通「自助吹风」页面自己的分支（`hairdryer-h5` 的 `choseDevice`）：
 *
 * ```
 * POST {UJING_API}/wechat_work/controlBox/devices/scan   {qrCode}    → deviceId
 * GET  {UJING_API}/wechat_work/controlBox/devices/{id}/info          → moduleType / subDevice / macAddress
 * GET  {UJING_API}/wechat_work/controlBox/devices/{sub}/model        → store.storeName / serviceSubjectName
 * ```
 *
 * 页面里 `moduleType !== 7` 一律弹「暂不支持蓝牙设备」（企业微信 / 一卡通容器没有蓝牙能力），
 * 只有 `7` 才继续 `controlBox/orders/create` 下单 —— 也就是说 **7 = 云端（4G），其余 = 蓝牙**。
 * 实测本校区 2026-10-08：`cd=0014202206120446` → `moduleType=1`，在那个页面点左右机确实弹
 * 「暂不支持蓝牙设备」；所以这台必须交回支付宝。
 *
 * 探测是「尽力而为」：任何一步失败都返回 [Outcome.Failed]，由调用方按回退链决定怎么走
 * （见 [UjingHairdryerEntryResolver]），绝不因为探测本身把用户堵死。
 */
@Singleton
class UjingHairdryerProbe @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 一次探测拿到的全部设备信息（也是记录列表要展示的字段）。 */
    data class Snapshot(
        /** 控制盒 ID（页面路由 `#/deviceSelector?deviceId=` 用的就是它）。 */
        val deviceId: String,
        /** 控制盒上第一台可用子机（左机优先）的 ID，用来查店铺名。 */
        val subDeviceId: String?,
        val moduleType: Int,
        /** 控制盒类型名（实测「电吹风」）。 */
        val hubTypeName: String?,
        /** 控制盒编号（实测 33301）。 */
        val deviceNo: String?,
        val macAddress: String?,
        /** 店铺名（如「南B-11」）。 */
        val storeName: String?,
        /** 服务主体名（如「武汉商学院26-本部」）。 */
        val subjectName: String?,
        /** 带一次性 ticket 的平台启动地址（不含页面路由）。 */
        val launchUrl: String?,
    ) {
        val type: HairdryerDeviceType
            get() = if (isCloud) HairdryerDeviceType.CLOUD else HairdryerDeviceType.BLUETOOTH

        val isCloud: Boolean
            get() = moduleType == WbuUjingClient.HAIRDRYER_MODULE_TYPE_CLOUD
    }

    sealed interface Outcome {
        data class Ok(val snapshot: Snapshot) : Outcome

        /** 探测没得出结论（未登录 / 网络 / 码没绑控制盒 / 左右机都不可用…）。 */
        data class Failed(val reason: String?) : Outcome
    }

    /**
     * 探测扫码原文对应的设备。
     *
     * @param raw 扫码原文（必须逐字回传：换成别的写法服务端会答「当前设备未绑定。」）。
     */
    suspend fun probe(raw: String): Outcome = withContext(Dispatchers.IO) {
        try {
            val cardClient = WbuCampusCardClient(context, useVpn = false)
            val token = cardClient.ensureValidAccessToken()
            val ujingClient = WbuUjingClient(context, cardClient)
            ujingClient.connect(token, appId = WbuUjingClient.HAIRDRYER_APP_ID)

            // 换页面启动地址与「扫码 → 设备信息」互不依赖，并发跑，省掉一整个往返
            val launchUrlJob = async(Dispatchers.IO) {
                cardClient.resolveAppLaunchUrl(WbuUjingClient.HAIRDRYER_APP_ID, token)
            }

            val scan = ujingClient.scanHairdryerCode(raw)
            val deviceId = scan.deviceId
            if (deviceId.isNullOrBlank()) {
                // 码没绑到控制盒（或左右机都被占满）：本就不是一卡通页面能处理的设备
                Log.i(TAG, "未解析到控制盒设备：status=${scan.status} reason=${scan.reason}")
                launchUrlJob.cancel()
                return@withContext Outcome.Failed(scan.reason)
            }

            val hub = ujingClient.getHairdryerHubInfo(deviceId)

            // 店铺名 / 服务主体名挂在子机上（页面 placeOrder 那一步才查），这里顺手取第一台可用子机
            var storeName: String? = null
            var subjectName: String? = null
            val subDeviceId = hub.availableSubDeviceId
            if (subDeviceId != null) {
                runCatching { ujingClient.getHairdryerModel(subDeviceId) }
                    .onSuccess { model ->
                        storeName = model.storeName
                        subjectName = model.subjectName
                    }
                    .onFailure { Log.d(TAG, "子机 $subDeviceId 的店铺信息未取到：${it.message}") }
            }

            val launchUrl = launchUrlJob.await()

            Log.i(
                TAG,
                "探测完成：deviceId=$deviceId moduleType=${hub.moduleType} " +
                    "hub=${hub.hubDeviceTypeName} no=${hub.no} store=$storeName/$subjectName " +
                    "→ ${if (hub.isCloud) "云端(4G)" else "蓝牙"}"
            )

            Outcome.Ok(
                Snapshot(
                    deviceId = deviceId,
                    subDeviceId = subDeviceId,
                    moduleType = hub.moduleType,
                    hubTypeName = hub.hubDeviceTypeName,
                    deviceNo = hub.no,
                    macAddress = hub.macAddress,
                    storeName = storeName,
                    subjectName = subjectName,
                    launchUrl = launchUrl
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "吹风机探测失败：$raw", e)
            Outcome.Failed(e.localizedMessage)
        }
    }

    /**
     * 只换平台启动地址、不扫码。
     *
     * 用途：强制「4G」模式下探测失败（码旧了、控制盒解绑了…）时，至少把一卡通那个
     * 「自助吹风」页面本身打开 —— 用户还能在页面里自己扫，而不是被推去支付宝。
     */
    suspend fun requestLaunchUrl(): String? = withContext(Dispatchers.IO) {
        try {
            val cardClient = WbuCampusCardClient(context, useVpn = false)
            val token = cardClient.ensureValidAccessToken()
            cardClient.resolveAppLaunchUrl(WbuUjingClient.HAIRDRYER_APP_ID, token)
        } catch (e: Exception) {
            Log.w(TAG, "吹风机页面启动地址换取失败", e)
            null
        }
    }

    companion object {
        private const val TAG = "UjingHairdryerProbe"
    }
}
