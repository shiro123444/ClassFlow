package com.xingheyuzhuan.shiguangschedule.ui.campus.qrscan

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.QrScanEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.CasQrLink
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.QrConfirmOutcome
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.QrScanOutcome
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "QrScanViewModel"

/** 扫码失败类型。 */
enum class QrScanError {
    /** 扫到的不是统一认证登录二维码。 */
    NOT_CAS_QR,

    /** 二维码已失效。 */
    EXPIRED,

    /** 网络异常等。 */
    NETWORK,
}

/** 扫一扫界面状态。 */
sealed interface QrScanUiState {
    /** 取景中。 */
    data object Scanning : QrScanUiState

    /** 本机未登录统一认证，需先登录。 */
    data object NeedLogin : QrScanUiState

    /** 已扫描（PC 端状态置 2），等待用户确认。 */
    data class Scanned(val uuid: String) : QrScanUiState

    /** 确认中。 */
    data class Confirming(val uuid: String) : QrScanUiState

    /** 已确认（PC 端状态置 1）。 */
    data object Success : QrScanUiState

    /** 失败，需用户重试或重新扫描。 */
    data class Failed(val kind: QrScanError) : QrScanUiState
}

/** 取景期间的一次性提示（含相册选图路径）。 */
enum class QrTransientNotice { NOT_CAS_QR, PHOTO_NO_CODE }

sealed interface QrScanEvent {
    data class NavigateToWater(val cd: String) : QrScanEvent
    data class NavigateToWasher(val initialUrl: String?, val pendingAutoScan: String) : QrScanEvent
    data class OpenHairdryer(val cd: String, val scheme: String, val ulinkUrl: String) : QrScanEvent
}

/**
 * 扫一扫（扫码端）逻辑：以本机已有的统一认证会话替 PC/其它端确认登录。
 *
 * 请求序列见 WBUCas/qr_scan_notes.md 第四节：
 * `qrCodeLogin.do?uuid=`（置 2）→ `qrCodeConfirm.do`（置 1）。
 */
