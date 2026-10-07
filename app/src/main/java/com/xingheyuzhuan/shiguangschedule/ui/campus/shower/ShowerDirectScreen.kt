package com.xingheyuzhuan.shiguangschedule.ui.campus.shower

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppId
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuAuthTipsScenario
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet
import com.xingheyuzhuan.shiguangschedule.ui.components.WbuLoadingPlaceholder

/**
 * 洗浴设备直达链接（`/s/{系统}/{设备号}[/{端口}]`）的落地页。
 *
 * 设计要点（决定了它为什么不是 `LinkHub`）：
 * - 动作恒免确认 —— 与「直接扫控水器二维码」完全等价，没有需要用户确认的内容，
 *   所以这里**没有标题栏、没有确认卡片**，任何时刻都不会出现「分享的内容」外壳；
 * - 过渡态直接用 [WbuLoadingPlaceholder]，与 [Destination.WebApp] 的首屏是**同一个组件**，
 *   两者之间由 `Navigation.isSeamlessHandoff` 关掉转场动画，硬切时像素一致、看不出切换；
 * - 失败/未登录时占位信息取代过渡态（这是用户唯一需要读信息的时刻）。
 *
 * 入口：NFC 标签、二维码、外部链接（VIEW），见 `CampusLinkRouter` 与 `CampusShowerLink`。
 */
@Composable
fun ShowerDirectScreen(
    navBridge: NavBridge,
    system: String,
    code: String,
    port: String? = null,
    viewModel: ShowerDirectViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showAuthSheet by remember { mutableStateOf(false) }

    LaunchedEffect(system, code, port) { viewModel.start(system, code, port) }

    // 解析成功：交给网页应用容器（一卡通），由它完成取票与页面加载
    LaunchedEffect(Unit) {
        viewModel.openWebApp.collect { ready ->
            // 已正证是淋浴：直接进原生用水页（其它设备类型保持原来的网页容器）
            val native = ready.nativeShower
            if (native != null) {
                navBridge.replace(
                    Destination.ShowerWater(
                        deviceId = native.deviceId,
                        port = native.port,
                        implid = native.implid,
                        feeitemid = native.feeitemid,
                        webFallbackUrl = ready.initialUrl
                    )
                )
                return@collect
            }
            navBridge.replace(
                Destination.WebApp(
                    appId = WebAppId.CAMPUS_CARD.name,
                    initialTargetUrl = ready.initialUrl,
                    pendingAutoScan = ready.pendingAutoScan
                )
            )
        }
    }

    BackHandler { navBridge.popBackStack() }

    // safeDrawingPadding 与 WebAppScreen 的容器保持一致：
    // 两侧品牌过渡的居中位置必须完全重合，硬切时才看不出切换
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        color = MaterialTheme.colorScheme.surface
    ) {
        when (val current = state) {
            // 解析中 / 已就绪待跳转：都保持品牌过渡（与 WebApp 首屏同款，硬切不可见）
            ShowerDirectUiState.Resolving,
            ShowerDirectUiState.Opening -> WbuLoadingPlaceholder(
                message = stringResource(R.string.status_fetching_webapp_token)
            )

            is ShowerDirectUiState.Failed -> FailedBlock(
                messageRes = current.messageRes,
                retryable = current.retryable,
                needsLogin = current.needsLogin,
                onLogin = { showAuthSheet = true },
                onRetry = viewModel::retry,
                onExit = { navBridge.popBackStack() }
            )
        }
    }

    // 「重新登录」入口：以前登录态失效只给「重试」，用户点多少次看到的都是同一句提示。
    if (showAuthSheet) {
        WbuCampusAuthSheet(
            onDismiss = { showAuthSheet = false },
            onLoginSuccess = {
                showAuthSheet = false
                viewModel.onLoginSuccess()
            },
            requireUnifiedCas = true,
            // 设备直达只需统一认证会话：不校验校园网、不登录教务，网络开关随「统一认证经过WebVPN」显隐
            unifiedAuthOnly = true,
            tipsScenario = WbuAuthTipsScenario.IDENTITY,
            title = stringResource(R.string.title_login_unified_auth)
        )
    }
}

@Composable
private fun FailedBlock(
    messageRes: Int,
    retryable: Boolean,
    needsLogin: Boolean,
    onLogin: () -> Unit,
    onRetry: () -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (needsLogin) {
                    Button(onClick = onLogin) {
                        Text(stringResource(R.string.action_relogin))
                    }
                }
                if (retryable) {
                    Button(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                OutlinedButton(onClick = onExit) {
                    Text(stringResource(R.string.action_exit))
                }
            }
        }
    }
}
