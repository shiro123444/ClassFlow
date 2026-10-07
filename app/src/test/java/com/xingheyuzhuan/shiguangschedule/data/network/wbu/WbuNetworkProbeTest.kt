package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校园网探测的判据单测。
 *
 * 这里钉住的是一次真实事故：实测现场是「本机 tun 代理 + 校外蜂窝」，代理把 TCP 连接自己接管了
 * （连 `10.255.255.1:80` 都是 10ms 就「连上」），于是**只连 TCP** 的快速探测得出「人在校园网」，
 * 同步流程便跑去直连一个根本到不了的教务地址，用户等了 9 秒只等到一句「操作没成功」。
 *
 * 结论：探测必须真的走完一次 HTTP；而且只有像样的响应码才算证据 —— 代理 / 网关自己的错误页
 * 恰恰证明我们**没有**摸到校园网。
 */
class WbuNetworkProbeTest {

    @Test
    fun loginPageAndRedirectsCountAsCampus() {
        // 校内认证页的正常表现：登录页 200，或跳转到认证页的 3xx
        for (code in listOf(200, 204, 301, 302, 303, 307, 308, 399)) {
            assertTrue("HTTP $code 应当算「在校园网内」", isCampusPortalResponseCode(code))
        }
    }

    @Test
    fun gatewayErrorPagesDoNotCountAsCampus() {
        // 代理 / 网关自己的错误页（含 502/504 这类「连上了但不是校园网」的典型表现）
        for (code in listOf(400, 401, 403, 404, 407, 429, 500, 502, 503, 504)) {
            assertFalse("HTTP $code 不得算「在校园网内」", isCampusPortalResponseCode(code))
        }
    }

    @Test
    fun neverClaimsCampusWithoutAResponse() {
        // 0 用来代表「一个响应都没拿到」（probeHttp 里异常分支的语义）
        assertFalse(isCampusPortalResponseCode(0))
    }
}
