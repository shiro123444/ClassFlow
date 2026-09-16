package com.xingheyuzhuan.shiguangschedule.ui.webapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppDefinition
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuAuthTipsScenario
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 通用网页应用全屏容器。
 *
 * 特性：
 * 1. 彻底移除顶部 TopAppBar 与底栏，只全屏展示 Web 内容，且内部避让系统状态栏与手势条；
 * 2. 右上方带一个半透明、可拖拽的圆形悬浮球供退出页面；
 * 3. 拦截物理/手势返回键：若当前处于页面主页（或已无法回退），直接退出页面容器；
 * 4. 彻底阻断并劫持跳向 CAS Web 统一认证登录页的行为，转为拉起原生的 [WbuCampusAuthSheet]；
 * 5. WebVPN 下注入通行证 Cookie，保障校外穿透。
 */
@Composable
fun WebAppScreen(
    navBridge: NavBridge,
    appId: String,
    viewModel: WebAppViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()

    var showAuthSheet by remember { mutableStateOf(false) }
    var sslErrorState by remember { mutableStateOf<Pair<SslErrorHandler, SslError>?>(null) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(appId) {
        viewModel.start(appId)
    }

    if (uiState.needLogin || showAuthSheet) {
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
            unifiedAuthOnly = true,
            initialUseVpnOverride = uiState.requireVpnForLogin || uiState.temporaryUseVpn,
            title = uiState.definition?.let { stringResource(it.titleRes) },
            tipsScenario = WbuAuthTipsScenario.IDENTITY,
            onNavigateToAccount = { navBridge.navigate(Destination.CredentialManagement) }
        )
    }

    // 拦截返回键：主页退出或历史回退
    BackHandler {
        val webView = webViewInstance
        if (webView != null) {
            val currentUrl = webView.url.orEmpty()
            val atHome = isAtHomeRoute(currentUrl, uiState.definition)
            if (!atHome && webView.canGoBack()) {
                webView.goBack()
            } else {
                navBridge.popBackStack()
            }
        } else {
            navBridge.popBackStack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
    ) {
        when (val stage = uiState.stage) {
            is WebAppStage.ProbingNetwork -> {
                CampusNetworkProbeOverlay(
                    statusText = uiState.probeStatusText,
                    isOffCampus = false,
                    onSelectCampus = { viewModel.chooseContinueDirect() },
                    onSelectVpn = { viewModel.chooseUseVpnTemporarily() }
                )
            }

            is WebAppStage.OffCampusChoice -> {
                CampusNetworkProbeOverlay(
                    statusText = stringResource(R.string.desc_off_campus_detected),
                    isOffCampus = true,
                    onSelectCampus = { viewModel.chooseContinueDirect() },
                    onSelectVpn = { viewModel.chooseUseVpnTemporarily() }
                )
            }

            is WebAppStage.LoadingToken -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.status_fetching_webapp_token),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            is WebAppStage.ContentReady -> {
                FullScreenWebContent(
                    targetUrl = stage.url,
                    useVpn = stage.useVpn,
                    definition = uiState.definition,
                    onSslError = { handler, error -> sslErrorState = Pair(handler, error) },
                    onSessionExpired = { showAuthSheet = true },
                    onWebViewReady = { webViewInstance = it }
                )
            }

            is WebAppStage.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.title_load_failed),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = stage.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { navBridge.popBackStack() }) {
                                Text(stringResource(R.string.action_exit))
                            }
                            Button(onClick = { viewModel.retry() }) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                }
            }
        }

        // 可拖拽的悬浮退出球（仅在页面内容或加载阶段显示）
        if (uiState.stage is WebAppStage.ContentReady || uiState.stage is WebAppStage.LoadingToken) {
            DraggableExitFab(
                onExit = { navBridge.popBackStack() }
            )
        }

        // SSL 证书异常弹窗
        sslErrorState?.let { (handler, _) ->
            AlertDialog(
                onDismissRequest = {
                    handler.cancel()
                    sslErrorState = null
                },
                title = { Text(stringResource(R.string.dialog_ssl_error_title)) },
                text = { Text(stringResource(R.string.dialog_ssl_error_message)) },
                confirmButton = {
                    Button(
                        onClick = {
                            handler.proceed()
                            sslErrorState = null
                        }
                    ) {
                        Text(stringResource(R.string.action_continue_browsing))
                    }
                },
                dismissButton = {
                    Button(
                        onClick = {
                            handler.cancel()
                            sslErrorState = null
                        }
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

/**
 * 判断当前 URL 是否处于应用定义的「主页/首页」路由。
 */
private fun isAtHomeRoute(currentUrl: String, def: WebAppDefinition?): Boolean {
    if (currentUrl.isBlank() || currentUrl == "about:blank") return true
    if (def == null) return false

    val markers = def.homeHashMarkers
    if (markers.isEmpty()) {
        val hash = currentUrl.substringAfter("#", "")
        return hash.isBlank() || hash == "/"
    }

    return markers.any { marker -> currentUrl.contains(marker, ignoreCase = true) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FullScreenWebContent(
    targetUrl: String,
    useVpn: Boolean,
    definition: WebAppDefinition?,
    onSslError: (SslErrorHandler, SslError) -> Unit,
    onSessionExpired: () -> Unit,
    onWebViewReady: (WebView) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var loadingProgress by remember { mutableFloatStateOf(0f) }

    var pendingPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request != null) {
            if (isGranted) {
                request.grant(request.resources)
            } else {
                request.deny()
            }
        }
    }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback != null) {
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            callback.onReceiveValue(uris)
        }
    }

    val defaultUserAgent = remember { WebSettings.getDefaultUserAgent(context) }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.javaScriptEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.textZoom = 100
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = defaultUserAgent
            settings.mediaPlaybackRequiresUserGesture = false

            setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            // 若处于 WebVPN 模式，向 CookieManager 注入 TWFID 以支持代理鉴权
            if (useVpn) {
                val twfid = WbuAuthTransport.getTwfid(context)
                if (twfid.isNotBlank()) {
                    cookieManager.setCookie("https://webvpn.wbu.edu.cn", "TWFID=$twfid; Path=/; Domain=.webvpn.wbu.edu.cn")
                    cookieManager.flush()
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false

                    // 核心要求：坚决不让 WebView 展示统一身份认证的 Web 登录页面
                    // 若重定向跳往 /authserver/login，直接拦截并转为在原生弹登录窗
                    if (url.contains("/authserver/login") || url.contains("/por/login")) {
                        Log.i("WebAppScreen", "Intercepted navigation to CAS login: $url")
                        onSessionExpired()
                        return true
                    }
                    return false
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    onSslError(handler, error)
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    // 当在 WebVPN 模式下，若页面 JS 内部写死了公网真实域名发起接口请求，将其自动重写为代理宿主请求
                    if (useVpn && definition != null) {
                        val reqUri = request?.url ?: return null
                        if (reqUri.host.equals(definition.targetHost, ignoreCase = true)) {
                            val transport = WbuAuthTransport.getShared(context, true)
                            val proxyBase = transport.webVpnProxyBase(definition.targetHost, withSingleSuffix = definition.vpnSingleSuffix)
                            val proxyUri = Uri.parse(proxyBase)
                            val rewritten = reqUri.buildUpon()
                                .scheme(proxyUri.scheme)
                                .encodedAuthority(proxyUri.encodedAuthority)
                                .build()
                            Log.d("WebAppScreen", "Rewrote subresource to VPN proxy: $reqUri -> $rewritten")
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    loadingProgress = newProgress / 100f
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request == null) return
                    val resources = request.resources
                    val needsCamera = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)

                    if (needsCamera) {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPermission) {
                            request.grant(resources)
                        } else {
                            pendingPermissionRequest = request
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    } else {
                        request.grant(resources)
                    }
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                    if (pendingPermissionRequest == request) {
                        pendingPermissionRequest = null
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallbackInternal: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = filePathCallbackInternal

                    return runCatching {
                        val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        fileChooserLauncher.launch(intent)
                        true
                    }.getOrElse {
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = null
                        false
                    }
                }
            }

            loadUrl(targetUrl)
        }
    }

    LaunchedEffect(webView) {
        onWebViewReady(webView)
    }

    DisposableEffect(webView) {
        onDispose {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            pendingPermissionRequest?.deny()
            pendingPermissionRequest = null
            webView.stopLoading()
            coroutineScope.launch(Dispatchers.IO) {
                runCatching {
                    webView.clearCache(true)
                    webView.clearHistory()
                    WebStorage.getInstance().deleteAllData()
                }
            }
            webView.removeAllViews()
            webView.destroy()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { webView }
        )

        if (loadingProgress < 1.0f) {
            LinearProgressIndicator(
                progress = { loadingProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
        }
    }
}

/**
 * 可拖拽悬浮退出球。
 */
@Composable
private fun DraggableExitFab(
    onExit: () -> Unit
) {
    val density = LocalDensity.current
    val fabSize = 44.dp
    val fabSizePx = with(density) { fabSize.toPx() }
    val marginPx = with(density) { 16.dp.toPx() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxXPx = constraints.maxWidth - fabSizePx - marginPx
        val maxYPx = constraints.maxHeight - fabSizePx - marginPx

        // 默认停靠在右侧中下部 (约屏幕 70% 高度处)，避开顶部导航栏、右上方个人中心卡片/头像以及底部手势条
        var offsetX by remember { mutableFloatStateOf(maxXPx) }
        var offsetY by remember { mutableFloatStateOf((maxYPx * 0.7f).coerceIn(marginPx, maxYPx)) }

        Surface(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(fabSize)
                .shadow(elevation = 6.dp, shape = CircleShape)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                )
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(marginPx, maxXPx)
                        offsetY = (offsetY + dragAmount.y).coerceIn(marginPx, maxYPx)
                    }
                },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            IconButton(
                onClick = onExit,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_exit),
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * 校园网检测浮层，支持随时中断并自主决策直接进入或走 WebVPN。
 */
@Composable
private fun CampusNetworkProbeOverlay(
    statusText: String,
    isOffCampus: Boolean = false,
    onSelectCampus: () -> Unit,
    onSelectVpn: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "campusProbe")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = if (isOffCampus) 1.0f else 0.75f,
        targetValue = if (isOffCampus) 1.0f else 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = if (isOffCampus) 0.3f else 0.12f,
        targetValue = if (isOffCampus) 0.3f else 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(140.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                            alpha = pulseAlpha
                        }
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            shape = CircleShape
                        )
                )
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .graphicsLayer {
                            scaleX = pulseScale * 0.85f
                            scaleY = pulseScale * 0.85f
                            alpha = pulseAlpha * 0.8f
                        }
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            shape = CircleShape
                        )
                )
                Surface(
                    shape = CircleShape,
                    color = if (isOffCampus) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(60.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isOffCampus) Icons.Default.VpnKey else Icons.Default.Wifi,
                            contentDescription = null,
                            tint = if (isOffCampus) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = stringResource(if (isOffCampus) R.string.title_off_campus_detected else R.string.title_campus_network_detect),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (!isOffCampus) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .width(180.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(if (isOffCampus) R.string.hint_off_campus_choose_access else R.string.hint_choose_access_method_direct),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // WebVPN Card (校外模式下优先置顶或突出高亮)
            OutlinedCard(
                onClick = onSelectVpn,
                shape = RoundedCornerShape(16.dp),
                colors = if (isOffCampus) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.28f)) else CardDefaults.outlinedCardColors(),
                border = if (isOffCampus) CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.secondary)) else CardDefaults.outlinedCardBorder(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.VpnKey,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.label_webvpn_off_campus),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (isOffCampus) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary
                                ) {
                                    Text(
                                        text = "推荐",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(R.string.desc_webvpn_guide),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 校园网直连 Card
            OutlinedCard(
                onClick = onSelectCampus,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_campus_direct_on_campus),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.desc_campus_direct_guide),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
