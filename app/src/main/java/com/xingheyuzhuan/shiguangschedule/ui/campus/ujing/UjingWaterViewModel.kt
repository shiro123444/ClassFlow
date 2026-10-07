package com.xingheyuzhuan.shiguangschedule.ui.campus.ujing

import com.xingheyuzhuan.shiguangschedule.R

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessFailure
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessLayer
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingMqttClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuCampusCardClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuFailureDetector
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSessionExpiredException
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuUjingClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.needsRelogin
import com.xingheyuzhuan.shiguangschedule.ui.components.accessFailureText
import com.xingheyuzhuan.shiguangschedule.ui.components.silentUnifiedAuthLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface UjingWaterUiStage {
    data object Splash : UjingWaterUiStage
    data class Loading(val message: String) : UjingWaterUiStage
    data class Active(
        val subject: WbuUjingClient.WaterServiceSubject,
        val orderResult: WbuUjingClient.WaterOrderResult,
        val detail: WbuUjingClient.WaterOrderDetail?,
        val mqttConnected: Boolean
    ) : UjingWaterUiStage
    data class Finished(
        val subject: WbuUjingClient.WaterServiceSubject,
        val detail: WbuUjingClient.WaterOrderDetail
    ) : UjingWaterUiStage
    data class Error(val message: String, val canRetry: Boolean = true) : UjingWaterUiStage
}

data class UjingWaterUiState(
    val stage: UjingWaterUiStage = UjingWaterUiStage.Splash,
    val cd: String = "",
    val needLogin: Boolean = false
)

class UjingWaterViewModel(application: Application) : AndroidViewModel(application) {

    data class WaterSession(
        val cd: String,
        val orderId: Long,
        val subject: WbuUjingClient.WaterServiceSubject,
        val stage: UjingWaterUiStage,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "UjingWaterViewModel"
        private const val SESSION_VALID_DURATION_MS = 20 * 60 * 1000L // 20 分钟内防重入

        @Volatile
        private var lastSession: WaterSession? = null

        fun clearSession() {
            lastSession = null
        }

        /**
         * 本进程里这台设备是否还有**正在进行中**的取水订单（20 分钟内有效）。
         *
         * 取水会话只活在内存里（订单号、取水点都在其中），不落盘，所以：
         * - 为 true：订单还在跑，订单信息也还拿得到。再碰 NFC / 再扫码都不要再开一单
         *   （一个账号同时只有一个出水点），页面可以接着显示；
         * - 为 false：本进程没有这张订单的任何信息 —— 那就别装出「正在取水」的样子：
         *   重建出来的旧页面靠它判断自己该不该直接退掉。
         */
        fun hasResumableSession(cd: String): Boolean {
            val session = lastSession ?: return false
            return session.cd == cd.trim() &&
                session.stage is UjingWaterUiStage.Active &&
                System.currentTimeMillis() - session.timestamp < SESSION_VALID_DURATION_MS
        }
    }

    private val cardClient = WbuCampusCardClient(application, useVpn = false)
    private val ujingClient = WbuUjingClient(application, cardClient)

    private val _uiState = MutableStateFlow(UjingWaterUiState())
    val uiState: StateFlow<UjingWaterUiState> = _uiState.asStateFlow()

    private var mqttClient: UjingMqttClient? = null
    private var pollJob: Job? = null
    private var currentCd: String = ""
    private var hasStarted: Boolean = false

    /**
     * 启动饮水机扫码出水全流程。
     */
    fun start(cd: String) {
        val trimmed = cd.trim()
        if (trimmed.isBlank()) return

        // 1. 同一 ViewModel 实例防重入：若正在加载或正在出水中，防止重复触发
        if (hasStarted && (_uiState.value.stage is UjingWaterUiStage.Active || _uiState.value.stage is UjingWaterUiStage.Loading)) {
            Log.d(TAG, "start ignored: already active in stage=${_uiState.value.stage::class.simpleName}")
            return
        }

        // 2. 检查跨重建/跨实例的近期会话记录，防止后台切回重建时重复出水（仅针对未完成的活跃订单）
        val cached = lastSession
        val now = System.currentTimeMillis()
        if (cached != null && cached.cd == trimmed && (now - cached.timestamp < SESSION_VALID_DURATION_MS)) {
            if (cached.stage is UjingWaterUiStage.Active) {
                Log.i(TAG, "Resuming active water session for cd=$trimmed, orderId=${cached.orderId}")
                hasStarted = true
                currentCd = trimmed
                _uiState.update { it.copy(cd = trimmed, stage = cached.stage, needLogin = false) }
                startStatusWatch(trimmed, cached.orderId, cached.subject)
                return
            }
        }

        hasStarted = true
        currentCd = trimmed
        _uiState.update { it.copy(cd = currentCd, stage = UjingWaterUiStage.Splash, needLogin = false) }

        viewModelScope.launch {
            // 开场过场动画保持 800ms
            delay(800)
            executeFlow(currentCd)
        }
    }

