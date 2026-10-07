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
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.QrScanEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import zxingcpp.BarcodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors

private const val TAG = "QrScannerView"

/** 取景框边长上限：Pad/大屏上不再无限放大。 */
private val MAX_FRAME_SIDE = 320.dp

/** 顶部渐变遮罩高度：横竖屏下都能盖住状态栏与圆形按钮。 */
private val TOP_SCRIM_HEIGHT = 148.dp

/** 取景框上方为圆形按钮预留的高度。 */
private val TOP_CHROME_RESERVED = 84.dp

/** 预览流分辨率：16:9 的 1080p。 */
private val PREVIEW_SIZE = android.util.Size(1920, 1080)

/**
 * 分析流目标分辨率。
 *
 * 两个引擎都只解取景框那一块（zxing-cpp 把 cropRect 交给库，纯 Java 的 ZXing 自己裁 Y 平面），
 * 代价与帧大小解耦，所以统一提到 1080p：取景框内的像素多 2.25 倍，远处/偏小的码才有细节可解。
 */
private val ANALYSIS_SIZE = android.util.Size(1920, 1080)

/**
 * 预览与分析共用 16:9：两边裁切一致，取景框才能按「所见即所得」映射成 ROI。
 * （宽高比不一致时预览看到的范围比分析流多/少一截，ROI 会偏。）
 */
private val RESOLUTION_ASPECT = AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY

