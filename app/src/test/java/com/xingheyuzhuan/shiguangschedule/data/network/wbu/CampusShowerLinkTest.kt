package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/s/{系统}/{设备号}[/{端口}]` 设备直达链接的解析与生成（严格互逆）。
 *
 * 与真机验证过的字节一致性无关 —— 这里只保证「生成器写出来的链接，App 一定能解析回同样两个字段」，
 * 这是标签写坏之后最难排查的一类问题，所以正反两个方向都要有用例。
 */
class CampusShowerLinkTest {

    // --- 解析：1 栋（y） ---

    @Test
    fun parsesYktPosno() {
        val direct = CampusShowerLink.parse("https://wbu.pennote.cn/s/y/10101")
        assertTrue(direct is CampusShowerLink.Direct.Ykt)
        assertEquals("10101", (direct as CampusShowerLink.Direct.Ykt).posno)
    }

    @Test
    fun normalizesYktPosnoLeadingZeros() {
        // 页面按 Number(substr) 使用机号，前导零没有意义，解析时就归一掉，避免同一台设备出现两种 code
        val direct = CampusShowerLink.parse("https://wbu.pennote.cn/s/y/0101")
        assertEquals("101", (direct as CampusShowerLink.Direct.Ykt).posno)
    }

    @Test
    fun rejectsYktWithNonDigits() {
        assertNull(CampusShowerLink.parse("https://wbu.pennote.cn/s/y/10a01"))
        assertNull(CampusShowerLink.parse("https://wbu.pennote.cn/s/y/123456"))
        assertNull(CampusShowerLink.parse("https://wbu.pennote.cn/s/y/0"))
        assertNull(CampusShowerLink.parse("https://wbu.pennote.cn/s/y/"))
    }

    // --- 解析：2-3 栋（l） ---

    @Test
    fun parsesLifeImei() {
        val direct = CampusShowerLink.parse("https://wbu.pennote.cn/s/l/1234567890")
        assertTrue(direct is CampusShowerLink.Direct.Life)
        val life = direct as CampusShowerLink.Direct.Life
        assertEquals("1234567890", life.imei)
        assertNull(life.port)
    }

    @Test
    fun parsesLifeImeiWithPort() {
        val life = CampusShowerLink.parse("https://wbu.pennote.cn/s/l/1234567890/2") as CampusShowerLink.Direct.Life
        assertEquals("1234567890", life.imei)
        assertEquals("2", life.port)
    }

    @Test
    fun parsesPercentEncodedVendorUrlImei() {
        // 2-3 栋二维码里的 imei 可能是整条厂商链接，链接里要百分号转义才能放进路径段
        val encoded = "https://wbu.pennote.cn/s/l/4gsk.shuibiao51.com%3Fid%3D123"
        val life = CampusShowerLink.parse(encoded) as CampusShowerLink.Direct.Life
        assertEquals("4gsk.shuibiao51.com?id=123", life.imei)
    }

    @Test
    fun rejectsLifeWithIllegalPortOrImei() {
        assertNull(CampusShowerLink.parse("https://wbu.pennote.cn/s/l/123/123456789"))  // 端口 9 位
        assertNull(CampusShowerLink.parse("https://wbu.pennote.cn/s/l/123/ab"))         // 端口非数字
        assertNull(CampusShowerLink.parse("https://wbu.pennote.cn/s/l/123/2/3"))        // 多余路径段
        assertNull(CampusShowerLink.parse("https://wbu.pennote.cn/s/l/"))               // 设备号为空
    }

    // --- 解析：不该命中的 ---

    @Test
    fun ignoresReservedSegment() {
        // 官网下载页 /s/download 不是设备号
        assertNull(CampusShowerLink.parse("https://wbu.pennote.cn/s/download"))
        assertNull(CampusShowerLink.parse("https://wbu.pennote.cn/s/l/download"))
    }

    @Test
    fun ignoresUnknownSystemAndOtherPaths() {
        assertNull(CampusShowerLink.parse("https://wbu.pennote.cn/s/x/123"))
        assertNull(CampusShowerLink.parse("https://wbu.pennote.cn/u/ab12cd"))
        assertNull(CampusShowerLink.parse("https://wbu.pennote.cn/w/123456"))
        assertNull(CampusShowerLink.parse("https://wbu.pennote.cn/s"))
    }

    @Test
    fun ignoresPlainText() {
        assertNull(CampusShowerLink.parse(""))
        assertNull(CampusShowerLink.parse("CFSHOWER10101"))
    }

    // --- 生成：与解析互逆 ---

    @Test
    fun buildsYktLink() {
        assertEquals("https://wbu.pennote.cn/s/y/10101", CampusShowerLink.build("wbu.pennote.cn", "y", "10101"))
        // 域名可带协议前缀 / 结尾斜杠，生成时统一归一
        assertEquals("https://wbu.pennote.cn/s/y/10101", CampusShowerLink.build("https://wbu.pennote.cn/", "y", "10101"))
    }

    @Test
    fun buildsLifeLinkAndRoundTrips() {
        val url = CampusShowerLink.build("wbu.pennote.cn", "l", "4gsk.shuibiao51.com?id=123", "2")
        assertEquals("https://wbu.pennote.cn/s/l/4gsk.shuibiao51.com%3Fid%3D123/2", url)
        val life = CampusShowerLink.parse(url!!) as CampusShowerLink.Direct.Life
        assertEquals("4gsk.shuibiao51.com?id=123", life.imei)
        assertEquals("2", life.port)
    }

    @Test
    fun rejectsIllegalBuildArguments() {
        assertNull(CampusShowerLink.build("wbu.pennote.cn", "y", "0"))
        assertNull(CampusShowerLink.build("wbu.pennote.cn", "y", "abc"))
        assertNull(CampusShowerLink.build("wbu.pennote.cn", "l", "a b"))
        assertNull(CampusShowerLink.build("wbu.pennote.cn", "l", "a", "123456789"))
        assertNull(CampusShowerLink.build("", "y", "10101"))
        assertNull(CampusShowerLink.build("wbu.pennote.cn", "x", "1"))
    }
}
