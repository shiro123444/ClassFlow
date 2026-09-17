package com.xingheyuzhuan.shiguangschedule.ui.campus.ujing

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingMqttClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuCampusCardClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSessionExpiredException
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuUjingClient
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

        // 1. 同一 ViewModel 实例防重入：已启动且不在初始 Splash 状态，直接跳过
        if (hasStarted && _uiState.value.stage !is UjingWaterUiStage.Splash) {
            Log.d(TAG, "start ignored: already started in stage=${_uiState.value.stage::class.simpleName}")
            return
        }

        // 2. 检查跨重建/跨实例的近期会话记录，防止后台切回重建时重复出水
        val cached = lastSession
        val now = System.currentTimeMillis()
        if (cached != null && cached.cd == trimmed && (now - cached.timestamp < SESSION_VALID_DURATION_MS)) {
            when (val cachedStage = cached.stage) {
                is UjingWaterUiStage.Finished -> {
                    Log.i(TAG, "Restoring finished water session for cd=$trimmed, orderId=${cached.orderId}")
                    hasStarted = true
                    currentCd = trimmed
                    _uiState.update { it.copy(cd = trimmed, stage = cachedStage, needLogin = false) }
                    return
                }
                is UjingWaterUiStage.Active -> {
                    Log.i(TAG, "Resuming active water session for cd=$trimmed, orderId=${cached.orderId}")
                    hasStarted = true
                    currentCd = trimmed
                    _uiState.update { it.copy(cd = trimmed, stage = cachedStage, needLogin = false) }
                    startStatusWatch(trimmed, cached.orderId, cached.subject)
                    return
                }
                else -> { /* 其它瞬态不恢复 */ }
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

    private fun executeFlow(cd: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. 获取平台有效的 access_token
                _uiState.update { it.copy(stage = UjingWaterUiStage.Loading("正在验证一卡通凭据...")) }
                val platformToken = runCatching { cardClient.ensureValidAccessToken() }.getOrElse { e ->
                    if (e is WbuSessionExpiredException || e.message?.contains("失效") == true) {
                        _uiState.update { it.copy(needLogin = true) }
                        return@launch
                    }
                    throw e
                }

                // 2. 建立 U净 会话（换票）
                _uiState.update { it.copy(stage = UjingWaterUiStage.Loading("正在连接 U净 校园饮水...")) }
                ujingClient.connect(platformToken, appId = WbuUjingClient.WATER_APP_ID)

                // 3. 绑定取水点
                _uiState.update { it.copy(stage = UjingWaterUiStage.Loading("正在绑定取水点...")) }
                val subject = ujingClient.bindWaterPoint(cd)

                // 4. 下单出水
                _uiState.update { it.copy(stage = UjingWaterUiStage.Loading("正在出水，请稍候...")) }
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
                    it.copy(stage = UjingWaterUiStage.Error(e.localizedMessage ?: "出水流程失败"))
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
                    if (consecutiveFails > 8) {
                        Log.e(TAG, "Poll failed too many times, stopping")
                        break
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
        val finishedStage = UjingWaterUiStage.Finished(subject, detail)
        lastSession = WaterSession(
            cd = currentCd,
            orderId = detail.orderId,
            subject = subject,
            stage = finishedStage,
            timestamp = System.currentTimeMillis()
        )
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
                            orderTypeName = "扫码取水",
                            payTypeName = "一卡通免密",
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
                stage = UjingWaterUiStage.Error("需要登录统一认证以使用饮水机服务", canRetry = false)
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
