package com.xingheyuzhuan.shiguangschedule.ui.campus.shower

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppId
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuShowerWaterClient
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuAuthTipsScenario
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 马影河 2-3 栋淋浴（生活服务 lifeService）**原生用水页面**。
 *
 * 与网页那套的差别：不再把一卡通那个「扩展应用」页面（`applications/lifeService`）拽进来，
 * 而是直接用平台自己的四个接口 + 结算推送（见 [WbuShowerWaterClient] / [WbuShowerWaterSocket]）：
 * 进页面先问服务端「有没有未结束的用水」——有就直接接着显示那一单（**不会再开一单**，
 * 切后台被系统回收后回来也是这个结果），没有才给「开始用水」；用水中可随时「刷新状态」或
 * 「结束用水」；结束的金额与耗时来自结算推送（推送不可用时退化成每 5 秒查询）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowerWaterScreen(
    deviceId: String,
    port: String?,
    implid: String,
    feeitemid: String,
    /** 平台生活服务页面地址（逃生口）：这里点「改用网页打开」时用它，不带一次性自启载体。 */
    webFallbackUrl: String? = null,
    navBridge: NavBridge,
    viewModel: ShowerWaterViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val billing = remember(implid, feeitemid) {
        WbuShowerWaterClient.Billing(implid = implid, feeitemid = feeitemid)
    }

    LaunchedEffect(deviceId, port, implid, feeitemid) {
        if (deviceId.isNotBlank()) viewModel.start(deviceId, port, billing)
    }

    // 用水中每秒刷新一次「本次已用」，页面本身不轮询服务端（那是 VM 的事）
    /** 逃生口：回到网页那套（地址由调用方给出，且**不含** `scanResult`，切过去不会顺手开单）。 */
    val openWebFallback: (() -> Unit)? = remember(webFallbackUrl) {
        webFallbackUrl?.takeIf { it.isNotBlank() }?.let { url ->
            {
                navBridge.replace(
                    Destination.WebApp(
                        appId = WebAppId.CAMPUS_CARD.name,
                        initialTargetUrl = url
                    )
                )
            }
        }
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(uiState.stage is ShowerWaterUiStage.Active) {
        while (uiState.stage is ShowerWaterUiStage.Active) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    BackHandler { navBridge.popBackStack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_shower_water)) },
                navigationIcon = {
                    IconButton(onClick = { navBridge.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_exit)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !uiState.busy && uiState.stage !is ShowerWaterUiStage.Loading
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.shower_water_refresh)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DeviceCard(deviceNo = uiState.deviceNo)

            Spacer(Modifier.height(20.dp))

            when (val stage = uiState.stage) {
                is ShowerWaterUiStage.Loading -> LoadingBlock()

                is ShowerWaterUiStage.Idle -> IdleBlock(
                    busy = uiState.busy,
                    onStart = viewModel::startWater,
                    onOpenWeb = openWebFallback
                )

                is ShowerWaterUiStage.Active -> ActiveBlock(
                    stage = stage,
                    scannedDevice = uiState.deviceNo,
                    busy = uiState.busy,
                    now = now,
                    onStop = viewModel::stopWater,
                    onRefresh = viewModel::refresh
                )

                is ShowerWaterUiStage.Finished -> FinishedBlock(
                    stage = stage,
                    busy = uiState.busy,
                    onRestart = viewModel::startWater,
                    onDone = { navBridge.popBackStack() }
                )

                is ShowerWaterUiStage.Error -> ErrorBlock(
                    message = stage.message,
                    canRetry = stage.canRetry,
                    busy = uiState.busy,
                    onRetry = viewModel::retry,
                    onOpenWeb = openWebFallback,
                    onExit = { navBridge.popBackStack() }
                )
            }
        }

        if (uiState.needLogin) {
            WbuCampusAuthSheet(
                onDismiss = { viewModel.onLoginDismissed() },
                onLoginSuccess = { viewModel.onLoginSuccess() },
                requireUnifiedCas = true,
                unifiedAuthOnly = true,
                title = stringResource(R.string.service_campus_card),
                tipsScenario = WbuAuthTipsScenario.IDENTITY
            )
        }
    }
}

@Composable
private fun DeviceCard(deviceNo: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.shower_water_device),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = deviceNo.ifBlank { "—" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.shower_water_checking),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IdleBlock(busy: Boolean, onStart: () -> Unit, onOpenWeb: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusBadge(
            text = stringResource(R.string.shower_water_idle_hint),
            container = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        PrimaryAction(
            text = stringResource(R.string.shower_water_start),
            busy = busy,
            busyText = stringResource(R.string.shower_water_starting),
            onClick = onStart
        )
        WebFallbackAction(onOpenWeb)
    }
}

