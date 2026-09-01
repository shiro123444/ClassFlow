package com.xingheyuzhuan.shiguangschedule.ui.components
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthMode
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuNetworkProbe
import com.xingheyuzhuan.shiguangschedule.ui.theme.LocalIsDarkTheme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WbuAuthBottomSheet(
    onDismissRequest: () -> Unit,
    onLoginClick: (String, String, Boolean, WbuAuthMode) -> Unit,
    isLoading: Boolean = false,
    statusMessage: String = "",
    errorMessage: String = "",
    initialStudentId: String = "",
    initialUseVpn: Boolean = false
) {
    val isDark = LocalIsDarkTheme.current
    var studentId by remember(initialStudentId) { mutableStateOf(initialStudentId) }
    var password by remember { mutableStateOf("") }
    var useVpn by remember(initialUseVpn) { mutableStateOf(initialUseVpn) }
    var authMode by remember { mutableStateOf(WbuAuthMode.UNIFIED_CAS) }
    var authMenuExpanded by remember { mutableStateOf(false) }
    val campus by WbuNetworkProbe.campusState.collectAsState()
    val loadingTips = remember {
        listOf(
            "正在和教务系统打招呼...",
            "课表小精灵正在搬运数据...",
            "马上就好，正在整理你的课程~"
        )
    }
    var loadingTipIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(isLoading) {
        if (!isLoading) {
            loadingTipIndex = 0
            return@LaunchedEffect
        }
        while (isLoading) {
            delay(1700)
            loadingTipIndex = (loadingTipIndex + 1) % loadingTips.size
        }
    }

    // 选择校园网直连（非 VPN）时实时探测校园网环境；结果经 campusState 更新提示
    LaunchedEffect(useVpn) {
        if (!useVpn) WbuNetworkProbe.refresh()
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 44.dp, height = 5.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(999.dp)
                    )
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "WBU 教务系统登录",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            // 学号输入
            OutlinedTextField(
                value = studentId,
                onValueChange = { studentId = it },
                label = { Text("账号") },
                leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                ),
                enabled = !isLoading
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            // 密码输入
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (authMode == WbuAuthMode.JYXT_LEGACY) "教务系统密码" else "统一认证密码") },
                leadingIcon = {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = { if (!isLoading) authMenuExpanded = true },
                            enabled = !isLoading
                        ) {
                            Icon(
                                imageVector = if (authMode == WbuAuthMode.JYXT_LEGACY) Icons.Default.Key else Icons.Default.Lock,
                                contentDescription = "选择登录认证方式"
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(16.dp)
                        )
                        DropdownMenu(
                            expanded = authMenuExpanded,
                            onDismissRequest = { authMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("统一认证密码") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                onClick = {
                                    authMode = WbuAuthMode.UNIFIED_CAS
                                    authMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("教务系统密码") },
                                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                                onClick = {
                                    authMode = WbuAuthMode.JYXT_LEGACY
                                    authMenuExpanded = false
                                }
                            )
                        }
                    }
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                ),
                enabled = !isLoading
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 校园网/VPN 切换
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (useVpn) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 0.8.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (isDark) 0.18f else 0.55f),
                                Color.White.copy(alpha = if (isDark) 0.05f else 0.16f)
                            )
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (useVpn) Icons.Default.VpnKey else Icons.Default.Wifi,
                        contentDescription = null,
                        tint = if (useVpn) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (useVpn) "使用 WebVPN (校外)" else "校园网直连 (校内)",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (useVpn) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Switch(
                        checked = useVpn,
                        onCheckedChange = { useVpn = it },
                        enabled = !isLoading
                    )
                }
            }

            // 校园网环境提示（仅直连模式显示）：检测中 / 未检测到；检测到校园网则不显示
            if (!useVpn && campus != true) {
                val (hintText, hintColor) = when (campus) {
                    null -> "正在检测校园网..." to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> "未检测到校园网，建议使用 WebVPN" to MaterialTheme.colorScheme.error
                }
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.bodySmall,
                    color = hintColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 登录按钮
            Button(
                onClick = { onLoginClick(studentId, password, useVpn, authMode) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
                ),
                enabled = studentId.isNotBlank() && password.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                    Text("正在获取课表...", style = MaterialTheme.typography.titleMedium)
                } else {
                    Text("一键全自动同步", style = MaterialTheme.typography.titleMedium)
                }
            }

            AnimatedVisibility(visible = isLoading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    if (statusMessage.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    width = 0.8.dp,
                                    color = Color.White.copy(alpha = if (isDark) 0.14f else 0.45f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                        ) {
                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = loadingTips[loadingTipIndex],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            if (errorMessage.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = if (isDark) 0.30f else 0.90f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
                Spacer(modifier = Modifier.height(28.dp))
            } else {
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }
}

/**
 * WebVPN 短信验证码输入对话框
 */
@Composable
fun VpnSmsCodeDialog(
    maskedPhone: String,
    onSubmit: (String) -> Unit,
    onResend: () -> Unit,
    onDismiss: () -> Unit,
    isVerifying: Boolean = false,
    errorMessage: String? = null
) {
    var smsCode by remember { mutableStateOf("") }
    var resendCooldown by remember { mutableIntStateOf(60) }

    LaunchedEffect(Unit) {
        while (resendCooldown > 0) {
            delay(1000)
            resendCooldown--
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isVerifying) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        icon = {
            Icon(
                imageVector = Icons.Default.Sms,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "WebVPN 短信验证",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "验证码已发送至 $maskedPhone",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = smsCode,
                    onValueChange = { if (it.length <= 6) smsCode = it.filter { c -> c.isDigit() } },
                    label = { Text("6 位验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isVerifying,
                    isError = errorMessage != null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (LocalIsDarkTheme.current) 0.14f else 0.24f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (LocalIsDarkTheme.current) 0.10f else 0.18f)
                    )
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = {
                        resendCooldown = 60
                        onResend()
                    },
                    enabled = resendCooldown <= 0 && !isVerifying
                ) {
                    Text(
                        if (resendCooldown > 0) "重新发送 (${resendCooldown}s)"
                        else "重新发送验证码"
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(smsCode) },
                enabled = smsCode.length == 6 && !isVerifying
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("验证")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isVerifying
            ) {
                Text("取消")
            }
        }
    )
}

