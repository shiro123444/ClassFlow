package com.xingheyuzhuan.shiguangschedule.ui.campus.qrscan

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.QrScanEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.CampusShowerEntryResolver
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.CampusShowerLink
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.CasQrLink
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.QrConfirmOutcome
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.QrScanOutcome
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.ShowerQrLink
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WasherAvailability
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.ui.components.shouldAttemptSavedPasswordLogin
import com.xingheyuzhuan.shiguangschedule.ui.components.silentUnifiedAuthLogin
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

/** 普通一次性提示的显示时长。 */
private const val NOTICE_DURATION_MS = 2000L

/** 跟码提示存活时间：相机持续回调同一条码即续期，停止回调超过这个时间就认定「码已移开」。 */
private const val STICKY_NOTICE_TIMEOUT_MS = 2000L

/** 跟码提示的续期检查间隔。 */
private const val STICKY_NOTICE_POLL_MS = 200L

/** 扫码失败类型。 */
enum class QrScanError {
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
enum class QrTransientNotice {
    /** 扫到的码不属于任何已支持的分流（统一认证 / 设备码 / 通用链接节点）。 */
    UNRECOGNIZED,

    /** 相册选图里没解出二维码。 */
    PHOTO_NO_CODE,

    /** 洗浴设备号经服务端确认无效（如控水器编号不存在）。跟码提示：码不离开取景框就一直显示。 */
    SHOWER_INVALID,

    /** 洗浴校验过程本身失败（网络 / 服务异常），移开重扫可再试。跟码提示。 */
    SHOWER_UNAVAILABLE
}

sealed interface QrScanEvent {
    data class NavigateToWater(val cd: String) : QrScanEvent
    data class NavigateToWasher(val initialUrl: String?, val pendingAutoScan: String) : QrScanEvent
    data class OpenHairdryer(val cd: String, val scheme: String, val ulinkUrl: String) : QrScanEvent
    /** 通用链接节点（`/url/{code}`、短别名 `/u/{code}`）。 */
    data class OpenLinkHub(val code: String?, val inline: String?, val origin: String) : QrScanEvent