@HiltViewModel
class QrScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private var engine = createEngine()

    private val _state = MutableStateFlow<QrScanUiState>(
        if (engine.hasUnifiedAuthSession()) QrScanUiState.Scanning else QrScanUiState.NeedLogin
    )
    val state: StateFlow<QrScanUiState> = _state.asStateFlow()

    /** 取景期间的一次性提示（如「不是统一认证二维码」），不中断取景。 */
    private val _transientNotice = MutableStateFlow<QrTransientNotice?>(null)
    val transientNotice: StateFlow<QrTransientNotice?> = _transientNotice.asStateFlow()

    /** 相册选图解码中（用于禁用入口并显示进度）。 */
    private val _photoBusy = MutableStateFlow(false)
    val photoBusy: StateFlow<Boolean> = _photoBusy.asStateFlow()

    /** WebVPN 证书校验异常询问（与登录 Sheet 同一条链路）。 */
    private val _tlsPrompt = MutableStateFlow<String?>(null)
    val tlsPrompt: StateFlow<String?> = _tlsPrompt.asStateFlow()

    /** 当前解码引擎（持久化，默认 ML Kit）。 */
    private val _scanEngine = MutableStateFlow(WbuAuthTransport.getQrScanEngine(context))
    val scanEngine: StateFlow<QrScanEngine> = _scanEngine.asStateFlow()

    /** 扫码分流事件（跳转原生饮水机 / 跳转洗衣机 WebApp）。 */
    private val _scanEvent = kotlinx.coroutines.flow.MutableSharedFlow<QrScanEvent>(extraBufferCapacity = 8)
    val scanEvent: kotlinx.coroutines.flow.SharedFlow<QrScanEvent> = _scanEvent.asSharedFlow()

    /** 吹风机暂不支持一卡通扫码对话框。 */
    private val _hairdryerPrompt = MutableStateFlow(false)
    val hairdryerPrompt: StateFlow<Boolean> = _hairdryerPrompt.asStateFlow()

    /** 洗衣机核验中状态。 */
    private val _washerLoading = MutableStateFlow(false)
    val washerLoading: StateFlow<Boolean> = _washerLoading.asStateFlow()

    /** 洗衣机设备离线提示。 */
    private val _washerOffline = MutableStateFlow(false)
    val washerOffline: StateFlow<Boolean> = _washerOffline.asStateFlow()

    private var tlsDeferred: CompletableDeferred<Boolean>? = null
    private var transientJob: Job? = null
    private var lastRejectNoticeAt = 0L

    /** 已成功送入扫描流程的 uuid，避免相机高频回调重复触发。 */
    private var handledUuid: String? = null

    /** 已处理过的 U净 二维码原文，避免相机高频回调重复触发。 */
    private var handledUjing: String? = null

    init {
        attachSslHandler(engine)
    }

    private fun createEngine(): WbuSyncEngine =
        WbuSyncEngine(context, WbuSyncEngine.getSavedUseVpn(context) ?: false)

    private fun attachSslHandler(target: WbuSyncEngine) {
        target.sslIssueHandler = { message ->
            val deferred = CompletableDeferred<Boolean>()
            tlsDeferred = deferred
            _tlsPrompt.value = message
            deferred.await().also {
                _tlsPrompt.value = null
                tlsDeferred = null
            }
        }
    }

    /** 相机解码到一段二维码原文。 */
    fun onCodeDecoded(raw: String) {
        android.util.Log.i(TAG, "Decoded: ${raw.take(160)}")
        // U净 设备码优先识别：不依赖统一认证登录态（登录由目标页自行处理），
        // 因此即使当前处于 NeedLogin 状态也能正常分流。
        if (handleUjingIfMatched(raw)) return
        if (_state.value !is QrScanUiState.Scanning) return
        submitDecoded(raw)
    }

    /**
     * 从相册选图解码：不依赖相机权限，因此 Scanning/NeedLogin/Failed 都可以直接用，
     * 只有「确认中 / 已完成」这两步不接受中途换一张图。
     */
    fun onPhotoPicked(uri: Uri) {
        val current = _state.value
        if (current is QrScanUiState.Confirming || current is QrScanUiState.Success) return

        viewModelScope.launch {
            _photoBusy.value = true
            val raw = withContext(Dispatchers.IO) {
                QrImageDecoder.decode(context, uri, _scanEngine.value)
            }
            _photoBusy.value = false

            // 用户主动选图：允许重复提交同一张（否则失败后重选会被去重逻辑挡掉）
            handledUuid = null
            handledUjing = null
            if (raw.isNullOrBlank()) notifyPhotoNoCode() else submitDecoded(raw)
        }
    }

    /** 解析并送入扫码流程（相机与相册共用）。 */
    private fun submitDecoded(raw: String) {
        val casUuid = CasQrLink.parseUuid(raw)
        if (casUuid == null) {
            if (handleUjingIfMatched(raw)) return
            notifyRejected()
            return
        }
        if (casUuid == handledUuid) return
        handledUuid = casUuid

        viewModelScope.launch {
            when (engine.scanPeerQrCode(casUuid)) {
                QrScanOutcome.SCANNED -> _state.value = QrScanUiState.Scanned(casUuid)
                QrScanOutcome.NEED_LOGIN -> _state.value = QrScanUiState.NeedLogin
                QrScanOutcome.EXPIRED -> _state.value = QrScanUiState.Failed(QrScanError.EXPIRED)
                QrScanOutcome.ERROR -> _state.value = QrScanUiState.Failed(QrScanError.NETWORK)
            }
        }
    }

    /**
     * U净 贴纸 / 设备二维码分流。命中返回 true。
     */
    private fun handleUjingIfMatched(raw: String): Boolean {
        val ujing = com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.parse(raw) ?: return false
        if (raw == handledUjing) return true
        handledUjing = raw
        android.util.Log.i(TAG, "Ujing QR matched: $ujing")

        when (ujing) {
            is com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.Result.Water -> {
                _scanEvent.tryEmit(QrScanEvent.NavigateToWater(ujing.cd))
            }

            is com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.Result.Hairdryer -> {
                val scheme = com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.buildHairdryerAlipayScheme(ujing.cd)
                val ulink = com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.buildHairdryerAlipayUrl(ujing.cd)
                _scanEvent.tryEmit(QrScanEvent.OpenHairdryer(cd = ujing.cd, scheme = scheme, ulinkUrl = ulink))
            }

            is com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.Result.Washer -> {
                handleWasher(ujing)
            }
        }
        return true
    }

    private fun handleWasher(washer: com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.Result.Washer) {
        viewModelScope.launch {
            _washerLoading.value = true
            val encodedRaw = runCatching { java.net.URLEncoder.encode(washer.raw, "UTF-8") }.getOrDefault(washer.raw)
            try {
                val cardClient = com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuCampusCardClient(context, useVpn = false)
                val ujingClient = com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuUjingClient(context, cardClient)
                val token = cardClient.ensureValidAccessToken()
                ujingClient.connect(token, appId = com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuUjingClient.WASHER_APP_ID)
                val result = ujingClient.scanWasherCode(washer.raw)
                _washerLoading.value = false

                if (!result.online) {
                    _washerOffline.value = true
                } else {
                    val launchUrl = cardClient.resolveAppLaunchUrl(com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuUjingClient.WASHER_APP_ID, token)
                    val separator = if (launchUrl?.contains("?") == true) "&" else "?"
                    val initialUrl = if (launchUrl != null) "${launchUrl}${separator}scanResult=$encodedRaw" else null
                    _scanEvent.emit(QrScanEvent.NavigateToWasher(initialUrl = initialUrl, pendingAutoScan = washer.raw))
                }
            } catch (e: Exception) {
                _washerLoading.value = false
                android.util.Log.w(TAG, "Washer scan failed, fallback to direct open", e)
                val cardClient = com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuCampusCardClient(context, useVpn = false)
                val token = runCatching { cardClient.ensureValidAccessToken() }.getOrNull()
                val launchUrl = if (token != null) cardClient.resolveAppLaunchUrl(com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuUjingClient.WASHER_APP_ID, token) else null
                val separator = if (launchUrl?.contains("?") == true) "&" else "?"
                val initialUrl = if (launchUrl != null) "${launchUrl}${separator}scanResult=$encodedRaw" else null
                _scanEvent.emit(QrScanEvent.NavigateToWasher(initialUrl = initialUrl, pendingAutoScan = washer.raw))
            }
        }
    }

    fun dismissHairdryerDialog() {
        _hairdryerPrompt.value = false
        handledUjing = null
        handledUuid = null
    }

    fun dismissWasherOfflineDialog() {
        _washerOffline.value = false
        handledUjing = null
        handledUuid = null
    }

    /** 确认登录（PC 端状态置 1）。 */
    fun confirm() {
        val current = _state.value
        if (current !is QrScanUiState.Scanned) return
        _state.value = QrScanUiState.Confirming(current.uuid)

        viewModelScope.launch {
            when (engine.confirmPeerQrCode(current.uuid)) {
                QrConfirmOutcome.CONFIRMED -> _state.value = QrScanUiState.Success
                QrConfirmOutcome.NEED_LOGIN -> _state.value = QrScanUiState.NeedLogin
                QrConfirmOutcome.EXPIRED -> _state.value = QrScanUiState.Failed(QrScanError.EXPIRED)
                QrConfirmOutcome.ERROR -> _state.value = QrScanUiState.Failed(QrScanError.NETWORK)
            }
        }
    }

    /** 重新扫描（重扫/重试）。 */
    fun rescan() {
        handledUuid = null
        handledUjing = null
        _transientNotice.value = null
        transientJob?.cancel()
        _state.value = if (engine.hasUnifiedAuthSession()) QrScanUiState.Scanning else QrScanUiState.NeedLogin
    }

    /**
     * 切换解码引擎并持久化；当前处于失败态时立即重扫，
     * 让用户换引擎后不用再点一次「重试」。
     */
    fun selectScanEngine(target: QrScanEngine) {
        if (target == _scanEngine.value) return
        _scanEngine.value = target
        WbuAuthTransport.setQrScanEngine(context, target)
        if (_state.value is QrScanUiState.Failed) rescan()
    }

    /** 登录 Sheet 登录成功后：可能切换了 WebVPN 模式，重建引擎再回到取景。 */
    fun onLoginSuccess() {
        engine = createEngine().also { attachSslHandler(it) }
        rescan()
    }

    /** 回应用户对 WebVPN 证书的信任询问。 */
    fun resolveTlsPrompt(allow: Boolean) {
        tlsDeferred?.complete(allow)
    }

    /** 相机每帧都会回调，扫描非统一认证码时用它节流提示。 */
    private fun notifyRejected() {
        val now = System.currentTimeMillis()
        if (now - lastRejectNoticeAt < 2000L) return
        lastRejectNoticeAt = now
        showNotice(QrTransientNotice.NOT_CAS_QR)
    }

    private fun notifyPhotoNoCode() {
        showNotice(QrTransientNotice.PHOTO_NO_CODE)
    }

    private fun showNotice(notice: QrTransientNotice) {
        _transientNotice.value = notice
        transientJob?.cancel()
        transientJob = viewModelScope.launch {
            delay(2000)
            _transientNotice.value = null
        }
    }
}
