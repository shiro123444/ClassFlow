package com.xingheyuzhuan.shiguangschedule.data.network.wbu

/**
 * 洗衣机「现在能不能下单」的判定结果。
 *
 * U净 `devices/scanWasherCode` 只回一个 `result.createOrderEnabled`，它的语义是
 * **「当前可不可以下单」而不是「设备在不在线」**：设备离线、被别人占用、已被预约、
 * 故障、未开通/停用，全都给 `false`。返回值里另有 `result.status` 才细分原因
 * （与官方 washer-h5 扫码异常页同一套编码：1 正在运行中、11 已被他人预约、2 故障中、
 * 8 已离线、7 未开通、9 已停用）。
 *
 * 只看 `createOrderEnabled` 会把上述情况全部说成「设备离线」——包括「码根本查不到设备」。
 */
sealed interface WasherAvailability {
    /**
     * 可以进 H5 下单。
     *
     * 用户自己有一笔未完成订单时也按可下单处理：官方流程会跳到订单详情，
     * 不该在本地拦下来。
     */
    data object Ready : WasherAvailability

    /**
     * 服务端明确判定当前不可下单。
     *
     * [reason] 决定给用户看的提示；[merchantMobile] 为服务端下发的商家电话（可能为空），
     * 用于让用户直接联系商家。
     */
    data class Unavailable(
        val reason: WasherUnavailableReason,
        val merchantMobile: String? = null
    ) : WasherAvailability
}

/** 洗衣机不可下单的具体原因（文案见 `WasherNoticeDialog`）。 */
enum class WasherUnavailableReason {
    /** `status = 1`：他人正在使用。 */
    RUNNING,

    /** `status = 11`：已被他人预约。 */
    RESERVED,

    /** `status = 2`：设备故障。 */
    FAULT,

    /** `status = 8`：设备离线。 */
    OFFLINE,

    /** `status = 7 / 9`：未开通 / 已停用。 */
    DISABLED,

    /** 码查不到设备：没有 `result`，或不可下单且没有 `deviceId`。 */
    UNKNOWN_DEVICE,

    /** 服务端只说了「不可下单」，没有给出更具体的原因。 */
    NOT_ORDERABLE;

    companion object {
        /** U净 washer-h5 扫码异常页的状态码 → 原因；未知状态返回 null。 */
        fun fromStatus(status: Int): WasherUnavailableReason? = when (status) {
            1 -> RUNNING
            11 -> RESERVED
            2 -> FAULT
            8 -> OFFLINE
            7, 9 -> DISABLED
            else -> null
        }
    }
}

/**
 * 把 `devices/scanWasherCode` 的 `data.result` 各字段收敛成 [WasherAvailability]。
 *
 * - [createOrderEnabled] 为 `null` 表示服务端没给这个字段：按「不可下单」处理。
 *   旧实现默认当成在线，会把「查不到设备」静默放行给 H5，再由 H5 弹一条同样含糊的
 *   「设备已离线～」。
 * - [hasOrderId]：`result.orderId` 非空 —— 用户自己有一笔未完成订单。
 * - [hasDeviceId]：`result.deviceId` 非空 —— 真机离线/占用时仍会带出设备号；
 *   不可下单又没有设备号的基本就是「码不存在 / 设备已拆除」。
 * - [status]：只有 [WasherUnavailableReason.fromStatus] 认识的码才给出具体原因。
 */
fun resolveWasherAvailability(
    createOrderEnabled: Boolean?,
    status: Int?,
    hasOrderId: Boolean,
    hasDeviceId: Boolean,
    merchantMobile: String? = null
): WasherAvailability {
    if (createOrderEnabled == true) return WasherAvailability.Ready
    if (hasOrderId) return WasherAvailability.Ready
    if (!hasDeviceId) return WasherAvailability.Unavailable(WasherUnavailableReason.UNKNOWN_DEVICE)
    val reason = status?.let { WasherUnavailableReason.fromStatus(it) }
        ?: WasherUnavailableReason.NOT_ORDERABLE
    return WasherAvailability.Unavailable(reason = reason, merchantMobile = merchantMobile)
}
