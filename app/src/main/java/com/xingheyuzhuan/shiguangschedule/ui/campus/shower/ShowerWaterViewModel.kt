package com.xingheyuzhuan.shiguangschedule.ui.campus.shower

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessFailure
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessLayer
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuFailureDetector
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuShowerWaterClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuShowerWaterSocket
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.needsRelogin
import com.xingheyuzhuan.shiguangschedule.ui.components.accessFailureText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 淋浴用水页面状态。
 *
 * 与网页那套「一切靠页面自己记」不同，这里每一步都以服务端为准：
 * [Idle] = `getDevicesStatus` 说没有未结束的用水；[Active] = 服务端说有（并且给出设备号、
 * 订单号、开始时间）。因此页面被重建（切后台被系统回收、转屏）之后重新查一次就会**接着显示
 * 那一单**，而不是再开一单 —— 重复开单在这个设计里从入口就被服务端状态挡住了。
 */
sealed interface ShowerWaterUiStage {

    /** 读取令牌 / 查询状态中。 */
    data class Loading(val messageRes: Int = R.string.shower_water_checking) : ShowerWaterUiStage

    /** 没有未结束的用水，可以开始。 */
    data object Idle : ShowerWaterUiStage

    /** 正在用水。 */
    data class Active(
        val deviceNo: String,
        val ordernum: String?,
        val startTime: String?,
        /** 结算推送是否连着（连不上就降级成每 5 秒轮询）。 */
        val liveConnected: Boolean
    ) : ShowerWaterUiStage

    /** 本次用水已结束（金额/耗时来自结算推送，没有推送时只有耗时）。 */
    data class Finished(
        val deviceNo: String,
        val message: String?,
        val amount: Double?,
        val minutes: Int?,
        val elapsedMinutes: Int?
    ) : ShowerWaterUiStage

    data class Error(val message: String, val canRetry: Boolean = true) : ShowerWaterUiStage
}

data class ShowerWaterUiState(
    val stage: ShowerWaterUiStage = ShowerWaterUiStage.Loading(),
    val deviceNo: String = "",
    val busy: Boolean = false,
    val needLogin: Boolean = false,
    /** 最近一次「刷新状态」的时间戳（用于给出「已刷新」的短提示）。 */
    val refreshedAt: Long = 0L
)

/**
 * 马影河 2-3 栋淋浴（生活服务 lifeService，`appId=65`）原生用水流程。
 *
 * 接口与实时推送见 [WbuShowerWaterClient] / [WbuShowerWaterSocket]：
 * 进页面查一次「有没有未结束的用水」，开始/结束各一次接口，结算结果靠推送，
 * 推送不可用时用轮询兜底 —— 和网页那套的差别只是不再依赖那个页面。
 */
