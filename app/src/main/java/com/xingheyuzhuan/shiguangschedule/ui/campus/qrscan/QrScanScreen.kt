package com.xingheyuzhuan.shiguangschedule.ui.campus.qrscan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.QrScanEngine
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet
import java.util.concurrent.Executors

private const val TAG = "QrScanScreen"

/**
 * 扫一扫：以本机已登录的统一认证会话，确认其它端（PC）展示的登录二维码。
 *
 * 服务端行为见 WBUCas/qr_scan_notes.md：扫码置 2，确认置 1。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    navBridge: NavBridge,
    viewModel: QrScanViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsState()
    val transientError by viewModel.transientError.collectAsState()
    val tlsPrompt by viewModel.tlsPrompt.collectAsState()
    val scanEngine by viewModel.scanEngine.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var showAuthSheet by remember { mutableStateOf(false) }
    var showEngineMenu by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 从系统设置返回后重新确认权限
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 取景期间保持屏幕常亮
    val view = LocalView.current
    val keepScreenOn = state is QrScanUiState.Scanning
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { if (keepScreenOn) view.keepScreenOn = false }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.title_qr_scan),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navBridge.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back),
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showEngineMenu = true }) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.a11y_qr_scan_engine),
                                tint = Color.White
                            )
                        }
                        // 解码引擎切换：ML Kit 识别不理想时改用 ZXing 备用
                        DropdownMenu(
                            expanded = showEngineMenu,
                            onDismissRequest = { showEngineMenu = false }
                        ) {
                            ScanEngineMenuItem(
                                engine = QrScanEngine.ML_KIT,
                                selected = scanEngine == QrScanEngine.ML_KIT,
                                onSelect = {
                                    showEngineMenu = false
                                    viewModel.selectScanEngine(QrScanEngine.ML_KIT)
                                }
                            )
                            ScanEngineMenuItem(
                                engine = QrScanEngine.ZXING,
                                selected = scanEngine == QrScanEngine.ZXING,
                                onSelect = {
                                    showEngineMenu = false
                                    viewModel.selectScanEngine(QrScanEngine.ZXING)
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasCameraPermission) {
                PermissionPanel(
                    onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = { openAppSettings(context) }
                )
            } else {
                QrCameraPreview(
                    engine = scanEngine,
                    scanning = state is QrScanUiState.Scanning,
                    onQrCode = viewModel::onCodeDecoded,
                    modifier = Modifier.fillMaxSize()
                )

                if (state is QrScanUiState.Scanning) {
                    ScanFrameOverlay()
                }

                StatePanel(
                    state = state,
                    transientError = transientError,
                    onConfirm = viewModel::confirm,
                    onRescan = viewModel::rescan,
                    onLogin = { showAuthSheet = true },
                    onDone = { navBridge.popBackStack() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                )
            }
        }
    }

    if (showAuthSheet) {
        WbuCampusAuthSheet(
            onDismiss = { showAuthSheet = false },
            onLoginSuccess = {
                showAuthSheet = false
                viewModel.onLoginSuccess()
            },
            requireUnifiedCas = true,
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
}

/** 相机预览 + 二维码解码；[scanning] 为 false 时保留预览但停止分析（画面即冻结在最后一帧）。 */
@Composable
private fun QrCameraPreview(
    engine: QrScanEngine,
    scanning: Boolean,
    onQrCode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val currentOnQrCode by rememberUpdatedState(onQrCode)
    val analyzer = remember(engine) { createQrAnalyzer(engine) { raw -> currentOnQrCode(raw) } }

    // 换引擎即换分析器：释放上一个（ML Kit 的 client 需要 close）
    DisposableEffect(analyzer) {
        onDispose { analyzer.close() }
    }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            { cameraProvider = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(context)
        )
    }

    AndroidView(factory = { previewView }, modifier = modifier)

    DisposableEffect(cameraProvider, lifecycleOwner, scanning, analyzer) {
        val provider = cameraProvider
        if (provider != null) {
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            provider.unbindAll()
            if (scanning) {
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, analyzer) }
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } else {
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            }
        }
        onDispose { cameraProvider?.unbindAll() }
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }
}

/** 可切换/可释放的二维码分析器。 */
private interface QrAnalyzer : ImageAnalysis.Analyzer {
    fun close() {}
}

