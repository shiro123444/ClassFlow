package com.xingheyuzhuan.shiguangschedule.ui.campus.qrscan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.QrScanEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val TAG = "QrScannerView"

/** 取景框边长上限：Pad/大屏上不再无限放大。 */
private val MAX_FRAME_SIDE = 320.dp

/** 顶部渐变遮罩高度：横竖屏下都能盖住状态栏与圆形按钮。 */
private val TOP_SCRIM_HEIGHT = 148.dp

/** 取景框上方为圆形按钮预留的高度。 */
private val TOP_CHROME_RESERVED = 84.dp

/**
 * 通用扫码取景脚手架：统一「扫一扫」与网页应用扫码的圆图标 UI。
 *
 * 内部负责相机权限、屏幕常亮、顶部渐变遮罩、圆形关闭/相册/引擎按钮、取景框避让，
 * 并允许调用方通过 [bottomContent] 提供底部提示或状态卡。
 *
 * @param scanning 为 false 时保留预览但停止分析（画面冻结在最后一帧）
 * @param onQrCode 相机解码回调
 * @param onDismiss 关闭
 * @param engine 当前解码引擎
 * @param onSelectEngine 切换解码引擎
 * @param onGallery 相册识别入口（null 则不显示按钮）
 * @param galleryBusy 相册解码中（按钮显示进度）
 * @param notice 顶部一次性提示
 * @param bottomContent 底部内容（提示文案 / 状态卡）
 */
@Composable
internal fun QrScannerScaffold(
    scanning: Boolean,
    onQrCode: (String) -> Unit,
    onDismiss: () -> Unit,
    engine: QrScanEngine,
    onSelectEngine: (QrScanEngine) -> Unit,
    onGallery: (() -> Unit)? = null,
    galleryBusy: Boolean = false,
    notice: String? = null,
    showGallery: Boolean = true,
    showEngineSwitch: Boolean = true,
    modifier: Modifier = Modifier,
    bottomContent: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var showEngineMenu by remember { mutableStateOf(false) }
    var bottomHeightPx by remember { mutableStateOf(0f) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    // 首次进入自动请求相机权限
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
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val reservedTopPx = with(density) { TOP_CHROME_RESERVED.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            QrCameraPreview(
                engine = engine,
                scanning = scanning,
                onQrCode = onQrCode,
                modifier = Modifier.fillMaxSize()
            )
            ScanFrameOverlay(
                reservedTop = reservedTopPx,
                reservedBottom = bottomHeightPx
            )
        }

        // 顶部渐变遮罩：让状态栏与圆形按钮在横竖屏、浅色背景下都清晰可读
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOP_SCRIM_HEIGHT)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.72f),
                            Color.Black.copy(alpha = 0.35f),
                            Color.Transparent
                        )
                    )
                )
        )

        // 关闭
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .statusBarsPadding()
                .padding(12.dp)
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.action_cancel),
                tint = Color.White
            )
        }

        // 相册识别 / 解码引擎切换
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(12.dp)
                .align(Alignment.TopEnd)
        ) {
            if (showGallery && onGallery != null) {
                IconButton(
                    onClick = onGallery,
                    enabled = !galleryBusy,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)
                ) {
                    if (galleryBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PhotoLibrary,
                            contentDescription = stringResource(R.string.a11y_qr_scan_photo),
                            tint = Color.White
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            if (showEngineSwitch) {
                Box {
                    IconButton(
                        onClick = { showEngineMenu = true },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.a11y_qr_scan_engine),
                            tint = Color.White
                        )
                    }
                    DropdownMenu(
                        expanded = showEngineMenu,
                        onDismissRequest = { showEngineMenu = false }
                    ) {
                        ScanEngineMenuItem(
                            engine = QrScanEngine.ML_KIT,
                            selected = engine == QrScanEngine.ML_KIT,
                            onSelect = {
                                showEngineMenu = false
                                onSelectEngine(QrScanEngine.ML_KIT)
                            }
                        )
                        ScanEngineMenuItem(
                            engine = QrScanEngine.ZXING,
                            selected = engine == QrScanEngine.ZXING,
                            onSelect = {
                                showEngineMenu = false
                                onSelectEngine(QrScanEngine.ZXING)
                            }
                        )
                    }
                }
            }
        }

        // 一次性提示（如相册未识别到二维码）
        notice?.let { text ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 60.dp)
            ) {
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        if (!hasCameraPermission) {
            PermissionPanel(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { openAppSettings(context) }
            )
        }

        // 底部内容：测量其高度交给取景框避让，避免提示/状态卡与取景框重叠
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .onSizeChanged { bottomHeightPx = it.height.toFloat() },
            contentAlignment = Alignment.BottomCenter
        ) {
            bottomContent()
        }
    }
}

/**
 * 网页应用扫码浮层（U净 / 一卡通平台）：圆图标 UI + 内部解码引擎与相册识别。
 */
@Composable
internal fun QrScannerOverlay(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    hint: String? = null,
    showGallery: Boolean = true,
    showEngineSwitch: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var engine by remember { mutableStateOf(WbuAuthTransport.getQrScanEngine(context)) }
    var handled by remember { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 拦截返回键：扫码浮层展示时返回键优先关闭浮层，防止透传至底层页面导致路由后退
    BackHandler { onDismiss() }

    // 系统 Photo Picker：不需要任何存储/媒体权限
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            photoBusy = true
            scope.launch {
                val decoded = withContext(Dispatchers.IO) { QrImageDecoder.decode(context, uri, engine) }
                photoBusy = false
                if (decoded.isNullOrBlank()) {
                    notice = context.getString(R.string.qr_scan_photo_no_code)
                } else {
                    handled = true
                    onScanned(decoded)
                }
            }
        }
    }

    QrScannerScaffold(
        scanning = !handled,
        onQrCode = { raw ->
            if (!handled) {
                handled = true
                onScanned(raw)
            }
        },
        onDismiss = onDismiss,
        engine = engine,
        onSelectEngine = {
            engine = it
            WbuAuthTransport.setQrScanEngine(context, it)
        },
        onGallery = if (showGallery) {
            {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        } else null,
        galleryBusy = photoBusy,
        notice = notice,
        showGallery = showGallery,
        showEngineSwitch = showEngineSwitch,
        modifier = modifier
    ) {
        if (hint != null && !handled) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 40.dp)
            ) {
                Text(
                    text = hint,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/** 相机预览 + 二维码解码；[scanning] 为 false 时保留预览但停止分析（画面即冻结在最后一帧）。 */
@Composable
internal fun QrCameraPreview(
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
internal fun ScanEngineMenuItem(
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

/**
 * 取景框：遮罩挖空 + 白色描边。
 *
 * [reservedTop] / [reservedBottom] 是顶部圆形按钮与底部提示/状态卡占用的空间（px），
 * 据此把取景框排进中间剩余区域并居中，避免与它们重叠。
 */
@Composable
internal fun ScanFrameOverlay(
    reservedTop: Float = 0f,
    reservedBottom: Float = 0f,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val freeHeight = (size.height - reservedTop - reservedBottom).coerceAtLeast(0f)
        val side = (minOf(size.width, freeHeight) * 0.68f).coerceAtMost(MAX_FRAME_SIDE.toPx())
        val left = (size.width - side) / 2f
        val top = reservedTop + (freeHeight - side) / 2f
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

/** 相机权限未授予时的说明卡片。 */
@Composable
internal fun PermissionPanel(
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
        // 限宽：Pad/横屏下文案不横跨整屏
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
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
}

/** 打开本应用的系统设置页（用于手动授予相机权限）。 */
internal fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
