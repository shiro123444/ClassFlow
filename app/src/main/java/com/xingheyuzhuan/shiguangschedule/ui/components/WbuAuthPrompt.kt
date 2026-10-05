package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine

/**
 * 静默登录过程中需要用户**就地补一次输入**时的请求。
 *
 * 用途：设备直达入口（NFC / 网页跳转 / 扫一扫）在 CASTGC 失效后自动用保存的密码登录，
 * 但「WebVPN 门禁密码」或「门户短信验证码」这类东西无法静默取得 —— 此时先弹小窗补齐，
 * 用户取消或补了还是失败，才回到完整的 [WbuAuthBottomSheet]。
 */
sealed interface WbuAuthPromptRequest {

    /** WebVPN 门禁需要密码，本地没有保存。 */
    data object VpnPassword : WbuAuthPromptRequest

    /**
     * 静默登录需要统一认证（学号）密码，但本机没有保存。
     * 此时先弹小窗就地补输入，用户取消才回落到登录 Sheet。
     */
    data object UnifiedAuthPassword : WbuAuthPromptRequest

    /** WebVPN 门户短信二次验证码。 */
    data class SmsCode(
        val maskedPhone: String,
        val isStillValid: Boolean,
        val sendInterval: Int,
        val promptText: String
    ) : WbuAuthPromptRequest
}

/**
 * 渲染当前的 [request]（null 表示没有待补输入）。
 *
 * 提交/取消都会走 [onSubmit]：取消传 null，调用方据此收尾（通常回落到登录 Sheet）。
 */
@Composable
fun WbuAuthPromptDialogs(
    request: WbuAuthPromptRequest?,
    onSubmit: (String?) -> Unit,
    onResendSmsCode: () -> Unit = {}
) {
    when (request) {
        null -> Unit
        WbuAuthPromptRequest.VpnPassword -> VpnPasswordPromptDialog(onSubmit = onSubmit)
        WbuAuthPromptRequest.UnifiedAuthPassword -> UnifiedAuthPasswordPromptDialog(onSubmit = onSubmit)
        is WbuAuthPromptRequest.SmsCode -> VpnSmsCodeDialog(
            maskedPhone = request.maskedPhone,
            isStillValid = request.isStillValid,
            sendInterval = request.sendInterval,
            promptText = request.promptText,
            onSubmit = { onSubmit(it) },
            onResend = onResendSmsCode,
            onDismiss = { onSubmit(null) }
        )
    }
}

/**
 * WebVPN 门禁密码询问弹窗（静默登录补齐门禁时用，不涉及统一认证账号）。
 *
 * [onSubmit] 收到 null 表示用户取消。
 */