private fun createQrAnalyzer(engine: QrScanEngine, onQrCode: (String) -> Unit): QrAnalyzer =
    when (engine) {
        QrScanEngine.ML_KIT -> MlKitQrAnalyzer(onQrCode)
        QrScanEngine.ZXING -> ZxingQrAnalyzer(onQrCode)
    }

/** ML Kit 解码；每帧处理完必须 close，否则前端会停止出帧。 */
private class MlKitQrAnalyzer(
    private val onQrCode: (String) -> Unit
) : QrAnalyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                barcodes.firstNotNullOfOrNull { it.rawValue }?.let(onQrCode)
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    override fun close() = scanner.close()
}

/**
 * ZXing 解码（备用引擎）：Y 平面 → 亮度矩阵 → 转正 → 二值化 → QR 解码。
 * 每帧处理完必须 close，否则前端会停止出帧。
 */
private class ZxingQrAnalyzer(
    private val onQrCode: (String) -> Unit
) : QrAnalyzer {

    private val reader = MultiFormatReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        // 备用引擎按「宁可慢也要认出」取舍
        DecodeHintType.TRY_HARDER to true
    )

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val plane = imageProxy.planes.firstOrNull() ?: return
            val y = QrLuminance.copyPlane(
                buffer = plane.buffer,
                width = imageProxy.width,
                height = imageProxy.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride
            )
            val luma = QrLuminance.rotate(
                data = y,
                width = imageProxy.width,
                height = imageProxy.height,
                degrees = imageProxy.imageInfo.rotationDegrees
            )
            val source = PlanarYUVLuminanceSource(
                luma.data, luma.width, luma.height, 0, 0, luma.width, luma.height, false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            reader.decode(bitmap, hints)?.text?.takeIf { it.isNotBlank() }?.let(onQrCode)
        } catch (e: NotFoundException) {
            // 本帧没有可识别的二维码：正常情况
        } catch (e: Exception) {
            Log.w(TAG, "ZXing 解码异常", e)
        } finally {
            imageProxy.close()
        }
    }
}

/** 引擎切换菜单项。 */
@Composable
private fun ScanEngineMenuItem(
    engine: QrScanEngine,
    selected: Boolean,
    onSelect: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                stringResource(
                    when (engine) {
                        QrScanEngine.ML_KIT -> R.string.qr_scan_engine_mlkit
                        QrScanEngine.ZXING -> R.string.qr_scan_engine_zxing
                    }
                )
            )
        },
        leadingIcon = { RadioButton(selected = selected, onClick = null) },
        onClick = onSelect
    )
}

/** 取景框：遮罩挖空 + 白色描边。 */
@Composable
private fun ScanFrameOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val side = minOf(size.width, size.height) * 0.68f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val corner = CornerRadius(24.dp.toPx(), 24.dp.toPx())

        val mask = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addRoundRect(RoundRect(left, top, left + side, top + side, corner))
        }
        drawPath(mask, Color.Black.copy(alpha = 0.45f))
        drawRoundRect(
            color = Color.White.copy(alpha = 0.9f),
            topLeft = Offset(left, top),
            size = Size(side, side),
            cornerRadius = corner,
            style = Stroke(width = 3.dp.toPx())
        )
    }
}

/** 底部状态面板：随扫码状态切换。 */
@Composable
private fun StatePanel(
    state: QrScanUiState,
    transientError: QrScanError?,
    onConfirm: () -> Unit,
    onRescan: () -> Unit,
    onLogin: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val notice = transientError
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (notice != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Text(
                    text = scannerErrorText(notice),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

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

/** 相机权限未授予时的说明卡片。 */
@Composable
private fun PermissionPanel(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.QrCodeScanner,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.qr_scan_permission_title),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.qr_scan_permission_desc),
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Row {
            Button(onClick = onRequest) {
                Text(stringResource(R.string.action_qr_scan_grant))
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onOpenSettings) {
                Text(
                    text = stringResource(R.string.action_qr_scan_open_settings),
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun scannerErrorText(kind: QrScanError): String = when (kind) {
    QrScanError.NOT_CAS_QR -> stringResource(R.string.qr_scan_err_not_cas)
    QrScanError.EXPIRED -> stringResource(R.string.qr_scan_err_expired)
    QrScanError.NETWORK -> stringResource(R.string.qr_scan_err_network)
}

private fun maskedUuid(uuid: String): String =
    if (uuid.length <= 8) uuid else "${uuid.take(3)}****${uuid.takeLast(4)}"

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
