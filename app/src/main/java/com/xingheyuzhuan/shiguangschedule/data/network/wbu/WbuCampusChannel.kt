package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context

/**
 * 「需要校园网的流程这次走哪条通道」的判据与决策。
 *
 * 放在数据层是有意的：它既服务 UI 的校园服务流水线（`ui/components/WbuCampusAccess.kt`），
 * 也服务数据层自己的探针（[CredentialVerifier] 验证各服务会话是否有效）。**判定标准只能有一份** ——
 * 否则又会出现「自动探测说人在校内该直连，另一条路径却还在绕 WebVPN」这种自相矛盾。
 *
 * 另外提供 [replanCampusChannel]：直连失败之后作废探测缓存、重算一次通道 ——
 * 「判据过期」（最常见的是刚走出校园网 WiFi，而短缓存里还写着「在校园网」）不该变成一次硬失败。
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
 * @param useVpn 本次实际使用的通道（`true` = WebVPN）。
 * @param decidedByProbe 这个结论是不是**探测**定的。用户自己把「WebVPN 模式」关掉（也就是
 *   明确要求直连）、或者「不检测校园网环境」开着时它为 false —— 那时直连失败就是失败，
 *   不该背着用户偷偷改道。
 */
data class CampusChannelDecision(
    val useVpn: Boolean,
    val decidedByProbe: Boolean
)

/**
 * 同 [resolveCampusUseVpn]，但把「这个结论是不是探测定的」也一并带出来。
 *
 * 需要它的地方只有一类：**直连失败之后**的重算（见 [replanCampusChannel]）——
 * 只有探测定的通道才值得重算，用户自己选的通道要尊重。
 */
suspend fun resolveCampusChannel(
    context: Context,
    savedUseVpn: Boolean = WbuSyncEngine.getSavedUseVpn(context)
): CampusChannelDecision {
    if (!shouldProbeCampusForAccess(
            savedUseVpn = savedUseVpn,
            autoProbeEnabled = WbuAuthTransport.isAutoCampusProbeEnabled(context),
            skipCampusCheck = WbuSyncEngine.getSkipCampusCheck(context)
        )
    ) {
        return CampusChannelDecision(useVpn = savedUseVpn, decidedByProbe = false)
    }
    return CampusChannelDecision(
        useVpn = useVpnAfterCampusProbe(WbuNetworkProbe.probeForCampusFlow(context)),
        decidedByProbe = true
    )
}

/**
 * 通道决策：在校园网内直连、不在校园网才走 WebVPN；设置没开「自动校园网探测」时就是设置值本身。
 *
 * @param savedUseVpn 设置里的「WebVPN 模式」；默认现场读一次。
 * @return 本次实际使用的通道（`true` = WebVPN）。
 */
suspend fun resolveCampusUseVpn(
    context: Context,
    savedUseVpn: Boolean = WbuSyncEngine.getSavedUseVpn(context)
): Boolean = resolveCampusChannel(context, savedUseVpn).useVpn

/**
 * 直连失败之后的**重算通道**：先把快速探测的短缓存作废，再重新真探一次。
 *
 * 为什么要作废缓存：失败本身就说明刚才那条判据不可信（最典型的是「探测说人在校园网，
 * 可刚走出楼门 WiFi 已经掉了」—— 60s 的短缓存里，这个结论还留着）。
 * 重新真探一次才可能得到「其实不在校园网」，从而翻到 WebVPN 上再试。
 *
 * 只在「通道是探测定的」（[CampusChannelDecision.decidedByProbe]）时调用：用户自己选直连时，
 * 直连失败就是失败，不替他改道。
 */
suspend fun replanCampusChannel(
    context: Context,
    savedUseVpn: Boolean = WbuSyncEngine.getSavedUseVpn(context)
): CampusChannelDecision {
    WbuNetworkProbe.invalidateFastCache()
    return resolveCampusChannel(context, savedUseVpn)
}
