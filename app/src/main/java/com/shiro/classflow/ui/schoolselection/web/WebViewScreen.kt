package com.shiro.classflow.ui.schoolselection.web

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.shiro.classflow.BuildConfig
import com.shiro.classflow.R
import com.shiro.classflow.data.network.wbu.WbuSyncEngine
import com.shiro.classflow.data.repository.AppSettingsRepository
import com.shiro.classflow.data.repository.CourseConversionRepository
import com.shiro.classflow.data.repository.TimeSlotRepository
import com.shiro.classflow.ui.components.CourseTablePickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

private const val WBU_HOST = "jwxt.wbu.edu.cn"
private const val WBU_VPN_HOST = "jwxt-wbu-edu-cn-s.webvpn.wbu.edu.cn"
private const val WBU_IDS_VPN_HOST = "ids-wbu-edu-cn.webvpn.wbu.edu.cn"
private const val WBU_FALLBACK_ASSET_JS_PATH = "WBU/wbu_chaoxing.js"
private val WBU_TIMETABLE_PATH_HINTS = listOf(
    "/xsd/pkgl/xskb",
    "/getcurrentpkzc",
    "/getxsdsykb",
    "querykbforxsd",
    "sdpkkblist"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(
    navController: NavController,
    initialUrl: String?,
    assetJsPath: String?,
    courseConversionRepository: CourseConversionRepository,
    timeSlotRepository: TimeSlotRepository,
    appSettingsRepository: AppSettingsRepository,
    courseScheduleRoute: String,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    val startedEmpty: Boolean = remember { initialUrl.isNullOrBlank() || initialUrl == "about:blank" }

    // --- 预取字符串资源 ---
    val titleEnterUrl = stringResource(R.string.title_enter_url)
    val titleLoading = stringResource(R.string.title_loading)
    val toastImportFinished = stringResource(R.string.toast_import_script_finished)
    val toastSwitchedToDesktop = stringResource(R.string.toast_switched_to_desktop)
    val toastSwitchedToPhone = stringResource(R.string.toast_switched_to_phone)
    val toastUrlEmpty = stringResource(R.string.toast_url_empty_enter_first)
    val toastDevToolsEnabledFmt = stringResource(R.string.toast_devtools_enabled_format)
    val statusEnabled = stringResource(R.string.status_enabled)
    val statusDisabled = stringResource(R.string.status_disabled)
    val toastNoManualImport = stringResource(R.string.toast_no_script_manual_import)
    val toastExecutingImport = stringResource(R.string.toast_executing_import_script)
    val toastNoImportScript = stringResource(R.string.toast_no_import_script)
    val toastImportNotFoundFmt = stringResource(R.string.toast_import_script_not_found)
    val toastLoadImportFailedFmt = stringResource(R.string.toast_load_import_script_failed)

    var currentUrl by remember { mutableStateOf(initialUrl ?: "about:blank") }
    var inputUrl by remember { mutableStateOf(initialUrl ?: "https://") }
    var loadingProgress by remember { mutableFloatStateOf(0f) }
    var pageTitle by remember {
        mutableStateOf(
            if (startedEmpty) titleEnterUrl else titleLoading
        )
    }
    var expanded by remember { mutableStateOf(false) }
    var isDesktopMode by remember { mutableStateOf(false) }

    var isEditingUrl by remember {
        mutableStateOf(startedEmpty)
    }

    var isDevToolsEnabled by remember { mutableStateOf(false) }
    var showCourseTablePicker by remember { mutableStateOf(false) }
    var autoImportTriggered by remember { mutableStateOf(false) }
    var vpnCookiesSaved by remember { mutableStateOf(false) }

    var sslErrorHandleState by remember {
        mutableStateOf<Pair<SslErrorHandler, SslError>?>(null)
    }

    // --- DevTools 逻辑 ---
    val enableDevToolsOptionInUi = BuildConfig.ENABLE_DEV_TOOLS_OPTION_IN_UI
    val enableAddressBarToggleButton = BuildConfig.ENABLE_ADDRESS_BAR_TOGGLE_BUTTON

    // --- 浏览器配置和 Agent ---
    val defaultUserAgent = remember { WebSettings.getDefaultUserAgent(context) }

    // --- Channel 和 Bridge 实例化 ---
    val uiEventChannel = remember { Channel<WebUiEvent>(Channel.BUFFERED) }
    var androidBridge: AndroidBridge? by remember { mutableStateOf(null) }


    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // 检查硬件加速状态
            Log.d("WebViewScreen", "=== WebView 初始化 ===")
            Log.d("WebViewScreen", "硬件加速支持: ${isHardwareAccelerated}")

            // WebView 配置
            @SuppressLint("SetJavaScriptEnabled")
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
            settings.userAgentString = defaultUserAgent

            // 性能优化设置
            settings.cacheMode = WebSettings.LOAD_DEFAULT

            // 启用硬件加速
            setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
            Log.d("WebViewScreen", "Layer Type 设置为: LAYER_TYPE_HARDWARE")
            Log.d("WebViewScreen", "当前 Layer Type: $layerType")

            CookieManager.getInstance().setAcceptCookie(true)


            // 实例化 AndroidBridge
            androidBridge = AndroidBridge(
                context = context,
                coroutineScope = coroutineScope,
                webView = this,
                uiEventChannel = uiEventChannel,
                courseConversionRepository = courseConversionRepository,
                timeSlotRepository = timeSlotRepository,
                onTaskCompleted = {
                    // 使用预取的字符串
                    Toast.makeText(context, toastImportFinished, Toast.LENGTH_LONG).show()
                    navController.popBackStack(
                        route = courseScheduleRoute,
                        inclusive = false
                    )
                }
            )
            addJavascriptInterface(androidBridge!!, "AndroidBridge")

            // WebViewClient: 页面导航和加载事件
            webViewClient = object : WebViewClient() {
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return false
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    sslErrorHandleState = Pair(handler, error)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d("WebViewScreen", "页面开始加载: $url")
                    Log.d("WebViewScreen", "硬件加速状态: ${view?.isHardwareAccelerated}")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d("WebViewScreen", "页面加载完成: $url")
                    view?.injectAllJavaScript(isDesktopMode)
                    view?.let { maybeAutofillWbuCredentials(it, url) }

                    // 检测到达 JWXT 后台后，自动提取 VPN cookies 供 OkHttp 复用
                    if (!vpnCookiesSaved && url != null) {
                        val host = Uri.parse(url).host?.lowercase()
                        if ((host == WBU_HOST || host == WBU_VPN_HOST) &&
                            (url.contains("/admin/index") || url.contains("/admin/?loginType") || url.contains("/admin?loginType"))) {
                            val engine = WbuSyncEngine(context = context, useVpn = host == WBU_VPN_HOST)
                            engine.importCookiesFromWebView(CookieManager.getInstance())
                            vpnCookiesSaved = true
                            view?.post {
                                Toast.makeText(context, "VPN 会话已保存，后续同步无需重新登录", Toast.LENGTH_LONG).show()
                            }
                            Log.d("WebViewScreen", "VPN cookies extracted and persisted for: $url")
                        }
                    }
                }

                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) {
                        val description = error.description.toString()
                        val ctx = view.context
                        Log.e("WebViewScreen", "页面加载错误: $description")
                        view.post {
                            Toast.makeText(ctx, ctx.getString(R.string.toast_web_load_error_format, description), Toast.LENGTH_LONG).show()
                        }
                    }
                }

            }

            // WebChromeClient: 进度条和标题
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    loadingProgress = newProgress / 100f
                    if (newProgress % 20 == 0) { // 每20%记录一次
                        Log.d("WebViewScreen", "加载进度: $newProgress%")
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (title != null) {
                        pageTitle = title
                        Log.d("WebViewScreen", "页面标题: $title")
                    }
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    if (consoleMessage != null) {
                        Log.d(
                            "WebViewConsole",
                            "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} @${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                        )
                    }
                    return super.onConsoleMessage(consoleMessage)
                }
            }

            loadUrl(initialUrl ?: "about:blank")
        }
    }

    val effectiveAssetJsPath = resolveAssetJsPath(
        configuredAssetJsPath = assetJsPath,
        currentUrl = webView.url ?: currentUrl
    )

    LaunchedEffect(appSettingsRepository) {
        val appSettings = appSettingsRepository.getAppSettingsOnce()
        appSettings?.currentCourseTableId
            ?.takeIf { it.isNotBlank() }
            ?.let { androidBridge?.setImportTableId(it) }
    }

    LaunchedEffect(currentUrl, effectiveAssetJsPath, autoImportTriggered) {
        if (autoImportTriggered) {
            return@LaunchedEffect
        }

        if (effectiveAssetJsPath != WBU_FALLBACK_ASSET_JS_PATH) {
            return@LaunchedEffect
        }

        val runtimeUrl = webView.url ?: currentUrl
        if (!shouldAutoImportWbu(runtimeUrl)) {
            return@LaunchedEffect
        }

        val appSettings = appSettingsRepository.getAppSettingsOnce()
        val targetTableId = appSettings?.currentCourseTableId
        if (targetTableId.isNullOrBlank()) {
            return@LaunchedEffect
        }

        try {
            androidBridge?.setImportTableId(targetTableId)
            val jsFile = File(context.filesDir, "repo/schools/resources/$effectiveAssetJsPath")
            if (!jsFile.exists()) {
                return@LaunchedEffect
            }

            val jsCode = jsFile.readText()
            webView.evaluateJavascript(jsCode, null)
            autoImportTriggered = true
            Toast.makeText(context, "WBU auto import triggered", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("WebViewScreen", "Auto import failed: ${e.message}", e)
        }
    }

    // 状态改变时加载 URL
    LaunchedEffect(currentUrl) {
        if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
            val urlToLoad = if (currentUrl.startsWith("http://") || currentUrl.startsWith("https://")) {
                currentUrl
            } else {
                "https://$currentUrl"
            }
            webView.loadUrl(urlToLoad)
        } else if (currentUrl == "about:blank") {
            webView.loadUrl("about:blank")
        }
    }

    // 资源清理
    DisposableEffect(webView) {
        onDispose {
            Log.d("WebViewScreen", "开始清理 WebView 资源")
            webView.stopLoading()

            // 在后台线程执行耗时的清理操作
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    webView.clearCache(true)
                    webView.clearFormData()
                    webView.clearHistory()
                    // VPN cookies 已桥接到 OkHttp 持久化存储，可安全清理 WebView cookies
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()
                    WebStorage.getInstance().deleteAllData()
                    Log.d("WebViewScreen", "WebView 资源清理完成")
                } catch (e: Exception) {
                    Log.e("WebViewScreen", "清理资源时出错: ${e.message}")
                }
            }

            webView.removeAllViews()
            webView.destroy()
        }
    }

    val onSearch: (String) -> Unit = { query ->
        keyboardController?.hide()
        currentUrl = query
        isEditingUrl = false
        pageTitle = titleLoading // 使用预取字符串
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (isEditingUrl) {
                            isEditingUrl = false
                            inputUrl = webView.url ?: currentUrl
                            keyboardController?.hide()
                        } else {
                            navController.popBackStack()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(if (isEditingUrl) R.string.a11y_cancel_editing else R.string.a11y_back)
                        )
                    }
                },

                title = {
                    if (isEditingUrl) {
                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = { newQuery: String -> inputUrl = newQuery },
                            placeholder = { Text(stringResource(R.string.placeholder_enter_url_full)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    onSearch(inputUrl)
                                    isEditingUrl = false
                                }
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                errorBorderColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        Text(pageTitle, style = MaterialTheme.typography.titleLarge)
                    }
                },

                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isEditingUrl) {
                            IconButton(
                                onClick = {
                                    onSearch(inputUrl)
                                    isEditingUrl = false
                                },
                                enabled = inputUrl.isNotBlank() && inputUrl != "https://"
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.a11y_load))
                            }
                        } else if (enableAddressBarToggleButton || startedEmpty) {
                            IconButton(onClick = {
                                isEditingUrl = true
                                inputUrl = webView.url?.takeIf { it.isNotBlank() && it != "about:blank" } ?: "https://"
                                keyboardController?.show()
                            }) {
                                Icon(Icons.Default.Link, contentDescription = stringResource(R.string.a11y_enter_url))
                            }
                        }

                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.a11y_more_options))
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            // 刷新
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_refresh)) },
                                onClick = { webView.reload(); expanded = false },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.a11y_refresh)) }
                            )

                            // 电脑/手机模式切换
                            val switchTextId = if (isDesktopMode) R.string.action_switch_to_phone_mode else R.string.action_switch_to_desktop_mode
                            val switchIcon = if (isDesktopMode) Icons.Filled.PhoneAndroid else Icons.Filled.DesktopWindows

                            DropdownMenuItem(
                                text = { Text(stringResource(switchTextId)) },
                                onClick = {
                                    isDesktopMode = !isDesktopMode
                                    webView.settings.userAgentString = if (isDesktopMode) DESKTOP_USER_AGENT else defaultUserAgent
                                    webView.settings.loadWithOverviewMode = !isDesktopMode

                                    val tText = if (isDesktopMode) toastSwitchedToDesktop else toastSwitchedToPhone
                                    Toast.makeText(context, tText, Toast.LENGTH_SHORT).show()

                                    if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                                        webView.loadUrl(currentUrl)
                                    } else {
                                        Toast.makeText(context, toastUrlEmpty, Toast.LENGTH_LONG).show()
                                    }
                                    expanded = false
                                },
                                leadingIcon = {
                                    Icon(
                                        switchIcon,
                                        contentDescription = stringResource(switchTextId)
                                    )
                                }
                            )

                            if (enableDevToolsOptionInUi) {
                                DropdownMenuItem(
                                    onClick = {
                                        isDevToolsEnabled = !isDevToolsEnabled
                                        WebView.setWebContentsDebuggingEnabled(isDevToolsEnabled)

                                        val statusText = if (isDevToolsEnabled) statusEnabled else statusDisabled
                                        Toast.makeText(
                                            context,
                                            toastDevToolsEnabledFmt.format(statusText),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    leadingIcon = { Icon(Icons.Filled.Build, contentDescription = stringResource(R.string.a11y_devtools)) },
                                    text = { Text(stringResource(R.string.item_devtools_debug)) },
                                    trailingIcon = { Switch(checked = isDevToolsEnabled, onCheckedChange = null) }
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.text_import_guide),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.width(12.dp))

                        Button(
                            onClick = {
                                // 移除限制，始终允许点击
                                if (effectiveAssetJsPath != null) {
                                    showCourseTablePicker = true
                                } else {
                                    // 即使没有匹配的脚本，也显示选择器让用户尝试
                                    Log.w("WebViewScreen", "effectiveAssetJsPath 为 null，但允许用户尝试")
                                    showCourseTablePicker = true
                                }
                            },
                            enabled = true  // 始终启用
                        ) {
                            Text(stringResource(R.string.action_execute_import))
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            // 渲染 WebView
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { webView },
                update = {}
            )

            // 加载进度条
            if (loadingProgress < 1.0f) {
                LinearProgressIndicator(
                    progress = { loadingProgress },
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }

            WebDialogHost(
                webView = webView,
                uiEvents = uiEventChannel.receiveAsFlow()
            )

            if (showCourseTablePicker) {
                CourseTablePickerDialog(
                    title = stringResource(R.string.dialog_title_select_table_for_import),
                    onDismissRequest = { showCourseTablePicker = false },
                    onTableSelected = { selectedTable ->
                        showCourseTablePicker = false
                        Log.d("WebViewScreen", "用户选择了课表: ${selectedTable.id}")
                        Log.d("WebViewScreen", "当前 effectiveAssetJsPath: $effectiveAssetJsPath")
                        Log.d("WebViewScreen", "当前 URL: ${webView.url}")

                        // 即使没有 effectiveAssetJsPath，也尝试使用默认路径
                        val assetPath = effectiveAssetJsPath ?: WBU_FALLBACK_ASSET_JS_PATH
                        Log.d("WebViewScreen", "使用脚本路径: $assetPath")

                        try {
                            androidBridge?.setImportTableId(selectedTable.id)

                            val jsFile = File(context.filesDir, "repo/schools/resources/$assetPath")
                            Log.d("WebViewScreen", "脚本文件路径: ${jsFile.absolutePath}")
                            Log.d("WebViewScreen", "脚本文件存在: ${jsFile.exists()}")

                            if (jsFile.exists()) {
                                val jsCode = jsFile.readText()
                                Log.d("WebViewScreen", "脚本文件大小: ${jsCode.length} 字符")
                                webView.evaluateJavascript(jsCode, null)
                                Toast.makeText(context, toastExecutingImport, Toast.LENGTH_SHORT).show()
                            } else {
                                val msg = toastImportNotFoundFmt.format(jsFile.path)
                                Log.e("WebViewScreen", "脚本文件不存在: ${jsFile.path}")
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            val errMsg = toastLoadImportFailedFmt.format(e.localizedMessage ?: "")
                            Log.e("WebViewScreen", "加载脚本失败: ${e.message}", e)
                            Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }

            sslErrorHandleState?.let { (handler, _) ->
                AlertDialog(
                    onDismissRequest = {
                        handler.cancel()
                        sslErrorHandleState = null
                    },
                    title = { Text(stringResource(R.string.dialog_ssl_error_title)) },
                    text = { Text(stringResource(R.string.dialog_ssl_error_message)) },
                    confirmButton = {
                        Button(
                            onClick = {
                                handler.proceed()
                                sslErrorHandleState = null
                            }
                        ) {
                            Text(stringResource(R.string.action_continue_browsing))
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = {
                                handler.cancel()
                                sslErrorHandleState = null
                            }
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }
        }
    }
}

private fun resolveAssetJsPath(configuredAssetJsPath: String?, currentUrl: String?): String? {
    configuredAssetJsPath?.takeIf { it.isNotBlank() }?.let { return it }

    val uri = currentUrl
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        ?: return null

    val host = uri.host?.lowercase() ?: return null
    if (host != WBU_HOST && host != WBU_VPN_HOST) {
        return null
    }

    // 只要是 WBU 域名就提供脚本路径，让手动导入按钮始终可用
    // 脚本内部会通过 API 调用获取课表数据，不依赖当前页面内容
    return WBU_FALLBACK_ASSET_JS_PATH
}

private fun shouldAutoImportWbu(url: String?): Boolean {
    val uri = url
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        ?: return false

    val host = uri.host?.lowercase()
    if (host != WBU_HOST && host != WBU_VPN_HOST) {
        return false
    }

    if (!isWbuTimetableUrl(uri)) {
        return false
    }

    val loginType = uri.getQueryParameter("loginType")
    if (!loginType.isNullOrBlank()) {
        return false
    }

    return true
}

private fun isWbuTimetableUrl(uri: Uri): Boolean {
    val path = uri.path?.lowercase().orEmpty()
    val fullUrl = uri.toString().lowercase()

    // 添加日志输出
    Log.d("WebViewScreen", "Checking URL: $fullUrl")
    Log.d("WebViewScreen", "Path: $path")

    // 检查路径是否包含任何提示词
    val matched = WBU_TIMETABLE_PATH_HINTS.any { hint ->
        val contains = path.contains(hint.lowercase()) || fullUrl.contains(hint.lowercase())
        Log.d("WebViewScreen", "Checking hint '$hint': $contains")
        contains
    }

    Log.d("WebViewScreen", "URL matched: $matched")
    return matched
}

private fun maybeAutofillWbuCredentials(webView: WebView, url: String?) {
        val currentUrl = url ?: return
        val uri = runCatching { Uri.parse(currentUrl) }.getOrNull() ?: return
        val host = uri.host?.lowercase() ?: return

        val shouldTryAutofill = host == "webvpn.wbu.edu.cn" ||
                host == WBU_IDS_VPN_HOST ||
                host == WBU_HOST ||
                host == WBU_VPN_HOST

        if (!shouldTryAutofill) return

        val credentials = WbuWebLoginAutofillStore.getActiveOrNull() ?: return
        val studentId = jsQuote(credentials.studentId)
        val password = jsQuote(credentials.password)

        // Fill username/password inputs only; keep captcha/manual verification for user interaction.
        val js = """
                (function() {
                    var userVal = $studentId;
                    var passVal = $password;
                    function dispatch(el) {
                        try { el.dispatchEvent(new Event('input', { bubbles: true })); } catch (e) {}
                        try { el.dispatchEvent(new Event('change', { bubbles: true })); } catch (e) {}
                    }
                    function setBySelectors(selectors, value) {
                        var el = null;
                        for (var i = 0; i < selectors.length; i++) {
                            el = document.querySelector(selectors[i]);
                            if (el) break;
                        }
                        if (!el) return false;
                        el.focus();
                        el.value = value;
                        dispatch(el);
                        return true;
                    }

                    var userFilled = setBySelectors([
                        "#username",
                        "input[name='username']",
                        "input[name='login_name']",
                        "input[name='svpn_name']"
                    ], userVal);

                    var passFilled = setBySelectors([
                        "#password",
                        "input[name='password']",
                        "input[name='svpn_password']",
                        "input[type='password']"
                    ], passVal);

                    return userFilled || passFilled;
                })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
}

private fun jsQuote(raw: String): String {
        val escaped = raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        return "\"$escaped\""
}

