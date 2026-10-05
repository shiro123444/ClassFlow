package com.xingheyuzhuan.shiguangschedule.ui.campus.paycode

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppId
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuAuthTipsScenario
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet

/** 二维码渲染像素边长：够大，缩放到屏幕上也锐利。 */
private const val QR_PIXEL_SIZE = 1024

/** 条码渲染像素尺寸（宽 × 高）；展示时会转 90°，所以「宽」对应屏幕上的高度。 */
private const val BARCODE_PIXEL_WIDTH = 1600
private const val BARCODE_PIXEL_HEIGHT = 460

/** 付款码展示时把屏幕亮度拉满的比例（平台 H5 同样会调高亮度，方便扫码枪识别）。 */
private const val PAY_CODE_BRIGHTNESS = 1f

/** 付款码的三种呈现方式：二维码 / 条码 / 数字码（与平台 H5 的「校园卡」页一致）。 */
private enum class PayCodeView {
    QR,
    BARCODE,
    DIGITS
}

/**
 * 一卡通付款码（校园码）原生页面。
 *
 * 入口：设置 →「付款码」、桌面长按图标「付款码」。
 *
 * 与网页版的区别只在「怎么画」：取码接口、翻码节奏、脱机码回落都与平台 H5 一致，
 * 因此这里没有任何 WebView，进入即出码；展示期间屏幕常亮 + 亮度拉满，方便柜机扫码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayCodeScreen(
    navBridge: NavBridge,
    viewModel: PayCodeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAuthSheet by remember { mutableStateOf(false) }

    /** 非空时全屏放大展示：二维码 / 条码 / 数字码（点任意处返回）。 */
    var enlargedView by remember { mutableStateOf<PayCodeView?>(null) }

    LaunchedEffect(Unit) { viewModel.start() }

    LaunchedEffect(uiState.needLogin) {
        if (uiState.needLogin) showAuthSheet = true
    }

    if (showAuthSheet) {
        WbuCampusAuthSheet(
            onDismiss = {
                showAuthSheet = false
                viewModel.onLoginDismissed()
            },
            onLoginSuccess = {
                showAuthSheet = false
                viewModel.onLoginSuccess()
            },
            requireUnifiedCas = true,
            // 付款码只需要统一认证会话（CASTGC → 平台令牌），不涉及教务与校园网
            unifiedAuthOnly = true,
            title = stringResource(R.string.service_campus_card),
            tipsScenario = WbuAuthTipsScenario.IDENTITY
        )
    }

    /** 兜底出路：丢掉原生页面，回到平台自己的「校园码」页。 */
    val openOfficialPage: () -> Unit = {
        navBridge.navigate(
            Destination.WebApp(
                appId = WebAppId.CAMPUS_CARD.name,
                initialTargetUrl = viewModel.officialPageUrl()
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.title_campus_pay_code),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navBridge.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !uiState.isLoading && !uiState.isRefreshing
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_refresh)
                        )
                    }
                    if (uiState.showOfficialPageEntry) {
                        IconButton(onClick = openOfficialPage) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.action_open_official_page)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        val pullRefreshState = rememberPullToRefreshState()

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            state = pullRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                uiState.isLoading -> LoadingBlock()

                uiState.errorMessage != null && !uiState.hasCode -> ErrorBlock(
                    message = uiState.errorMessage.orEmpty(),
                    onRetry = {
                        if (uiState.needLogin) showAuthSheet = true else viewModel.refresh()
                    },
                    onOpenOfficialPage = openOfficialPage
                )

                else -> PayCodeContent(
                    state = uiState,
                    onSelectMethod = viewModel::selectMethod,
                    onOpenOfficialPage = openOfficialPage,
                    onShowView = { enlargedView = it }
                )
            }

            // 放大展示层（二维码 / 条码 / 数字码），点任意处返回；取码在后台照常轮换
            val currentView = enlargedView
            if (currentView != null && uiState.hasCode) {
                PayCodeViewerOverlay(
                    initialView = currentView,
                    code = uiState.code.orEmpty(),
                    onDismiss = { enlargedView = null }
                )
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.loading_pay_code),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorBlock(
    message: String,
    onRetry: () -> Unit,
    onOpenOfficialPage: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(28.dp)
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenOfficialPage) {
                Text(stringResource(R.string.action_open_official_page))
            }
        }
    }
}

