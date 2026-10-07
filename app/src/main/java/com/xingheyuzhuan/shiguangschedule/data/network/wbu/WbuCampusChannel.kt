package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context

/**
 * 「需要校园网的流程这次走哪条通道」的判据与决策。
 *
 * 放在数据层是有意的：它既服务 UI 的校园服务流水线（`ui/components/WbuCampusAccess.kt`），
 * 也服务数据层自己的探针（[CredentialVerifier] 验证各服务会话是否有效）。**判定标准只能有一份** ——
 * 否则又会出现「自动探测说人在校内该直连，另一条路径却还在绕 WebVPN」这种自相矛盾。
 */

/**
 * 需要校园网的流程在发起访问前，要不要先探一次校园网？
 *
 * 三个条件同时成立才探（这就是「自动校园网探测」开关的全部作用面）：
 * - 现在是 WebVPN 模式：本来就是直连的话探它没有意义，直连就完了；
 * - 「自动校园网探测」开着；
 * - 「不检测校园网环境」关着：用户已经明确说了别检测，那就别检测。
 */
fun shouldProbeCampusForAccess(
    savedUseVpn: Boolean,
    autoProbeEnabled: Boolean,
    skipCampusCheck: Boolean
): Boolean = savedUseVpn && autoProbeEnabled && !skipCampusCheck

/** 探测结果 → 本次实际通道：在校园网内优先直连，不在校园网才走 WebVPN。 */
fun useVpnAfterCampusProbe(onCampus: Boolean): Boolean = !onCampus

/**
 * 需要校园网的流程（教务 / 图书馆 / 选课 / 课表同步与导入 …）在发起访问前决定这次走哪条通道。
 *
 * 过去只有「WebVPN 模式」一个开关：开着就一律绕 WebVPN —— 人在校园网里也绕，又慢又容易掉线。
 * 现在开了「自动校园网探测」时先做一次**快速探测**（短超时 + 短缓存，见
 * [WbuNetworkProbe.fastRefresh]），在校园网内就直接连、不绕 WebVPN。
 *
 * 不需要校园网的应用（一卡通 / U净 / 付款码 / `directOnly` 的网页应用）不要调用它：
 * 它们本来就不该做任何探测。
 *
 * @param savedUseVpn 设置里的「WebVPN 模式」；默认现场读一次。
 * @return 本次实际使用的通道（`true` = WebVPN）。
 */
suspend fun resolveCampusUseVpn(
    context: Context,
    savedUseVpn: Boolean = WbuSyncEngine.getSavedUseVpn(context)
): Boolean {
    if (!shouldProbeCampusForAccess(
            savedUseVpn = savedUseVpn,
            autoProbeEnabled = WbuAuthTransport.isAutoCampusProbeEnabled(context),
            skipCampusCheck = WbuSyncEngine.getSkipCampusCheck(context)
        )
    ) {
        return savedUseVpn
    }
    return useVpnAfterCampusProbe(WbuNetworkProbe.probeForCampusFlow(context))
}