    /**
     * 重新开始打水（用户在取水完成页再次触碰 NFC 或点击继续出水）。
     */
    fun restart(cd: String) {
        clearSession()
        stopStatusWatch()
        hasStarted = false
        _uiState.update { it.copy(stage = UjingWaterUiStage.Splash, needLogin = false) }
        start(cd)
    }

    private fun executeFlow(cd: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. 获取平台有效的 access_token
                _uiState.update { it.copy(stage = UjingWaterUiStage.Loading(getApplication<Application>().getString(R.string.status_verifying_campus_card))) }
                val platformToken = runCatching { cardClient.ensureValidAccessToken() }.getOrElse { e ->
                    if (e is WbuSessionExpiredException || e.message?.contains("失效") == true) {
                        // 会话失效：用保存的密码静默重登一次，再换一次平台票（都发生在下单之前）
                        val failure = silentLogin(
                            app = getApplication(),
                            stageText = getApplication<Application>().getString(R.string.status_login_saved_credentials)
                        )
                        if (failure != null) {
                            showLoginOrError(failure)
                            return@launch
                        }
                        runCatching { cardClient.ensureValidAccessToken() }.getOrElse { secondErr ->
                            Log.w(TAG, "Re-fetch platform token after silent login failed", secondErr)
                            showLoginOrError(
                                WbuFailureDetector.fromThrowable(secondErr, AccessLayer.CampusDirect)
                            )
                            return@launch
                        }
                    } else {
                        throw e
                    }
                }

                // 2. 建立 U净 会话（换票）
                _uiState.update { it.copy(stage = UjingWaterUiStage.Loading(getApplication<Application>().getString(R.string.status_connecting_ujing))) }
                ujingClient.connect(platformToken, appId = WbuUjingClient.WATER_APP_ID)

                // 3. 绑定取水点
                _uiState.update { it.copy(stage = UjingWaterUiStage.Loading(getApplication<Application>().getString(R.string.status_binding_water_point))) }
                val subject = ujingClient.bindWaterPoint(cd)

                // 4. 下单出水
                _uiState.update { it.copy(stage = UjingWaterUiStage.Loading(getApplication<Application>().getString(R.string.status_dispensing_water))) }
                val order = ujingClient.dispenseWater(cd)

                // 5. 进入出水进行中状态并开启监听
                val activeStage = UjingWaterUiStage.Active(
                    subject = subject,
                    orderResult = order,
                    detail = null,
                    mqttConnected = false
                )
                lastSession = WaterSession(
                    cd = cd,
                    orderId = order.orderId,
                    subject = subject,
                    stage = activeStage,
                    timestamp = System.currentTimeMillis()
                )
                _uiState.update { it.copy(stage = activeStage) }

                startStatusWatch(cd, order.orderId, subject)

            } catch (e: WbuSessionExpiredException) {
                _uiState.update { it.copy(needLogin = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Water flow error", e)
                _uiState.update {
                    it.copy(stage = UjingWaterUiStage.Error(e.localizedMessage ?: getApplication<Application>().getString(R.string.err_dispensing_failed)))
                }
            }
        }
    }

