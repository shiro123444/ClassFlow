package com.xingheyuzhuan.shiguangschedule.ui.campus.hairdryer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerCodeFormat
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerDetectionMode
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerDeviceType
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerLaunchSource
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingHairdryerEntryResolver
import com.xingheyuzhuan.shiguangschedule.data.repository.HairdryerHubRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 吹风机分流过渡页的状态。 */
sealed interface HairdryerLaunchUiState {

    /** 正在判型（页面只有品牌过渡）。 */
    data object Resolving : HairdryerLaunchUiState

    /**
     * 第一次遇到这种码格式：先问一句「这台是不是 [guess]」，再继续。
     *
     * 判据分不清南北校区、也挡不住学校以后换码 —— 与其默默判错，不如问一次，
     * 并顺手把「以后去哪改」告诉用户。
     */
    data class Confirm(
        val format: HairdryerCodeFormat,
        val guess: HairdryerDeviceType
    ) : HairdryerLaunchUiState

    /**
     * 去向已定，等页面执行跳转。
     *
     * 目标直接放进状态（而不是一次性事件流）：状态不会被漏收 —— 判型可能只花 200ms，
     * 而一次性事件在订阅建立前发出就永远丢了。
     */
    data class Ready(val target: HairdryerLaunchTarget) : HairdryerLaunchUiState
}

/** 判型结果：这次该跳哪儿。 */
sealed interface HairdryerLaunchTarget {

    /**
     * 云端（4G）：换成网页应用容器。
     *
     * [initialUrl] 是页面启动地址（带平台票据）；[hashRoute] 是页面自己授权完成后要落到的
     * 「选择左右机」路由 —— 冷启动时不能直接落在受保护路由上，见 `UjingHairdryerEntryResolver`。
     */
    data class CampusCard(val initialUrl: String, val hashRoute: String?) : HairdryerLaunchTarget

    /** 蓝牙：调起支付宝 U净 小程序。 */
    data object Alipay : HairdryerLaunchTarget
}

/**
 * 吹风机分流过渡页：**三种入口共用一份判型与跳转逻辑**。
 *
 * | 入口 | [HairdryerLaunchSource] | 用哪套判型设置 |
 * | --- | --- | --- |
 * | 全局扫一扫 | `SCAN` | 扫码判型模式 |
 * | NFC 触碰 `/hd/{cd}` | `NFC` | NFC 判型模式（默认跟随扫码） |
 * | 桌面快捷方式 / 记录直达 | `DIRECT` | 这条记录自己的类型（不再临时探测） |
 *
 * 判型结果、判据来源与实际跳转都会写进本地记录（[HairdryerHubRepository.record]），
 * 于是「吹风机页」里能直接看到用过哪几台、各自是蓝牙还是 4G。
 */