private val PREVIEW_RESOLUTION_SELECTOR: ResolutionSelector = ResolutionSelector.Builder()
    .setResolutionStrategy(
        ResolutionStrategy(PREVIEW_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
    )
    .setAspectRatioStrategy(RESOLUTION_ASPECT)
    .build()

/**
 * 分析流分辨率：按引擎选。
 *
 * zxing-cpp 按 Binary Eye 的做法直接要相机能给的最高分辨率（它只解取景框那块，代价可控）；
 * 纯 Java 的 ZXing 取 1080p。
 */
private fun analysisResolutionSelector(engine: QrScanEngine): ResolutionSelector {
    val builder = ResolutionSelector.Builder().setAspectRatioStrategy(RESOLUTION_ASPECT)
    return when (engine) {
        QrScanEngine.ZXING_CPP -> builder
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            .build()

        else -> builder
            .setResolutionStrategy(
                ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()
    }
}

/** 取景框外再放宽的比例：吸收手抖，以及预览流/分析流裁切略有差异带来的偏差。 */
private const val ROI_PADDING = 0.15f

/** 每隔多少帧退回一次整帧解码：保证「没对准取景框也能扫到」的老行为还在。 */
private const val FULL_FRAME_EVERY = 8

/**
 * 「进行中」提示的底色（绿色）。
 *
 * 提示浮层压在相机预览上，所以不能用跟随主题的浅色容器（会看不清），
 * 直接用一块不透明绿底 + 白字，浅色/深色主题、任何画面下都清晰。
 */
private val PROGRESS_NOTICE_CONTAINER = Color(0xFF2E7D32)

/** 中性提示：半透明黑底 + 白字（与取景框底部的提示胶囊同款）。 */
private val NEUTRAL_NOTICE_CONTAINER = Color.Black.copy(alpha = 0.6f)

/**
 * 顶部一次性提示的语气。
 *
 * [PROGRESS] 表示「正在进行中」（如正在核验设备状态）：用绿色，让用户知道程序在干活而不是报错；
 * [NEUTRAL] 表示「一个中性的识别结果」（如扫到的码不认识）：用半透明黑底，不渲染成错误；
 * [ERROR] 表示「这次没成」（设备无效、网络异常等）：用主题错误色。
 */
internal enum class QrNoticeTone { PROGRESS, NEUTRAL, ERROR }

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
 * @param noticeTone 提示语气（决定配色），见 [QrNoticeTone]
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
    noticeTone: QrNoticeTone = QrNoticeTone.ERROR,
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
    var torchOn by remember { mutableStateOf(false) }
    var flashAvailable by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

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
    // 取景框的几何只在这里算一次：画框和解码 ROI 用的是同一份，避免两边算歪
    val scanFrame = scanFrameRect(
        viewWidth = viewSize.width.toFloat(),
        viewHeight = viewSize.height.toFloat(),
        reservedTop = reservedTopPx,
        reservedBottom = bottomHeightPx,
        maxSide = with(density) { MAX_FRAME_SIDE.toPx() }
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewSize = it }
    ) {
        if (hasCameraPermission) {
            QrCameraPreview(
                engine = engine,
                scanning = scanning,
                onQrCode = onQrCode,
                scanFrame = scanFrame,
                viewWidthPx = viewSize.width,
                viewHeightPx = viewSize.height,
                torchOn = torchOn,
                onFlashAvailable = { flashAvailable = it },
                modifier = Modifier.fillMaxSize()
            )
            ScanFrameOverlay(frame = scanFrame)
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
            if (flashAvailable) {
                IconButton(
                    onClick = { torchOn = !torchOn },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (torchOn) {
                            Icons.Rounded.FlashlightOn
                        } else {
                            Icons.Rounded.FlashlightOff
                        },
                        contentDescription = stringResource(
                            if (torchOn) R.string.a11y_qr_scan_torch_off else R.string.a11y_qr_scan_torch_on
                        ),
                        tint = if (torchOn) Color(0xFFFFD54F) else Color.White
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
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
                            engine = QrScanEngine.ZXING,
                            selected = engine == QrScanEngine.ZXING,
                            onSelect = {
                                showEngineMenu = false
                                onSelectEngine(QrScanEngine.ZXING)
                            }
                        )
                        ScanEngineMenuItem(
                            engine = QrScanEngine.ZXING_CPP,
                            selected = engine == QrScanEngine.ZXING_CPP,
                            onSelect = {
                                showEngineMenu = false
                                onSelectEngine(QrScanEngine.ZXING_CPP)
                            }
                        )
                    }
                }
            }
        }

        // 一次性提示（相册未识别到二维码 / 正在核验设备状态 / 设备无效等）
        notice?.let { text ->
            val (container, content) = when (noticeTone) {
                QrNoticeTone.PROGRESS -> PROGRESS_NOTICE_CONTAINER to Color.White
                QrNoticeTone.NEUTRAL -> NEUTRAL_NOTICE_CONTAINER to Color.White
                QrNoticeTone.ERROR ->
                    MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = container,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    // 左右留边：提示再长也只折行，不会撑满屏幕宽度
                    .padding(top = 60.dp, start = 24.dp, end = 24.dp)
            ) {
                Text(
                    text = text,
                    color = content,
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

/**
 * 相机预览 + 二维码解码；[scanning] 为 false 时保留预览但停止分析（画面即冻结在最后一帧）。
 *
 * 流水线要点（对齐 Binary Eye 那套经验）：
 * - 分析流分辨率按引擎给（见 [ANALYSIS_SIZE_ZXING]）；
 * - 解码区域 = 屏幕上的取景框 [scanFrame]，ZXing 只解这一块，等于给取景框做数字变焦；
 * - 手电筒（[torchOn]）、捏合变焦、点击对焦。
 */
@Composable
internal fun QrCameraPreview(
    engine: QrScanEngine,
    scanning: Boolean,
    onQrCode: (String) -> Unit,
    scanFrame: ScanFrameRect,
    viewWidthPx: Int,
    viewHeightPx: Int,
    torchOn: Boolean,
    onFlashAvailable: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            // ROI 映射按「等比铺满 + 居中裁剪」算，这里必须与之一致
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val currentOnQrCode by rememberUpdatedState(onQrCode)
    val currentOnFlashAvailable by rememberUpdatedState(onFlashAvailable)

    var camera by remember { mutableStateOf<Camera?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var zoomTouched by remember { mutableStateOf(false) }
    var showZoomChip by remember { mutableStateOf(false) }
    var zoomChipText by remember { mutableStateOf("") }
    // 预览流裁切宽高比：与分析流不一致时不能按「所见即所得」映射 ROI，退回整帧
    var previewAspect by remember { mutableStateOf(0f) }
    val currentPreviewAspect by rememberUpdatedState(previewAspect)

    // ROI 每帧现算：视图尺寸、上下预留、引擎都可能变
    val roiProvider by rememberUpdatedState(
        { frameWidth: Int, frameHeight: Int, rotationDegrees: Int ->
            val aspect = currentPreviewAspect
            if (aspect > 0f && !aspectClose(aspect, frameAspect(frameWidth, frameHeight))) {
                null
            } else {
                mapViewRectToFrame(
                    rect = scanFrame,
                    viewWidth = viewWidthPx,
                    viewHeight = viewHeightPx,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    rotationDegrees = rotationDegrees,
                    padding = ROI_PADDING
                )
            }
        }
    )

    val analyzer = remember(engine) {
        createQrAnalyzer(
            engine = engine,
            onQrCode = { raw -> currentOnQrCode(raw) },
            roi = { frameWidth, frameHeight, rotationDegrees ->
                roiProvider(frameWidth, frameHeight, rotationDegrees)
            }
        )
    }

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

    Box(modifier = modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // 手势层：触摸交给盖在预览上的一层 Compose 节点，不去跟嵌进去的 PreviewView 抢事件
        //（它重写过 onTouchEvent）。点击对焦、捏合变焦都走这里。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    detectTapGestures { offset ->
                        val cam = camera ?: return@detectTapGestures
                        val point = previewView.meteringPointFactory
                            .createPoint(offset.x, offset.y)
                        cam.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(point).build()
                        )
                    }
                }
                .pointerInput(camera) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val state = camera?.cameraInfo?.zoomState?.value
                            ?: return@detectTransformGestures
                        // 诊断用：确认手势事件到底有没有到达这一层（只打第一条）
                        if (!zoomTouched) Log.w(TAG, "捏合手势到达，zoom=" + zoom)
                        zoomTouched = true
                        // 手势给的 zoom 是「相对上一次事件的倍数」，倍率要按当前倍率累乘。
                        // 注意别拿 linearZoom 累乘：它的初值是 0，0 乘任何数还是 0，捏了也没反应。
                        zoomRatio = (zoomRatio * zoom)
                            .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    }
                }
        )

        // 变焦反馈：捏合时露一下当前倍数，看不出反应时至少能确认手势到底有没有生效
        if (showZoomChip) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = zoomChipText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }

    LaunchedEffect(zoomRatio, camera) {
        val cam = camera ?: return@LaunchedEffect
        cam.cameraControl.setZoomRatio(zoomRatio)
        if (!zoomTouched) return@LaunchedEffect
        zoomChipText = String.format(Locale.US, "%.1f×", zoomRatio)
        showZoomChip = true
        // 这 1 秒会随手势不断续期，松手后自己消失
        delay(1100)
        showZoomChip = false
    }

    LaunchedEffect(torchOn, hasFlash, camera) {
        if (hasFlash) camera?.cameraControl?.enableTorch(torchOn)
    }

    DisposableEffect(cameraProvider, lifecycleOwner, scanning, analyzer, engine) {
        val provider = cameraProvider
        if (provider != null) {
            val preview = Preview.Builder()
                .setResolutionSelector(PREVIEW_RESOLUTION_SELECTOR)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            camera = if (scanning) {
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResolutionSelector(engine))
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
            // 预览流实际尺寸只用来判断与分析流的裁切是否一致（同宽高比时 ROI 映射才成立）
            previewAspect = preview.resolutionInfo?.resolution
                ?.let { frameAspect(it.width, it.height) }
                ?: 0f
            camera?.let { cam ->
                hasFlash = cam.cameraInfo.hasFlashUnit()
                currentOnFlashAvailable(hasFlash)
                cam.cameraControl.enableTorch(torchOn)
                cam.cameraControl.setZoomRatio(zoomRatio)
            }
        }
        onDispose {
            // 离开扫码页要把手电筒关掉，否则会一直亮着
            camera?.cameraControl?.enableTorch(false)
            camera = null
            hasFlash = false
            currentOnFlashAvailable(false)
            cameraProvider?.unbindAll()
        }
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }
}

