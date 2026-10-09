package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.util.Log
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerCodeFormat
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerDetectionMode
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerDeviceType
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerTypeSource
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 吹风机入口分流：这台吹风机该**跳一卡通「自助吹风」页面**（云端 / 控制盒 / 4G），
 * 还是**跳支付宝 U净 小程序**（手机蓝牙）？
 *
 * ## 两层判据，先本地后接口
 *
 * 1. **码的印刷格式**（[UjingHairdryerCode]，零成本）：16 位纯数字 = 蓝牙、
 *    32 位十六进制 = 云端 —— 实测本校区完全吻合。
 * 2. **设备信息探测**（[UjingHairdryerProbe]）：问 U净 要 `moduleType`，`7` 才是云端。
 *
 * 默认策略 [HairdryerDetectionMode.AUTO] 是「格式优先、探测兜底」，但**格式判据第一次遇到某种
 * 格式时要先让用户确认一次**（[Result.Confirm]）—— 判据分不清南北校区、也挡不住学校以后换码，
 * 与其默默判错，不如问一句「这台是不是蓝牙」，并把「以后去哪改」一并说清。用户答「不正确」
 * 等于「这种格式从此不再用本地判据」，之后一律走接口。
 *
 * ## 探测失败绝不堵路
 *
 * 回退链：**探测 → 格式 → 支付宝**。任何一环失败都继续往下退，最后一定落在改造前的行为
 * （支付宝 U净 页面）上；强制「4G」模式下探测失败时至少把一卡通页面本身打开，用户还能在
 * 页面里自己扫。
 */
