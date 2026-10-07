package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 生活服务「用水」计费上下文的解析。
 *
 * `implid` / `feeitemid` 只能由平台给的子应用启动地址决定（`_implid=63_101` 里的 63 是
 * 「生活服务」的实现 id，`feeitemid=414` 是淋浴这个缴费项）；写死一个缴费项，换成洗衣机
 * 之类的别的项就会查到错的账上。
 */
class WbuShowerWaterBillingTest {

    private val lifeServiceLaunchUrl =
        "http://yktfwpt.wbu.edu.cn/applications/lifeService" +
            "?_dt=101&_implid=63_101&feeitemid=414&appId=65&loginFrom=h5&synAccessSource=h5" +
            "&synjones-auth=bearer%20eyJhbGciOiJIUzI1NiJ9.abc"

    @Test
    fun parseBilling_readsImplidAndFeeitemid() {
        val billing = WbuShowerWaterClient.parseBilling(lifeServiceLaunchUrl)
        assertEquals(WbuShowerWaterClient.Billing(implid = "63", feeitemid = "414"), billing)
    }

    @Test
    fun parseBilling_takesImplidBeforeTheDeviceTypeSuffix() {
        // `_implid` 的形态是 `<实现id>_<设备类型>`；计费只认前面那个实现 id
        val billing = WbuShowerWaterClient.parseBilling(
            "http://yktfwpt.wbu.edu.cn/applications/lifeService?_implid=45_7&feeitemid=500"
        )
        assertEquals("45", billing?.implid)
        assertEquals("500", billing?.feeitemid)
    }

    @Test
    fun parseBilling_rejectsUrlWithoutBillingParams() {
        assertNull(WbuShowerWaterClient.parseBilling("http://yktfwpt.wbu.edu.cn/applications/lifeService"))
        assertNull(WbuShowerWaterClient.parseBilling("http://yktfwpt.wbu.edu.cn/applications/lifeService?_dt=101&feeitemid=414"))
        assertNull(WbuShowerWaterClient.parseBilling("http://yktfwpt.wbu.edu.cn/applications/lifeService?_implid=63_101"))
        assertNull(WbuShowerWaterClient.parseBilling(""))
        assertNull(WbuShowerWaterClient.parseBilling(null))
    }

    @Test
    fun showerDevicesTypeIs101() {
        assertEquals(101, WbuShowerWaterClient.DEVICES_TYPE_SHOWER)
        assertEquals("淋浴", WbuShowerWaterClient.DEVICES_TYPE_NAMES[101])
        assertEquals("洗衣机", WbuShowerWaterClient.DEVICES_TYPE_NAMES[7])
    }
}
