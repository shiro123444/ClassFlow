package com.xingheyuzhuan.shiguangschedule.ui.link

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.BuildConfig
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.link.LinkHubHandlers
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubActionHandler
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubApplyResult
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubFact
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubNode
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubOrigin
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubType
import com.xingheyuzhuan.shiguangschedule.data.network.link.LinkHubClient
import com.xingheyuzhuan.shiguangschedule.data.network.link.LinkHubCompactCodec
import com.xingheyuzhuan.shiguangschedule.data.network.link.LinkHubFetchResult
import com.xingheyuzhuan.shiguangschedule.data.network.link.LinkHubInlineResult
import com.xingheyuzhuan.shiguangschedule.data.network.link.LinkHubUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 通用链接节点页面的失败类型（各自对应一句本地化文案）。 */
enum class LinkHubFailure(@StringRes val messageRes: Int) {
    /** 链接损坏 / 结构非法 / 服务端 404。 */
    INVALID_LINK(R.string.link_hub_error_invalid),

    /** 服务端标记为已失效（410），或本地已过期。 */
    EXPIRED(R.string.link_hub_error_expired),

    /** 节点要求更高的 App 版本（协议版本不识、或 minAppVersion 过高）。 */
    APP_TOO_OLD(R.string.link_hub_error_app_version),

    /** 本版本不认识该动作类型。 */
    UNSUPPORTED_TYPE(R.string.link_hub_reserved_type),

    /** 网络异常 / 服务端返回异常：可重试。 */
    NETWORK(R.string.link_hub_error_network)
}

/** 页面状态。 */
sealed interface LinkHubUiState {

    /** 解析中（内嵌载荷本地解码，通常一闪而过；服务端短码要联网）。 */
    data object Resolving : LinkHubUiState

    /** 解析成功，等待用户确认。 */
    data class Confirm(
        val node: LinkHubNode,
        val facts: List<LinkHubFact>
    ) : LinkHubUiState

    /** 类型未注册：不明类型或「已约定但本版本未实现」。 */
    data class Unsupported(val type: String, val reserved: Boolean) : LinkHubUiState

    /**
     * 失败；[retryable] 为 true 时页面提供「重试」，
     * [openInBrowserUrl] 非空时额外提供「用浏览器打开」（服务端没返回 JSON 时的逃生口）。
     */
    data class Failed(
        @StringRes val messageRes: Int,
        val retryable: Boolean = false,
        val openInBrowserUrl: String? = null
    ) : LinkHubUiState

    data object Applying : LinkHubUiState

    data class Applied(@StringRes val messageRes: Int) : LinkHubUiState
}

/**
 * 通用链接节点（`/url/{code}`、`/u/{code}`）读侧逻辑。
 *
 * 两种来源：
 * - 内嵌载荷（URL fragment）→ 本地解码，**不联网**；
 * - 服务端短码 → [LinkHubClient] 取 JSON 信封（线格式 A，见 `LINK_HUB_PROTOCOL.md` 第 3 节）。
 *
 * 默认任何动作都必须经过用户点「应用」；声明 `autoApply` 的处理器在**服务端短码**节点上免确认落地，
 * 声明 `autoApplyInline` 的处理器在**内嵌载荷**上同样免确认（仅限动作边界明确的设备类型）。
 */