@HiltViewModel
class HairdryerLaunchViewModel @Inject constructor(
    private val repository: HairdryerHubRepository,
    private val resolver: UjingHairdryerEntryResolver,
) : ViewModel() {

    private val _state = MutableStateFlow<HairdryerLaunchUiState>(HairdryerLaunchUiState.Resolving)
    val state: StateFlow<HairdryerLaunchUiState> = _state.asStateFlow()

    private var started = false
    private var cd: String = ""
    private var raw: String = ""
    private var source: HairdryerLaunchSource = HairdryerLaunchSource.SCAN
    private var mode: HairdryerDetectionMode = HairdryerDetectionMode.AUTO

    /** 扫码原文是「我们自己拼的」（NFC / 深链只给了设备码），允许失败后换个写法再试一次。 */
    private var rawReconstructed = false
    private var schemeRetried = false

    /** 页面进入时调用一次；重复调用（重组、配置变更）无副作用。 */
    fun start(cd: String, raw: String, source: HairdryerLaunchSource) {
        if (started) return
        started = true
        this.cd = cd
        // 只有「贴纸原文」才值得逐字回传：NFC / 深链（`/hd/{cd}`）给的是一串设备直达地址，
        // 服务端按原文认设备，直接拿去探测必然答「当前设备未绑定。」——
        // 这种情况下我们自己拼一条贴纸地址（见 [canonicalUrl]）。
        val stickerRaw = raw.takeIf { it.contains("cd=") && it.contains("/6d/") }
        this.raw = stickerRaw ?: canonicalUrl(cd)
        this.rawReconstructed = stickerRaw == null
        this.source = source
        viewModelScope.launch {
            // 用哪套判型方式由入口来源决定（扫码 / NFC / 直达），见 HairdryerHubRepository.effectiveMode
            mode = repository.effectiveMode(cd = this@HairdryerLaunchViewModel.cd, source = source)
            resolve()
        }
    }

    /** 首次确认弹窗的回答：[correct] = 本地判据对这台机器成立。 */
    fun onConfirm(correct: Boolean) {
        val current = _state.value as? HairdryerLaunchUiState.Confirm ?: return
        viewModelScope.launch {
            repository.setFormatVerdict(current.format, correct)
            resolve()
        }
    }

    private suspend fun resolve() {
        _state.value = HairdryerLaunchUiState.Resolving
        val policy = repository.policyFor(cd, mode)
        var result = resolver.resolve(raw, cd, policy)

        // NFC / 深链给的只有设备码，扫码原文是我们拼的；服务端按原文认设备（换写法会答
        // 「当前设备未绑定。」），所以探测失败时换一种 scheme 再试一次，命中就把原文记下来。
        if (result is UjingHairdryerEntryResolver.Result.Bluetooth &&
            result.snapshot == null && rawReconstructed && !schemeRetried
        ) {
            schemeRetried = true
            val alternative = alternateScheme(raw)
            if (alternative != raw) {
                val retry = resolver.resolve(alternative, cd, policy)
                if (retry !is UjingHairdryerEntryResolver.Result.Bluetooth || retry.snapshot != null) {
                    raw = alternative
                    result = retry
                }
            }
        }

        when (result) {
            is UjingHairdryerEntryResolver.Result.Confirm ->
                _state.value = HairdryerLaunchUiState.Confirm(result.format, result.guess)

            is UjingHairdryerEntryResolver.Result.CampusCard -> {
                repository.record(
                    cd = cd,
                    raw = raw,
                    type = HairdryerDeviceType.CLOUD,
                    source = result.source,
                    snapshot = result.snapshot
                )
                if (result.snapshot == null) repository.enrichAsync(cd, raw)
                _state.value = HairdryerLaunchUiState.Ready(
                    HairdryerLaunchTarget.CampusCard(result.initialUrl, result.hashRoute)
                )
            }

            is UjingHairdryerEntryResolver.Result.Bluetooth -> {
                repository.record(
                    cd = cd,
                    raw = raw,
                    type = HairdryerDeviceType.BLUETOOTH,
                    source = result.source,
                    snapshot = result.snapshot
                )
                // 格式判据直接分流时没有探测过：后台补一次，把店铺名之类的填进记录
                if (result.snapshot == null) repository.enrichAsync(cd, raw)
                _state.value = HairdryerLaunchUiState.Ready(HairdryerLaunchTarget.Alipay)
            }
        }
    }

    companion object {

        /** 贴纸的标准写法（`6d` 是吹风机贴纸的路径，见 `UjingQrLink`）。 */
        private const val CANONICAL_URL = "http://q.ujing.com.cn/6d/index.html?cd="

        /** 只有设备码时，用它拼一条扫码原文。 */
        fun canonicalUrl(cd: String): String = "$CANONICAL_URL$cd"

        /** 换一种 scheme 的同一台设备（`http` ↔ `https`）。 */
        private fun alternateScheme(raw: String): String = when {
            raw.startsWith("http://") -> raw.replaceFirst("http://", "https://")
            raw.startsWith("https://") -> raw.replaceFirst("https://", "http://")
            else -> raw
        }
    }
}