@Composable
private fun ActiveBlock(
    stage: ShowerWaterUiStage.Active,
    scannedDevice: String,
    busy: Boolean,
    now: Long,
    onStop: () -> Unit,
    onRefresh: () -> Unit
) {
    val elapsed = remember(stage.startTime, now) { elapsedText(stage.startTime, now) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusBadge(
            text = stringResource(R.string.shower_water_active),
            container = MaterialTheme.colorScheme.primaryContainer
        )
        Spacer(Modifier.height(18.dp))
        // 服务端按「缴费项」查：未结束的那一单可能在**另一台**设备上（比如刚扫的这台其实没人用）。
        // 这时把在用的设备说清楚，结束按钮结束的也是它。
        if (stage.deviceNo != scannedDevice) {
            InfoRow(stringResource(R.string.shower_water_device), stage.deviceNo)
            Text(
                text = stringResource(R.string.shower_water_active_other_device, stage.deviceNo),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
        }
        InfoRow(stringResource(R.string.shower_water_started_at), stage.startTime ?: "—")
        InfoRow(stringResource(R.string.shower_water_elapsed), elapsed ?: "—")
        stage.ordernum?.let { InfoRow(stringResource(R.string.ujing_water_order_no), it) }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(
                if (stage.liveConnected) {
                    R.string.shower_water_live_connected
                } else {
                    R.string.shower_water_live_polling
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        PrimaryAction(
            text = stringResource(R.string.shower_water_stop),
            busy = busy,
            busyText = stringResource(R.string.shower_water_stopping),
            onClick = onStop
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onRefresh,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.shower_water_refresh))
        }
    }
}

@Composable
private fun FinishedBlock(
    stage: ShowerWaterUiStage.Finished,
    busy: Boolean,
    onRestart: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusBadge(
            text = stage.message ?: stringResource(R.string.shower_water_finished_title),
            container = MaterialTheme.colorScheme.secondaryContainer
        )
        Spacer(Modifier.height(18.dp))
        InfoRow(
            stringResource(R.string.shower_water_cost),
            stage.amount?.let { String.format(Locale.CHINA, "¥%.2f", it) }
                ?: stringResource(R.string.shower_water_cost_unknown)
        )
        val minutes = stage.minutes ?: stage.elapsedMinutes
        InfoRow(
            stringResource(R.string.shower_water_minutes),
            minutes?.let { stringResource(R.string.shower_water_minutes_value, it) } ?: "—"
        )
        Spacer(Modifier.height(24.dp))
        PrimaryAction(
            text = stringResource(R.string.shower_water_start),
            busy = busy,
            busyText = stringResource(R.string.shower_water_starting),
            onClick = onRestart
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_exit))
        }
    }
}

@Composable
private fun ErrorBlock(
    message: String,
    canRetry: Boolean,
    busy: Boolean,
    onRetry: () -> Unit,
    onOpenWeb: (() -> Unit)?,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusBadge(
            text = message,
            container = MaterialTheme.colorScheme.errorContainer
        )
        Spacer(Modifier.height(24.dp))
        if (canRetry) {
            PrimaryAction(
                text = stringResource(R.string.action_retry),
                busy = busy,
                busyText = stringResource(R.string.shower_water_checking),
                onClick = onRetry
            )
            Spacer(Modifier.height(10.dp))
        }
        WebFallbackAction(onOpenWeb)
        OutlinedButton(
            onClick = onExit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_exit))
        }
    }
}

/** 「改用网页打开」：只在调用方确实给了逃生口地址时出现。 */
@Composable
private fun WebFallbackAction(onOpenWeb: (() -> Unit)?) {
    if (onOpenWeb == null) return
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = onOpenWeb, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.shower_water_open_web))
    }
}

@Composable
private fun StatusBadge(text: String, container: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(14.dp), color = container, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        )
    }
}

@Composable
private fun PrimaryAction(text: String, busy: Boolean, busyText: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy,
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.size(10.dp))
            Text(busyText)
        } else {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** 「本次已用」：服务端开始时间到现在；开始时间解析不了就退回 null（界面显示 —）。 */
private fun elapsedText(startTime: String?, now: Long): String? {
    val start = parseServerTime(startTime) ?: return null
    val totalSeconds = ((now - start) / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    // 计时器的数字与语言无关，不必翻译
    return if (totalSeconds >= 3600) {
        "%d:%02d:%02d".format(totalSeconds / 3600, minutes, seconds)
    } else {
        "%d:%02d".format(totalSeconds / 60, seconds)
    }
}

private fun parseServerTime(raw: String?): Long? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).parse(text)?.time
    }.getOrNull()
}
