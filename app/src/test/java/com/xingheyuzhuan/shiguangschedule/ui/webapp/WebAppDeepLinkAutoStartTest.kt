package com.xingheyuzhuan.shiguangschedule.ui.webapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 深链一次性自启载体（`scanResult=`）的摘除逻辑。
 *
 * 背景：2-3 栋淋浴深链把扫码原文拼在地址里（`…&scanResult=<原文>`），lifeService 页面在
 * `mounted` 里读到它就 `getDevicesType` → 无档位价格时直接开一单。导航条目重建（切后台被
 * 系统回收后回来、转屏）会把这个地址再加载一次，等于又开一单；所以重建时必须先摘掉这个参数，
 * 同时**其余参数一个都不能动**（`_dt` / `_implid` / `feeitemid` / `appId` / `synjones-auth`…）。
 */
class WebAppDeepLinkAutoStartTest {

    private val lifeServiceUrl =
        "http://yktfwpt.wbu.edu.cn/applications/lifeService" +
            "?_dt=101&_implid=63_101&feeitemid=414&appId=65&loginFrom=h5&synAccessSource=h5" +
            "&synjones-auth=bearer%20eyJhbGciOiJIUzI1NiJ9.abc" +
            "&scanResult=http%3A%2F%2Fyktxyyy1.wbu.edu.cn%3A50040%3Fid%3D17010737"

    @Test
    fun stripsScanResultAndKeepsEveryOtherParam() {
        assertEquals(
            "http://yktfwpt.wbu.edu.cn/applications/lifeService" +
                "?_dt=101&_implid=63_101&feeitemid=414&appId=65&loginFrom=h5&synAccessSource=h5" +
                "&synjones-auth=bearer%20eyJhbGciOiJIUzI1NiJ9.abc",
            stripDeepLinkAutoTrigger(lifeServiceUrl)
        )
    }

    @Test
    fun keepsUrlUntouchedWhenThereIsNoTrigger() {
        val plain = "http://yktfwpt.wbu.edu.cn/applications/lifeService?_dt=101&_implid=63_101"
        assertEquals(plain, stripDeepLinkAutoTrigger(plain))
    }

    @Test
    fun keepsUrlUntouchedWhenThereIsNoQueryAtAll() {
        val plain = "http://yktfwpt.wbu.edu.cn/applications/lifeService"
        assertEquals(plain, stripDeepLinkAutoTrigger(plain))
    }

    @Test
    fun dropsLoneTriggerAndLeavesBarePath() {
        assertEquals(
            "http://yktfwpt.wbu.edu.cn/applications/lifeService",
            stripDeepLinkAutoTrigger("http://yktfwpt.wbu.edu.cn/applications/lifeService?scanResult=x")
        )
    }

    @Test
    fun keepsFragmentAfterStripping() {
        assertEquals(
            "http://yktfwpt.wbu.edu.cn/plat/shouyeUser?a=1#/main",
            stripDeepLinkAutoTrigger("http://yktfwpt.wbu.edu.cn/plat/shouyeUser?a=1&scanResult=x#/main")
        )
    }

    @Test
    fun onlyDropsWholeSegmentsStartingWithTheParamName() {
        // 只是**值**里提到 scanResult 的参数不能动（例如平台的 /plat/scanResult 回跳地址）
        val url = "http://yktfwpt.wbu.edu.cn/plat/scan?redirectUrl=%2Fplat%2FscanResult"
        assertEquals(url, stripDeepLinkAutoTrigger(url))
    }

    @Test
    fun stripsTriggerWithTimeStampPortForm() {
        // 多端口深链（`<设备号>$#$<端口>`）的原文同样经 URLEncoder 编码，`$`/`#` 都是百分号形式
        assertEquals(
            "http://yktfwpt.wbu.edu.cn/applications/lifeService?appId=65",
            stripDeepLinkAutoTrigger(
                "http://yktfwpt.wbu.edu.cn/applications/lifeService?appId=65" +
                    "&scanResult=17010737%24%23%242"
            )
        )
    }
}
