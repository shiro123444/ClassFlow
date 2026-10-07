package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2-3 栋淋浴「设备号」的取法（页面 `handleImei` 的等价规则），以及原生页参数的计费来源。
 *
 * 规则来自 lifeService 页面产物（`applications/js/chunk-b88c7b1e.f1981e59.js`）：
 * 原文带 `$#$` 时页面**不再解析 URL**，直接取第一段原文当设备号；否则取链接里的 `id`。
 * 原生页要用同一个设备号去 `getDevicesType` / `consumption`，取错就会开到别的设备上。
 */
class CampusShowerDeviceIdTest {

    @Test
    fun readsIdFromTheSchoolEntryLink() {
        assertEquals(
            "17010737",
            CampusShowerEntryResolver.lifeServiceDeviceId("http://yktxyyy1.wbu.edu.cn:50040?id=17010737")
        )
    }

    @Test
    fun readsIdFromTheVendorLink() {
        assertEquals(
            "17010737",
            CampusShowerEntryResolver.lifeServiceDeviceId("http://4gsk.shuibiao51.com?id=17010737")
        )
    }

    @Test
    fun takesTheFirstSegmentWhenTheTextCarriesAPort() {
        assertEquals("17010737", CampusShowerEntryResolver.lifeServiceDeviceId("17010737${'$'}#${'$'}2"))
        assertEquals(
            "abc",
            CampusShowerEntryResolver.lifeServiceDeviceId("abc${'$'}#${'$'}3")
        )
    }

    @Test
    fun rejectsTextWithoutAnId() {
        assertNull(CampusShowerEntryResolver.lifeServiceDeviceId("http://yktxyyy1.wbu.edu.cn:50040"))
        assertNull(CampusShowerEntryResolver.lifeServiceDeviceId("17010737"))
        assertNull(CampusShowerEntryResolver.lifeServiceDeviceId("   "))
    }

    @Test
    fun billingComesFromThePlatformLaunchUrl() {
        val billing = WbuShowerWaterClient.parseBilling(
            "http://yktfwpt.wbu.edu.cn/applications/lifeService" +
                "?_dt=101&_implid=63_101&feeitemid=414&appId=65&loginFrom=h5&synAccessSource=h5"
        )
        assertEquals("63", billing?.implid)
        assertEquals("414", billing?.feeitemid)
    }
}
