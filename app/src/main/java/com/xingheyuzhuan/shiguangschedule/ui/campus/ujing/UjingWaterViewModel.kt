package com.xingheyuzhuan.shiguangschedule.ui.campus.ujing

import com.xingheyuzhuan.shiguangschedule.R

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingMqttClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuCampusCardClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSessionExpiredException
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuUjingClient
import com.xingheyuzhuan.shiguangschedule.ui.components.WbuAuthPromptRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** 静默登录时向 UI 索取的补充输入（WebVPN 密码 / 短信验证码），null 表示当前无需输入。 */
    private val _authPrompt = MutableStateFlow<WbuAuthPromptRequest?>(null)
    val authPrompt: StateFlow<WbuAuthPromptRequest?> = _authPrompt.asStateFlow()

    private var authPromptDeferred: CompletableDeferred<String?>? = null
    private var silentLoginEngine: WbuSyncEngine? = null

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
                        // 尝试静默使用已保存的统一认证密码登录
                        val autoLoginOk = trySilentUnifiedAuthLogin()
                        if (autoLoginOk) {
                            // 静默登录成功，再次获取 access_token
                            runCatching { cardClient.ensureValidAccessToken() }.getOrElse { secondErr ->
                                Log.w(TAG, "Re-fetch platform token after silent login failed", secondErr)
                                _uiState.update { it.copy(needLogin = true) }
                                return@launch
                            }
                        } else {
                            // 无密码或静默登录失败，降级弹起登录 Sheet
                            _uiState.update { it.copy(needLogin = true) }
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
     * 尝试使用已保存的统一认证凭据进行后台静默登录。
     *
     * 调用时机：先让 `WbuCampusCardClient.ensureValidAccessToken()` 复用仍然有效的 **CASTGC**（免密换票），
     * 只有它缺失 / 失效时才走到这里；这里再失败（没保存密码、需要滑块、用户取消）才把 `needLogin`
     * 交给 UI 弹登录 Sheet。
     *
     * 经 WebVPN 时：本地存了 WebVPN 密码就直接用，没存则弹小窗让用户补一次门禁密码 + 短信验证码，
     * 补了还是不行才回落到 Sheet —— 全程只影响这一条静默链路，不会把主界面顶掉。
     */
    private suspend fun trySilentUnifiedAuthLogin(): Boolean {
        val app = getApplication<Application>()
        val studentId = WbuAuthTransport.getSavedStudentId(app)
        val password = WbuAuthTransport.getSavedPassword(app, CredentialService.UNIFIED_AUTH)

        if (studentId.isBlank() || password.isNullOrBlank()) {
            Log.d(TAG, "No saved unified auth credentials found for silent login")
            return false
        }

        return try {
            _uiState.update { it.copy(stage = UjingWaterUiStage.Loading(app.getString(R.string.status_login_saved_credentials))) }
            // 是否经 WebVPN 只由「统一认证经过WebVPN」决定：没开就完全不牵扯 WebVPN
            // （本项目里 /w/ 是直连服务，本来也不需要校园网）
            val viaWebVpn = WbuAuthTransport.getIdsViaWebVpn(app)
            val engine = WbuSyncEngine(app, useVpn = viaWebVpn)
            silentLoginEngine = engine
            val success = engine.loginUnifiedAuthOnly(
                studentId = studentId,
                password = password,
                viaWebVpn = viaWebVpn,
                flowTag = "UJING_WATER_AUTO_AUTH",
                vpnPasswordProvider = {
                    WbuAuthTransport.getSavedVpnPassword(app)?.takeIf { it.isNotBlank() }
                        ?: requestAuthPrompt(WbuAuthPromptRequest.VpnPassword)
                },
                smsCodeProvider = { maskedPhone, isStillValid, sendInterval, promptText ->
                    requestAuthPrompt(
                        WbuAuthPromptRequest.SmsCode(
                            maskedPhone = maskedPhone,
                            isStillValid = isStillValid,
                            sendInterval = sendInterval,
                            promptText = promptText
                        )
                    )
                }
            )
            Log.i(TAG, "Silent unified auth login result: $success")
            success
        } catch (e: Exception) {
            Log.w(TAG, "Silent unified auth login failed", e)
            false
        } finally {
            silentLoginEngine = null
            dismissAuthPrompt()
        }
    }

    /** UI 提交（传 null = 取消）当前补充输入弹窗。 */
    fun submitAuthPrompt(value: String?) {
        val deferred = authPromptDeferred
        authPromptDeferred = null
        _authPrompt.value = null
        deferred?.complete(value)
    }

    /** 短信验证码「重新发送」。 */
    fun resendVpnSmsCode() {
        val engine = silentLoginEngine ?: return
        viewModelScope.launch {
            runCatching { engine.resendVpnSmsCode() }
                .onFailure { Log.w(TAG, "重新发送短信验证码失败", it) }
        }
    }

    /** 向 UI 索取一次补充输入，挂起直到用户提交或取消。 */
    private suspend fun requestAuthPrompt(request: WbuAuthPromptRequest): String? {
        val deferred = CompletableDeferred<String?>()
        withContext(Dispatchers.Main) {
            authPromptDeferred = deferred
            _authPrompt.value = request
        }
        return deferred.await()
    }

    private fun dismissAuthPrompt() {
        authPromptDeferred = null
        _authPrompt.value = null
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
