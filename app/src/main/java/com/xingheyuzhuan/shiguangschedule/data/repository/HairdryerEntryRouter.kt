package com.xingheyuzhuan.shiguangschedule.data.repository

import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerDeviceType
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerLaunchSource
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingHairdryerEntryResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 吹风机各个入口的**第一跳**：先问一句「本地是不是已经能确定这是蓝牙机」。
 *
 * 判型要联网（探测）或要问用户（首次确认）时，才把用户请进过渡页
 * （[com.xingheyuzhuan.shiguangschedule.Destination.HairdryerLaunch]）。
 * 否则 —— 也就是绝大多数老码、以及用户确认过判据之后的每一次扫码 / 碰一碰 ——
 * 直接返回 true，调用方照原样播品牌过场并调起支付宝：**不经过任何多余界面**，
 * 与「吹风机一律跳支付宝」时代的手感完全一致。
 *
 * 判据本身由 [UjingHairdryerEntryResolver.localOnly] 提供（与过渡页里用的是同一份），
 * 这里只负责把设置 / 记录读出来、并把这次使用记进记录里。
 */
@Singleton
class HairdryerEntryRouter @Inject constructor(
    private val repository: HairdryerHubRepository,
    private val resolver: UjingHairdryerEntryResolver,
) {

    /**
     * @return true = 已确定是手机蓝牙机，调用方直接走原体验（过场 + 支付宝）；
     *   false = 需要过渡页（要探测，或这种码格式第一次遇到要确认）。
     */
    suspend fun localBluetooth(
        cd: String,
        raw: String,
        source: HairdryerLaunchSource
    ): Boolean {
        if (cd.isBlank()) return false
        val settings = repository.currentSettings()
        val mode = repository.effectiveMode(cd, source, settings)
        val decision = resolver.localOnly(cd, repository.policyFor(cd, mode)) ?: return false

        repository.record(
            cd = cd,
            raw = raw,
            type = HairdryerDeviceType.BLUETOOTH,
            source = decision.source,
            snapshot = null
        )
        // 格式判据直接分流时没探测过：后台补一次，把店铺名之类的填进记录
        repository.enrichAsync(cd, raw)
        return true
    }
}
