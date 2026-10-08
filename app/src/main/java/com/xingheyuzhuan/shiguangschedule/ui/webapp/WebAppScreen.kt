package com.xingheyuzhuan.shiguangschedule.ui.webapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
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
import android.widget.Toast
import java.io.ByteArrayInputStream
import org.json.JSONObject
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppId
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WasherNoticeDialog
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuAuthTipsScenario
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet
import com.xingheyuzhuan.shiguangschedule.ui.campus.qrscan.QrScannerOverlay
import com.xingheyuzhuan.shiguangschedule.ui.components.UjingBrandLoading
import com.xingheyuzhuan.shiguangschedule.ui.components.WbuLoadingPlaceholder
import com.xingheyuzhuan.shiguangschedule.ui.components.WebJsDialogHost
import com.xingheyuzhuan.shiguangschedule.ui.components.WebJsDialogRequest
import com.xingheyuzhuan.shiguangschedule.ui.components.WebJsDialogState
import com.xingheyuzhuan.shiguangschedule.ui.components.webDialogPageLabel
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WasherAvailability
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WasherEntryResolver
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web.DESKTOP_USER_AGENT
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 原生扫码请求：区分「平台扫码页回填」、「wx 桩桥接回调」、「新中新/水控桥接回调」。 */
private sealed interface ScanRequest {
    /** 来自平台 `/plat/scan?redirectUrl=...`：扫完把结果以 `scanResult` 拼回该地址。 */
    data class Redirect(val redirectUrl: String) : ScanRequest

    /** 来自注入的 `wx` 桩：扫完通过 `window.__cfScanResolve(callbackId, result)` 回调页面。 */
    data class Bridge(val callbackId: String) : ScanRequest

    /** 来自 `em.scanQRCode`（TjtcApp 协议）：扫完通过 `window.__cfEmScanResolve(callbackId, result)` 回调页面。 */
    data class EmBridge(val callbackId: String) : ScanRequest

    /** 来自 `JsAgent.startScan`（SynATP 协议）：扫完通过 `window[callbackName]({ code: 200, data: { qrCodeUTF: result } })` 回调。 */
    data class JsAgent(val callbackName: String) : ScanRequest

    /** 来自 `AndroidFunc.SynJSNative` / `invokeNativeMethod`：扫完通过 `window[callbackName](result)` 回调页面。 */
    data class AndroidFunc(val callbackName: String) : ScanRequest
}

/**
 * 注入到 U净 等页面的 `wx` 桩脚本（幂等，document 级）。
 *
 * 背景（见研究笔记第 15、16 节）：U净 判定 `wechatWorkH5` 走的是 URL 特征；
 * - water-h5（饮水机）：自建 WebView 里 `window.wx` 未定义，`wx.scanQRCode` 会同步抛错；
 * - washer-h5（洗衣机/烘干机/洗鞋机）：`index.html` **必定加载 jweixin**，其加载时执行
 *   `window.wx = window.jWeixin = _`，会覆盖普通赋值的桩；且非微信环境下 jweixin 的
 *   `WeixinJSBridge` 不存在，`success`/`fail` 都不回调 → 扫码按钮"点了没反应"，
 *   也到不了平台兜底页 `/plat/scan`。
 *
 * 因此这里用 `Object.defineProperty` 的 getter 桩**抗覆盖**（setter 直接吞掉 jweixin 的赋值），
 * 并同时保护 `wx` 与 `jWeixin` 两个全局名，把扫码/支付桥接到原生能力。
 */
