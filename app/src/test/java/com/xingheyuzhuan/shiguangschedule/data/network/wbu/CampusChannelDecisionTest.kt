package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自动校园网探测」的判据层单测。
 *
 * 这条链路最容易出的两类事故：
 * 1. **该直连的却绕了 WebVPN** —— 人就在校园网里，登录与同步还跑到校外绕一圈，又慢又容易掉线；
 * 2. **不该探测的也探测** —— 「不检测校园网环境」开着（或压根是直连模式）时还去探测，
 *    白白让用户多等一次超时。
 *
 * 所以这里把「什么时候探测」与「探测完走哪条通道」钉成真值表。
 */
class CampusChannelDecisionTest {

    // ---------- 什么时候探测 ----------

    @Test
    fun directModeNeverProbes() {
        // 本来就是直连：探它没有意义，直连就完了
        assertFalse(
            shouldProbeCampusForAccess(savedUseVpn = false, autoProbeEnabled = true, skipCampusCheck = false)
        )
        assertFalse(
            shouldProbeCampusForAccess(savedUseVpn = false, autoProbeEnabled = false, skipCampusCheck = false)
        )
        assertFalse(
            shouldProbeCampusForAccess(savedUseVpn = false, autoProbeEnabled = true, skipCampusCheck = true)
        )
    }

    @Test
    fun webVpnModeProbesOnlyWhenSwitchOnAndCampusCheckNotSkipped() {
        assertTrue(
            shouldProbeCampusForAccess(savedUseVpn = true, autoProbeEnabled = true, skipCampusCheck = false)
        )
    }

    @Test
    fun autoProbeOffFallsBackToWebVpnWithoutProbing() {
        // 关掉开关 = 回到加这个开关之前的行为：WebVPN 模式下就走 WebVPN
        assertFalse(
            shouldProbeCampusForAccess(savedUseVpn = true, autoProbeEnabled = false, skipCampusCheck = false)
        )
    }

    @Test
    fun skippingCampusCheckAlsoSkipsTheAutoProbe() {
        // 用户已经明确说了「不检测校园网环境」，那就别检测
        assertFalse(
            shouldProbeCampusForAccess(savedUseVpn = true, autoProbeEnabled = true, skipCampusCheck = true)
        )
    }

    // ---------- 探测结果 → 通道 ----------

    @Test
    fun onCampusPrefersDirectConnection() {
        assertFalse(useVpnAfterCampusProbe(onCampus = true))
    }

    @Test
    fun offCampusUsesWebVpn() {
        assertTrue(useVpnAfterCampusProbe(onCampus = false))
    }

    // ---------- 组合：设置里的通道 → 本次实际通道 ----------

    @Test
    fun webVpnModeWithProbeOnPrefersDirectWhenOnCampus() {
        // WebVPN 模式 + 探测开 + 没跳过检测：校内直连、校外才走 WebVPN
        assertFalse(resolve(savedUseVpn = true, autoProbe = true, skipCampusCheck = false, onCampus = true))
        assertTrue(resolve(savedUseVpn = true, autoProbe = true, skipCampusCheck = false, onCampus = false))
    }

    @Test
    fun probeOffOrSkippedCheckKeepsWebVpnRegardlessOfCampusState() {
        assertTrue(resolve(savedUseVpn = true, autoProbe = false, skipCampusCheck = false, onCampus = true))
        assertTrue(resolve(savedUseVpn = true, autoProbe = true, skipCampusCheck = true, onCampus = true))
    }

    @Test
    fun directModeStaysDirectEvenOffCampus() {
        assertFalse(resolve(savedUseVpn = false, autoProbe = true, skipCampusCheck = false, onCampus = false))
    }

    // ---------- 直连失败之后：值不值得重算通道 ----------

    @Test
    fun onlyProbeChosenChannelsAreWorthReplanning() {
        // 通道是探测定的 → 直连白跑一趟时值得作废缓存重算一次（判据可能刚过期：人已经走出校园网了）
        assertTrue(decidedByProbe(savedUseVpn = true, autoProbe = true, skipCampusCheck = false))
        // 用户自己选了直连 / 自己关了探测 / 明确说了「不检测校园网环境」→ 尊重用户，不偷偷改道
        assertFalse(decidedByProbe(savedUseVpn = false, autoProbe = true, skipCampusCheck = false))
        assertFalse(decidedByProbe(savedUseVpn = true, autoProbe = false, skipCampusCheck = false))
        assertFalse(decidedByProbe(savedUseVpn = true, autoProbe = true, skipCampusCheck = true))
    }

    @Test
    fun replanOnlySwitchesTheChannelWhenTheFreshProbeSaysOffCampus() {
        // 重算的用处全在这一条：过期的「在校园网」被重新探成「其实不在」→ 翻到 WebVPN 再试
        assertTrue(useVpnAfterCampusProbe(onCampus = false))
        // 重新探一次仍然说「在校园网」→ 通道不变，别硬把用户改道
        assertFalse(useVpnAfterCampusProbe(onCampus = true))
    }

    /**
     * 与 [resolveCampusChannel] 里 `decidedByProbe` 同构的纯函数：
     * 「这次通道是探测定的吗」就是「这次该探测吗」。
     */
    private fun decidedByProbe(savedUseVpn: Boolean, autoProbe: Boolean, skipCampusCheck: Boolean): Boolean =
        shouldProbeCampusForAccess(savedUseVpn, autoProbe, skipCampusCheck)

    /**
     * 与 `resolveCampusUseVpn` 同构的纯函数：先问「要不要探测」，再按结果选通道。
     * 真实实现只是把这两个判据接到了设置项与快速探测上。
     */
    private fun resolve(
        savedUseVpn: Boolean,
        autoProbe: Boolean,
        skipCampusCheck: Boolean,
        onCampus: Boolean
    ): Boolean =
        if (shouldProbeCampusForAccess(savedUseVpn, autoProbe, skipCampusCheck)) {
            useVpnAfterCampusProbe(onCampus)
        } else {
            savedUseVpn
        }
}