@Singleton
class UjingHairdryerEntryResolver @Inject constructor(
    private val probe: UjingHairdryerProbe
) {

    /**
     * 本次分流要用的策略快照（由调用方从设置里读出，避免解析层再去依赖存储）。
     *
     * @param mode 判型模式（设置页可改）。
     * @param formatTrusted 这种码格式是否已被用户确认过「判据正确」；null = 从没问过。
     * @param formatRejected 这种码格式是否已被用户否掉（否掉后不再使用本地判据）。
     * @param knownType 本地记录里这台机器**已被证实**的类型（探测结论或用户手动指定）。
     *   有它时优先于码格式 —— 记录是用户自己见过的事实，比印刷规律可信。
     */
    data class Policy(
        val mode: HairdryerDetectionMode,
        val formatTrusted: Boolean? = null,
        val formatRejected: Boolean = false,
        val knownType: HairdryerDeviceType? = null,
    ) {
        companion object {
            /** 首次遇到、还没有任何用户裁决时的默认策略。 */
            fun of(mode: HairdryerDetectionMode) = Policy(mode = mode)
        }
    }

    sealed interface Result {

        /** 云端（控制盒 / 4G）：一卡通「自助吹风」页面自己就能下单。 */
        data class CampusCard(
            /** 带平台票据的页面**启动**地址（不含 `#` 路由）。 */
            val initialUrl: String,
            val source: HairdryerTypeSource,
            /**
             * 页面把票据换成会话**之后**要落到的路由（`#/deviceSelector?deviceId=…`）。
             *
             * 不能直接把路由拼进启动地址：`deviceSelector` 是 `requireAuth` 路由，冷启动时
             * 页面的路由守卫看不到令牌（授权流程只在 `/`「授权中」那一步发生），会直接跳到
             * `/login`；所以必须让页面先自己授权（落到 `/home`），再切到设备页。
             * null = 只把页面本身打开（探测失败时的兜底，用户还得自己扫）。
             */
            val hashRoute: String? = null,
            val snapshot: UjingHairdryerProbe.Snapshot? = null,
        ) : Result

        /** 手机蓝牙：只有支付宝 / U净 App 这类能开蓝牙的容器能用。 */
        data class Bluetooth(
            val source: HairdryerTypeSource,
            val snapshot: UjingHairdryerProbe.Snapshot? = null,
            /** 排查用说明（不面向用户）。 */
            val reason: String? = null,
        ) : Result

        /**
         * 首次遇到这种码格式：[guess] 是本地判据的猜测，先请用户确认。
         *
         * 用户答「正确」→ 记住这种格式可信（下次直接按格式分流，不再问）；
         * 答「不正确」→ 记住这种格式不可信，之后一律走设备信息探测。
         */
        data class Confirm(
            val format: HairdryerCodeFormat,
            val guess: HairdryerDeviceType,
        ) : Result
    }

    /**
     * **不联网、不问用户**就能下的结论：这台机器按本地信息（模式 / 用户裁决 / 历史记录）
     * 已经能确定是手机蓝牙机 —— 调用方应当直接走原体验（品牌过场 + 调起支付宝），
     * 连过渡页都不用进。
     *
     * null = 必须交给过渡页：需要探测（云端 / 未知格式），或这种码格式还没被用户确认过。
     *
     * 与 [resolve] 共享同一份判据（[resolve] 开头就调它），所以两条路永远不会打架。
     */
    fun localOnly(cd: String, policy: Policy): Result.Bluetooth? {
        // 显式「固定蓝牙」：不问格式、不联网
        if (policy.mode == HairdryerDetectionMode.BLUETOOTH) {
            return Result.Bluetooth(source = HairdryerTypeSource.MANUAL)
        }
        // 强制 4G / 仅设备信息：一个要票据、一个要真探测，本地给不出结论
        if (policy.mode != HairdryerDetectionMode.AUTO) return null
        // 本地记录已经证实是蓝牙机：格式判据都不用看
        if (policy.knownType == HairdryerDeviceType.BLUETOOTH) {
            return Result.Bluetooth(source = HairdryerTypeSource.MANUAL)
        }
        // 记录说是云端：换票只能联网
        if (policy.knownType == HairdryerDeviceType.CLOUD) return null
        if (policy.formatRejected) return null

        val guess = UjingHairdryerCode.formatOf(cd).guess ?: return null
        // 这种码格式还没问过用户：先确认（过渡页里有弹窗）
        if (policy.formatTrusted != true) return null
        return if (guess == HairdryerDeviceType.BLUETOOTH) {
            Result.Bluetooth(source = HairdryerTypeSource.FORMAT)
        } else null
    }

    /**
     * 解析扫码原文，给出跳转目标。
     *
     * @param raw 扫码原文（逐字回传：换写法服务端会答「当前设备未绑定。」）。
     * @param cd 设备码（[UjingQrLink.Result.Hairdryer.cd]）。
     * @param policy 判型策略（见 [Policy]）。
     */
    suspend fun resolve(raw: String, cd: String, policy: Policy): Result {
        // 本地能定的一律先定：调用方（扫描页 / NFC / 快捷方式）大多已经在外面问过一次了
        localOnly(cd, policy)?.let { return it }

        val format = UjingHairdryerCode.formatOf(cd)
        return when (policy.mode) {
            // localOnly 已覆盖（固定蓝牙永远不会走到这里）
            HairdryerDetectionMode.BLUETOOTH -> resolveByProbe(raw, cd, format = null)

            HairdryerDetectionMode.CLOUD -> resolveForcedCloud(raw, cd)

            HairdryerDetectionMode.AUTO_DEVICE -> resolveByProbe(raw, cd, format = null)

            HairdryerDetectionMode.AUTO -> {
                val guess = format.guess
                // 只有「格式认识 + 用户没否掉过 + 还没确认过」才问；否掉过的这类码一律走接口
                if (guess != null && !policy.formatRejected && policy.formatTrusted != true) {
                    Log.i(TAG, "自动判型：$format 第一次出现，先请用户确认（猜测 $guess）")
                    Result.Confirm(format = format, guess = guess)
                } else {
                    resolveByProbe(raw, cd, format)
                }
            }
        }
    }

    /** 设备信息优先：探测成功就按 `moduleType` 定，失败按格式兜底，再不行交回支付宝。 */
    private suspend fun resolveByProbe(
        raw: String,
        cd: String,
        format: HairdryerCodeFormat?
    ): Result {
        return when (val outcome = probe.probe(raw)) {
            is UjingHairdryerProbe.Outcome.Ok -> {
                val snapshot = outcome.snapshot
                val url = snapshot.launchUrl
                if (snapshot.isCloud) {
                    if (url.isNullOrBlank()) {
                        Log.w(TAG, "云端吹风机 ${snapshot.deviceId} 未取到页面启动地址，回退支付宝")
                        Result.Bluetooth(
                            source = HairdryerTypeSource.FALLBACK,
                            snapshot = snapshot,
                            reason = "云端设备未取到页面启动地址"
                        )
                    } else {
                        Result.CampusCard(
                            initialUrl = url,
                            source = HairdryerTypeSource.PROBE,
                            hashRoute = deviceSelectorRoute(snapshot.deviceId),
                            snapshot = snapshot
                        )
                    }
                } else {
                    Log.i(TAG, "吹风机 ${snapshot.deviceId} 是蓝牙设备（moduleType=${snapshot.moduleType}）→ 支付宝")
                    Result.Bluetooth(
                        source = HairdryerTypeSource.PROBE,
                        snapshot = snapshot,
                        reason = if (format != null && format.guess == HairdryerDeviceType.CLOUD) {
                            "设备信息与码格式判据冲突，以设备信息为准"
                        } else null
                    )
                }
            }

            is UjingHairdryerProbe.Outcome.Failed -> {
                val guessed = format?.guess
                Log.w(TAG, "吹风机探测失败（${outcome.reason}），按格式 $format 兜底")
                Result.Bluetooth(
                    source = if (guessed == HairdryerDeviceType.BLUETOOTH) {
                        HairdryerTypeSource.FORMAT
                    } else {
                        HairdryerTypeSource.FALLBACK
                    },
                    reason = outcome.reason
                )
            }
        }
    }

    /** 强制「4G」：探测成功走深链；失败就把一卡通页面本身打开（用户还能在页面里扫）。 */
    private suspend fun resolveForcedCloud(raw: String, cd: String): Result {
        when (val outcome = probe.probe(raw)) {
            is UjingHairdryerProbe.Outcome.Ok -> {
                val snapshot = outcome.snapshot
                val url = snapshot.launchUrl
                if (!url.isNullOrBlank()) {
                    return Result.CampusCard(
                        initialUrl = url,
                        source = HairdryerTypeSource.PROBE,
                        hashRoute = deviceSelectorRoute(snapshot.deviceId),
                        snapshot = snapshot
                    )
                }
            }

            is UjingHairdryerProbe.Outcome.Failed ->
                Log.w(TAG, "强制 4G 模式探测失败（${outcome.reason}），尝试只取页面地址")
        }

        val fallbackUrl = probe.requestLaunchUrl()
        return if (!fallbackUrl.isNullOrBlank()) {
            Log.i(TAG, "强制 4G 模式：只把一卡通吹风机页面打开（不带设备路由）")
            Result.CampusCard(
                initialUrl = fallbackUrl,
                source = HairdryerTypeSource.MANUAL,
                hashRoute = null
            )
        } else {
            Log.w(TAG, "强制 4G 模式：连页面地址都没取到，最后回退支付宝（cd=$cd）")
            Result.Bluetooth(source = HairdryerTypeSource.FALLBACK, reason = "一卡通页面地址不可用")
        }
    }

    companion object {

        private const val TAG = "UjingHairdryerEntry"

        /**
         * 页面里「选择设备」的 hash 路由。页面自己扫完码也是
         * `this.$router.push({path: "/deviceSelector", query: {deviceId}})`，
         * 照抄这条路，进来就停在左右机选择页，用户不必再点一次「扫一扫」。
         */
        private const val ROUTE_DEVICE_SELECTOR = "#/deviceSelector"

        /** 「选择左右机」页的路由（由容器在页面授权完成后落上去，不能直接拼在启动地址里）。 */
        fun deviceSelectorRoute(deviceId: String): String {
            val encoded = runCatching { URLEncoder.encode(deviceId, "UTF-8") }.getOrDefault(deviceId)
            return "$ROUTE_DEVICE_SELECTOR?deviceId=$encoded"
        }
    }
}
