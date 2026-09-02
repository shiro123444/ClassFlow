package com.xingheyuzhuan.shiguangschedule.ui.components
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthMode
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuLoginMethod
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuNetworkProbe
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.DynamicCodeSendResult
import com.xingheyuzhuan.shiguangschedule.ui.theme.LocalIsDarkTheme

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 二维码登录阶段。
 */
enum class QrPhase {
    PLACEHOLDER, // 无内容，空二维码占位
    GENERATING,  // 正在生成/刷新（模糊 + 进度）
    WAIT,        // 等待扫码
    SCANNED,     // 已扫码，等待手机确认（遮罩 + 对勾）
    CONFIRMING,  // 已确认，正在登录（遮罩 + 旋转对勾）
    EXPIRED,     // 已过期（遮罩 + 刷新图标）
    ERROR        // 出错（遮罩 + 刷新图标）
}

/**
 * 二维码登录的 UI 状态：qrContent 用于本地渲染二维码，phase 决定遮罩/图标，statusText 为提示。
 */
data class QrUiState(
    val qrContent: String? = null,
    val phase: QrPhase = QrPhase.WAIT,
    val statusText: String = ""
)

private const val QR_PLACEHOLDER_CONTENT = " "

private fun generateQrBitmap(content: String, size: Int = 512): ImageBitmap? {
    return runCatching {
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (x in 0 until size) {
            for (y in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WbuAuthBottomSheet(
    onDismissRequest: () -> Unit,
    onPasswordLogin: (String, String, Boolean, WbuAuthMode) -> Unit,
    onDynamicCodeLogin: (String, String, Boolean) -> Unit,
    onSendDynamicCode: suspend (String) -> DynamicCodeSendResult,
    onStartQr: () -> Unit,
    onRefreshQr: () -> Unit,
    method: WbuLoginMethod = WbuLoginMethod.PASSWORD,
    onMethodChange: (WbuLoginMethod) -> Unit,
    qrState: QrUiState? = null,
    isLoading: Boolean = false,
    statusMessage: String = "",
    errorMessage: String = "",
    initialStudentId: String = "",
    initialUseVpn: Boolean = false
) {
    val isDark = LocalIsDarkTheme.current
    val context = LocalContext.current
    var studentId by remember(initialStudentId) { mutableStateOf(initialStudentId) }
    var password by remember { mutableStateOf("") }
    var useVpn by remember(initialUseVpn) { mutableStateOf(initialUseVpn) }
    var authMode by remember { mutableStateOf(WbuAuthMode.UNIFIED_CAS) }
    var authMenuExpanded by remember { mutableStateOf(false) }
    var panelExpanded by remember { mutableStateOf(false) }
    var idsVpnEnabled by remember { mutableStateOf(WbuSyncEngine.getIdsViaWebVpn(context)) }
    var qrVpnEnabled by remember { mutableStateOf(WbuSyncEngine.getQrViaWebVpn(context)) }
    var pcUaEnabled by remember { mutableStateOf(WbuSyncEngine.getUsePcUserAgent(context)) }
    var skipCampusCheck by remember { mutableStateOf(WbuSyncEngine.getSkipCampusCheck(context)) }
    var keepTeacherId by remember { mutableStateOf(WbuSyncEngine.getKeepTeacherId(context)) }
    val isZhCN = remember { WbuSyncEngine.isSimplifiedChinese(context) }
    var engSmsEnabled by remember { mutableStateOf(WbuSyncEngine.getSendEnglishSms(context)) }
    var dynamicCode by remember(method) { mutableStateOf("") }
    var codeSent by remember(method) { mutableStateOf(false) }
    var sendingCode by remember(method) { mutableStateOf(false) }
    var resendCooldown by remember(method) { mutableIntStateOf(0) }
    var cooldownRun by remember(method) { mutableIntStateOf(0) }
    var dynamicSendError by remember(method) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

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

    // 选择校园网直连（非 VPN）时实时探测校园网环境；结果经 campusState 更新提示。
    // 开启「不检测校园网环境」则跳过探测。
    LaunchedEffect(useVpn, skipCampusCheck) {
        if (!useVpn && !skipCampusCheck) WbuNetworkProbe.refresh()
    }

    // 动态码发送后倒计时；每次开始冷却（cooldownRun 变化）都会重跑（按钮显示重新发送 (Ns)）
    LaunchedEffect(cooldownRun) {
        if (cooldownRun > 0) {
            while (resendCooldown > 0) {
                delay(1000)
                resendCooldown--
            }
        }
    }

    // 「发送过于频繁」红字在几秒后自动消失（其余错误保留持久）
    LaunchedEffect(dynamicSendError) {
        if (dynamicSendError.startsWith("发送过于频繁")) {
            delay(3000)
            dynamicSendError = ""
        }
    }

    fun sendCode() {
        if (studentId.isBlank() || sendingCode || resendCooldown > 0) return
        scope.launch {
            dynamicSendError = ""
            sendingCode = true
            val result = runCatching { onSendDynamicCode(studentId.trim()) }
                .getOrElse { DynamicCodeSendResult.Failure("发送失败，请重试") }
            sendingCode = false
            when (result) {
                is DynamicCodeSendResult.Success -> {
                    dynamicSendError = ""
                    codeSent = true
                    resendCooldown = 120
                    cooldownRun++
                }
                is DynamicCodeSendResult.Failure -> {
                    codeSent = true
                    if (result.waitSeconds > 0) {
                        resendCooldown = result.waitSeconds
                        dynamicSendError = "发送过于频繁，请等待 ${result.waitSeconds} 秒后重试"
                        cooldownRun++
                    } else {
                        resendCooldown = 0
                        dynamicSendError = result.message
                    }
                }
            }
        }
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

            // 账号输入（二维码登录无需账号）
            if (method != WbuLoginMethod.QR) {
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
            }

            // 按登录方式切换的输入区
            when (method) {
                WbuLoginMethod.PASSWORD -> PasswordInput(
                    value = password,
                    onValueChange = { password = it },
                    authMode = authMode,
                    onAuthModeChange = { authMode = it },
                    menuExpanded = authMenuExpanded,
                    onMenuExpandedChange = { authMenuExpanded = it },
                    enabled = !isLoading
                )

                WbuLoginMethod.DYNAMIC_CODE -> {
                    OutlinedTextField(
                        value = dynamicCode,
                        onValueChange = { if (it.length <= 6) dynamicCode = it.filter { c -> c.isDigit() } },
                        label = { Text("动态验证码") },
                        leadingIcon = { Icon(Icons.Default.Sms, contentDescription = null) },
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "验证码将发送至绑定手机/学号",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { sendCode() },
                            enabled = !isLoading && !sendingCode && studentId.isNotBlank() && resendCooldown <= 0
                        ) {
                            Text(
                                when {
                                    sendingCode -> "发送中..."
                                    resendCooldown > 0 -> "重新发送 (${resendCooldown}s)"
                                    codeSent -> "重新发送"
                                    else -> "获取验证码"
                                }
                            )
                        }
                    }
                    if (dynamicSendError.isNotBlank()) {
                        Text(
                            text = dynamicSendError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        )
                    }
                }

                WbuLoginMethod.QR -> QrInput(
                    qrState = qrState,
                    onRefreshQr = onRefreshQr
                )
            }

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
                Row(
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

            // 校园网环境提示（仅直连模式显示）：检测中 / 未检测到；检测到校园网则不显示。
            // 开启「不检测校园网环境」时不探测、也不显示该提示。
            if (!useVpn && !skipCampusCheck && campus != true) {
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

            // “V 展开”：登录方式 + 两个开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { panelExpanded = !panelExpanded }) {
                    Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("展开")
                }
            }

            if (panelExpanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("登录方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MethodRow("密码登录", WbuLoginMethod.PASSWORD, method, onMethodChange)
                    MethodRow("二维码登录", WbuLoginMethod.QR, method, onMethodChange)
                    MethodRow("手机动态码", WbuLoginMethod.DYNAMIC_CODE, method, onMethodChange)

                    Text(
                        text = "网络设置",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    ToggleRow(
                        label = "桌面版UA",
                        checked = pcUaEnabled,
                        onCheckedChange = {
                            pcUaEnabled = it
                            WbuSyncEngine.setUsePcUserAgent(context, it)
                        }
                    )
                    ToggleRow(
                        label = "不检测校园网环境",
                        checked = skipCampusCheck,
                        onCheckedChange = {
                            skipCampusCheck = it
                            WbuSyncEngine.setSkipCampusCheck(context, it)
                        }
                    )
                    ToggleRow(
                        label = "保留教师工号",
                        checked = keepTeacherId,
                        onCheckedChange = {
                            keepTeacherId = it
                            WbuSyncEngine.setKeepTeacherId(context, it)
                        }
                    )
                    ToggleRow(
                        label = "统一认证经过WebVPN",
                        checked = idsVpnEnabled,
                        onCheckedChange = {
                            idsVpnEnabled = it
                            WbuSyncEngine.setIdsViaWebVpn(context, it)
                        }
                    )
                    ToggleRow(
                        label = "二维码内容包含WebVPN链接",
                        checked = qrVpnEnabled,
                        onCheckedChange = {
                            qrVpnEnabled = it
                            WbuSyncEngine.setQrViaWebVpn(context, it)
                        }
                    )
                    if (!isZhCN) {
                        ToggleRow(
                            label = "发送英语验证码（可能更慢）",
                            checked = engSmsEnabled,
                            onCheckedChange = {
                                engSmsEnabled = it
                                WbuSyncEngine.setSendEnglishSms(context, it)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 登录按钮
            val (label, enabled, action) = when (method) {
                WbuLoginMethod.PASSWORD -> Triple("一键全自动同步", studentId.isNotBlank() && password.isNotBlank(), { onPasswordLogin(studentId, password, useVpn, authMode) })
                WbuLoginMethod.DYNAMIC_CODE -> Triple("登录", studentId.isNotBlank() && dynamicCode.length == 6, { onDynamicCodeLogin(studentId, dynamicCode, useVpn) })
                WbuLoginMethod.QR -> {
                    val phase = qrState?.phase ?: QrPhase.PLACEHOLDER
                    val busy = phase == QrPhase.GENERATING || phase == QrPhase.SCANNED || phase == QrPhase.CONFIRMING
                    val label = when (phase) {
                        QrPhase.EXPIRED, QrPhase.ERROR -> "重新生成"
                        QrPhase.WAIT -> "刷新二维码"
                        QrPhase.PLACEHOLDER -> "生成二维码"
                        else -> "处理中..."
                    }
                    Triple(label, !busy, { onStartQr() })
                }
            }
            Button(
                onClick = { action() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
                ),
                enabled = enabled && !isLoading
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
                    Text(label, style = MaterialTheme.typography.titleMedium)
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

@Composable
private fun PasswordInput(
    value: String,
    onValueChange: (String) -> Unit,
    authMode: WbuAuthMode,
    onAuthModeChange: (WbuAuthMode) -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(if (authMode == WbuAuthMode.JYXT_LEGACY) "教务系统密码" else "统一认证密码") },
        leadingIcon = {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = { if (!enabled) {} else onMenuExpandedChange(true) },
                    enabled = enabled
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
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) }
                ) {
                    DropdownMenuItem(
                        text = { Text("统一认证密码") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        onClick = {
                            onAuthModeChange(WbuAuthMode.UNIFIED_CAS)
                            onMenuExpandedChange(false)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("教务系统密码") },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                        onClick = {
                            onAuthModeChange(WbuAuthMode.JYXT_LEGACY)
                            onMenuExpandedChange(false)
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
        enabled = enabled
    )
}

@Composable
private fun QrInput(
    qrState: QrUiState?,
    onRefreshQr: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        val phase = qrState?.phase ?: QrPhase.PLACEHOLDER
        // 占位/生成时用空格内容生成空二维码，避免 zxing 对空串报错
        val content = if (qrState?.qrContent.isNullOrBlank()) QR_PLACEHOLDER_CONTENT else qrState!!.qrContent!!
        val bmp = remember(content) { generateQrBitmap(content) }
        // 有遮罩的阶段（生成中/已扫码/确认中/过期/出错）都模糊显示
        val blurRadius = when (phase) {
            QrPhase.GENERATING, QrPhase.SCANNED, QrPhase.CONFIRMING, QrPhase.EXPIRED, QrPhase.ERROR -> 18.dp
            else -> 0.dp
        }

        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = "登录二维码",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .blur(blurRadius)
                )
            }

            when (phase) {
                QrPhase.GENERATING -> {
                    Scrim()
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                }
                QrPhase.PLACEHOLDER -> {
                    Text(
                        text = "正在生成二维码...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                QrPhase.SCANNED -> {
                    Scrim()
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "已扫码",
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                }
                QrPhase.CONFIRMING -> {
                    Scrim()
                    val infinite = rememberInfiniteTransition(label = "qrSpin")
                    val rotation by infinite.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
                        label = "qrRot"
                    )
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "正在登录",
                        tint = Color.White,
                        modifier = Modifier
                            .rotate(rotation)
                            .size(56.dp)
                    )
                }
                QrPhase.EXPIRED, QrPhase.ERROR -> {
                    Scrim()
                    IconButton(onClick = onRefreshQr) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新二维码",
                            tint = Color.White,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }
                else -> Unit // WAIT：无遮罩
            }
        }

        if (qrState != null && qrState.statusText.isNotBlank()) {
            Text(
                text = qrState.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (phase == QrPhase.EXPIRED || phase == QrPhase.ERROR) {
            Text(
                text = "点二维码可刷新",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun Scrim() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
    )
}

@Composable
private fun MethodRow(
    label: String,
    candidate: WbuLoginMethod,
    current: WbuLoginMethod,
    onSelect: (WbuLoginMethod) -> Unit
) {
    val selected = candidate == current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(candidate) }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when (candidate) {
                WbuLoginMethod.PASSWORD -> Icons.Default.Lock
                WbuLoginMethod.QR -> Icons.Default.QrCode2
                WbuLoginMethod.DYNAMIC_CODE -> Icons.Default.Sms
            },
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