/** 可切换/可释放的二维码分析器。 */
private interface QrAnalyzer : ImageAnalysis.Analyzer {
    fun close() {}
}

/**
 * 每帧现算的解码 ROI（帧缓冲坐标）。
 *
 * 返回 null 表示这一帧按整帧解码。
 */
private fun interface RoiProvider {
    operator fun invoke(frameWidth: Int, frameHeight: Int, rotationDegrees: Int): FrameRect?
}

private fun createQrAnalyzer(
    engine: QrScanEngine,
    onQrCode: (String) -> Unit,
    roi: RoiProvider
): QrAnalyzer = when (engine) {
    QrScanEngine.ZXING -> ZxingQrAnalyzer(roi, onQrCode)
    QrScanEngine.ZXING_CPP -> ZxingCppQrAnalyzer(roi, onQrCode)
}

/**
 * zxing-cpp 解码（默认引擎，跟 Binary Eye 同一套解码器）。
 *
 * 与 [ZxingQrAnalyzer] 的差别：ROI 直接写进 [ImageProxy.cropRect]，由库按它裁（并自己处理旋转），
 * 我们不用拷 Y 平面；原生实现也快得多。参数对齐 Binary Eye：高分辨率 + 取景框 + 局部均值二值化。
 */
private class ZxingCppQrAnalyzer(
    private val roi: RoiProvider,
    private val onQrCode: (String) -> Unit
) : QrAnalyzer {

    private val reader = BarcodeReader(
        BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.QR_CODE),
            tryHarder = true,
            tryRotate = true,
            tryInvert = true,
            binarizer = BarcodeReader.Binarizer.LOCAL_AVERAGE
        )
    )
    private var frames = 0
    private var cropWarned = false

    private fun warnCropOnce(e: Throwable) {
        if (cropWarned) return
        cropWarned = true
        Log.w(TAG, "zxing-cpp 取景框裁剪失败，本帧整帧解码", e)
    }

    override fun analyze(imageProxy: ImageProxy) {
        try {
            frames++
            val full = FrameRect(0, 0, imageProxy.width, imageProxy.height)
            val region = if (frames % FULL_FRAME_EVERY == 0) {
                full
            } else {
                roi(imageProxy.width, imageProxy.height, imageProxy.imageInfo.rotationDegrees)
                    ?: full
            }
            // 官方封装就是按 ImageProxy.cropRect 裁的，把取景框写进去即可
            //（getCropRect 是 @NonNull、setCropRect 参数是 @Nullable，Kotlin 不认成属性，只能显式调）
            // 设不上（越界之类）就当这帧整帧解，别让整个扫码卡死
            runCatching {
                imageProxy.setCropRect(
                    android.graphics.Rect(region.left, region.top, region.right, region.bottom)
                )
            }.onFailure { warnCropOnce(it) }
            val text = reader.read(imageProxy).firstOrNull()?.text
            if (!text.isNullOrBlank()) onQrCode(text)
        } catch (e: Exception) {
            Log.w(TAG, "zxing-cpp 解码异常", e)
        } finally {
            imageProxy.close()
        }
    }
}

