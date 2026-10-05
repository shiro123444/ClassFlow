package com.xingheyuzhuan.shiguangschedule.ui.components

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xingheyuzhuan.shiguangschedule.R

private const val TAG = "WebJsDialog"

/**
 * 网页原生对话框（`window.alert` / `window.confirm` / `window.prompt`）的挂起请求。
 *
 * 背景：WebView 的 `WebChromeClient.onJsAlert` 等回调返回 true 表示「我们自己处理」，
 * 此时页面 JS 会被阻塞，直到对应的 `JsResult` 被落定。因此每个请求都必须持有落定动作，
 * 且无论用户点确定、点取消、点遮罩还是页面被销毁，都恰好落定一次。
 */
sealed interface WebJsDialogRequest {

    /** 触发对话框的页面（域名或 URL），作为弹窗标题的补充信息；可能为空。 */
    val pageLabel: String?

    /** 页面传给对话框的正文。 */
    val message: String

    /**
     * 对话框被「关闭」时用来兜底落定 JS（页面销毁、点取消、点遮罩）。
     *
     * `alert` 只有「确定」一个按钮，所以它的关闭动作与确定相同。
     */
    val onCancel: () -> Unit

    /** `window.alert(message)`：只有一个「确定」按钮。 */
    data class Alert(
        override val pageLabel: String?,
        override val message: String,
        val onConfirm: () -> Unit,
    ) : WebJsDialogRequest {
        override val onCancel: () -> Unit get() = onConfirm
    }

    /** `window.confirm(message)`：返回 true / false。 */
    data class Confirm(
        override val pageLabel: String?,
        override val message: String,
        val onConfirm: () -> Unit,
        override val onCancel: () -> Unit,
    ) : WebJsDialogRequest

    /** `window.prompt(message, defaultValue)`：返回用户输入，取消时返回 null。 */
    data class Prompt(
        override val pageLabel: String?,
        override val message: String,
        val defaultValue: String,
        val onConfirmInput: (String) -> Unit,
        override val onCancel: () -> Unit,
    ) : WebJsDialogRequest
}

/**
 * 网页对话框队列。
 *
 * WebView 同一时刻只允许一个 JS 对话框挂起，队列只是防御多帧同时弹窗的极端情况：
 * 始终只展示队首，落定后自动展示下一个。
 */
@Stable
class WebJsDialogState {

    private val queue = mutableStateListOf<WebJsDialogRequest>()

    /** 当前正在展示的请求；null 表示没有待处理对话框。 */
    val current: WebJsDialogRequest? get() = queue.firstOrNull()

    /** JS 侧发起了对话框。 */
    fun enqueue(request: WebJsDialogRequest) {
        queue.add(request)
    }

    /**
     * 当前对话框已被用户处理：先出队，再执行落定动作（保证 JS 只被唤醒一次）。
     *
     * 只有队首就是 [request] 时才生效——连点两下、或旧弹窗的延迟回调都会被忽略，
     * 避免对同一个 `JsResult` 调用两次 confirm / cancel。
     */
    fun complete(request: WebJsDialogRequest, resolve: () -> Unit) {
        if (queue.firstOrNull() !== request) return
        queue.removeAt(0)
        runCatching(resolve).onFailure { Log.w(TAG, "落定 JS 对话框失败", it) }
    }

    /**
     * 宿主销毁 / 离开页面：把仍在等待的对话框全部按「取消」落定。
     *
     * 不落定会让页面 JS 永久卡在 alert 上；但此时 WebView 可能已经在销毁流程里，
     * 因此这里吞掉异常。
     */
    fun cancelAll() {
        while (queue.isNotEmpty()) {
            val request = queue.removeAt(0)
            runCatching(request.onCancel).onFailure { Log.w(TAG, "兜底落定 JS 对话框失败", it) }
        }
    }
}

/** 从触发对话框的 URL 里取出用于展示的页面标识（域名优先，退化为整条 URL）。 */
fun webDialogPageLabel(url: String?): String? {
    val raw = url?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("about:") } ?: return null
    val host = runCatching { Uri.parse(raw).host?.takeIf { it.isNotBlank() } }.getOrNull()
    return host ?: raw
}

/**
 * 用应用自己的 Material 3 弹窗接管网页原生 alert / confirm / prompt。
 *
 * 相比 WebView 默认的系统弹窗（标题是「网址为 xxx 的网页显示：」），这里：
 * 1. 视觉与应用其它弹窗一致，可跟随深浅色与主题色；
 * 2. 标题显示触发弹窗的站点，多标签/跳转场景更容易判断来源；
 * 3. prompt 提供正常的输入框体验（默认值可编辑、支持回车确认由系统键盘处理）。
 */
@Composable
fun WebJsDialogHost(state: WebJsDialogState) {
    val request = state.current ?: return

    // 页面被销毁时兜底落定，避免页面 JS 卡在对话框上
    DisposableEffect(state) {
        onDispose { state.cancelAll() }
    }

    val title = request.pageLabel
        ?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.dialog_web_message_title_from, it) }
        ?: stringResource(R.string.dialog_web_message_title)

    when (request) {
        is WebJsDialogRequest.Alert -> AlertDialog(
            onDismissRequest = { state.complete(request) { request.onCancel() } },
            title = { Text(title) },
            text = { WebMessageBody(request.message) },
            confirmButton = {
                Button(onClick = { state.complete(request) { request.onConfirm() } }) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )

        is WebJsDialogRequest.Confirm -> AlertDialog(
            onDismissRequest = { state.complete(request) { request.onCancel() } },
            title = { Text(title) },
            text = { WebMessageBody(request.message) },
            confirmButton = {
                Button(onClick = { state.complete(request) { request.onConfirm() } }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                Button(onClick = { state.complete(request) { request.onCancel() } }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )

        is WebJsDialogRequest.Prompt -> {
            var input by remember(request) { mutableStateOf(request.defaultValue) }

            AlertDialog(
                onDismissRequest = { state.complete(request) { request.onCancel() } },
                title = { Text(title) },
                text = {
                    Column {
                        if (request.message.isNotBlank()) {
                            WebMessageBody(request.message)
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text(stringResource(R.string.dialog_web_prompt_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { state.complete(request) { request.onConfirmInput(input) } }) {
                        Text(stringResource(R.string.action_confirm))
                    }
                },
                dismissButton = {
                    Button(onClick = { state.complete(request) { request.onCancel() } }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

/** 对话框正文：网页可能塞进很长的多行文本，超出时可滚动。 */
@Composable
private fun WebMessageBody(message: String) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
