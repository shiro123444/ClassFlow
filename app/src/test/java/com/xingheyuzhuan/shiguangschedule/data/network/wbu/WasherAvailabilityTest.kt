package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 洗衣机可下单性映射单测。
 *
 * 服务端 `devices/scanWasherCode` 只回一个「现在能不能下单」的 `createOrderEnabled`，
 * 离线 / 他人占用 / 已被预约 / 故障 / 停用 / 码不存在都给 false。这里验证这些情况
 * 能按 `status`（与官方 washer-h5 扫码异常页同一套编码）区分开，而不是一律判成「设备离线」。
 */
class WasherAvailabilityTest {

    private fun reasonOf(
        createOrderEnabled: Boolean?,
        status: Int? = null,
        hasOrderId: Boolean = false,
        hasDeviceId: Boolean = true
    ): WasherUnavailableReason? =
        (
            resolveWasherAvailability(
                createOrderEnabled = createOrderEnabled,
                status = status,
                hasOrderId = hasOrderId,
                hasDeviceId = hasDeviceId
            ) as? WasherAvailability.Unavailable
            )?.reason

    @Test
    fun allowsOrderWhenCreateOrderEnabled() {
        val availability = resolveWasherAvailability(
            createOrderEnabled = true,
            status = null,
            hasOrderId = false,
            hasDeviceId = true
        )
        assertTrue(availability is WasherAvailability.Ready)
    }

    @Test
    fun allowsOrderWhenUserAlreadyHasRunningOrder() {
        // 自己有一笔未完成订单：官方流程会跳订单详情，本地不该拦
        val availability = resolveWasherAvailability(
            createOrderEnabled = false,
            status = null,
            hasOrderId = true,
            hasDeviceId = true
        )
        assertTrue(availability is WasherAvailability.Ready)
    }

    @Test
    fun mapsRunningDevice() {
        assertEquals(WasherUnavailableReason.RUNNING, reasonOf(createOrderEnabled = false, status = 1))
    }

    @Test
    fun mapsReservedDevice() {
        assertEquals(WasherUnavailableReason.RESERVED, reasonOf(createOrderEnabled = false, status = 11))
    }

    @Test
    fun mapsFaultyDevice() {
        assertEquals(WasherUnavailableReason.FAULT, reasonOf(createOrderEnabled = false, status = 2))
    }

    @Test
    fun mapsOfflineDevice() {
        assertEquals(WasherUnavailableReason.OFFLINE, reasonOf(createOrderEnabled = false, status = 8))
    }

    @Test
    fun mapsNotOpenOrDisabledDevice() {
        assertEquals(WasherUnavailableReason.DISABLED, reasonOf(createOrderEnabled = false, status = 7))
        assertEquals(WasherUnavailableReason.DISABLED, reasonOf(createOrderEnabled = false, status = 9))
    }

    @Test
    fun mapsMissingResultToUnknownDevice() {
        assertEquals(
            WasherUnavailableReason.UNKNOWN_DEVICE,
            reasonOf(createOrderEnabled = null, hasDeviceId = false)
        )
    }

    @Test
    fun mapsOrderableWithNoDeviceIdToUnknownDevice() {
        // 真机离线/占用时也会带出设备号；没有设备号的基本就是码不存在
        assertEquals(
            WasherUnavailableReason.UNKNOWN_DEVICE,
            reasonOf(createOrderEnabled = false, status = 8, hasDeviceId = false)
        )
    }

    @Test
    fun fallsBackToGenericReasonForUnknownStatus() {
        assertEquals(
            WasherUnavailableReason.NOT_ORDERABLE,
            reasonOf(createOrderEnabled = false, status = 42)
        )
        assertEquals(
            WasherUnavailableReason.NOT_ORDERABLE,
            reasonOf(createOrderEnabled = false)
        )
    }

    @Test
    fun keepsMerchantMobileOnUnavailable() {
        val availability = resolveWasherAvailability(
            createOrderEnabled = false,
            status = 2,
            hasOrderId = false,
            hasDeviceId = true,
            merchantMobile = "13800000000"
        )
        availability as WasherAvailability.Unavailable
        assertEquals(WasherUnavailableReason.FAULT, availability.reason)
        assertEquals("13800000000", availability.merchantMobile)
    }

    @Test
    fun readyHasNoReason() {
        val availability = resolveWasherAvailability(
            createOrderEnabled = true,
            status = 8,
            hasOrderId = false,
            hasDeviceId = true
        )
        assertNull((availability as? WasherAvailability.Unavailable)?.reason)
    }
}
