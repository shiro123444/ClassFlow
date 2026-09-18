package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import com.xingheyuzhuan.shiguangschedule.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusLinkRouterTest {

    @Test
    fun parsesWaterUri() {
        val dest = CampusLinkRouter.parse("https://ujing_test.wbu.edu.cn/w/0011202004140940")
        assertNotNull(dest)
        assertTrue(dest is Destination.UjingWater)
        assertEquals("0011202004140940", (dest as Destination.UjingWater).cd)
    }

    @Test
    fun parsesWasherUri() {
        val dest = CampusLinkRouter.parse("https://ujing_test.wbu.edu.cn/wm/0000000000000A1234567202208040004678")
        assertNotNull(dest)
        assertTrue(dest is Destination.WebApp)
        val webApp = dest as Destination.WebApp
        assertEquals(com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppId.CAMPUS_CARD.name, webApp.appId)
        assertEquals("http://app.littleswan.com/u_download.html?type=Ujing&uuid=0000000000000A1234567202208040004678", webApp.pendingAutoScan)
    }

    @Test
    fun ignoresUnrelatedUri() {
        val dest = CampusLinkRouter.parse("https://ujing_test.wbu.edu.cn/other/path")
        assertNull(dest)
    }
}