/**
 * ZXing 解码（备用引擎）：Y 平面 → 取景框那一块 → 二值化 → QR 解码。
 * 每帧处理完必须 close，否则前端会停止出帧。
 *
 * 和 ML Kit 那条路的差别都在「喂什么进去」：
 * - 只拷取景框内的像素，代价不随帧大小涨，所以分辨率敢要到 [ANALYSIS_SIZE]；
 * - 不做整帧旋转：二维码四个方向都能解，转正纯属白拷一份内存；
 * - 局部均值 / 全局直方图两种二值化隔帧轮换，兼顾反光与低对比度；
 * - ALSO_INVERTED 覆盖屏幕翻拍、深底白码这类反色场景；
 * - 每 [FULL_FRAME_EVERY] 帧退回整帧，保住「没对准取景框也能扫到」的老行为。
 */
private class ZxingQrAnalyzer(
    private val roi: RoiProvider,
    private val onQrCode: (String) -> Unit
) : QrAnalyzer {

    private val reader = MultiFormatReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        // 备用引擎按「宁可慢也要认出」取舍
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true
    )
    private var frames = 0

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val plane = imageProxy.planes.firstOrNull() ?: return
            val width = imageProxy.width
            val height = imageProxy.height
            frames++
            val full = FrameRect(0, 0, width, height)
            val region = if (frames % FULL_FRAME_EVERY == 0) {
                full
            } else {
                roi(width, height, imageProxy.imageInfo.rotationDegrees) ?: full
            }
            val luma = QrLuminance.copyRect(
                buffer = plane.buffer,
                width = width,
                height = height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                rect = region
            )
            if (luma.width <= 0 || luma.height <= 0) return
            val source = PlanarYUVLuminanceSource(
                luma.data, luma.width, luma.height, 0, 0, luma.width, luma.height, false
            )
            val bitmap = BinaryBitmap(
                if (frames % 2 == 0) HybridBinarizer(source) else GlobalHistogramBinarizer(source)
            )
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
                        QrScanEngine.ZXING -> R.string.qr_scan_engine_zxing
                        QrScanEngine.ZXING_CPP -> R.string.qr_scan_engine_zxing_cpp
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
 * 位置由 [scanFrame] 给（`QrScannerScaffold` 算一次，画框与解码 ROI 共用同一份几何），
 * 这里只负责画。
 */
@Composable
internal fun ScanFrameOverlay(
    frame: ScanFrameRect,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (frame.side <= 0f) return@Canvas
        val corner = CornerRadius(24.dp.toPx(), 24.dp.toPx())

        val mask = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addRoundRect(
                RoundRect(
                    frame.left,
                    frame.top,
                    frame.left + frame.side,
                    frame.top + frame.side,
                    corner
                )
            )
        }
        drawPath(mask, Color.Black.copy(alpha = 0.45f))
        drawRoundRect(
            color = Color.White.copy(alpha = 0.9f),
            topLeft = Offset(frame.left, frame.top),
            size = Size(frame.side, frame.side),
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