    /**
     * 结合 MQTT 长连接与 HTTP 轮询监听订单状态。
     */
    private fun startStatusWatch(
        cd: String,
        orderId: Long,
        subject: WbuUjingClient.WaterServiceSubject
    ) {
        stopStatusWatch()

        // 1. 启动 MQTT 长连接
        val mqtt = UjingMqttClient(
            deviceId = cd,
            targetOrderId = orderId,
            scope = viewModelScope
        ).also { mqttClient = it }

        viewModelScope.launch {
            mqtt.connectionState.collect { connected ->
                _uiState.update { current ->
                    if (current.stage is UjingWaterUiStage.Active) {
                        current.copy(stage = current.stage.copy(mqttConnected = connected))
                    } else current
                }
            }
        }

        viewModelScope.launch {
            mqtt.statusFlow.collect { mqttStatus ->
                // MQTT 推送到达，立即更新本地状态
                handleStatusUpdate(mqttStatus.status, orderId, subject)
            }
        }

        mqtt.start()

        // 2. 启动 HTTP 轮询兜底（每 2.5 秒拉取详情，补全出水量与扣费）
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            var consecutiveFails = 0
            while (isActive) {
                try {
                    val detail = ujingClient.fetchOrderDetail(orderId)
                    consecutiveFails = 0
                    updateWithDetail(detail, subject)

                    if (detail.isTerminal) {
                        Log.i(TAG, "Order reached terminal status via poll: ${detail.orderStatusName}")
                        markOrderFinished(subject, detail)
                        break
                    }
                } catch (e: Exception) {
                    consecutiveFails++
                    Log.w(TAG, "Poll order detail fail ($consecutiveFails)", e)
                    // 一直拉不到就放慢重试，但不彻底停：页面还开着，网络一恢复状态就能补上，
                    // 不会卡在「正在取水」上再也不动（页面关掉后协程自己结束）
                    if (consecutiveFails > 8) {
                        delay(10_000)
                        continue
                    }
                }
                delay(2500)
            }
        }
    }

    private fun markOrderFinished(
        subject: WbuUjingClient.WaterServiceSubject,
        detail: WbuUjingClient.WaterOrderDetail
    ) {
        stopStatusWatch()
        clearSession()
        hasStarted = false
        val finishedStage = UjingWaterUiStage.Finished(subject, detail)
        _uiState.update { it.copy(stage = finishedStage) }
    }

    private fun handleStatusUpdate(
        status: Int,
        orderId: Long,
        subject: WbuUjingClient.WaterServiceSubject
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val detail = runCatching { ujingClient.fetchOrderDetail(orderId) }.getOrNull()
            if (detail != null) {
                updateWithDetail(detail, subject)
                if (detail.isTerminal) {
                    Log.i(TAG, "Order reached terminal status via MQTT/fetch: ${detail.orderStatusName}")
                    markOrderFinished(subject, detail)
                }
            } else {
                // 如果一时拉不到详情，根据 status 简易更新
                _uiState.update { current ->
                    if (current.stage is UjingWaterUiStage.Active) {
                        val updatedDetail = current.stage.detail?.copy(
                            orderStatus = status,
                            orderStatusName = WbuUjingClient.orderStatusName(status),
                            isTerminal = status in WbuUjingClient.TERMINAL_STATUS
                        ) ?: WbuUjingClient.WaterOrderDetail(
                            orderId = orderId,
                            orderNo = current.stage.orderResult.orderNo,
                            orderStatus = status,
                            orderStatusName = WbuUjingClient.orderStatusName(status),
                            storeName = subject.storeName,
                            deviceNo = null,
                            orderTypeName = getApplication<Application>().getString(R.string.label_scan_qr_water),
                            payTypeName = getApplication<Application>().getString(R.string.label_campus_card_quick_pay),
                            hotWaterMl = 0,
                            warmWaterMl = 0,
                            payPrice = 0.0,
                            isTerminal = status in WbuUjingClient.TERMINAL_STATUS
                        )
                        current.copy(stage = current.stage.copy(detail = updatedDetail))
                    } else current
                }
            }
        }
    }

    private fun updateWithDetail(
        detail: WbuUjingClient.WaterOrderDetail,
        subject: WbuUjingClient.WaterServiceSubject
    ) {
        _uiState.update { current ->
            if (current.stage is UjingWaterUiStage.Active) {
                current.copy(stage = current.stage.copy(detail = detail))
            } else current
        }
    }

    private fun stopStatusWatch() {
        pollJob?.cancel()
        pollJob = null
        mqttClient?.stop()
        mqttClient = null
    }

    /**
     * 用保存的密码静默登录统一认证（缺 WebVPN 门禁密码 / 短信验证码 / 图形校验时就地弹小窗）。
     *
     * 调用时机：先让 `WbuCampusCardClient.ensureValidAccessToken()` 复用仍然有效的 **CASTGC**（免密换票），
     * 只有它缺失 / 失效时才走到这里。
     *
     * 实现统一交给 [silentUnifiedAuthLogin]：小窗走进程级通道（不再占用本页状态），失败原因也是结构化的，
     * 于是「只有确实要用户补新凭据才弹登录 Sheet」这条规则在本页同样成立。
     */
    private suspend fun silentLogin(app: Application, stageText: String): AccessFailure? {
        _uiState.update { it.copy(stage = UjingWaterUiStage.Loading(stageText)) }
        // 是否经 WebVPN 只由「统一认证经过WebVPN」决定：没开就完全不牵扯 WebVPN
        // （本项目里 /w/ 是直连服务，本来也不需要校园网）
        return silentUnifiedAuthLogin(
            context = app,
            flowTag = "UJING_WATER",
            viaWebVpn = WbuAuthTransport.getIdsViaWebVpn(app),
            // 本机没存密码就别弹小窗问：直接回落到登录 Sheet（本页的老行为）
            onlyWithSavedPassword = true
        )
    }

    /**
     * 静默登录失败后的收尾。
     *
     * **只有**「会话失效 / 凭据被拒」才把用户请去登录 Sheet；用户取消小窗、网络不通、服务端异常
     * 都只留一条可重试的说明 —— 以前这里任何失败都会弹 Sheet，用户在小窗上点个取消也会被顶一脸登录框。
     */
    private fun showLoginOrError(failure: AccessFailure) {
        val app = getApplication<Application>()
        if (failure.needsRelogin) {
            _uiState.update { it.copy(needLogin = true) }
            return
        }
        _uiState.update {
            it.copy(
                needLogin = false,
                stage = UjingWaterUiStage.Error(
                    accessFailureText(app, failure)
                        ?: app.getString(R.string.err_need_unified_auth_session)
                )
            )
        }
    }

    fun onLoginSuccess() {
        _uiState.update { it.copy(needLogin = false) }
        if (currentCd.isNotBlank()) {
            executeFlow(currentCd)
        }
    }

    fun onLoginDismissed() {
        _uiState.update {
            it.copy(
                needLogin = false,
                stage = UjingWaterUiStage.Error(getApplication<Application>().getString(R.string.err_login_required_water), canRetry = false)
            )
        }
    }

    fun retry() {
        if (currentCd.isNotBlank()) {
            clearSession()
            hasStarted = true
            executeFlow(currentCd)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopStatusWatch()
    }
}