    /**
     * 洗浴控水设备（马影河 1 栋「智能控水」/ 2-3 栋 lifeService）。
     *
     * [initialUrl] 为带票据的深链；[pendingAutoScan] 非空时由 WebAppScreen
     * 在页面就绪后自动执行（1 栋裸码），2-3 栋直接拼在 URL 的 `scanResult` 上。
     */
    data class NavigateToShower(
        val initialUrl: String?,
        val pendingAutoScan: String?,
        /** 非空表示已正证是 2-3 栋淋浴：直接进原生用水页，不加载网页。 */
        val nativeShower: CampusShowerEntryResolver.NativeShower? = null
    ) : QrScanEvent
}

/**
 * 扫一扫（扫码端）逻辑：以本机已有的统一认证会话替 PC/其它端确认登录。
 *
 * 请求序列见 WBUCas/qr_scan_notes.md 第四节：
 * `qrCodeLogin.do?uuid=`（置 2）→ `qrCodeConfirm.do`（置 1）。
 *
 * 本页**不以「本机有没有统一认证会话」当门面条件**：没登录也照常取景、照常分流
 * （U净 / 洗浴 / 通用链接节点本就不需要统一认证），只有真扫到需要 CAS 的码时才就地补一次登录
 * （[loginThenRetry]），补不上才落到 [QrScanUiState.NeedLogin] 面板，由用户自己点登录。
 */
@HiltViewModel
class QrScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val showerResolver: CampusShowerEntryResolver
) : ViewModel() {

    private var engine = createEngine()

    /** 进页面即取景：是否登录统一认证不影响能不能扫，等到真要用了再补（见 [loginThenRetry]）。 */
    private val _state = MutableStateFlow<QrScanUiState>(QrScanUiState.Scanning)
    val state: StateFlow<QrScanUiState> = _state.asStateFlow()

    /** 取景期间的一次性提示（码不属于任何已支持的分流、洗浴设备校验失败等），不中断取景。 */
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

    /**
     * 洗衣机当前不可下单的提示（离线 / 他人占用 / 已被预约 / 故障 / 停用 / 码无效），
     * null 表示不显示。文案见 `WasherNoticeDialog`。
     */
    private val _washerNotice = MutableStateFlow<WasherAvailability.Unavailable?>(null)
    val washerNotice: StateFlow<WasherAvailability.Unavailable?> = _washerNotice.asStateFlow()

    /** 洗浴设备核验中状态。 */
    private val _showerChecking = MutableStateFlow(false)
    val showerChecking: StateFlow<Boolean> = _showerChecking.asStateFlow()

    private var tlsDeferred: CompletableDeferred<Boolean>? = null
    private var transientJob: Job? = null
    private var lastRejectNoticeAt = 0L

    /** 当前「跟码」提示的类型、对应原文，以及最后一次在取景框里看到它的时间。 */
    private var stickyNotice: QrTransientNotice? = null
    private var stickyRaw: String? = null
    private var stickyLastSeenAt = 0L
    private var stickyJob: Job? = null

    /** 已成功送入扫描流程的 uuid，避免相机高频回调重复触发。 */
    private var handledUuid: String? = null

    /** 已处理过的 U净 二维码原文，避免相机高频回调重复触发。 */
    private var handledUjing: String? = null

    /** 已处理过的通用链接节点原文，避免相机高频回调重复触发。 */
    private var handledLinkHub: String? = null

    /** 已处理过的洗浴设备二维码原文，避免相机高频回调重复触发。 */
    private var handledShower: String? = null

    /** 本页是否已自动补过一遍统一认证登录：只试一次，重扫同一类码不再反复登录（成环只会白烧服务端失败次数）。 */
    private var silentLoginAttempted = false

    /** 因「本机没有统一认证会话」而中断的那一步；登录成功后原样重放，用户不必再扫一次。 */
    private var pendingAuthRetry: (suspend () -> Unit)? = null

    init {
        attachSslHandler(engine)
    }

    /**
     * 扫到需要统一认证的码时才就近补一次登录，登录成功就把刚才中断的那一步原样重放。
     *
     * 重放是安全的：未登录时 `qrCodeLogin.do` 只会被 CAS 挡回来（uuid 不会被置为「已扫描」），
     * 所以登录后重放不会破坏 PC 端状态；顺带省掉「登录完还得把码再扫一遍」这一步。
     *
     * 以下情况直接落到 [QrScanUiState.NeedLogin] 面板，让用户自己决定（与改动前的门面行为一致）：
     * - 「自动使用保存的密码登录」关着，或本机根本没存统一认证密码（闸门见 [shouldAttemptSavedPasswordLogin]）；
     * - 本次进页面已经自动试过一遍（[silentLoginAttempted]）；
     * - 补登录没成（网络 / 用户取消 / 服务端拒绝）：原因由随后的登录面板 / Sheet 承接。
     *
     * 静默过程中的补输入小窗（WebVPN 门禁密码 / 短信验证码 / 图形校验）由 App 根部的
     * `WbuAuthPromptHost` 就地弹出，本页不用自己挂。
     */
    private fun loginThenRetry(retry: suspend () -> Unit) {
        pendingAuthRetry = retry
        if (silentLoginAttempted || !shouldAttemptSavedPasswordLogin(context)) {
            _state.value = QrScanUiState.NeedLogin
            return
        }
        silentLoginAttempted = true
        viewModelScope.launch {
            val failure = silentUnifiedAuthLogin(
                context = context,
                flowTag = "QR_SCAN",
                viaWebVpn = WbuAuthTransport.getIdsViaWebVpn(context),
                // 绝不因为「缺密码」在扫码时就地弹密码小窗：没存密码就走登录面板
                onlyWithSavedPassword = true
            )
            // 静默登录换掉了进程级 Cookie 罐里的会话，本页的 engine 要重建才能看到新的 CASTGC
            engine = createEngine().also { attachSslHandler(it) }
            if (failure == null && engine.hasUnifiedAuthSession()) {
                runPendingRetry()
            } else {
                _state.value = QrScanUiState.NeedLogin
            }
        }
    }

    /** 重放因缺会话而中断的那一步；没有待续动作时返回 false。 */
    private suspend fun runPendingRetry(): Boolean {
        val retry = pendingAuthRetry ?: return false
        pendingAuthRetry = null
        retry()
        return true
    }

    private fun createEngine(): WbuSyncEngine =
        WbuSyncEngine(context, WbuSyncEngine.getSavedUseVpn(context))

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
        // 同一条码还在取景框里：给「跟码」提示续期。相机对同一条码是每帧持续回调的，
        // 所以只要不移开，提示就不会消失；一旦停止回调（码被移走）就由轮询收掉。
        if (raw == stickyRaw) stickyLastSeenAt = System.currentTimeMillis()
        // U净 / 洗浴设备码优先识别：不依赖统一认证登录态（登录由目标页自行处理），
        // 因此即使当前处于 NeedLogin 状态也能正常分流。
        if (handleUjingIfMatched(raw)) return
        if (handleShowerIfMatched(raw)) return
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
            handledLinkHub = null
            handledShower = null
            if (raw.isNullOrBlank()) notifyPhotoNoCode() else submitDecoded(raw)
        }
    }

    /** 解析并送入扫码流程（相机与相册共用）。 */
    private fun submitDecoded(raw: String) {
        val casUuid = CasQrLink.parseUuid(raw)
        if (casUuid == null) {
            if (handleCampusLinkIfMatched(raw)) return
            notifyRejected()
            return
        }
        if (casUuid == handledUuid) return
        handledUuid = casUuid
        clearStickyNotice()

        viewModelScope.launch {
            scanPeer(casUuid)
        }
    }

    /** 扫码端「已扫描」（PC 端置 2）。缺统一认证会话时就地补登录一次再重放本步。 */
    private suspend fun scanPeer(casUuid: String) {
        when (engine.scanPeerQrCode(casUuid)) {
            QrScanOutcome.SCANNED -> _state.value = QrScanUiState.Scanned(casUuid)
            QrScanOutcome.NEED_LOGIN -> loginThenRetry { scanPeer(casUuid) }
            QrScanOutcome.EXPIRED -> _state.value = QrScanUiState.Failed(QrScanError.EXPIRED)
            QrScanOutcome.ERROR -> _state.value = QrScanUiState.Failed(QrScanError.NETWORK)
        }
    }

    /**
     * 校园直达链接分流：U净 设备码 / 通用链接节点 / 洗浴控水设备。命中返回 true。
     */
    private fun handleCampusLinkIfMatched(raw: String): Boolean =
        handleUjingIfMatched(raw) || handleLinkHubIfMatched(raw) || handleShowerIfMatched(raw)

    /**
     * 通用链接节点（`/url/{code}`、短别名 `/u/{code}`）分流。命中返回 true。
     */
    private fun handleLinkHubIfMatched(raw: String): Boolean {
        val node = com.xingheyuzhuan.shiguangschedule.data.network.link.LinkHubUrl.parse(raw) ?: return false
        if (raw == handledLinkHub) return true
        handledLinkHub = raw
        clearStickyNotice()
        android.util.Log.i(TAG, "Link hub node matched: code=${node.code} inline=${node.inline != null}")
        _scanEvent.tryEmit(
            QrScanEvent.OpenLinkHub(code = node.code, inline = node.inline, origin = node.origin)
        )
        return true
    }

    /**
     * U净 贴纸 / 设备二维码分流。命中返回 true。
     */
    private fun handleUjingIfMatched(raw: String): Boolean {
        val ujing = com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.parse(raw) ?: return false
        if (raw == handledUjing) return true
        handledUjing = raw
        clearStickyNotice()
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

                when (val availability = result.availability) {
                    is WasherAvailability.Ready -> {
                        val launchUrl = cardClient.resolveAppLaunchUrl(com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuUjingClient.WASHER_APP_ID, token)
                        val separator = if (launchUrl?.contains("?") == true) "&" else "?"
                        val initialUrl = if (launchUrl != null) "${launchUrl}${separator}scanResult=$encodedRaw" else null
                        _scanEvent.emit(QrScanEvent.NavigateToWasher(initialUrl = initialUrl, pendingAutoScan = washer.raw))
                    }

                    is WasherAvailability.Unavailable -> {
                        // 不能下单的原因不止「离线」：被占用 / 已预约 / 故障 / 停用 / 码无效各有文案
                        android.util.Log.i(
                            TAG,
                            "Washer ${washer.uuid} 不可下单: ${availability.reason}, status=${result.status}, raw=${result.rawData}"
                        )
                        _washerNotice.value = availability
                    }
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

    /**
     * 洗浴控水设备分流。命中返回 true。
     *
     * 三种输入：
     * - 设备直达链接 `/s/{系统}/{设备号}[/{端口}]`：系统与设备号都在链接里，直接解析（见 [CampusShowerLink]）；
     * - 2-3 栋（水表 51）原二维码：直接深链 lifeService，页面自己校验设备；
     * - 1 栋（智能控水裸码）：先经服务端 `CheckKsPos` 正证，确认是真实设备后才深链，
     *   避免把商品条码等 13 位数字码误分流。
     */
    private fun handleShowerIfMatched(raw: String): Boolean {
        val direct = CampusShowerLink.parse(raw)
        val shower = if (direct == null) ShowerQrLink.parse(raw) else null
        if (direct == null && shower == null) return false
        if (raw == handledShower) return true
        handledShower = raw
        clearStickyNotice()
        android.util.Log.i(TAG, "Shower matched: ${direct ?: shower}")

        when {
            direct is CampusShowerLink.Direct.Ykt ->
                resolveShower(raw) { showerResolver.resolveYktXyyy(direct.posno) }

            direct is CampusShowerLink.Direct.Life ->
                resolveShower(raw) { showerResolver.resolveLifeService(direct.imei, direct.port) }

            shower is ShowerQrLink.Result.LifeService ->
                resolveShower(raw) { showerResolver.resolveLifeServiceRaw(shower.raw) }

            shower is ShowerQrLink.Result.YktXyyy ->
                resolveShower(raw) { showerResolver.resolveYktXyyy(shower.posno) }
        }
        return true
    }

    /**
     * 洗浴解析的公共收尾：转圈 → 结果分类 → 成功则交给网页应用容器
     * （1 栋为 appId=62 + 合成原文自动执行，2-3 栋为 appId=65 + `scanResult`）。
     */
    private fun resolveShower(raw: String, block: suspend () -> CampusShowerEntryResolver.Result) {
        viewModelScope.launch {
            _showerChecking.value = true
            try {
                when (val result = block()) {
                    is CampusShowerEntryResolver.Result.Ready ->
                        _scanEvent.emit(
                            QrScanEvent.NavigateToShower(
                                initialUrl = result.initialUrl,
                                pendingAutoScan = result.pendingAutoScan,
                                nativeShower = result.nativeShower
                            )
                        )

                    CampusShowerEntryResolver.Result.InvalidDevice -> notifyShowerInvalid(raw)

                    // 分流本身要统一认证（一卡通令牌 + refresh 都续不上）：就地补登录一次再重跑这条分流
                    CampusShowerEntryResolver.Result.NeedLogin ->
                        loginThenRetry { resolveShower(raw, block) }

                    is CampusShowerEntryResolver.Result.Unavailable -> notifyShowerUnavailable(raw)
                }
            } finally {
                _showerChecking.value = false
            }
        }
    }

    fun dismissHairdryerDialog() {
        _hairdryerPrompt.value = false
        handledUjing = null
        handledLinkHub = null
        handledShower = null
        handledUuid = null
    }

    /** 关掉洗衣机「当前不可下单」提示，并允许重新扫同一条码。 */
    fun dismissWasherNotice() {
        _washerNotice.value = null
        handledUjing = null
        handledLinkHub = null
        handledShower = null
        handledUuid = null
    }

    /** 确认登录（PC 端状态置 1）。 */
    fun confirm() {
        val current = _state.value
        if (current !is QrScanUiState.Scanned) return
        _state.value = QrScanUiState.Confirming(current.uuid)

        viewModelScope.launch {
            confirmPeer(current.uuid)
        }
    }

    /** 扫码端「确认登录」（PC 端置 1）。缺统一认证会话时同样就地补登录一次再重放本步。 */
    private suspend fun confirmPeer(uuid: String) {
        when (engine.confirmPeerQrCode(uuid)) {
            QrConfirmOutcome.CONFIRMED -> _state.value = QrScanUiState.Success
            QrConfirmOutcome.NEED_LOGIN -> loginThenRetry { confirmPeer(uuid) }
            QrConfirmOutcome.EXPIRED -> _state.value = QrScanUiState.Failed(QrScanError.EXPIRED)
            QrConfirmOutcome.ERROR -> _state.value = QrScanUiState.Failed(QrScanError.NETWORK)
        }
    }

    /** 重新扫描（重扫/重试）：回到取景，不再拿登录态当门槛。 */
    fun rescan() {
        handledUuid = null
        handledUjing = null
        handledLinkHub = null
        handledShower = null
        // 上一次扫到一半的待续动作作废：用户要重扫，就按新扫到的码重新决定
        pendingAuthRetry = null
        _transientNotice.value = null
        transientJob?.cancel()
        stickyJob?.cancel()
        stickyJob = null
        stickyNotice = null
        stickyRaw = null
        _state.value = QrScanUiState.Scanning
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

    /**
     * 登录 Sheet 登录成功后：可能切换了 WebVPN 模式，重建引擎；
     * 有因缺会话而中断的动作就接着做完（不用把码再扫一遍），否则回到取景。
     */
    fun onLoginSuccess() {
        engine = createEngine().also { attachSslHandler(it) }
        viewModelScope.launch {
            if (!runPendingRetry()) rescan()
        }
    }

    /** 回应用户对 WebVPN 证书的信任询问。 */
    fun resolveTlsPrompt(allow: Boolean) {
        tlsDeferred?.complete(allow)
    }

    /** 相机每帧都会回调，扫到不认识的码时用它节流提示。 */
    private fun notifyRejected() {
        val now = System.currentTimeMillis()
        if (now - lastRejectNoticeAt < 2000L) return
        lastRejectNoticeAt = now
        showNotice(QrTransientNotice.UNRECOGNIZED)
    }

    /** 机号经服务端确认无效时提示（跟码：不把码移开就一直显示）。 */
    private fun notifyShowerInvalid(raw: String) {
        showStickyNotice(QrTransientNotice.SHOWER_INVALID, raw)
    }

    /** 洗浴校验过程本身失败时提示（跟码，移开重扫即可再试一次）。 */
    private fun notifyShowerUnavailable(raw: String) {
        showStickyNotice(QrTransientNotice.SHOWER_UNAVAILABLE, raw)
    }

    private fun notifyPhotoNoCode() {
        showNotice(QrTransientNotice.PHOTO_NO_CODE)
    }

    private fun showNotice(notice: QrTransientNotice) {
        // 新的普通提示直接接管画面，避免「跟码」任务过一会儿再把这条提示清掉
        clearStickyNotice()
        _transientNotice.value = notice
        transientJob?.cancel()
        transientJob = viewModelScope.launch {
            delay(NOTICE_DURATION_MS)
            _transientNotice.value = null
        }
    }

    /**
     * 显示「跟码」提示：只要相机还在回调同一条码（[raw]），提示就一直挂着不消失；
     * 码被移开后相机停止回调，[STICKY_NOTICE_TIMEOUT_MS] 之后提示自动收起，
     * 同时放开去重标记 —— 于是同一条码重新扫进来时会**重新走一遍校验**，
     * 而不是被「已处理过」的短路吃掉。
     */
    private fun showStickyNotice(notice: QrTransientNotice, raw: String) {
        if (stickyNotice == notice && stickyRaw == raw) {
            stickyLastSeenAt = System.currentTimeMillis()
            return
        }
        stickyJob?.cancel()
        transientJob?.cancel()
        stickyNotice = notice
        stickyRaw = raw
        stickyLastSeenAt = System.currentTimeMillis()
        _transientNotice.value = notice
        stickyJob = viewModelScope.launch {
            while (System.currentTimeMillis() - stickyLastSeenAt < STICKY_NOTICE_TIMEOUT_MS) {
                delay(STICKY_NOTICE_POLL_MS)
            }
            _transientNotice.value = null
            stickyNotice = null
            stickyRaw = null
            // 码已经离开取景框：放开去重，重新扫到时重新校验
            handledShower = null
            handledUjing = null
            handledLinkHub = null
        }
    }

    /** 成功分流到别的功能后收掉跟码提示（去重标记保持不动，避免同一条码被反复处理）。 */
    private fun clearStickyNotice() {
        if (stickyNotice == null) return
        stickyJob?.cancel()
        stickyJob = null
        stickyNotice = null
        stickyRaw = null
        _transientNotice.value = null
    }
}