@Composable
fun VpnPasswordPromptDialog(onSubmit: (String?) -> Unit) {
    val context = LocalContext.current
    var rememberVpnPassword by remember { mutableStateOf(WbuSyncEngine.isRememberVpnPasswordEnabled(context)) }
    var hasSavedVpnPassword by remember { mutableStateOf(WbuSyncEngine.hasSavedVpnPassword(context)) }
    // 门禁密码同样用「草稿 + 占位符」：占位符只是显示态，退格一次不会再把它当成真密码
    val draft = remember { PasswordDraft() }
    var passwordVisible by remember { mutableStateOf(false) }
    val showPlaceholder = hasSavedVpnPassword && !draft.edited

    /** 勾选/取消「记住密码」：勾上就地保存，取消时的抹除留到弹窗关闭（见下面的 DisposableEffect）。 */
    fun onRememberChange(checked: Boolean) {
        rememberVpnPassword = checked
        if (!checked) {
            hasSavedVpnPassword = false
            return
        }
        WbuSyncEngine.setRememberVpnPasswordEnabled(context, true)
        val effective = if (showPlaceholder) {
            WbuSyncEngine.getSavedVpnPassword(context) ?: ""
        } else {
            draft.text
        }
        if (effective.isNotBlank()) {
            WbuSyncEngine.saveVpnPassword(context, effective)
            hasSavedVpnPassword = true
        }
    }

    // 关闭弹窗时若用户取消了记住密码，统一清空持久化数据。
    // key 用 Unit：取消勾选那一刻不该立刻抹除（误触就没了），等弹窗真正离开组合时再按最新状态清。
    val currentRememberVpnPassword = rememberUpdatedState(rememberVpnPassword)
    DisposableEffect(Unit) {
        onDispose {
            if (!currentRememberVpnPassword.value) {
                WbuSyncEngine.setRememberVpnPasswordEnabled(context, false)
                WbuSyncEngine.clearSavedVpnPassword(context)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { onSubmit(null) },
        title = { Text(stringResource(R.string.title_connect_webvpn)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.desc_connect_webvpn),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                SavedPasswordField(
                    draft = draft,
                    hasSavedPassword = hasSavedVpnPassword,
                    label = stringResource(R.string.label_webvpn_password),
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            // 预填已记住的密码（占位符）时隐藏"显示密码"按钮，避免展示无意义的占位符
                            if (!showPlaceholder) {
                                IconButton(
                                    onClick = { passwordVisible = !passwordVisible },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (passwordVisible) {
                                            stringResource(R.string.a11y_hide_password)
                                        } else {
                                            stringResource(R.string.a11y_show_password)
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onRememberChange(!rememberVpnPassword) }
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.label_remember_password),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Checkbox(
                                    checked = rememberVpnPassword,
                                    onCheckedChange = { onRememberChange(it) },
                                    modifier = Modifier
                                        .size(20.dp)
                                        .scale(0.85f)
                                )
                            }
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalPassword = if (showPlaceholder) {
                        WbuSyncEngine.getSavedVpnPassword(context).orEmpty()
                    } else {
                        draft.text
                    }
                    if (rememberVpnPassword && finalPassword.isNotBlank()) {
                        WbuSyncEngine.setRememberVpnPasswordEnabled(context, true)
                        WbuSyncEngine.saveVpnPassword(context, finalPassword)
                    } else if (!rememberVpnPassword) {
                        WbuSyncEngine.setRememberVpnPasswordEnabled(context, false)
                        WbuSyncEngine.clearSavedVpnPassword(context)
                        hasSavedVpnPassword = false
                    }
                    onSubmit(finalPassword.ifBlank { null })
                }
            ) {
                Text(stringResource(R.string.action_continue_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = { onSubmit(null) }) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * 统一认证密码询问弹窗（静默登录缺保存密码时用）。
 *
 * [onSubmit] 收到 null 表示用户取消。
 */
@Composable
fun UnifiedAuthPasswordPromptDialog(onSubmit: (String?) -> Unit) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberPassword by remember {
        mutableStateOf(WbuAuthTransport.isRememberPasswordEnabled(context, CredentialService.UNIFIED_AUTH))
    }

    AlertDialog(
        onDismissRequest = { onSubmit(null) },
        title = { Text(stringResource(R.string.title_login_unified_auth)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.desc_enter_unified_auth_password),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.label_password_input)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            IconButton(
                                onClick = { passwordVisible = !passwordVisible },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (passwordVisible) {
                                        stringResource(R.string.a11y_hide_password)
                                    } else {
                                        stringResource(R.string.a11y_show_password)
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { rememberPassword = !rememberPassword }
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.label_remember_password),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Checkbox(
                                    checked = rememberPassword,
                                    onCheckedChange = { rememberPassword = it },
                                    modifier = Modifier
                                        .size(20.dp)
                                        .scale(0.85f)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = password.trim()
                    if (rememberPassword && value.isNotBlank()) {
                        WbuAuthTransport.setRememberPasswordEnabled(context, CredentialService.UNIFIED_AUTH, true)
                        WbuAuthTransport.savePassword(context, CredentialService.UNIFIED_AUTH, value)
                    }
                    onSubmit(value.ifBlank { null })
                }
            ) {
                Text(stringResource(R.string.action_confirm_login))
            }
        },
        dismissButton = {
            TextButton(onClick = { onSubmit(null) }) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * 全局「静默登录就地补输入」通道。
 *
 * 静默登录可能发生在任何页面，而补输入弹窗本身是**模态**的，所以统一在进程级托管：
 * 业务侧只 [ask] 挂起等待，UI 侧由 App 根部的 [WbuAuthPromptHost] 负责渲染。
 * 这样每个页面都不必各自复制一套弹窗状态机。
 */
object WbuAuthPromptBus {

    private val _request = MutableStateFlow<WbuAuthPromptRequest?>(null)

    /** 当前待补输入的请求，null 表示没有。 */
    val request: StateFlow<WbuAuthPromptRequest?> = _request.asStateFlow()

    private var pending: CompletableDeferred<String?>? = null

    /**
     * 串行化补输入：弹窗本身是模态的，同时来两个请求只会互相覆盖 ——
     * 旧的 `pending` 会被后一个覆盖，前一个 `ask` 永远等不到结果；而且 `finally` 还会把界面上的新请求一起清掉。
     */
    private val mutex = Mutex()

    /** 短信重发钩子：由发起方在调用前注入。 */
    var onResendSmsCode: (suspend () -> Unit)? = null

    /** 挂起等待用户输入；返回 null 表示用户取消。 */
    suspend fun ask(request: WbuAuthPromptRequest): String? = mutex.withLock {
        val deferred = CompletableDeferred<String?>()
        pending = deferred
        _request.value = request
        try {
            deferred.await()
        } finally {
            pending = null
            _request.value = null
        }
    }

    /** UI 提交结果（取消传 null）。 */
    fun submit(value: String?) {
        pending?.complete(value)
    }
}

/** App 根部渲染一次即可，负责把 [WbuAuthPromptBus] 的请求渲染成弹窗。 */
@Composable
fun WbuAuthPromptHost() {
    val request by WbuAuthPromptBus.request.collectAsState()
    val scope = rememberCoroutineScope()
    WbuAuthPromptDialogs(
        request = request,
        onSubmit = { WbuAuthPromptBus.submit(it) },
        onResendSmsCode = { scope.launch { WbuAuthPromptBus.onResendSmsCode?.invoke() } }
    )
}