private const val WX_STUB_JS = """
(function() {
  if (window.__cfWxStubInstalled) return;
  window.__cfWxStubInstalled = true;
  window.__cfScanCallbacks = window.__cfScanCallbacks || {};
  var shim = {};
  shim.config = function () {};
  shim.ready = function (cb) { try { setTimeout(cb, 0); } catch (e) {} };
  shim.error = function () {};
  shim.checkJsApi = function (opts) {
    opts = opts || {};
    if (opts.success) { try { opts.success({ checkResult: {}, errMsg: 'checkJsApi:ok' }); } catch (e) {} }
    if (opts.complete) { try { opts.complete({ errMsg: 'checkJsApi:ok' }); } catch (e) {} }
  };
  shim.scanQRCode = function (opts) {
    opts = opts || {};
    if (window.__cfAutoScan) {
      var autoRes = String(window.__cfAutoScan);
      window.__cfAutoScan = null;
      setTimeout(function () {
        if (opts.success) { try { opts.success({ resultStr: autoRes, errMsg: 'scanQRCode:ok' }); } catch (e) {} }
        if (opts.complete) { try { opts.complete({ errMsg: 'scanQRCode:ok' }); } catch (e2) {} }
      }, 0);
      return;
    }
    var cbId = 'cfscan_' + Date.now() + '_' + Math.random().toString(36).slice(2);
    window.__cfScanCallbacks[cbId] = opts;
    try {
      window.CFWebAppBridge.scanQRCode(cbId);
    } catch (e) {
      delete window.__cfScanCallbacks[cbId];
      if (opts.fail) { try { opts.fail({ errMsg: 'scanQRCode:fail' }); } catch (e2) {} }
    }
  };
  window.__cfScanResolve = function (cbId, result) {
    var opts = (window.__cfScanCallbacks || {})[cbId];
    if (!opts) return;
    delete window.__cfScanCallbacks[cbId];
    if (result === null || result === undefined || result === '') {
      if (opts.fail) { try { opts.fail({ errMsg: 'scanQRCode:cancel' }); } catch (e) {} }
    } else {
      if (opts.success) { try { opts.success({ resultStr: String(result), errMsg: 'scanQRCode:ok' }); } catch (e) {} }
    }
    if (opts.complete) { try { opts.complete({ errMsg: 'scanQRCode:ok' }); } catch (e) {} }
  };
  function cfBuildWeixinPayUrl(o) {
    var prepay = o.prepayid || '';
    var pkg = o.package || '';
    if (!prepay && typeof pkg === 'string' && pkg.indexOf('prepay_id=') === 0) {
      prepay = pkg.substring('prepay_id='.length);
    }
    var nonce = o.nonceStr || o.noncestr || '';
    var sign = o.paySign || o.sign || '';
    var ts = o.timeStamp || o.timestamp || '';
    if (!prepay && !pkg) return '';
    var q = [];
    if (prepay) q.push('prepayid=' + encodeURIComponent(prepay));
    if (pkg) q.push('package=' + encodeURIComponent(pkg));
    if (nonce) q.push('noncestr=' + encodeURIComponent(nonce));
    if (sign) q.push('sign=' + encodeURIComponent(sign));
    if (ts) q.push('timestamp=' + encodeURIComponent(ts));
    return 'weixin://wap/pay?' + q.join('&');
  }
  shim.chooseWXPay = function (opts) {
    opts = opts || {};
    var url = opts.url || opts.mwebUrl || opts.cashierUrl || cfBuildWeixinPayUrl(opts);
    if (!url) {
      if (opts.fail) { try { opts.fail({ errMsg: 'chooseWXPay:fail' }); } catch (e) {} }
      if (opts.complete) { try { opts.complete({ errMsg: 'chooseWXPay:fail' }); } catch (e) {} }
      return;
    }
    var launched = false;
    try { launched = window.CFWebAppBridge.openExternal(String(url)); } catch (e) { launched = false; }
    setTimeout(function () {
      if (launched) {
        if (opts.success) { try { opts.success({ errMsg: 'chooseWXPay:ok' }); } catch (e) {} }
        if (opts.complete) { try { opts.complete({ errMsg: 'chooseWXPay:ok' }); } catch (e) {} }
      } else {
        if (opts.fail) { try { opts.fail({ errMsg: 'chooseWXPay:fail' }); } catch (e) {} }
        if (opts.complete) { try { opts.complete({ errMsg: 'chooseWXPay:fail' }); } catch (e) {} }
      }
    }, 300);
  };
  function cfDefineWx(name) {
    try {
      Object.defineProperty(window, name, {
        configurable: true,
        get: function () { return shim; },
        set: function () {}
      });
    } catch (e) {
      try { window[name] = shim; } catch (e2) {}
    }
  }
  cfDefineWx('wx');
  cfDefineWx('jWeixin');

  // -------------------------------------------------------------
  // 新中新生活服务 / 智能控水 多协议原生扫码桥接入（研究笔记第 18 节）
  // -------------------------------------------------------------

  // 1. window.em 桩（TjtcApp 协议，被 applications/lifeService 与 yktxyyy:5001 共同支持）
  if (!window.__cfEmStubInstalled) {
    window.__cfEmStubInstalled = true;
    window.__cfEmScanCallbacks = window.__cfEmScanCallbacks || {};
    var emShim = window.em || {};
    emShim.scanQRCode = function(opts) {
      opts = opts || {};
      var cbId = 'cfemscan_' + Date.now() + '_' + Math.random().toString(36).slice(2);
      window.__cfEmScanCallbacks[cbId] = opts;
      try {
        if (window.CFWebAppBridge && window.CFWebAppBridge.scanQRCodeEm) {
          window.CFWebAppBridge.scanQRCodeEm(cbId);
        } else if (window.CFWebAppBridge && window.CFWebAppBridge.scanQRCode) {
          window.CFWebAppBridge.scanQRCode(cbId);
        }
      } catch (e) {
        delete window.__cfEmScanCallbacks[cbId];
        if (opts.fail) { try { opts.fail(e); } catch (e2) {} }
      }
    };
    window.__cfEmScanResolve = function(cbId, result) {
      var opts = (window.__cfEmScanCallbacks || {})[cbId];
      if (!opts) return;
      delete window.__cfEmScanCallbacks[cbId];
      if (result === null || result === undefined || result === '') {
        if (opts.fail) { try { opts.fail({ errMsg: 'scanQRCode:cancel' }); } catch (e) {} }
      } else {
        if (opts.success) {
          try {
            // TjtcApp 既传 resultStr 也传整个对象，同时满足 lifeService 的 res 与 yktxyyy 的 res.resultStr
            opts.success({ resultStr: String(result), text: String(result) });
          } catch (e) {}
        }
      }
      if (opts.complete) { try { opts.complete(); } catch (e) {} }
    };
    try {
      Object.defineProperty(window, 'em', {
        configurable: true,
        get: function() { return emShim; },
        set: function(v) { if (v && typeof v === 'object') { Object.assign(emShim, v); } }
      });
    } catch (e) {
      window.em = emShim;
    }
  }

  // 2. window.JsAgent 桩（SynATP 协议，applications/lifeService 在 SynATP 模式下调用）
  if (!window.JsAgent) {
    window.JsAgent = {
      startScan: function(callbackName) {
        var cb = callbackName || 'scanCallback';
        try {
          if (window.CFWebAppBridge && window.CFWebAppBridge.scanQRCodeJsAgent) {
            window.CFWebAppBridge.scanQRCodeJsAgent(cb);
          }
        } catch (e) {
          console.error('JsAgent.startScan error:', e);
        }
      }
    };
  }
})();
"""

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
    initialTargetUrl: String? = null,
    pendingAutoScan: String? = null,
    viewModel: WebAppViewModel = viewModel()
) {
    /** 仅洗衣机（/wm/ 或小天鹅链接）进入时显示 U净 品牌首屏。 */
    val isWasherEntry = pendingAutoScan?.contains("littleswan.com", ignoreCase = true) == true
    /** 只带设备码、没有直达地址的洗衣机深链：需要先解析出洗衣机 H5 真实入口。 */
    val isWasherDeepLink = isWasherEntry && initialTargetUrl.isNullOrBlank()
    Box(modifier = Modifier.fillMaxSize()) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        /** 回填页面结果用：WebView 的方法只能在它自己的线程（主线程）上调用，见 [postToWebViewThread]。 */
        val mainHandler = remember { Handler(Looper.getMainLooper()) }
        val uiState by viewModel.uiState.collectAsState()

        var sslErrorState by remember { mutableStateOf<Pair<SslErrorHandler, SslError>?>(null) }
        var webViewInstance by remember { mutableStateOf<WebView?>(null) }
        var scanRequest by remember { mutableStateOf<ScanRequest?>(null) }

        /** 最终用于加载的入口地址（洗衣机深链解析完成后才有值；其余场景等于入参）。 */
        var resolvedInitialUrl by rememberSaveable(pendingAutoScan) { mutableStateOf(initialTargetUrl) }
        var washerResolving by rememberSaveable(pendingAutoScan) { mutableStateOf(isWasherDeepLink) }

        /** 洗衣机不可下单提示（离线 / 占用 / 预约 / 故障 / 停用 / 码无效），null 表示无提示。 */
        var washerNotice by remember { mutableStateOf<WasherAvailability.Unavailable?>(null) }

        /**
         * VM 是否已按应用定义开始流程。
         *
         * 未开始时 [WebAppUiState.stage] 只是默认占位值（ProbingNetwork）：
         * 洗衣机深链解析中、设备不可下单（此时根本不会启动 VM）都属于这种情况，
         * 若照常渲染就会露出「校园网环境检测」这一与洗衣机无关的页面。
         */
        var vmStarted by rememberSaveable(pendingAutoScan) { mutableStateOf(false) }

        /**
         * 深链的一次性自启（地址里的 `scanResult` 载体 / [pendingAutoScan] 注入）是否已经执行过。
         *
         * 这类自启都是「页面一挂载就直接开一单」的动作（2-3 栋淋浴 `prices` 为空时直接开用水、
         * 洗衣机直接下单、1 栋洗浴直接 `useWater`），可它躺在导航参数里：进程被系统回收后重建、
         * 或转屏重建，地址与原样都会重放一遍 —— 现场表现就是「切后台过一会儿回来又重新开始订单」。
         *
         * 标记放保存状态：同一个导航条目重建时会以 true 回来，于是只把页面本身打开
         * （页面自己会读设备状态：还在用水就显示「使用中」），不再自动开单；
         * 重新扫码进入的是新的导航条目，标记是全新的 false，自启照旧。
         */
        var deepLinkAutoStarted by rememberSaveable(pendingAutoScan) { mutableStateOf(false) }

        LaunchedEffect(pendingAutoScan) {
            if (isWasherDeepLink) {
                // /wm/{uuid}：先解析洗衣机 H5 真实入口，避免只停在一卡通首页
                when (val result = WasherEntryResolver.resolve(context, pendingAutoScan)) {
                    is WasherEntryResolver.Result.Ready -> resolvedInitialUrl = result.url
                    is WasherEntryResolver.Result.Unavailable -> washerNotice = result.availability
                    WasherEntryResolver.Result.Failed -> resolvedInitialUrl = null
                }
                washerResolving = false
            }
        }

        LaunchedEffect(appId, resolvedInitialUrl, washerResolving, washerNotice) {
            if (!washerResolving && washerNotice == null) {
                viewModel.start(appId, resolvedInitialUrl)
                vmStarted = true
            }
        }

        washerNotice?.let { notice ->
            WasherNoticeDialog(
                notice = notice,
                onDismiss = {
                    washerNotice = null
                    navBridge.popBackStack()
                }
            )
        }

        val dismissScan = remember(webViewInstance) {
            {
                val request = scanRequest
                val webView = webViewInstance
                if (request != null && webView != null) {
                    when (request) {
                        is ScanRequest.Bridge -> {
                            // 取消：回调 null 触发 U净 的 fail 分支
                            postToWebViewThread(mainHandler) {
                                webView.evaluateJavascript(
                                    "window.__cfScanResolve(${JSONObject.quote(request.callbackId)}, null);",
                                    null
                                )
                            }
                        }
                        is ScanRequest.EmBridge -> {
                            postToWebViewThread(mainHandler) {
                                webView.evaluateJavascript(
                                    "window.__cfEmScanResolve(${JSONObject.quote(request.callbackId)}, null);",
                                    null
                                )
                            }
                        }
                        is ScanRequest.JsAgent -> {
                            val callback = request.callbackName.ifBlank { "scanCallback" }
                            postToWebViewThread(mainHandler) {
                                webView.evaluateJavascript(
                                    """
                                    (function() {
                                        var cb = window[${JSONObject.quote(callback)}];
                                        if (typeof cb === 'function') {
                                            try { cb({ code: 500, msg: '用户取消' }); } catch (e) {}
                                        }
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                            }
                        }
                        is ScanRequest.AndroidFunc -> {
                            val callback = request.callbackName.ifBlank { "scanCallback" }
                            postToWebViewThread(mainHandler) {
                                webView.evaluateJavascript(
                                    """
                                    (function() {
                                        var cb = window[${JSONObject.quote(callback)}];
                                        if (typeof cb === 'function') {
                                            try { cb(null); } catch (e) {}
                                        }
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                            }
                        }
                        is ScanRequest.Redirect -> { /* 页面跳转类取消无需主动注入 */ }
                    }
                }
                scanRequest = null
            }
        }

        // 只在真需要用户登录统一认证时弹面板：进容器时缺凭据、以及网页撞到 CAS 登录页而凭据自愈也没成功
        if (uiState.needLogin) {
            WbuCampusAuthSheet(
                onDismiss = { viewModel.onLoginDismissed() },
                onLoginSuccess = { viewModel.onLoginSuccess() },
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
            // 优先关闭正在进行的扫码浮层并通知页面取消，防止按返回键时退回上一页
            if (scanRequest != null) {
                dismissScan()
                return@BackHandler
            }

            val webView = webViewInstance
            if (webView != null) {
                val currentUrl = webView.url.orEmpty()
                val def = uiState.definition
                val isDeepLinked = !initialTargetUrl.isNullOrBlank() || !pendingAutoScan.isNullOrBlank()

                if (isDeepLinked) {
                    // 全局扫码直达场景：用户扫码直接进入洗衣/洗烘程序选择页（programV2 / reserveV2）。
                    // 在程序选择页、扫码异常页（scanError）或落地首页（home）按返回键直接退出容器回 ClassFlow，
                    // 彻底解决在第三方 OAuth 历史链与自动跳转逻辑间卡住「退不出去」的问题。
                    if (isThirdPartyEntryRoute(currentUrl, def)) {
                        Log.i("WebAppScreen", "Back on deep-linked third-party entry route ($currentUrl), exiting to ClassFlow")
                        navBridge.popBackStack()
                        return@BackHandler
                    }
                } else {
                    // 普通平台门户浏览场景：按之前要求，仅在 #/home 时跳回平台主页
                    val home = def?.homeUrl
                    if (!home.isNullOrBlank() && isThirdPartyHomeRoute(currentUrl, def)) {
                        Log.i("WebAppScreen", "Back on third-party home route, returning to platform home: $home")
                        webView.loadUrl(home)
                        return@BackHandler
                    }
                }

                if (!isAtHomeRoute(currentUrl, def) && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    navBridge.popBackStack()
                }
            } else {
                navBridge.popBackStack()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .safeDrawingPadding()
            ) {
            val stage = uiState.stage
            when {
                // 洗衣机（U净）：深链解析中 → 取 token 全程显示同一个 U净 品牌首屏，
                // logo 不重播淡入；不可下单时不显示加载态，交由提示弹窗说明
                isWasherEntry && (!vmStarted || stage is WebAppStage.LoadingToken) -> {
                    if (washerNotice == null) {
                        UjingBrandLoading(
                            message = stringResource(R.string.ujing_washer_checking),
                            showSpinner = true
                        )
                    }
                }

                // 非洗衣机网页应用（一卡通 / 图书馆）才走校园网探测与通道选择
                stage is WebAppStage.ProbingNetwork -> {
                    CampusNetworkProbeOverlay(
                        statusText = uiState.probeStatusText,
                        isOffCampus = false,
                        onSelectCampus = { viewModel.chooseContinueDirect() },
                        onSelectVpn = { viewModel.chooseUseVpnTemporarily() }
                    )
                }

                stage is WebAppStage.OffCampusChoice -> {
                    CampusNetworkProbeOverlay(
                        statusText = stringResource(R.string.desc_off_campus_detected),
                        isOffCampus = true,
                        onSelectCampus = { viewModel.chooseContinueDirect() },
                        onSelectVpn = { viewModel.chooseUseVpnTemporarily() }
                    )
                }

                stage is WebAppStage.LoadingToken -> {
                    // 非洗衣机网页应用（预留）：淡蓝底 + WBU 编钟纹样
                    WbuLoadingPlaceholder(
                        message = stringResource(R.string.status_fetching_webapp_token)
                    )
                }

                stage is WebAppStage.ContentReady -> {
                    FullScreenWebContent(
                        // 深链自启已经执行过的条目（重建场景）：把地址里的一次性载体摘掉再加载，
                        // 否则页面重新挂载就会再开一单（见 [deepLinkAutoStarted]）
                        targetUrl = if (deepLinkAutoStarted) stripDeepLinkAutoTrigger(stage.url) else stage.url,
                        useVpn = stage.useVpn,
                        definition = uiState.definition,
                        platformToken = stage.token,
                        // 同理：重建时不再把自启载荷交给页面
                        pendingAutoScan = if (deepLinkAutoStarted) null else pendingAutoScan,
                        onSslError = { handler, error -> sslErrorState = Pair(handler, error) },
                        onWebAuthRedirect = { pageUrl -> viewModel.onWebAuthRedirect(pageUrl) },
                        onInterceptScan = { redirectUrl -> scanRequest = ScanRequest.Redirect(redirectUrl) },
                        onBridgeScan = { callbackId -> scanRequest = ScanRequest.Bridge(callbackId) },
                        onEmScan = { callbackId -> scanRequest = ScanRequest.EmBridge(callbackId) },
                        onJsAgentScan = { callbackName -> scanRequest = ScanRequest.JsAgent(callbackName) },
                        onAndroidFuncScan = { callbackName -> scanRequest = ScanRequest.AndroidFunc(callbackName) },
                        onDeepLinkAutoStartHandedOff = { deepLinkAutoStarted = true },
                        onWebViewReady = { webViewInstance = it }
                    )
                }

                stage is WebAppStage.Error -> {
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

            // 原生扫码浮层（复用「扫一扫」的相机取景；针对 U净 wx 桩桥接 / 平台扫码页回填）
            // 放在 safeDrawingPadding 之外：取景与顶部渐变遮罩可覆盖状态栏，横竖屏一致
            scanRequest?.let { request ->
                QrScannerOverlay(
                    onDismiss = dismissScan,
                    onScanned = { rawResult ->
                        val webView = webViewInstance
                        scanRequest = null
                        val hairdryer = com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.parse(rawResult) as? com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.Result.Hairdryer
                        if (hairdryer != null) {
                            val scheme = com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.buildHairdryerAlipayScheme(hairdryer.cd)
                            val ulinkUrl = com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.buildHairdryerAlipayUrl(hairdryer.cd)
                            val nfcScheme = com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.buildHairdryerNfcScheme(hairdryer.cd)
                            val explicitIntent = Intent(Intent.ACTION_VIEW, Uri.parse(scheme)).apply {
                                setPackage(com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.ALIPAY_PACKAGE_NAME)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            val launched = runCatching {
                                context.startActivity(explicitIntent)
                                true
                            }.getOrElse {
                                val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse(scheme)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatching {
                                    context.startActivity(genericIntent)
                                    true
                                }.getOrElse {
                                    val nfcIntent = Intent(android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED, Uri.parse(nfcScheme)).apply {
                                        setPackage(com.xingheyuzhuan.shiguangschedule.data.network.wbu.UjingQrLink.ALIPAY_PACKAGE_NAME)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    runCatching {
                                        context.startActivity(nfcIntent)
                                        true
                                    }.getOrElse {
                                        val ulinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ulinkUrl)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        runCatching {
                                            context.startActivity(ulinkIntent)
                                            true
                                        }.getOrDefault(false)
                                    }
                                }
                            }
                            if (!launched) {
                                Toast.makeText(context, context.getString(R.string.ujing_alipay_not_installed), Toast.LENGTH_SHORT).show()
                            }
                            return@QrScannerOverlay
                        }
                        if (webView != null && rawResult.isNotBlank()) {
                            // 相机解码回调可能不在主线程，而 WebView 的 loadUrl / evaluateJavascript
                            // 都必须在主线程调用（见 [postToWebViewThread]），这里统一兜一层。
                            when (request) {
                                is ScanRequest.Redirect -> {
                                    val separator = if (request.redirectUrl.contains("?")) "&" else "?"
                                    val encoded = java.net.URLEncoder.encode(rawResult, "UTF-8")
                                    val finalCallbackUrl = "${request.redirectUrl}${separator}scanResult=$encoded"
                                    Log.i("WebAppScreen", "Loading scan callback URL: $finalCallbackUrl")
                                    postToWebViewThread(mainHandler) { webView.loadUrl(finalCallbackUrl) }
                                }

                                is ScanRequest.Bridge -> {
                                    val js = "window.__cfScanResolve(${JSONObject.quote(request.callbackId)}, ${JSONObject.quote(rawResult)});"
                                    Log.i("WebAppScreen", "Resolving wx stub scan result")
                                    postToWebViewThread(mainHandler) { webView.evaluateJavascript(js, null) }
                                }

                                is ScanRequest.EmBridge -> {
                                    val js = "window.__cfEmScanResolve(${JSONObject.quote(request.callbackId)}, ${JSONObject.quote(rawResult)});"
                                    Log.i("WebAppScreen", "Resolving em stub scan result")
                                    postToWebViewThread(mainHandler) { webView.evaluateJavascript(js, null) }
                                }

                                is ScanRequest.JsAgent -> {
                                    val callback = request.callbackName.ifBlank { "scanCallback" }
                                    val js = """
                                        (function() {
                                            var cb = window[${JSONObject.quote(callback)}];
                                            if (typeof cb === 'function') {
                                                try {
                                                    cb({ code: 200, data: { qrCodeUTF: ${JSONObject.quote(rawResult)} } });
                                                } catch (e) {}
                                            }
                                        })();
                                    """.trimIndent()
                                    Log.i("WebAppScreen", "Resolving JsAgent scan result: $callback")
                                    postToWebViewThread(mainHandler) { webView.evaluateJavascript(js, null) }
                                }

                                is ScanRequest.AndroidFunc -> {
                                    val callback = request.callbackName.ifBlank { "scanCallback" }
                                    val js = """
                                        (function() {
                                            var cb = window[${JSONObject.quote(callback)}];
                                            if (typeof cb === 'function') {
                                                try { cb(${JSONObject.quote(rawResult)}); } catch (e) {}
                                            } else if (typeof window.scanCallback === 'function') {
                                                try { window.scanCallback(${JSONObject.quote(rawResult)}); } catch (e2) {}
                                            }
                                        })();
                                    """.trimIndent()
                                    Log.i("WebAppScreen", "Resolving AndroidFunc scan result: $callback")
                                    postToWebViewThread(mainHandler) { webView.evaluateJavascript(js, null) }
                                }
                            }
                        }
                    },
                    hint = stringResource(R.string.webapp_scan_hint)
                )
            }
        }
    }
}


/**
 * 在主线程执行一段调用 [WebView] 的代码。
 *
 * [WebView] 的每个公开方法都带线程检查（`WebView.checkThread`）：只要不是创建它的那个线程
 * （本类里恒为主线程）调用，就会直接抛出
 * `Throwable: A WebView method was called on thread ...`。
 * 扫码结果回填可能来自相机分析线程（见 [QrScannerView] 的说明），异常一旦被上层的
 * `try/catch` 吃掉，页面就永远收不到结果，表现为「扫了没反应」——所以这里统一兜一层。
 */
private fun postToWebViewThread(handler: Handler, block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block()
    } else {
        handler.post(block)
    }
}

/**
 * 深链里的一次性「自启」载体：`CampusShowerEntryResolver` 把扫码原文拼在地址最后
 * （`…&scanResult=<URLEncoder 原文>`），lifeService 页面在 `mounted` 里读它就直接开单。
 */
private const val DEEP_LINK_AUTO_START_PARAM = "scanResult="

/**
 * 摘掉地址里的一次性自启载体 [DEEP_LINK_AUTO_START_PARAM]。
 *
 * 页面 `urlCallBackhandle()` 只要在地址里读到 `scanResult`，就会拿它去 `getDevicesType`；
 * 2-3 栋淋浴在没有档位价格时紧接着 `deviceStatus(1)` 开单 —— 也就是说**地址每被重新加载一次
 * 就多开一单**（切后台被系统回收 → 回来自动重建 → 页面重新挂载）。已经自启过的条目重建时
 * 用它把参数摘掉，页面就只打开设备页、显示服务端当前状态。
 *
 * `scanResult` 的值经 `URLEncoder.encode`，内部不含裸 `&`/`#`，所以按 `&` 切段丢弃即可，
 * 其余参数（`_dt` / `_implid` / `feeitemid` / `appId` / `synjones-auth`…）原样保留。
 */
internal fun stripDeepLinkAutoTrigger(url: String): String {
    val hashAt = url.indexOf('#')
    val fragment = if (hashAt >= 0) url.substring(hashAt) else ""
    val head = if (hashAt >= 0) url.substring(0, hashAt) else url
    val queryAt = head.indexOf('?')
    if (queryAt < 0) return url

    val prefix = head.substring(0, queryAt)
    val segments = head.substring(queryAt + 1).split('&')
    val kept = segments.filterNot { it.startsWith(DEEP_LINK_AUTO_START_PARAM) }
    if (kept.size == segments.size) return url

    val rebuilt = if (kept.isEmpty()) prefix else "$prefix?" + kept.joinToString("&")
    return rebuilt + fragment
}

/**
 * 判断当前加载的地址是不是带一次性自启载体的深链（含 `scanResult` 参数）。
 */
private fun hasDeepLinkAutoTrigger(url: String?): Boolean =
    !url.isNullOrBlank() && url.contains(DEEP_LINK_AUTO_START_PARAM)

/**
 * 判断是否是一卡通缴费页（需要切换为桌面/H5 UA）。
 *
 * 一卡通支付网关会按 UA 判定「官方移动端模块」：Android UA 返回 4030
 * 「安卓模块未授权(12)」，iOS UA 返回「IOS模块未授权(11)」；桌面/H5 UA 才会放行。
 * 缴费页不需要 SynATP 原生扫码，因此只在 /charge-app/ 与 /blade-pay/ 上切换。
 */
private fun isCampusCardPaymentUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    if (!uri.host.equals("yktfwpt.wbu.edu.cn", ignoreCase = true)) return false
    val path = uri.path.orEmpty()
    return path.startsWith("/charge-app") || path.startsWith("/blade-pay")
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

    // 同时对整条 URL、Path 与 Hash 进行匹配（涵盖 Hash 路由与 History 路由如 /plat/shouyeUser）
    return markers.any { marker -> currentUrl.contains(marker, ignoreCase = true) }
}

/**
 * 判断当前是否处于第三方 H5（如 U净）的首页路由 `#/home`。
 *
 * 只有这一页（OAuth 回调落地页）按返回键才跳回平台主页；
 * 其余第三方页面仍按 WebView 历史正常回退，避免一刀切。
 */
private fun isThirdPartyHomeRoute(currentUrl: String, def: WebAppDefinition?): Boolean {
    if (def == null || def.homeUrl.isNullOrBlank()) return false
    if (currentUrl.isBlank() || currentUrl == "about:blank") return false
    val host = runCatching { Uri.parse(currentUrl).host }.getOrNull() ?: return false
    if (host.equals(def.targetHost, ignoreCase = true)) return false
    val hash = currentUrl.substringAfter("#", "").substringBefore('?')
    return hash == "/home" || hash.startsWith("/home/")
}

/**
 * 判断当前是否处于第三方 H5（如 U净 洗衣机）扫码直达的起始/入口路由。
 * 包括：程序选择页 (`#/programV2`, `#/reserveV2`)、首页 (`#/home`)、扫码异常页 (`#/scanError`)、或根路由。
 * 用户在这些由全局扫码直达的界面按返回键时直接退出 WebApp 容器并返回 ClassFlow。
 */
private fun isThirdPartyEntryRoute(currentUrl: String, def: WebAppDefinition?): Boolean {
    if (currentUrl.isBlank() || currentUrl == "about:blank") return true
    val host = runCatching { Uri.parse(currentUrl).host }.getOrNull() ?: return true
    if (def != null && host.equals(def.targetHost, ignoreCase = true)) return false

    val hash = currentUrl.substringAfter("#", "").substringBefore('?')
    val entryRoutes = listOf("/programV2", "/reserveV2", "/home", "/scanError", "/createorder")
    return hash.isBlank() || hash == "/" || entryRoutes.any { hash == it || hash.startsWith("$it/") }
}

/** 独立控水（马影河 1 栋「智能控水」）host。 */
private const val YKT_XYYY_HOST = "yktxyyy.wbu.edu.cn"

/** 当前 URL 是否处于智能控水页面。 */
private fun isYktXyyyUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return runCatching { Uri.parse(url).host.equals(YKT_XYYY_HOST, ignoreCase = true) }
        .getOrDefault(false)
}

/**
 * 智能控水（yktxyyy）深链自动执行脚本。
 *
 * 全局扫码分流已由服务端 `CheckKsPos` 正证过机号，进入 H5 后不应再要求用户点一次「扫一扫」：
 * 1. 轮询 Vue 组件树，找到带 `useWater` 且 `account` 就绪的主组件，
 *    按页面规则 `Number(raw.substr(8, 5))` 直接调 `useWater(posno, 'K')`，绕开页面自带的调试 alert；
 * 2. 兜底调用页面挂在 `window.scanCallback` 上的入口（临时屏蔽其调试 alert）；
 * 3. `__cfShowerAutoUsed` 保证同一页面上下文只执行一次。
 */
private fun buildYktShowerAutoUseJs(raw: String): String = """
(function() {
  if (window.__cfShowerAutoUsed) return;
  var raw = ${JSONObject.quote(raw)};
  var posno = Number(String(raw).substr(8, 5));
  if (!isFinite(posno) || posno <= 0) return;
  function find(vm) {
    if (!vm) return null;
    if (typeof vm.useWater === 'function') return vm;
    var kids = vm.${'$'}children || [];
    for (var i = 0; i < kids.length; i++) {
      var r = find(kids[i]);
      if (r) return r;
    }
    return null;
  }
  var tries = 0;
  (function tick() {
    if (window.__cfShowerAutoUsed) return;
    var root = document.querySelector('#app');
    var vm = root && root.__vue__;
    var target = vm ? find(vm) : null;
    if (target) {
      if (typeof target.account === 'string' && target.account.length > 0) {
        window.__cfShowerAutoUsed = true;
        try {
          target.useWater(String(posno), 'K');
        } catch (e) {
          window.__cfShowerAutoUsed = false;
        }
        return;
      }
      if (tries++ < 60) { setTimeout(tick, 300); return; }
    }
    if (typeof window.scanCallback === 'function') {
      window.__cfShowerAutoUsed = true;
      var oldAlert = window.alert;
      window.alert = function() {};
      try {
        window.scanCallback(raw);
      } catch (e) {
        window.__cfShowerAutoUsed = false;
      } finally {
        window.alert = oldAlert;
      }
      return;
    }
    if (tries++ < 60) setTimeout(tick, 300);
  })();
})();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FullScreenWebContent(
    targetUrl: String,
    useVpn: Boolean,
    definition: WebAppDefinition?,
    platformToken: String?,
    pendingAutoScan: String? = null,
    onSslError: (SslErrorHandler, SslError) -> Unit,
    /** 网页被踢回统一认证登录页；[pageUrl] 是当前停留在的那一页（可能为 null），供自愈时回到原地。 */
    onWebAuthRedirect: (pageUrl: String?) -> Unit,
    onInterceptScan: (redirectUrl: String) -> Unit,
    onBridgeScan: (callbackId: String) -> Unit,
    onEmScan: (callbackId: String) -> Unit,
    onJsAgentScan: (callbackName: String) -> Unit,
    onAndroidFuncScan: (callbackName: String) -> Unit,
    /** 深链的一次性自启已经交给页面（见 [WebAppScreen] 的 [deepLinkAutoStarted] 说明）。 */
    onDeepLinkAutoStartHandedOff: () -> Unit = {},
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

    var autoScanConsumed by remember(pendingAutoScan) { mutableStateOf(false) }

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
    // 一卡通平台 / U净 场景启用 wx 桩与原生桥（避免影响其它网页应用）
    val enableScanBridge = definition?.id == WebAppId.CAMPUS_CARD
    val customUserAgent = remember(defaultUserAgent, enableScanBridge) {
        // 当为一卡通平台时，追加 "SynATP E-Mobile" 使得：
        // 1. 新中新扩展应用（applications/lifeService）识别为 SynATP / TjtcApp，解锁原生扫码；
        // 2. 独立控水（yktxyyy.wbu.edu.cn:5001）识别为 TjtcApp，解锁 em.scanQRCode 原生扫码；
        // 3. 绝不追加 Synjones-E-Campus，保证 sessionStorage.agentType 保持为 "h5"。
        //
        // 但缴费页（/charge-app/）必须例外：一卡通的支付网关会按 UA 判定「官方移动端模块」，
        // 非官方 App 使用 Android UA 会被 401 拒绝（4030 安卓模块未授权(12)），iOS UA 则是 11。
        // 因此缴费页单独使用桌面/H5 UA，服务端会按普通 H5 放行。
        if (enableScanBridge && !defaultUserAgent.contains("SynATP")) {
            "$defaultUserAgent SynATP E-Mobile"
        } else {
            defaultUserAgent
        }
    }
    // 初始页面如果本身就是缴费页（如外部深链直达 /charge-app/），创建 WebView 时就要用 H5 UA
    val initialUserAgent = if (enableScanBridge && isCampusCardPaymentUrl(targetUrl)) {
        DESKTOP_USER_AGENT
    } else {
        customUserAgent
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // 网页原生 alert / confirm / prompt 的挂起队列（由 WebJsDialogHost 渲染成应用自己的弹窗）
    val jsDialogState = remember { WebJsDialogState() }

    // 网页弹窗标题：优先用应用名（一卡通 / 图书馆座位预约），没匹配到定义时才退回域名
    val jsDialogPageLabel: String? = definition?.let { context.getString(it.titleRes) }

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
            settings.userAgentString = initialUserAgent
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

            // 原生桥：供注入的 wx 桩、em 桩、JsAgent 以及 AndroidFunc 调用扫码与外跳支付
            if (enableScanBridge) {
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun scanQRCode(callbackId: String) {
                            mainHandler.post { onBridgeScan(callbackId) }
                        }

                        @JavascriptInterface
                        fun scanQRCodeEm(callbackId: String) {
                            mainHandler.post { onEmScan(callbackId) }
                        }

                        @JavascriptInterface
                        fun scanQRCodeJsAgent(callbackName: String) {
                            mainHandler.post { onJsAgentScan(callbackName) }
                        }

                        @JavascriptInterface
                        fun openExternal(url: String): Boolean {
                            val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
                            val allowed = setOf("weixin", "alipays", "alipay", "upwallet", "unionpay")
                            if (scheme == null || scheme !in allowed) {
                                Log.w("WebAppScreen", "openExternal rejected scheme=$scheme url=$url")
                                return false
                            }
                            return runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                                true
                            }.getOrElse {
                                Log.w("WebAppScreen", "openExternal failed: $url", it)
                                false
                            }
                        }
                    },
                    "CFWebAppBridge"
                )

                // 注入 AndroidFunc（支持传统水控或备用 SynJSNative 协议，不诱发 agentType 改变）
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun SynJSNative(jsonStr: String?): String {
                            Log.d("WebAppScreen", "AndroidFunc.SynJSNative invoked: $jsonStr")
                            if (jsonStr.isNullOrBlank()) return ""
                            try {
                                val json = JSONObject(jsonStr)
                                val primaryKey = json.optString("primaryKey")
                                val callback = json.optString("callback").ifBlank { "scanCallback" }
                                if (primaryKey.contains("scan", ignoreCase = true)) {
                                    mainHandler.post {
                                        onAndroidFuncScan(callback)
                                    }
                                    return JSONObject().apply {
                                        put("code", 200)
                                        put("message", "success")
                                    }.toString()
                                }
                            } catch (e: Exception) {
                                Log.w("WebAppScreen", "Failed to parse SynJSNative JSON", e)
                            }
                            return ""
                        }

                        @JavascriptInterface
                        fun invokeNativeMethod(methodName: String?, param: String? = null): String {
                            Log.d("WebAppScreen", "AndroidFunc.invokeNativeMethod invoked: $methodName, param: $param")
                            val callback = if (!methodName.isNullOrBlank() && !methodName.contains("invoke", ignoreCase = true)) {
                                methodName
                            } else "scanCallback"
                            mainHandler.post {
                                onAndroidFuncScan(callback)
                            }
                            return "success"
                        }
                    },
                    "AndroidFunc"
                )
            }

            webViewClient = object : WebViewClient() {
                /**
                 * 根据主框架 URL 在「SynATP 移动 UA」与「桌面/H5 UA」之间切换。
                 *
                 * 一卡通缴费页 (/charge-app/) 必须使用 H5 UA：支付网关会按 UA 判定官方移动端模块，
                 * Android UA 会返回 4030「安卓模块未授权(12)」。其余页面保留移动 UA 以免影响原生扫码。
                 *
                 * 注意：只能在 onPageStarted / doUpdateVisitedHistory 中调用，绝不能在
                 * shouldOverrideUrlLoading 里调用——该回调中修改 userAgentString 后返回 false
                 * 会让当前这次跳转被 WebView 直接取消（表现为点应用图标没反应）。
                 */
                private fun applyCampusCardUserAgent(view: WebView?, url: String?) {
                    if (!enableScanBridge || url.isNullOrBlank()) return
                    val useDesktop = isCampusCardPaymentUrl(url)
                    val desired = if (useDesktop) DESKTOP_USER_AGENT else customUserAgent
                    if (view?.settings?.userAgentString != desired) {
                        Log.i("WebAppScreen", "Switch UA for $url -> ${if (useDesktop) "desktop" else "mobile"}")
                        view?.settings?.userAgentString = desired
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false

                    // 1. 核心要求：坚决不让 WebView 展示统一身份认证的 Web 登录页面。
                    //    跳到 CAS 登录页只有一个含义 —— 这一页手里的会话失效了；但「页面会话失效」不等于
                    //    「必须让用户登录统一认证」：先交回 ViewModel，用本机凭据（一卡通平台令牌 / 保存的
                    //    账号密码）把页面重新救回来，救不回来它才把需要登录的状态交给 UI 弹原生登录窗。
                    if (url.contains("/authserver/login") || url.contains("/por/login")) {
                        Log.i("WebAppScreen", "Intercepted navigation to CAS login: $url (page=${view?.url})")
                        onWebAuthRedirect(view?.url)
                        return true
                    }

                    // 2. 针对 U净 / 平台扫一扫：页面跳转 /plat/scan?redirectUrl=... 时拦截，并拉起原生扫码
                    if (url.contains("/plat/scan")) {
                        val parsed = Uri.parse(url)
                        val redirect = parsed.getQueryParameter("redirectUrl").orEmpty()
                        if (redirect.isNotBlank()) {
                            Log.i("WebAppScreen", "Intercepted /plat/scan with redirectUrl: $redirect")
                            onInterceptScan(redirect)
                            return true
                        }
                    }

                    // 3. U净 → 平台 OAuth：给 authorize 补上平台身份，让平台把 code 回调给 U净 页面
                    if (enableScanBridge &&
                        !platformToken.isNullOrBlank() &&
                        url.contains("/berserker-auth/oauth/authorize") &&
                        !url.contains("synjones-auth=")
                    ) {
                        val separator = if (url.contains("?")) "&" else "?"
                        val encodedToken = java.net.URLEncoder.encode("bearer $platformToken", "UTF-8")
                        val authedUrl = "$url${separator}synjones-auth=$encodedToken"
                        Log.i("WebAppScreen", "Appending synjones-auth to OAuth authorize navigation")
                        this@apply.loadUrl(authedUrl)
                        return true
                    }

                    // 4. 拦截微信支付（weixin://）、支付宝（alipays://）及其他第三方自定义 URI Scheme
                    val uri = Uri.parse(url)
                    val scheme = uri.scheme?.lowercase()
                    if (scheme != null && scheme != "http" && scheme != "https" && scheme != "about" && scheme != "data" && scheme != "javascript") {
                        Log.i("WebAppScreen", "Handling external app scheme: $url")
                        return runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            true
                        }.getOrElse {
                            Log.w("WebAppScreen", "Failed to launch intent for scheme $scheme", it)
                            val appName = when {
                                scheme.startsWith("weixin") -> context.getString(R.string.label_wechat)
                                scheme.startsWith("alipay") -> context.getString(R.string.label_alipay)
                                scheme.startsWith("upwallet") -> context.getString(R.string.label_unionpay)
                                else -> scheme
                            }
                            Toast.makeText(context, context.getString(R.string.format_no_app_handle_request, appName), Toast.LENGTH_SHORT).show()
                            true
                        }
                    }

                    return false
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    onSslError(handler, error)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    applyCampusCardUserAgent(view, url)
                    // document 早期注入 wx 桩（幂等），保证 U净 扫码/支付桥可用
                    if (enableScanBridge) {
                        view?.evaluateJavascript(WX_STUB_JS, null)
                        if (!pendingAutoScan.isNullOrBlank() && !autoScanConsumed) {
                            autoScanConsumed = true
                            val autoJs = """
                                (function() {
                                    window.__cfAutoScan = ${JSONObject.quote(pendingAutoScan)};
                                    try {
                                        sessionStorage.setItem('scanResult', ${JSONObject.quote(pendingAutoScan)});
                                    } catch (e) {}
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(autoJs, null)
                        }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    applyCampusCardUserAgent(view, url)
                    // 本次加载是否把「一次性自启」交给了页面
                    var autoStartHandedOff = false
                    // 兜底再注入一次（部分机型 onPageStarted 时 JS 上下文尚未就绪）
                    if (enableScanBridge) {
                        view?.evaluateJavascript(WX_STUB_JS, null)
                        if (!pendingAutoScan.isNullOrBlank() && !autoScanConsumed) {
                            autoScanConsumed = true
                            autoStartHandedOff = true
                            val autoJs = """
                                (function() {
                                    window.__cfAutoScan = ${JSONObject.quote(pendingAutoScan)};
                                    try {
                                        sessionStorage.setItem('scanResult', ${JSONObject.quote(pendingAutoScan)});
                                    } catch (e) {}
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(autoJs, null)
                        }
                        // 智能控水（1 栋洗浴）：深链已带票据，页面就绪后自动进入设备页
                        if (!pendingAutoScan.isNullOrBlank() && isYktXyyyUrl(url)) {
                            view?.evaluateJavascript(buildYktShowerAutoUseJs(pendingAutoScan), null)
                            autoStartHandedOff = true
                        }
                    }
                    // 通知外层「这个条目的深链自启已经用完」：它存进保存状态，
                    // 于是进程被回收后重建时不再重放（页面重新挂载就会再开一单）
                    if (autoStartHandedOff || hasDeepLinkAutoTrigger(url)) {
                        onDeepLinkAutoStartHandedOff()
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    // SPA 内部 pushState/replaceState 也会回调这里，确保缴费页始终使用 H5 UA
                    applyCampusCardUserAgent(view, url)
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    // U净 washer-h5 的 index.html 必定加载 jweixin（见研究笔记第 16 节）。
                    // 非微信环境里它没有 WeixinJSBridge：config 静默失败、ready/success/fail 都不回调，
                    // 既会覆盖注入的 wx 桩，又让扫码按钮"点了没反应"。这里直接吞掉 jweixin-*.js，
                    // 让注入的 wx 桩始终生效（water-h5 不受影响）。
                    val reqUrl = request?.url?.toString().orEmpty()
                    if (enableScanBridge &&
                        reqUrl.contains("jweixin", ignoreCase = true) &&
                        reqUrl.substringBefore('?').endsWith(".js", ignoreCase = true)
                    ) {
                        Log.i("WebAppScreen", "Blocked jweixin script: $reqUrl")
                        return WebResourceResponse(
                            "application/javascript",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }

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

                // ── 劫持网页原生对话框：返回 true 表示由我们自己弹窗 ──
                // 页面 JS 会阻塞到 JsResult 被落定为止（与系统弹窗行为一致），
                // 因此这里只入队，落定交给 WebJsDialogHost 的按钮 / 页面销毁兜底。

                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    if (message == null || result == null) {
                        return super.onJsAlert(view, url, message, result)
                    }
                    Log.i("WebAppScreen", "接管网页 alert: $message @$url")
                    jsDialogState.enqueue(
                        WebJsDialogRequest.Alert(
                            pageLabel = jsDialogPageLabel ?: webDialogPageLabel(url ?: view?.url),
                            message = message,
                            onConfirm = { result.confirm() },
                        )
                    )
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    if (message == null || result == null) {
                        return super.onJsConfirm(view, url, message, result)
                    }
                    Log.i("WebAppScreen", "接管网页 confirm: $message @$url")
                    jsDialogState.enqueue(
                        WebJsDialogRequest.Confirm(
                            pageLabel = jsDialogPageLabel ?: webDialogPageLabel(url ?: view?.url),
                            message = message,
                            onConfirm = { result.confirm() },
                            onCancel = { result.cancel() },
                        )
                    )
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?
                ): Boolean {
                    if (message == null || result == null) {
                        return super.onJsPrompt(view, url, message, defaultValue, result)
                    }
                    Log.i("WebAppScreen", "接管网页 prompt: $message @$url")
                    jsDialogState.enqueue(
                        WebJsDialogRequest.Prompt(
                            pageLabel = jsDialogPageLabel ?: webDialogPageLabel(url ?: view?.url),
                            message = message,
                            defaultValue = defaultValue.orEmpty(),
                            onConfirmInput = { input -> result.confirm(input) },
                            onCancel = { result.cancel() },
                        )
                    )
                    return true
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

            // 先把还挂着的网页对话框按「取消」落定，否则页面 JS 会永久卡住
            jsDialogState.cancelAll()

            webView.stopLoading()

            // WebView 的方法必须在创建它的线程（主线程）上调用：放在 Dispatchers.IO 里
            // 会直接抛 "A WebView method was called on thread ..."，等于什么都没清掉。
            runCatching {
                webView.clearCache(true)
                webView.clearHistory()
                WebStorage.getInstance().deleteAllData()
            }.onFailure { Log.w("WebAppScreen", "清理 WebView 资源失败", it) }

            webView.removeAllViews()
            webView.destroy()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { webView }
        )

        // 网页原生 alert / confirm / prompt：用应用自己的弹窗替代系统样式
        WebJsDialogHost(jsDialogState)

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
                                        text = stringResource(R.string.label_recommended),
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