@HiltViewModel
class LinkHubViewModel @Inject constructor(
    private val handlers: LinkHubHandlers,
    private val client: LinkHubClient
) : ViewModel() {

    private val _state = MutableStateFlow<LinkHubUiState>(LinkHubUiState.Resolving)
    val state: StateFlow<LinkHubUiState> = _state.asStateFlow()

    /** 需要在内置 WebView 打开的地址（一次性事件）。 */
    private val _openWebView = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openWebView: SharedFlow<String> = _openWebView.asSharedFlow()

    /** 需要跳转到指定网页应用容器（一次性事件，如 `campus_shower` 免确认直达）。 */
    private val _openWebApp = MutableSharedFlow<LinkHubApplyResult.OpenWebApp>(extraBufferCapacity = 1)
    val openWebApp: SharedFlow<LinkHubApplyResult.OpenWebApp> = _openWebApp.asSharedFlow()

    private var started = false
    private var pendingHandler: LinkHubActionHandler? = null

    /** 当前待解析的服务端短码与来源（供「重试」复用）。 */
    private var pendingCode: String? = null
    private var pendingOrigin: String? = null

    /** 由页面在进入时调用一次；重复调用（重组/配置变更）无副作用。 */
    fun start(code: String?, inline: String?, origin: String? = null) {
        if (started) return
        started = true

        if (!inline.isNullOrEmpty()) {
            when (val decoded = LinkHubCompactCodec.decode(inline)) {
                is LinkHubInlineResult.Ok ->
                    show(LinkHubNode(envelope = decoded.envelope, origin = LinkHubOrigin.INLINE))

                is LinkHubInlineResult.Invalid -> fail(LinkHubFailure.INVALID_LINK)
                is LinkHubInlineResult.UnsupportedVersion -> fail(LinkHubFailure.APP_TOO_OLD)
                is LinkHubInlineResult.UnsupportedType -> fail(LinkHubFailure.UNSUPPORTED_TYPE)
            }
            return
        }

        if (code.isNullOrEmpty()) {
            fail(LinkHubFailure.INVALID_LINK)
            return
        }
        resolveFromServer(code, origin)
    }

    /** 网络类失败后由页面发起重试。 */
    fun retry() {
        val code = pendingCode ?: return
        resolveFromServer(code, pendingOrigin)
    }

    /** 用户点击「应用」。 */
    fun apply() {
        val confirm = _state.value as? LinkHubUiState.Confirm ?: return
        val handler = pendingHandler ?: return
        applyNode(confirm.node, handler, auto = false)
    }

    /**
     * 执行落地。
     *
     * [auto] 为 true（免确认节点）时**不写任何中间状态**：状态停在 [LinkHubUiState.Resolving]，
     * 页面因此一直保持品牌过渡态，直到被目标页替换或被失败态取代 —— 避免「确认页闪一下再跳走」。
     * 失败仍然如实上报（[LinkHubUiState.Failed]），用户才有机会看到原因。
     */
    private fun applyNode(node: LinkHubNode, handler: LinkHubActionHandler, auto: Boolean) {
        viewModelScope.launch {
            if (!auto) _state.value = LinkHubUiState.Applying
            val result = runCatching { handler.apply(node.envelope) }
                .getOrElse { LinkHubApplyResult.Failed(R.string.link_hub_error_invalid) }
            when (result) {
                is LinkHubApplyResult.Done ->
                    _state.value = LinkHubUiState.Applied(result.messageRes)

                is LinkHubApplyResult.Failed ->
                    _state.value = LinkHubUiState.Failed(result.messageRes, retryable = result.retryable)

                is LinkHubApplyResult.OpenUrl -> {
                    if (!auto) _state.value = LinkHubUiState.Applied(R.string.link_hub_applied_open)
                    _openWebView.tryEmit(result.url)
                }

                is LinkHubApplyResult.OpenWebApp -> {
                    if (!auto) _state.value = LinkHubUiState.Applied(R.string.link_hub_applied_open)
                    _openWebApp.tryEmit(result)
                }
            }
        }
    }

    /**
     * 服务端短码：带 `Accept: application/json` 取回信封。
     *
     * 分类逻辑在 [LinkHubFetchResult]；不认识的协议版本、404、410 分别给不同提示，
     * 网络类失败给「重试」；服务端返回 HTML（未启用 JSON 协商）时退回内置 WebView。
     */
    private fun resolveFromServer(code: String, origin: String?) {
        pendingCode = code
        pendingOrigin = origin
        viewModelScope.launch {
            _state.value = LinkHubUiState.Resolving
            when (val result = client.fetch(code, origin)) {
                is LinkHubFetchResult.Ok ->
                    show(LinkHubNode(envelope = result.envelope, origin = LinkHubOrigin.SERVER))

                LinkHubFetchResult.NotJson -> _state.value = LinkHubUiState.Failed(
                    messageRes = R.string.link_hub_error_not_json,
                    retryable = true,
                    // 逃生口：交给系统浏览器（那里的 intent:// / App Links 行为才正常）
                    openInBrowserUrl = LinkHubUrl.buildCodeUrl(code = code, origin = origin)
                )

                LinkHubFetchResult.NotFound -> fail(LinkHubFailure.INVALID_LINK)
                LinkHubFetchResult.Expired -> fail(LinkHubFailure.EXPIRED)
                LinkHubFetchResult.UnsupportedVersion -> fail(LinkHubFailure.APP_TOO_OLD)

                LinkHubFetchResult.NetworkError,
                LinkHubFetchResult.Malformed -> fail(LinkHubFailure.NETWORK)

                is LinkHubFetchResult.ServerError -> fail(LinkHubFailure.NETWORK)
            }
        }
    }

    private fun show(node: LinkHubNode) {
        val envelope = node.envelope
        val type = envelope.type.trim().lowercase()
        val handler = handlers.find(type)
        if (handler == null) {
            _state.value = LinkHubUiState.Unsupported(type = type, reserved = type in LinkHubType.RESERVED)
            return
        }

        val expiresAt = envelope.expiresAt
        val nowSeconds = System.currentTimeMillis() / 1000
        if (expiresAt != null && expiresAt > 0 && nowSeconds > expiresAt) {
            fail(LinkHubFailure.EXPIRED)
            return
        }

        val minAppVersion = envelope.minAppVersion
        if (minAppVersion != null && minAppVersion > BuildConfig.VERSION_CODE) {
            fail(LinkHubFailure.APP_TOO_OLD)
            return
        }

        pendingHandler = handler
        // 免确认：服务端短码恒可；内嵌载荷需处理器显式声明 autoApplyInline（如 campus_shower）
        if (handler.autoApply && (node.origin == LinkHubOrigin.SERVER || handler.autoApplyInline)) {
            applyNode(node, handler, auto = true)
            return
        }
        _state.value = LinkHubUiState.Confirm(node = node, facts = handler.summarize(envelope))
    }

    private fun fail(failure: LinkHubFailure) {
        _state.value = LinkHubUiState.Failed(
            messageRes = failure.messageRes,
            retryable = failure == LinkHubFailure.NETWORK
        )
    }
}