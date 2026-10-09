package com.xingheyuzhuan.shiguangschedule.ui.campus.hairdryer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerDeviceType
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerLaunchSource
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppId
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink
import com.xingheyuzhuan.shiguangschedule.ui.campus.ujing.launchUjingHairdryer
import com.xingheyuzhuan.shiguangschedule.ui.components.LocalEntryOverlayController
import com.xingheyuzhuan.shiguangschedule.ui.components.WbuLoadingPlaceholder

/**
 * 吹风机分流过渡页：扫码 / NFC / 桌面快捷方式三种入口都先落到这里。
 *
 * 判型可能连着问几次接口（一卡通换票 → U净 换票 → 扫码 → 设备信息 → 门店信息），所以过渡态
 * 直接用品牌过渡组件 [WbuLoadingPlaceholder] —— 与 [Destination.WebApp] 的首屏是同一个组件，
 * 两者之间由 `Navigation.isSeamlessHandoff` 关掉转场动画，硬切时像素一致、看不出切换。
 *
 * - 判出**云端（4G）**：换成网页应用容器，地址已停在「选择左右机」那一步；
 * - 判出**蓝牙**：应用层品牌过场 + 调起支付宝 U净 小程序（本容器没有蓝牙能力）；
 * - 第一次遇到某种码格式：先弹一次确认（见 [HairdryerLaunchUiState.Confirm]）。
 */
@Composable
fun HairdryerLaunchScreen(
    navBridge: NavBridge,
    cd: String,
    raw: String,
    source: String,
    viewModel: HairdryerLaunchViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val entryOverlay = LocalEntryOverlayController.current
    val launchSource = HairdryerLaunchSource.fromNameOrNull(source) ?: HairdryerLaunchSource.SCAN

    LaunchedEffect(cd, raw, source) { viewModel.start(cd, raw, launchSource) }

    // 去向已定：执行跳转。目标就在状态里，不依赖一次性事件（判型快的时候事件会漏）
    LaunchedEffect(state) {
        val ready = state as? HairdryerLaunchUiState.Ready ?: return@LaunchedEffect
        when (val target = ready.target) {
            is HairdryerLaunchTarget.CampusCard -> navBridge.replace(
                Destination.WebApp(
                    appId = WebAppId.CAMPUS_CARD.name,
                    initialTargetUrl = target.initialUrl,
                    // 设备页是受保护路由：交给容器在页面授权完成后落上去（冷启动直接落会被
                    // 路由守卫扔到 /login），见 Navigation.Destination.WebApp.pendingHashRoute
                    pendingHashRoute = target.hashRoute
                )
            )

            is HairdryerLaunchTarget.Alipay -> {
                // 先请求应用层品牌过场：本页随后会 pop，动画挂在应用层才不会被销毁，
                // 正好盖住支付宝冷启动的空白期
                entryOverlay.show(1800)
                launchUjingHairdryer(
                    context = context,
                    cd = cd,
                    scheme = UjingQrLink.buildHairdryerAlipayScheme(cd),
                    ulinkUrl = UjingQrLink.buildHairdryerAlipayUrl(cd)
                )
                navBridge.popBackStack()
            }
        }
    }

    BackHandler { navBridge.popBackStack() }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        color = MaterialTheme.colorScheme.surface
    ) {
        WbuLoadingPlaceholder(message = stringResource(R.string.ujing_hairdryer_checking))
    }

    val confirm = state as? HairdryerLaunchUiState.Confirm
    if (confirm != null) {
        HairdryerConfirmDialog(
            guess = confirm.guess,
            onAnswer = viewModel::onConfirm,
            onCancel = { navBridge.popBackStack() }
        )
    }
}

/**
 * 本地判据的首次确认：这种码格式第一次出现时问一句，回答会被记住（以后不再问）。
 *
 * 「以后去哪改」必须写清楚 —— 这是用户第一次（也可能唯一一次）意识到这个设置存在的地方。
 * 点外面 / 返回 = 放弃这次跳转（不记录判据），下次再问。
 */
@Composable
private fun HairdryerConfirmDialog(
    guess: HairdryerDeviceType,
    onAnswer: (Boolean) -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.hairdryer_confirm_title)) },
        text = {
            Text(
                text = stringResource(
                    if (guess == HairdryerDeviceType.CLOUD) {
                        R.string.hairdryer_confirm_message_cloud
                    } else {
                        R.string.hairdryer_confirm_message_bluetooth
                    }
                ) + "\n\n" + stringResource(R.string.hairdryer_confirm_hint)
            )
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) {
                Text(stringResource(R.string.hairdryer_confirm_correct))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) {
                Text(stringResource(R.string.hairdryer_confirm_incorrect))
            }
        }
    )
}
