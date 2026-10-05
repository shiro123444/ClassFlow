package com.xingheyuzhuan.shiguangschedule.ui.campus.paycode

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CampusPayMethod
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.PayCodeBatch
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.PayCodeQueue
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessFailure
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.PayCodeUnavailableException
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuCampusCardClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuPayCodeClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSessionExpiredException
import com.xingheyuzhuan.shiguangschedule.ui.components.accessFailureText
import com.xingheyuzhuan.shiguangschedule.ui.components.silentUnifiedAuthLogin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 付款码页面状态。 */
data class PayCodeUiState(
    /** 首次加载（整页占位）。 */
    val isLoading: Boolean = true,
    /** 下拉 / 点按刷新（保留当前码，只在顶部转圈）。 */
    val isRefreshing: Boolean = false,
    /** 该账号下的支付方式（校园卡 / 电子账户 / 签约银行卡）。 */
    val methods: List<CampusPayMethod> = emptyList(),
    /** 当前选中的支付方式下标。 */
    val selectedIndex: Int = 0,
    /** 当前展示的付款码内容；null = 还没有码（不可用 / 正在取）。 */
    val code: String? = null,
    /** 当前码剩余秒数（倒计时进度）。 */
    val secondsLeft: Int = 0,
    /** 当前码的展示周期（秒）。 */
    val slotSeconds: Int = PayCodeQueue.DEFAULT_SLOT_SECONDS,
    /** 正在取新码（本轮用尽 / 切支付方式 / 刷新）。 */
    val isFetchingCode: Boolean = false,
    /** 业务提示：支付方式未开通、卡状态异常、服务端原话。 */
    val notice: String? = null,
    /** 失败文案（网络 / 解析）。 */
    val errorMessage: String? = null,
    /** 需要用户先登录统一身份认证。 */
    val needLogin: Boolean = false,
    /** 该支付方式必须用官方页面展示（脱机码，原生暂不接管）。 */
    val needsOfficialPage: Boolean = false
) {
    /** 当前选中的支付方式。 */
    val selectedMethod: CampusPayMethod? get() = methods.getOrNull(selectedIndex)

    /** 是否有码可展示。 */
    val hasCode: Boolean get() = !code.isNullOrBlank()

    /** 是否需要给用户一条「打开官方页面」的出路（脱机码 / 取不到码 / 加载失败）。 */
    val showOfficialPageEntry: Boolean
        get() = needsOfficialPage || notice != null || errorMessage != null
}

/**
 * 一卡通「付款码」原生页面逻辑。
 *
 * 与平台 H5 `campusCode`（校园码）等价，但不再需要 WebView：
 * 1. 复用 [WbuCampusCardClient.ensureValidAccessToken] 拿到平台 JWT（refresh_token 续期不顶号）；
 * 2. [WbuPayCodeClient.queryPayMethods] 取支付方式与余额；
 * 3. [WbuPayCodeClient.fetchPayCodes] 取一批码，按 [PayCodeQueue] 的节奏翻码，用完自动续取；
 * 4. 令牌过期先静默重登一次（缺 WebVPN 密码 / 短信验证码时由全局补输入弹窗就地补齐），
 *    仍失败才回落到登录 Sheet。
 *
 * 翻码节奏全部交给纯逻辑 [PayCodeQueue]，ViewModel 只负责「到点了」与「取不到码」两件事。
 */
class PayCodeViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "PayCodeViewModel"
        const val FLOW_TAG = "CARD_PAY_CODE"

        /** 记住上次选择的支付方式（平台 H5 用 localStorage.payInfoId，这里用本机轻量偏好）。 */
        const val PREFS_NAME = "campus_pay_code"
        const val KEY_LAST_METHOD_ID = "last_method_id"

        /** 翻码检查间隔：半秒一次，倒计时看起来是连着的，又不至于太耗电。 */
        const val TICK_MS = 500L

        /** 平台把「登录态没了」写成各种措辞，这里统一识别。 */
        val SESSION_HINTS = listOf("失效", "过期", "登录", "ticket", "SSO", "expired", "login")
    }

    private val cardClient = WbuCampusCardClient(application, useVpn = false)
    private val payCodeClient = WbuPayCodeClient(application, useVpn = false)

    private val _uiState = MutableStateFlow(PayCodeUiState())
    val uiState: StateFlow<PayCodeUiState> = _uiState.asStateFlow()

    /** 当前有效的平台令牌；取到之后再翻码就不必反复换票。 */
    private var accessToken: String? = null

    /** 翻码协程；刷新 / 切换支付方式时先取消。 */
    private var rotationJob: Job? = null

    private var started = false

    /** 页面进入时调用一次。 */
    fun start() {
        if (started) return
        started = true
        load(isRefresh = false)
    }

    /** 下拉 / 点按刷新：整轮重来（支付方式 + 码）。 */
    fun refresh() {
        if (_uiState.value.isLoading || _uiState.value.isRefreshing) return
        load(isRefresh = true)
    }

    /** 切换支付方式。 */
    fun selectMethod(index: Int) {
        val state = _uiState.value
        if (index !in state.methods.indices || index == state.selectedIndex) return
        rotationJob?.cancel()
        rememberMethod(state.methods[index])
        _uiState.update {
            it.copy(
                selectedIndex = index,
                code = null,
                secondsLeft = 0,
                notice = null,
                errorMessage = null,
                needsOfficialPage = false,
                isFetchingCode = false
            )
        }
        startRotation()
    }

    /** 登录 Sheet 登录成功。 */
    fun onLoginSuccess() {
        _uiState.update { it.copy(needLogin = false) }
        load(isRefresh = true)
    }

    /** 登录 Sheet 被关闭。 */
    fun onLoginDismissed() {
        _uiState.update { it.copy(needLogin = false) }
    }

    /**
     * 「打开官方页面」的目标地址。
     *
     * 有令牌时直达平台「校园码」页（`/plat/campusCode`）；连令牌都没有（还没登录）时退回平台首页，
     * 由网页容器自己走统一认证登录 —— 无论如何都给用户留一条能走通的路。
     */
    fun officialPageUrl(): String {
        val token = accessToken?.takeIf { it.isNotBlank() }
            ?: return "${WbuCampusCardClient.BASE_URL}/plat/"
        return cardClient.buildLaunchUrl(token, WbuCampusCardClient.PATH_CAMPUS_CODE)
    }

    // ------------------- 加载主流程 -------------------

    private fun load(isRefresh: Boolean) {
        viewModelScope.launch {
            rotationJob?.cancel()
            _uiState.update {
                it.copy(
                    isLoading = !isRefresh,
                    isRefreshing = isRefresh,
                    errorMessage = null,
                    needLogin = false,
                    notice = null,
                    needsOfficialPage = false
                )
            }

            val token = ensureToken() ?: return@launch
            accessToken = token

            val methods = try {
                payCodeClient.queryPayMethods(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PayCodeUnavailableException) {
                // 服务端明确拒绝（没有一卡通账号 / 维护中）：把原话直接写出来，别翻译成通用失败文案
                Log.w(TAG, "查询支付方式失败：${e.serverMessage}")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        methods = emptyList(),
                        code = null,
                        notice = e.serverMessage
                            ?: getApplication<Application>().getString(R.string.pay_code_no_methods)
                    )
                }
                return@launch
            } catch (e: Exception) {
                failWith(e)
                return@launch
            }

            if (methods.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        methods = emptyList(),
                        code = null,
                        notice = getApplication<Application>().getString(R.string.pay_code_no_methods)
                    )
                }
                return@launch
            }

            val index = pickMethodIndex(methods)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    methods = methods,
                    selectedIndex = index
                )
            }
            rememberMethod(methods[index])
            startRotation()
        }
    }

    /**
     * 翻码循环：展示队首 → 到点前进一格 → 整批用完再取一批。
     *
     * 「到点」与「过期」的判断都交给 [PayCodeQueue]，这里只负责取码与写入 UI。
     */
    private fun startRotation() {
        val method = _uiState.value.selectedMethod ?: return

        // 不可用 / 脱机码：不取码，直接把原因写在页面上
        if (!method.isAvailable) {
            _uiState.update {
                it.copy(
                    code = null,
                    secondsLeft = 0,
                    isFetchingCode = false,
                    notice = getApplication<Application>().getString(unavailableReasonRes(method)),
                    needsOfficialPage = method.needsVoucherCode
                )
            }
            return
        }

        rotationJob = viewModelScope.launch {
            var queue = fetchQueue(method) ?: return@launch

            while (isActive) {
                delay(TICK_MS)
                if (queue.secondsLeft() > 0) {
                    publish(queue, fetching = false)
                    continue
                }

                val next = queue.advance()
                if (next != null) {
                    publish(queue, fetching = false)
                    continue
                }

                // 整批用完：旧码先留在屏幕上，等新一批到达
                _uiState.update { it.copy(secondsLeft = 0, isFetchingCode = true) }
                queue = fetchQueue(method) ?: break
            }
        }
    }

    /** 取一批码并建好轮换队列；返回 null 表示这一轮取不到（原因已写到 UI 上）。 */
    private suspend fun fetchQueue(method: CampusPayMethod): PayCodeQueue? {
        val batch = requestBatch(method) ?: return null
        val queue = PayCodeQueue(
            codes = batch.codes,
            expiresSeconds = batch.expiresSeconds,
            fetchedAtMs = System.currentTimeMillis()
        )
        publish(queue, fetching = false)
        return queue
    }

    /** 取一批码；令牌过期就地续期重试一次。 */
    private suspend fun requestBatch(method: CampusPayMethod): PayCodeBatch? {
        val token = accessToken ?: ensureToken() ?: return null
        return try {
            payCodeClient.fetchPayCodes(token, method)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WbuSessionExpiredException) {
            Log.i(TAG, "取码令牌失效，续期后重试：${e.message}")
            val renewed = ensureToken() ?: return null
            try {
                payCodeClient.fetchPayCodes(renewed, method)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                handleFetchFailure(e2)
                null
            }
        } catch (e: Exception) {
            handleFetchFailure(e)
            null
        }
    }

    private fun handleFetchFailure(e: Exception) {
        if (e is PayCodeUnavailableException) {
            Log.w(TAG, "服务端未下发付款码：${e.serverMessage}")
            _uiState.update {
                it.copy(
                    code = null,
                    secondsLeft = 0,
                    isFetchingCode = false,
                    notice = e.serverMessage
                        ?: getApplication<Application>().getString(R.string.pay_code_error_empty)
                )
            }
        } else {
            failWith(e)
        }
    }

    /**
     * 保证拿到有效的平台令牌：
     * 先走 [WbuCampusCardClient.ensureValidAccessToken]（校验会话 → refresh_token 续期 → CASTGC 换票），
     * 会话过期时用保存的统一认证密码静默重登一次（缺 WebVPN 密码 / 短信验证码会就地弹小窗补齐），
     * 仍不行才把 [PayCodeUiState.needLogin] 交给页面弹登录 Sheet。
     */
    private suspend fun ensureToken(): String? {
        val app = getApplication<Application>()

        val direct = try {
            cardClient.ensureValidAccessToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!e.looksLikeSessionExpired()) {
                failWith(e)
                return null
            }
            null
        }
        if (!direct.isNullOrBlank()) return direct

        // 会话过期：静默重登一次（是否经 WebVPN 只由「统一认证经过 WebVPN」决定）
        val failure = silentUnifiedAuthLogin(
            context = app,
            flowTag = FLOW_TAG,
            viaWebVpn = WbuAuthTransport.getIdsViaWebVpn(app)
        )
        if (failure != null) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    isFetchingCode = false,
                    // 用户主动取消补输入：不弹登录 Sheet，只留一条可重试的说明
                    needLogin = failure !is AccessFailure.Cancelled,
                    errorMessage = if (failure is AccessFailure.Cancelled) {
                        app.getString(R.string.err_need_unified_auth_session)
                    } else {
                        accessFailureText(app, failure)
                            ?: app.getString(R.string.err_need_unified_auth_session)
                    }
                )
            }
            return null
        }

        return try {
            cardClient.ensureValidAccessToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "静默重登后仍未取得平台令牌", e)
            if (e.looksLikeSessionExpired()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        needLogin = true,
                        errorMessage = app.getString(R.string.err_need_unified_auth_session)
                    )
                }
            } else {
                failWith(e)
            }
            null
        }
    }

    private fun publish(queue: PayCodeQueue, fetching: Boolean) {
        _uiState.update {
            it.copy(
                code = queue.current,
                secondsLeft = queue.secondsLeft(),
                slotSeconds = queue.effectiveSlotSeconds,
                isFetchingCode = fetching
            )
        }
    }

    private fun failWith(e: Exception) {
        Log.w(TAG, "付款码加载失败", e)
        val app = getApplication<Application>()
        _uiState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                isFetchingCode = false,
                errorMessage = accessFailureText(app, e)
                    ?: app.getString(R.string.pay_code_error_load_failed)
            )
        }
    }

    // ------------------- 小工具 -------------------

    /** 选中下标：优先上次选择（按 id 匹配），否则第一个可用的方式，最后才退回第一个。 */
    private fun pickMethodIndex(methods: List<CampusPayMethod>): Int {
        val remembered = prefs().getInt(KEY_LAST_METHOD_ID, -1)
        val rememberedIndex = methods.indexOfFirst { it.id == remembered }
        if (rememberedIndex >= 0) return rememberedIndex
        val availableIndex = methods.indexOfFirst { it.isAvailable }
        return if (availableIndex >= 0) availableIndex else 0
    }

    private fun rememberMethod(method: CampusPayMethod) {
        prefs().edit().putInt(KEY_LAST_METHOD_ID, method.id).apply()
    }

    private fun prefs() = getApplication<Application>()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 支付方式不可用时的原因文案（对应平台 `notShowType`）。 */
    @StringRes
    private fun unavailableReasonRes(method: CampusPayMethod): Int = when (method.notShowType) {
        "lostflag" -> R.string.pay_code_card_lost
        "freezeflag" -> R.string.pay_code_card_frozen
        "expdate" -> R.string.pay_code_card_expired
        "buildBankCard" -> R.string.pay_code_bank_unsigned
        else -> R.string.pay_code_method_unavailable
    }

    /** 会话过期判据：结构化异常优先，其余按平台文案兜底（SSO 票据 / 登录页特征）。 */
    private fun Throwable.looksLikeSessionExpired(): Boolean {
        if (this is WbuSessionExpiredException) return true
        val msg = message.orEmpty()
        return SESSION_HINTS.any { msg.contains(it, ignoreCase = true) }
    }
}
