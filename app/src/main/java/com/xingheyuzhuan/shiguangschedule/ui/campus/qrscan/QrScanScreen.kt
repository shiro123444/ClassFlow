package com.xingheyuzhuan.shiguangschedule.ui.campus.qrscan

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuAuthTipsScenario
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet

/**
 * 扫一扫：以本机已登录的统一认证会话，确认其它端（PC）展示的登录二维码。
 *
 * 服务端行为见 WBUCas/qr_scan_notes.md：扫码置 2，确认置 1。
 */
@Composable
fun QrScanScreen(
    navBridge: NavBridge,
    viewModel: QrScanViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val transientNotice by viewModel.transientNotice.collectAsState()
    val tlsPrompt by viewModel.tlsPrompt.collectAsState()
    val scanEngine by viewModel.scanEngine.collectAsState()
    val photoBusy by viewModel.photoBusy.collectAsState()
    val hairdryerPrompt by viewModel.hairdryerPrompt.collectAsState()
    val washerOffline by viewModel.washerOffline.collectAsState()
    val washerLoading by viewModel.washerLoading.collectAsState()

    var showAuthSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.scanEvent.collect { event ->
            when (event) {
                is QrScanEvent.NavigateToWater -> {
                    navBridge.replace(com.xingheyuzhuan.shiguangschedule.Destination.UjingWater(event.cd))
                }
                is QrScanEvent.NavigateToWasher -> {
                    navBridge.replace(
                        com.xingheyuzhuan.shiguangschedule.Destination.WebApp(
                            appId = com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppId.CAMPUS_CARD.name,
                            initialTargetUrl = event.initialUrl,
                            pendingAutoScan = event.pendingAutoScan
                        )
                    )
                }
                is QrScanEvent.OpenHairdryer -> {
                    val schemeUri = Uri.parse(event.scheme)
                    // 1. 优先使用标准 alipays:// Scheme 显式调起支付宝官方客户端（经实测可直接唤起吹风机原生小程序）
                    val explicitIntent = Intent(Intent.ACTION_VIEW, schemeUri).apply {
                        setPackage(com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.ALIPAY_PACKAGE_NAME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val launched = runCatching {
                        context.startActivity(explicitIntent)
                        true
                    }.getOrElse {
                        // 2. 兜底：通用 alipays Scheme（适配分身等）
                        val genericIntent = Intent(Intent.ACTION_VIEW, schemeUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching {
                            context.startActivity(genericIntent)
                            true
                        }.getOrElse {
                            // 3. 兜底：尝试 NFC Action 调起 alipay://nfc/app
                            val nfcScheme = com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.buildHairdryerNfcScheme(event.cd)
                            val nfcIntent = Intent(android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED, Uri.parse(nfcScheme)).apply {
                                setPackage(com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.ALIPAY_PACKAGE_NAME)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching {
                                context.startActivity(nfcIntent)
                                true
                            }.getOrElse {
                                // 4. 降级：Universal Link 网页或浏览器调起
                                val ulinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(event.ulinkUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatching {
                                    context.startActivity(ulinkIntent)
                                    true
                                }.getOrDefault(false)
                            }
                        }
                    }
                    if (launched) {
                        navBridge.popBackStack()
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.ujing_alipay_not_installed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    // 系统 Photo Picker：不需要任何存储/媒体权限
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::onPhotoPicked) }

    // NeedLogin 时也保持解码：U净 设备码不依赖统一认证登录态，需随时可扫；
    // 统一认证二维码在 NeedLogin 下会被 ViewModel 忽略。
    val scanning = state is QrScanUiState.Scanning || state is QrScanUiState.NeedLogin
    val notice = transientNotice
    val noticeString = when {
        washerLoading -> stringResource(R.string.ujing_washer_checking)
        notice != null -> noticeText(notice)
        else -> null
    }

    // 与网页应用扫码共用同一套圆图标 UI（顶部渐变遮罩 + 圆形关闭/相册/引擎按钮）
    QrScannerScaffold(
        scanning = scanning,
        onQrCode = viewModel::onCodeDecoded,
        onDismiss = { navBridge.popBackStack() },
        engine = scanEngine,
        onSelectEngine = viewModel::selectScanEngine,
        onGallery = {
            photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        galleryBusy = photoBusy,
        notice = noticeString,
        bottomContent = {
            // 相册选图 / 登录 / 确认都不依赖相机，权限被拒时也要能看到状态卡。
            // 取景框会按本卡实测高度避让，横竖屏都不会重叠。
            StatePanel(
                state = state,
                onConfirm = viewModel::confirm,
                onRescan = viewModel::rescan,
                onLogin = { showAuthSheet = true },
                onDone = { navBridge.popBackStack() },
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
                    .widthIn(max = 420.dp)
            )
        }
    )

    if (showAuthSheet) {
        WbuCampusAuthSheet(
            onDismiss = { showAuthSheet = false },
            onLoginSuccess = {
                showAuthSheet = false
                viewModel.onLoginSuccess()
            },
            requireUnifiedCas = true,
            // 扫一扫只为拿到/续期统一认证会话：不校验校园网、不登录教务，网络开关随「统一认证经过WebVPN」显隐
            unifiedAuthOnly = true,
            // 轮播文案用统一认证款（该 Sheet 在这里只为拿/续统一认证会话）
            tipsScenario = WbuAuthTipsScenario.IDENTITY,
            title = stringResource(R.string.title_login_unified_auth)
        )
    }

    val prompt = tlsPrompt
    if (prompt != null) {
        AlertDialog(
            onDismissRequest = { viewModel.resolveTlsPrompt(false) },
            title = { Text(stringResource(R.string.title_qr_scan)) },
            text = { Text(prompt) },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveTlsPrompt(true) }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveTlsPrompt(false) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (hairdryerPrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissHairdryerDialog() },
            title = { Text(stringResource(R.string.dialog_ujing_hairdryer_title)) },
            text = { Text(stringResource(R.string.dialog_ujing_hairdryer_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissHairdryerDialog() }) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }

    if (washerOffline) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissWasherOfflineDialog() },
            title = { Text(stringResource(R.string.dialog_ujing_washer_offline_title)) },
            text = { Text(stringResource(R.string.dialog_ujing_washer_offline_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissWasherOfflineDialog() }) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }
}

/** 底部状态面板：随扫码状态切换。 */
@Composable
private fun StatePanel(
    state: QrScanUiState,
    onConfirm: () -> Unit,
    onRescan: () -> Unit,
    onLogin: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        when (state) {
            is QrScanUiState.Scanning -> {
                Text(
                    text = stringResource(R.string.qr_scan_hint_align),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            is QrScanUiState.NeedLogin -> PanelCard(
                title = stringResource(R.string.qr_scan_need_login_title),
                description = stringResource(R.string.qr_scan_need_login_desc),
                primaryText = stringResource(R.string.action_qr_scan_login),
                onPrimary = onLogin
            )

            is QrScanUiState.Scanned -> PanelCard(
                title = stringResource(R.string.qr_scan_scanned_title),
                description = stringResource(R.string.qr_scan_confirm_hint),
                badge = maskedUuid(state.uuid),
                primaryText = stringResource(R.string.action_qr_scan_confirm),
                onPrimary = onConfirm,
                secondaryText = stringResource(R.string.action_qr_scan_rescan),
                onSecondary = onRescan
            )

            is QrScanUiState.Confirming -> PanelCard(
                title = stringResource(R.string.qr_scan_confirming),
                description = null,
                badge = maskedUuid(state.uuid),
                loading = true
            )

            is QrScanUiState.Success -> PanelCard(
                title = stringResource(R.string.qr_scan_success_title),
                description = stringResource(R.string.qr_scan_success_desc),
                success = true,
                primaryText = stringResource(R.string.action_qr_scan_done),
                onPrimary = onDone
            )

            is QrScanUiState.Failed -> PanelCard(
                title = scannerErrorText(state.kind),
                description = null,
                primaryText = stringResource(R.string.action_qr_scan_retry),
                onPrimary = onRescan
            )
        }
    }
}

@Composable
private fun PanelCard(
    title: String,
    description: String?,
    badge: String? = null,
    loading: Boolean = false,
    success: Boolean = false,
    primaryText: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.height(12.dp))
            }
            if (success) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.height(8.dp))
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            if (badge != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = badge,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (description != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            if (primaryText != null && onPrimary != null) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onPrimary) { Text(primaryText) }
                    if (secondaryText != null && onSecondary != null) {
                        Spacer(Modifier.width(12.dp))
                        TextButton(onClick = onSecondary) { Text(secondaryText) }
                    }
                }
            }
        }
    }
}

@Composable
private fun noticeText(notice: QrTransientNotice): String = when (notice) {
    QrTransientNotice.NOT_CAS_QR -> stringResource(R.string.qr_scan_err_not_cas)
    QrTransientNotice.PHOTO_NO_CODE -> stringResource(R.string.qr_scan_photo_no_code)
}

@Composable
private fun scannerErrorText(kind: QrScanError): String = when (kind) {
    QrScanError.NOT_CAS_QR -> stringResource(R.string.qr_scan_err_not_cas)
    QrScanError.EXPIRED -> stringResource(R.string.qr_scan_err_expired)
    QrScanError.NETWORK -> stringResource(R.string.qr_scan_err_network)
}

private fun maskedUuid(uuid: String): String =
    if (uuid.length <= 8) uuid else "${uuid.take(3)}****${uuid.takeLast(4)}"
