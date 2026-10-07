package com.xingheyuzhuan.shiguangschedule.ui.campus.shower

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.CampusShowerEntryResolver
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.CampusShowerLink
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.needsRelogin
import com.xingheyuzhuan.shiguangschedule.ui.components.shouldAttemptSavedPasswordLogin
import com.xingheyuzhuan.shiguangschedule.ui.components.silentUnifiedAuthLogin
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 设备直达落地页状态。 */
sealed interface ShowerDirectUiState {

    /** 正在解析设备 / 换取入口（此期间页面只有品牌过渡）。 */
    data object Resolving : ShowerDirectUiState

    /** 解析成功，即将交给网页应用容器。 */
    data object Opening : ShowerDirectUiState

    /**
     * 解析失败。
     *
     * [retryable] 为 true 时页面给出「重试」（网络类失败）；
     * [needsLogin] 为 true 时页面再给一个「重新登录」入口 —— 登录态失效是这里唯一
     * 用户自己动手就能解决的情况，以前只给「重试」，等于让用户对着同一个提示反复点。
     */
    data class Failed(
        @StringRes val messageRes: Int,
        val retryable: Boolean = false,
        val needsLogin: Boolean = false
    ) : ShowerDirectUiState
}

/**
 * 洗浴设备直达链接（`/s/{系统}/{设备号}`）的落地逻辑。
 *
 * 与全局扫码共用 [CampusShowerEntryResolver]：设备是否存在由各自后端裁决
 * （1 栋 `CheckKsPos` 正证、2-3 栋 lifeService），解析成功后才跳网页应用容器。
 */
@HiltViewModel
class ShowerDirectViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: CampusShowerEntryResolver
) : ViewModel() {

    private val _state = MutableStateFlow<ShowerDirectUiState>(ShowerDirectUiState.Resolving)
    val state: StateFlow<ShowerDirectUiState> = _state.asStateFlow()

    /** 解析成功后的跳转请求（一次性事件）。 */
    private val _openWebApp = MutableSharedFlow<CampusShowerEntryResolver.Result.Ready>(extraBufferCapacity = 1)
    val openWebApp: SharedFlow<CampusShowerEntryResolver.Result.Ready> = _openWebApp.asSharedFlow()

    private var started = false
    private var system: String = CampusShowerLink.SYSTEM_YKT
    private var code: String = ""
    private var port: String? = null

    /** 由页面在进入时调用一次；重复调用（重组/配置变更）无副作用。 */
    fun start(system: String, code: String, port: String? = null) {
        if (started) return
        started = true
        this.system = system
        this.code = code
        this.port = port
        resolve()
    }

    /** 失败后由页面发起重试。 */
    fun retry() {
        if (_state.value !is ShowerDirectUiState.Failed) return
        resolve()
    }

    /** 用户在「重新登录」面板里登录成功后调用：会话已换新，重新解析一次。 */
    fun onLoginSuccess() {
        resolve()
    }

    private fun resolve() {
        viewModelScope.launch {
            _state.value = ShowerDirectUiState.Resolving
            // 登录态失效时最多再自动登一次，然后重新解析 —— 成环就白烧服务端失败次数
            var autoLoginTried = false
            while (true) {
                val result = when (system.trim().lowercase()) {
                    CampusShowerLink.SYSTEM_YKT -> resolver.resolveYktXyyy(code)
                    CampusShowerLink.SYSTEM_LIFE -> resolver.resolveLifeService(code, port)
                    else -> CampusShowerEntryResolver.Result.InvalidDevice
                }

                if (result == CampusShowerEntryResolver.Result.NeedLogin &&
                    !autoLoginTried &&
                    shouldAttemptSavedPasswordLogin(context)
                ) {
                    autoLoginTried = true
                    _state.value = ShowerDirectUiState.Resolving
                    val failure = silentUnifiedAuthLogin(
                        context = context,
                        flowTag = "SHOWER_DIRECT",
                        viaWebVpn = WbuAuthTransport.getIdsViaWebVpn(context),
                        // 上面已确认存着密码：绝不因为「缺密码」在落地页弹窗
                        onlyWithSavedPassword = true
                    )
                    if (failure == null) continue
                    _state.value = ShowerDirectUiState.Failed(
                        R.string.err_need_unified_auth_session,
                        retryable = true,
                        // 只有「确实需要用户补新凭据」才给重新登录入口；网络类问题点重试就行
                        needsLogin = failure.needsRelogin
                    )
                    return@launch
                }

                when (result) {
                    is CampusShowerEntryResolver.Result.Ready -> {
                        _state.value = ShowerDirectUiState.Opening
                        _openWebApp.tryEmit(result)
                    }

                    CampusShowerEntryResolver.Result.InvalidDevice ->
                        _state.value = ShowerDirectUiState.Failed(R.string.link_hub_error_shower_invalid)

                    CampusShowerEntryResolver.Result.NeedLogin ->
                        _state.value = ShowerDirectUiState.Failed(
                            R.string.err_need_unified_auth_session,
                            retryable = true,
                            // 没存密码 / 开关关着时以前只能干瞪眼，现在至少给一个登录入口
                            needsLogin = true
                        )

                    is CampusShowerEntryResolver.Result.Unavailable ->
                        _state.value = ShowerDirectUiState.Failed(R.string.link_hub_error_network, retryable = true)
                }
                return@launch
            }
        }
    }
}
