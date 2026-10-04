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
    var isVpnPasswordModified by remember { mutableStateOf(false) }
    var inputPassword by remember {
        mutableStateOf(if (WbuSyncEngine.hasSavedVpnPassword(context)) "••••••••" else "")
    }
    var passwordVisible by remember { mutableStateOf(false) }

    // 关闭弹窗时若用户取消了记住密码，统一清空持久化数据
    DisposableEffect(rememberVpnPassword) {
        onDispose {
            if (!rememberVpnPassword) {
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
                OutlinedTextField(
                    value = inputPassword,
                    onValueChange = { newValue ->
                        if (hasSavedVpnPassword && !isVpnPasswordModified) {
                            isVpnPasswordModified = true
                            inputPassword = if (newValue.startsWith("••••••••")) {
                                newValue.removePrefix("••••••••")
                            } else if (newValue.endsWith("••••••••")) {
                                newValue.removeSuffix("••••••••")
                            } else if (newValue.contains("••••••••")) {
                                newValue.replace("••••••••", "")
                            } else {
                                newValue
                            }
                        } else {
                            inputPassword = newValue
                        }
                    },
                    label = { Text(stringResource(R.string.label_webvpn_password)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            // 预填已记住的密码（••••••••）时隐藏"显示密码"按钮，避免展示无意义的占位符
                            if (!(hasSavedVpnPassword && !isVpnPasswordModified)) {
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
                                    .clickable {
                                        val next = !rememberVpnPassword
                                        rememberVpnPassword = next
                                        if (!next) {
                                            hasSavedVpnPassword = false
                                            if (!isVpnPasswordModified && inputPassword == "••••••••") {
                                                inputPassword = ""
                                            }
                                        } else {
                                            WbuSyncEngine.setRememberVpnPasswordEnabled(context, true)
                                        }
                                    }
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
                                    onCheckedChange = { next ->
                                        rememberVpnPassword = next
                                        if (!next) {
                                            hasSavedVpnPassword = false
                                            if (!isVpnPasswordModified && inputPassword == "••••••••") {
                                                inputPassword = ""
                                            }
                                        } else {
                                            WbuSyncEngine.setRememberVpnPasswordEnabled(context, true)
                                        }
                                    },
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
                    val finalPassword = if (hasSavedVpnPassword && !isVpnPasswordModified) {
                        WbuSyncEngine.getSavedVpnPassword(context) ?: ""
                    } else {
                        inputPassword
                    }
                    if (rememberVpnPassword && finalPassword.isNotBlank()) {
                        WbuSyncEngine.saveVpnPassword(context, finalPassword)
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
