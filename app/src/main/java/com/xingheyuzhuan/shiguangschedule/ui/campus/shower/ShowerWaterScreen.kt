package com.xingheyuzhuan.shiguangschedule.ui.campus.shower

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppId
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuShowerWaterClient
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuAuthTipsScenario
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet
import com.xingheyuzhuan.shiguangschedule.ui.components.wbuBrandAccent
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
 *
 * 版面按三个「屏」组织，每一屏都是「上面一幅矢量插画 + 下面一组操作」：
 * 待机是静止的淋浴头 + 起按钮 + 一行小字设备 ID；用水中是流动的水柱动画 + 大字计时 + 订单编号。
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

    // 用水中每秒刷新一次「已用时间」，页面本身不轮询服务端（那是 VM 的事）
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
                    if (openWebFallback != null) {
                        IconButton(onClick = openWebFallback) {
                            Icon(
                                imageVector = Icons.Rounded.OpenInBrowser,
                                contentDescription = stringResource(R.string.shower_water_open_web)
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !uiState.busy && uiState.stage !is ShowerWaterUiStage.Loading
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            val stage = uiState.stage
            when (stage) {
                is ShowerWaterUiStage.Loading -> LoadingBlock()

                is ShowerWaterUiStage.Idle -> IdleBlock(
                    deviceNo = uiState.deviceNo,
                    busy = uiState.busy,
                    onStart = viewModel::startWater
                )

                is ShowerWaterUiStage.Active -> ActiveBlock(
                    stage = stage,
                    scannedDevice = uiState.deviceNo,
                    busy = uiState.busy,
                    now = now,
                    onStop = viewModel::stopWater
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

/**
 * 读取状态中：WBU 编钟 logo 下面转圈圈。
 *
 * 用品牌色单色纹样（[wbuBrandAccent]）而不是满屏铺底，避免从上一个页面切进来时闪一大块色块。
 */
@Composable
private fun LoadingBlock() {
    val accent = wbuBrandAccent()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.wbu_bell_emblem),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(accent),
            modifier = Modifier.size(132.dp)
        )
        Spacer(Modifier.height(22.dp))
        CircularProgressIndicator(
            color = accent,
            strokeWidth = 3.dp,
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.shower_water_checking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** 待机：上幅静止插画，底部「开始用水」+ 一行小字设备 ID。 */
@Composable
private fun IdleBlock(deviceNo: String, busy: Boolean, onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            ShowerIllustration(
                active = false,
                modifier = Modifier.size(200.dp)
            )
        }
        Text(
            text = stringResource(R.string.shower_water_idle_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        PrimaryAction(
            text = stringResource(R.string.shower_water_start),
            busy = busy,
            busyText = stringResource(R.string.shower_water_starting),
            onClick = onStart
        )
        Spacer(Modifier.height(12.dp))
        DeviceIdLine(deviceNo)
        Spacer(Modifier.height(20.dp))
    }
}

/** 用水中：上幅流动的水柱动画，底部大字计时 + 订单编号 + 「结束用水」。 */
@Composable
private fun ActiveBlock(
    stage: ShowerWaterUiStage.Active,
    scannedDevice: String,
    busy: Boolean,
    now: Long,
    onStop: () -> Unit
) {
    val elapsed = remember(stage.startTime, now) { elapsedText(stage.startTime, now) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            ShowerIllustration(active = true, modifier = Modifier.fillMaxSize())
        }

        Text(
            text = stringResource(R.string.shower_water_active),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.shower_water_elapsed),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = elapsed ?: "—",
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.SemiBold,
                // 等宽数字：计时器每秒跳一次也不会左右抖
                fontFeatureSettings = "tnum"
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(16.dp))

        // 服务端按「缴费项」查：未结束的那一单可能在**另一台**设备上（比如刚扫的这台其实没人用）。
        // 这时把在用的设备说清楚，结束按钮结束的也是它。
        if (stage.deviceNo != scannedDevice) {
            NoticeCard(
                text = stringResource(R.string.shower_water_active_other_device, stage.deviceNo),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(10.dp))
        }

        DetailCard(
            rows = buildList {
                stage.ordernum?.let {
                    add(stringResource(R.string.ujing_water_order_no) to it)
                }
                add(stringResource(R.string.shower_water_started_at) to (stage.startTime ?: "—"))
            }
        )
        Spacer(Modifier.height(10.dp))
        LiveStatusLine(connected = stage.liveConnected)
        Spacer(Modifier.height(18.dp))
        PrimaryAction(
            text = stringResource(R.string.shower_water_stop),
            busy = busy,
            busyText = stringResource(R.string.shower_water_stopping),
            onClick = onStop
        )
        Spacer(Modifier.height(20.dp))
    }
}

/** 已结束：勾 + 金额大字 + 耗时明细，下面可以再来一单或退出。 */
@Composable
private fun FinishedBlock(
    stage: ShowerWaterUiStage.Finished,
    busy: Boolean,
    onRestart: () -> Unit,
    onDone: () -> Unit
) {
    val minutes = stage.minutes ?: stage.elapsedMinutes
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BadgeIcon(
                    icon = Icons.Rounded.Check,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stage.message ?: stringResource(R.string.shower_water_finished_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stage.amount?.let { String.format(Locale.CHINA, "¥%.2f", it) } ?: "—",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum"
                    )
                )
                Text(
                    text = stringResource(
                        if (stage.amount == null) {
                            R.string.shower_water_cost_unknown
                        } else {
                            R.string.shower_water_cost
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DetailCard(
            rows = listOf(
                stringResource(R.string.shower_water_minutes) to (
                    minutes?.let { stringResource(R.string.shower_water_minutes_value, it) } ?: "—"
                    ),
                stringResource(R.string.shower_water_device) to stage.deviceNo
            )
        )
        Spacer(Modifier.height(18.dp))
        PrimaryAction(
            text = stringResource(R.string.shower_water_start),
            busy = busy,
            busyText = stringResource(R.string.shower_water_starting),
            onClick = onRestart
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_exit))
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 出错：图标 + 原因，重试 / 改用网页 / 退出。 */
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
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BadgeIcon(
                    icon = Icons.Rounded.ErrorOutline,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        if (canRetry) {
            PrimaryAction(
                text = stringResource(R.string.action_retry),
                busy = busy,
                busyText = stringResource(R.string.shower_water_checking),
                onClick = onRetry
            )
            Spacer(Modifier.height(10.dp))
        }
        if (onOpenWeb != null) {
            OutlinedButton(onClick = onOpenWeb, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.shower_water_open_web))
            }
            Spacer(Modifier.height(10.dp))
        }
        TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_exit))
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 「ID：17010737」——按钮下面那行小字，说明这次扫的是哪台设备。 */
@Composable
private fun DeviceIdLine(deviceNo: String) {
    Text(
        text = stringResource(R.string.shower_water_device_id, deviceNo.ifBlank { "—" }),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        textAlign = TextAlign.Center
    )
}

/** 详情卡：一组「标签 → 值」的行，长得像一张浅色卡片，而不是散落的文字。 */
@Composable
private fun DetailCard(rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                // 长值（订单编号那种 32 位十六进制）单独占一行，否则右侧放不下会被截成省略号
                if (value.length > INLINE_VALUE_MAX) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        DetailLabel(label)
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DetailLabel(label)
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 超过这个长度就把值换到标签下面单独一行显示。 */
private const val INLINE_VALUE_MAX = 20

/** 一张窄提示（比如「另一台设备正在用水」）。 */
@Composable
private fun NoticeCard(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(14.dp), color = container, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = content,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

/** 结算推送是否连着：一个小圆点 + 一句小字（连不上时会走 5 秒轮询兜底）。 */
@Composable
private fun LiveStatusLine(connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    if (connected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = stringResource(
                if (connected) {
                    R.string.shower_water_live_connected
                } else {
                    R.string.shower_water_live_polling
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 圆形底 + 图标（结束的勾、出错的叹号）。 */
@Composable
private fun BadgeIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    container: Color,
    content: Color
) {
    Box(
        modifier = Modifier
            .size(104.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(56.dp)
        )
    }
}

/** 主操作按钮：整屏宽、圆角胶囊，忙时转圈。 */
@Composable
private fun PrimaryAction(text: String, busy: Boolean, busyText: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
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
