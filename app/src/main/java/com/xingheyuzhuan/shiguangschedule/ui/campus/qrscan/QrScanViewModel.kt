package com.xingheyuzhuan.shiguangschedule.ui.campus.qrscan

import android.content.Context
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    private val _transientError = MutableStateFlow<QrScanError?>(null)
    val transientError: StateFlow<QrScanError?> = _transientError.asStateFlow()

    /** WebVPN 证书校验异常询问（与登录 Sheet 同一条链路）。 */
    private val _tlsPrompt = MutableStateFlow<String?>(null)
    val tlsPrompt: StateFlow<String?> = _tlsPrompt.asStateFlow()

    /** 当前解码引擎（持久化，默认 ML Kit）。 */
    private val _scanEngine = MutableStateFlow(WbuAuthTransport.getQrScanEngine(context))
    val scanEngine: StateFlow<QrScanEngine> = _scanEngine.asStateFlow()

    private var tlsDeferred: CompletableDeferred<Boolean>? = null
    private var transientJob: Job? = null
    private var lastRejectNoticeAt = 0L

    /** 已成功送入扫描流程的 uuid，避免相机高频回调重复触发。 */
    private var handledUuid: String? = null

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
        if (_state.value !is QrScanUiState.Scanning) return

        val uuid = CasQrLink.parseUuid(raw)
        if (uuid == null) {
            notifyRejected()
            return
        }
        if (uuid == handledUuid) return
        handledUuid = uuid

        viewModelScope.launch {
            when (engine.scanPeerQrCode(uuid)) {
                QrScanOutcome.SCANNED -> _state.value = QrScanUiState.Scanned(uuid)
                QrScanOutcome.NEED_LOGIN -> _state.value = QrScanUiState.NeedLogin
                QrScanOutcome.EXPIRED -> _state.value = QrScanUiState.Failed(QrScanError.EXPIRED)
                QrScanOutcome.ERROR -> _state.value = QrScanUiState.Failed(QrScanError.NETWORK)
            }
        }
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
        _transientError.value = null
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

    private fun notifyRejected() {
        val now = System.currentTimeMillis()
        if (now - lastRejectNoticeAt < 2000L) return
        lastRejectNoticeAt = now
        _transientError.value = QrScanError.NOT_CAS_QR
        transientJob?.cancel()
        transientJob = viewModelScope.launch {
            delay(2000)
            _transientError.value = null
        }
    }
}