@Composable
private fun PayCodeContent(
    state: PayCodeUiState,
    onSelectMethod: (Int) -> Unit,
    onOpenOfficialPage: () -> Unit,
    onShowView: (PayCodeView) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 支付方式切换（校园卡 / 电子账户 / 签约银行卡）：多于一个时横向可滚，避免挤爆一行
        if (state.methods.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                state.methods.forEachIndexed { index, method ->
                    FilterChip(
                        selected = index == state.selectedIndex,
                        onClick = { onSelectMethod(index) },
                        label = { Text(method.name) }
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.selectedMethod?.name.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    state.selectedMethod?.balance?.let { balance ->
                        Text(
                            text = stringResource(R.string.pay_code_balance, formatAmount(balance)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                state.selectedMethod?.bankCardTail?.let { tail ->
                    Text(
                        text = stringResource(R.string.pay_code_bank_card_tail, tail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 付款码本体：白底黑码，带静区；展示期间屏幕常亮 + 亮度拉满
        if (state.hasCode) {
            KeepScreenBright()

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                shadowElevation = 2.dp,
                // 点二维码 = 全屏放大看（平台 H5 点一下也会出大码）
                modifier = Modifier.clickable { onShowView(PayCodeView.QR) }
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val qr = remember(state.code) { renderQrCode(state.code.orEmpty(), QR_PIXEL_SIZE) }
                    if (qr != null) {
                        Image(
                            bitmap = qr,
                            contentDescription = stringResource(R.string.a11y_pay_code_qr),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(240.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LinearProgressIndicator(
                        progress = { countdownProgress(state) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color(0x1F000000)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when {
                            state.isFetchingCode -> stringResource(R.string.pay_code_refreshing)
                            state.secondsLeft > 0 -> stringResource(R.string.pay_code_valid_for, state.secondsLeft)
                            // 整批用完又没续上（网络失败等）：别让用户对着一个失效码发呆
                            else -> stringResource(R.string.pay_code_expired)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF5F6368)
                    )
                }
            }

            Text(
                text = stringResource(R.string.pay_code_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // 条码 / 数字码：扫码枪读不动二维码时的备用呈现（对应平台 H5「校园卡」页的条码与数字）
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onShowView(PayCodeView.BARCODE) }) {
                    Icon(
                        Icons.Default.ViewWeek,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.pay_code_action_barcode))
                }
                OutlinedButton(onClick = { onShowView(PayCodeView.DIGITS) }) {
                    Text(stringResource(R.string.pay_code_action_digits))
                }
            }
        }

        // 业务提示（未开通 / 挂失 / 冻结 / 服务端原话）与「取码失败但旧码还在屏幕上」的失败原因
        (state.notice ?: state.errorMessage)?.let { message ->
            NoticeCard(message = message, onOpenOfficialPage = onOpenOfficialPage)
        }
    }
}

@Composable
private fun NoticeCard(message: String, onOpenOfficialPage: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            OutlinedButton(onClick = onOpenOfficialPage) {
                Text(stringResource(R.string.action_open_official_page))
            }
        }
    }
}

/**
 * 展示付款码期间：屏幕常亮 + 亮度拉满，离开页面恢复原样。
 *
 * 柜机扫码枪对环境光很敏感，这也是平台 App 与 H5 都会做的事。
 */
@Composable
private fun KeepScreenBright() {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()

    DisposableEffect(activity) {
        view.keepScreenOn = true
        val window = activity?.window
        val previous = window?.attributes?.screenBrightness
        window?.let { w ->
            val attrs = w.attributes
            attrs.screenBrightness = PAY_CODE_BRIGHTNESS
            w.attributes = attrs
        }
        onDispose {
            view.keepScreenOn = false
            window?.let { w ->
                val attrs = w.attributes
                attrs.screenBrightness = previous ?: -1f
                w.attributes = attrs
            }
        }
    }
}

/** 倒计时进度：刚出码时 1，到点归 0。 */
private fun countdownProgress(state: PayCodeUiState): Float {
    if (state.slotSeconds <= 0) return 0f
    return (state.secondsLeft.toFloat() / state.slotSeconds.toFloat()).coerceIn(0f, 1f)
}

/** 金额格式化：两位小数（与平台 H5 的 `toFixed(2)` 一致）。 */
private fun formatAmount(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)

/** Context 链上找 Activity（Compose 里拿 Window 用）。 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 付款码内容 → 位图（ZXing，纯本地渲染，不发请求）。
 *
 * 用 `MARGIN = 1`：静区由外面的白色卡片给，避免二维码被缩得太小。
 */
private fun renderQrCode(content: String, size: Int): ImageBitmap? {
    if (content.isBlank()) return null
    return runCatching {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        android.graphics.Bitmap
            .createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }.getOrNull()
}

/**
 * 付款码内容 → **条码**位图（ZXing `CODE_128`）。
 *
 * 平台 H5 的条码用 `vue-barcode`（底层 JsBarcode）渲染，其默认格式正是 CODE128；
 * 这里复用已有的 `com.google.zxing:core`，**不需要再引新的条码库**。
 *
 * @param rotateQuarterTurns 顺时针旋转 90° 的倍数。传 1 时条码横躺在屏幕上（等效 H5 的 `rotate(90deg)`），
 *                           既占满手机的长边，也方便柜机扫码枪从左到右一枪扫完。
 */
private fun renderBarcodeCode(
    content: String,
    width: Int,
    height: Int,
    rotateQuarterTurns: Int = 0
): ImageBitmap? {
    if (content.isBlank()) return null
    return runCatching {
        // 两侧留白（静区）：1D 码两侧没有白边扫码枪会读不到
        val hints = mapOf(EncodeHintType.MARGIN to 24)
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.CODE_128, width, height, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val rotated = if (rotateQuarterTurns % 4 == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                w,
                h,
                Matrix().apply { postRotate(90f * rotateQuarterTurns) },
                true
            )
        }
        rotated.asImageBitmap()
    }.getOrNull()
}

/** 数字码：每 4 位一组（与平台 H5 的 `replace(/(.{4})/g, "$1 ")` 一致，方便照着念 / 手输）。 */
private fun formatCodeDigits(code: String): String = code.trim().chunked(4).joinToString(" ")

/**
 * 付款码放大展示层：二维码 / 条码 / 数字码，点任意处返回。
 *
 * 对应平台 H5「校园卡」页的三件能力：
 * 1. 二维码可放大（这里点主界面的码就能全屏看）；
 * 2. 条码视图（横过来给扫码枪读）+ 分组数字一起显示；
 * 3. 纯数字码视图，扫码枪读不到时可以手输。
 *
 * 展示期间取码仍在后台按节奏轮换，层里的内容跟着变，不需要退出重进。
 */
@Composable
private fun PayCodeViewerOverlay(
    initialView: PayCodeView,
    code: String,
    onDismiss: () -> Unit
) {
    var view by remember(initialView) { mutableStateOf(initialView) }

    BackHandler { onDismiss() }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() },
        color = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                when (view) {
                    PayCodeView.QR -> {
                        val qr = remember(code) { renderQrCode(code, QR_PIXEL_SIZE) }
                        if (qr != null) {
                            Image(
                                bitmap = qr,
                                contentDescription = stringResource(R.string.a11y_pay_code_qr),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    PayCodeView.BARCODE -> {
                        val barcode = remember(code) {
                            renderBarcodeCode(
                                content = code,
                                width = BARCODE_PIXEL_WIDTH,
                                height = BARCODE_PIXEL_HEIGHT,
                                rotateQuarterTurns = 1
                            )
                        }
                        if (barcode != null) {
                            Image(
                                bitmap = barcode,
                                contentDescription = stringResource(R.string.a11y_pay_code_barcode),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    PayCodeView.DIGITS -> {
                        Text(
                            text = formatCodeDigits(code),
                            color = Color(0xFF111111),
                            fontSize = 34.sp,
                            lineHeight = 48.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // 条码视图下再写一遍分组数字：H5 的条码页同样是「条码 + 数字」一起给
            if (view == PayCodeView.BARCODE) {
                Text(
                    text = formatCodeDigits(code),
                    color = Color(0xFF333333),
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = view == PayCodeView.QR,
                    onClick = { view = PayCodeView.QR },
                    label = { Text(stringResource(R.string.pay_code_action_qr)) }
                )
                FilterChip(
                    selected = view == PayCodeView.BARCODE,
                    onClick = { view = PayCodeView.BARCODE },
                    label = { Text(stringResource(R.string.pay_code_action_barcode)) }
                )
                FilterChip(
                    selected = view == PayCodeView.DIGITS,
                    onClick = { view = PayCodeView.DIGITS },
                    label = { Text(stringResource(R.string.pay_code_action_digits)) }
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.pay_code_overlay_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF5F6368),
                textAlign = TextAlign.Center
            )
        }
    }
}