@HiltViewModel
class ShowerWaterViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val client = WbuShowerWaterClient(context)

    private companion object {
        const val TAG = "ShowerWaterViewModel"

        /** 结算推送不可用时的兜底轮询间隔。 */
        const val POLL_INTERVAL_MS = 5_000L

        /** 结束用水后确认服务端已停的轮询次数 / 间隔。 */
        const val STOP_CONFIRM_TRIES = 6
        const val STOP_CONFIRM_GAP_MS = 1_500L

        /** 平台返回的开始时间格式。 */
        const val SERVER_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"

        /**
         * 刚开单后的宽限期。
         *
         * `getDevicesStatus` 的状态可能比 `consumption` 晚几秒才翻过来，这段时间里若照实显示
         * 「可以开始」，用户再点一次就是第二单 —— 所以宽限期内一律按「用水中」展示。
         */
        const val START_GRACE_MS = 15_000L
    }

    private val _uiState = MutableStateFlow(ShowerWaterUiState())
    val uiState: StateFlow<ShowerWaterUiState> = _uiState.asStateFlow()

    private var billing: WbuShowerWaterClient.Billing? = null
    private var deviceId: String = ""
    private var port: String? = null
    private var token: String? = null
    private var started = false

    private var socket: WbuShowerWaterSocket? = null
    private var pollJob: Job? = null

    /** 本次用水会话（结束时用来算「本次耗时」）。 */
    private var activeStartMillis: Long? = null

    /** 结算推送带回的明细（金额 / 耗时）；结束流程据此展示。 */
    private var lastSettlement: WbuShowerWaterSocket.Settlement? = null

    /** 本进程最近一次开单的时刻（服务端状态延迟期间用它维持「用水中」）。 */
    private var justStartedAt: Long? = null

    /**
     * 进入页面：查询令牌与当前用水状态。
     *
     * 重复调用（重组 / 配置变更）无副作用；进程重建后是新实例，会重新查一次服务端状态，
     * 因此「已经开着的单」会接着显示，而不是又开一单。
     */
    fun start(deviceId: String, port: String?, billing: WbuShowerWaterClient.Billing) {
        if (started) return
        started = true
        this.deviceId = deviceId.trim()
        this.port = port?.takeIf { it.isNotBlank() }
        this.billing = billing
        _uiState.update { it.copy(deviceNo = deviceId.trim(), stage = ShowerWaterUiStage.Loading()) }
        refreshInternal(showBusy = false)
    }

    /** 手动「刷新状态」：重新查一次服务端（用户要的那个按钮）。 */
    fun refresh() {
        if (billing == null) return
        refreshInternal(showBusy = false, markRefreshed = true)
    }

    /** 开始用水（`consumption`）。 */
    fun startWater() {
        val billing = billing ?: return
        val token = token ?: return
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            runCatching { client.startUse(token, deviceId, billing, port) }
                .onSuccess { result ->
                    Log.i(TAG, "start use ok: device=${result.deviceNo} status=${result.status}")
                    activeStartMillis = System.currentTimeMillis()
                    justStartedAt = activeStartMillis
                    // 开单成功后立刻以服务端状态刷新一次：拿到 ordernum / startTime 好接着展示
                    refreshInternal(showBusy = false)
                }
                .onFailure { handleFailure(it, fallback = R.string.shower_water_start) }
        }
    }

    /** 结束用水（`endConsumption`），随后确认服务端确实停了。 */
    fun stopWater() {
        val billing = billing ?: return
        val token = token ?: return
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val device = currentActiveDevice() ?: deviceId
            val ordernum = (currentStage() as? ShowerWaterUiStage.Active)?.ordernum
            runCatching { client.endUse(token, device, billing, ordernum) }
                .onSuccess { message ->
                    Log.i(TAG, "end use ok: $message")
                    stopPolling()
                    val stopped = confirmStopped(token, billing)
                    val minutes = currentStage() as? ShowerWaterUiStage.Active
                    markFinished(
                        deviceNo = device,
                        message = message,
                        minutes = minutes?.let { elapsedMinutes(it.startTime) }
                    )
                    _uiState.update { it.copy(busy = false) }
                    if (!stopped) {
                        Log.w(TAG, "server still reports an active session after endConsumption")
                    }
                }
                .onFailure { handleFailure(it, fallback = R.string.shower_water_stop) }
        }
    }

    fun onLoginSuccess() {
        _uiState.update { it.copy(needLogin = false) }
        refreshInternal(showBusy = false)
    }

    fun onLoginDismissed() {
        _uiState.update {
            it.copy(
                needLogin = false,
                stage = ShowerWaterUiStage.Error(
                    context.getString(R.string.err_need_unified_auth_session),
                    canRetry = true
                )
            )
        }
    }

    fun retry() {
        refreshInternal(showBusy = true)
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
        socket?.close()
        socket = null
    }

    // ───────────────────────── 内部实现 ─────────────────────────

    /**
     * 拉令牌 + 查一次状态，并按结果切换界面。
     *
     * 状态是唯一的真相来源：服务端说在用就显示在用（并接上结算推送），说没有就显示可以开始。
     */
    private fun refreshInternal(showBusy: Boolean, markRefreshed: Boolean = false) {
        val billing = billing ?: return
        if (showBusy) _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching {
                val token = token ?: client.accessToken().also { token = it }
                client.getUsage(token, billing)
            }
                .onSuccess { usage ->
                    // 刚开单的宽限期：状态还没翻过来时别显示成「可以开始」（会诱导再开一单）
                    val withinStartGrace = justStartedAt
                        ?.let { System.currentTimeMillis() - it < START_GRACE_MS } == true
                    if (usage.active) justStartedAt = null
                    if (usage.active || withinStartGrace) {
                        val activeDevice = usage.deviceNo ?: currentActiveDevice() ?: deviceId
                        if (usage.active) {
                            activeStartMillis = parseServerTime(usage.startTime) ?: activeStartMillis
                        }
                        _uiState.update {
                            it.copy(
                                busy = false,
                                stage = ShowerWaterUiStage.Active(
                                    deviceNo = activeDevice,
                                    ordernum = usage.ordernum,
                                    startTime = usage.startTime,
                                    liveConnected = socket != null && it.stage is ShowerWaterUiStage.Active
                                ),
                                refreshedAt = if (markRefreshed) System.currentTimeMillis() else it.refreshedAt
                            )
                        }
                        ensureSocket()
                        startPolling()
                    } else {
                        stopPolling()
                        _uiState.update {
                            it.copy(
                                busy = false,
                                stage = ShowerWaterUiStage.Idle,
                                refreshedAt = if (markRefreshed) System.currentTimeMillis() else it.refreshedAt
                            )
                        }
                    }
                }
                .onFailure { handleFailure(it, fallback = R.string.shower_water_checking) }
        }
    }

    /** 结算推送：主题 URL 里带学号，学号从平台令牌载荷里取。 */
    private fun ensureSocket() {
        if (socket != null) return
        val sno = WbuShowerWaterClient.parseSno(token) ?: return
        val ws = WbuShowerWaterSocket(
            onSettlement = { settlement ->
                Log.i(TAG, "settlement received: ${settlement.message}")
                lastSettlement = settlement
                stopPolling()
                val device = currentActiveDevice() ?: deviceId
                markFinished(deviceNo = device, message = settlement.message, minutes = settlement.minutes)
            },
            onConnected = { connected ->
                _uiState.update { state ->
                    val stage = state.stage
                    if (stage is ShowerWaterUiStage.Active) {
                        state.copy(stage = stage.copy(liveConnected = connected))
                    } else {
                        state
                    }
                }
            }
        )
        socket = ws
        ws.connect(sno)
    }

    private fun closeSocket() {
        socket?.close()
        socket = null
    }

    /**
     * 推送不可用时的兜底：每 5 秒查一次状态，发现服务端已经判定结束就切到「已结束」。
     *
     * 页面自己没有轮询（整个 lifeService 产物里没有 `setInterval`），所以这一层是**我们**加的
     * ——推送到不了、或者对方只改了状态没推消息时，页面不会一直停在「正在用水」。
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val billing = billing ?: continue
                val token = token ?: continue
                val usage = runCatching { client.getUsage(token, billing) }.getOrNull() ?: continue
                if (!usage.active) {
                    val stage = currentStage() as? ShowerWaterUiStage.Active ?: continue
                    markFinished(
                        deviceNo = stage.deviceNo,
                        message = null,
                        minutes = elapsedMinutes(stage.startTime)
                    )
                    break
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** 结束用水后确认服务端确实没有未结束的用水了。 */
    private suspend fun confirmStopped(token: String, billing: WbuShowerWaterClient.Billing): Boolean {
        repeat(STOP_CONFIRM_TRIES) {
            val usage = runCatching { client.getUsage(token, billing) }.getOrNull()
            if (usage != null && !usage.active) return true
            delay(STOP_CONFIRM_GAP_MS)
        }
        return false
    }

    private fun markFinished(deviceNo: String, message: String?, minutes: Int?) {
        closeSocket()
        val settlement = lastSettlement
        val elapsed = activeStartMillis?.let {
            ((System.currentTimeMillis() - it) / 60_000L).toInt()
        }
        activeStartMillis = null
        _uiState.update { state ->
            state.copy(
                busy = false,
                stage = ShowerWaterUiStage.Finished(
                    deviceNo = deviceNo,
                    message = message ?: settlement?.message,
                    amount = settlement?.amount,
                    minutes = minutes ?: settlement?.minutes,
                    elapsedMinutes = elapsed ?: minutes
                )
            )
        }
    }

    private fun currentStage(): ShowerWaterUiStage = _uiState.value.stage

    private fun currentActiveDevice(): String? =
        (_uiState.value.stage as? ShowerWaterUiStage.Active)?.deviceNo

    /** 「开始时间」（服务端给的是 `yyyy-MM-dd HH:mm:ss`）→ 毫秒；解析不了就返回 null。 */
    private fun parseServerTime(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching {
            SimpleDateFormat(SERVER_TIME_PATTERN, Locale.CHINA).parse(text)?.time
        }.getOrNull()
    }

    /** 已用分钟：优先用服务端开始时间，拿不到就用本进程记录的开单时刻。 */
    private fun elapsedMinutes(serverStart: String?): Int? {
        val start = parseServerTime(serverStart) ?: activeStartMillis ?: return null
        return ((System.currentTimeMillis() - start) / 60_000L).toInt().coerceAtLeast(0)
    }

    /**
     * 失败处理与 U净 一致：会话过期/凭据被拒 → 弹登录；其它 → 就地报错（可重试）。
     */
    private fun handleFailure(error: Throwable, fallback: Int) {
        val failure: AccessFailure = WbuFailureDetector.fromThrowable(error, AccessLayer.Service)
        Log.w(TAG, "shower water failed: $failure")
        if (failure.needsRelogin) {
            // 令牌可能已失效：清掉缓存，登录成功后重新取
            token = null
            _uiState.update { it.copy(busy = false, needLogin = true) }
            return
        }
        _uiState.update {
            it.copy(
                busy = false,
                needLogin = false,
                stage = ShowerWaterUiStage.Error(
                    accessFailureText(context, failure) ?: context.getString(fallback)
                )
            )
        }
    }
}
