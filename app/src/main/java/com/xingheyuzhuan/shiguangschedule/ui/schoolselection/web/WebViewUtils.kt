package com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web

import android.webkit.WebView

// 桌面模式的 User Agent
const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** WebView 未加载任何页面时的占位地址。 */
const val BLANK_WEB_PAGE_URL = "about:blank"

/**
 * 判断某个地址是否属于「空白页」：空串（还没加载过）或 [BLANK_WEB_PAGE_URL]。
 *
 * 这类地址不应该出现在返回路径上——否则用户按返回只会看到一张白屏，得再按一次才能退出。
 */
fun isBlankWebPageUrl(url: String?): Boolean =
    url.isNullOrBlank() || url.equals(BLANK_WEB_PAGE_URL, ignoreCase = true)

/**
 * 计算历史中「上一条有效页面」的下标：从 [currentIndex] 往前逐条跳过空白页。
 *
 * 抽成纯函数是为了能直接用单元测试覆盖（见 `WebViewBackNavigationTest`）。
 *
 * @param urls 历史条目地址，下标从小到大排列
 * @param currentIndex 当前条目下标
 * @return 上一条有效页面的下标；返回 null 表示前面已经没有有效页面
 */
fun previousRealHistoryIndex(urls: List<String?>, currentIndex: Int): Int? {
    var index = currentIndex - 1
    while (index >= 0) {
        if (!isBlankWebPageUrl(urls.getOrNull(index))) return index
        index--
    }
    return null
}

/**
 * 在 WebView 内回退到上一条有效页面，自动跳过 `about:blank` 之类的空白历史条目。
 *
 * @return true 表示已触发回退；false 表示前面没有有效页面，调用方应直接退出当前页面
 */
fun WebView.goBackSkippingBlankPages(): Boolean {
    val history = copyBackForwardList()
    val urls = (0 until history.size).map { history.getItemAtIndex(it)?.url }
    val currentIndex = history.currentIndex
    val targetIndex = previousRealHistoryIndex(urls, currentIndex) ?: return false
    val steps = targetIndex - currentIndex
    if (steps == -1) goBack() else goBackOrForward(steps)
    return true
}

/**
 * 注入网页端交互所需的所有 JavaScript 代码。
 * 包括：
 * 1. 桌面模式下的视口和CSS强制调整。
 * 2. AndroidBridgePromise 垫片，用于支持 JS 中的 Promise 调用 Android 原生功能。
 *
 * @param isDesktopMode 当前是否为桌面模式。
 */
fun WebView.injectAllJavaScript(isDesktopMode: Boolean) {
    // 1. 桌面模式注入逻辑
    if (isDesktopMode) {
        val desktopWidth = 1920
        evaluateJavascript("""
            (function() {
                var desktopWidth = ${desktopWidth};
                
                // 1. 强制视口注入
                var existingMeta = document.querySelector('meta[name=viewport]');
                if (existingMeta) {
                    existingMeta.parentNode.removeChild(existingMeta);
                }
                
                var meta = document.createElement('meta');
                meta.setAttribute('name', 'viewport');
                meta.setAttribute('content', 'width=' + desktopWidth + ', initial-scale=0.5, maximum-scale=3.0, user-scalable=yes'); 
                
                var head = document.getElementsByTagName('head')[0];
                if (head) {
                    head.appendChild(meta);
                }

                // 2. 强制 CSS 注入
                var style = document.createElement('style');
                style.innerHTML = 'html, body { ' +
                                  'overflow-x: visible !important; ' + 
                                  'min-width: ' + desktopWidth + 'px !important; ' + 
                                  'width: auto !important; ' + 
                                  'position: static !important; ' + 
                                  'padding: 0 !important; margin: 0 !important;' +
                                  '}';
                var head = document.getElementsByTagName('head')[0];
                if (head) {
                    head.appendChild(style);
                }
            })();
        """, null)
    }

    // 2. 注入 Promise 垫片代码 (始终注入，因为 Bridge 依赖它)
    evaluateJavascript("""
        window._androidPromiseResolvers = {};
        window._androidPromiseRejectors = {};

        window._resolveAndroidPromise = function(promiseId, result) {
            if (window._androidPromiseResolvers[promiseId]) {
                window._androidPromiseResolvers[promiseId](result);
                delete window._androidPromiseResolvers[promiseId];
                delete window._androidPromiseRejectors[promiseId];
            }
        };

        window._rejectAndroidPromise = function(promiseId, error) {
            if (window._androidPromiseRejectors[promiseId]) {
                window._androidPromiseRejectors[promiseId](new Error(error));
                delete window._androidPromiseResolvers[promiseId];
                delete window._androidPromiseRejectors[promiseId];
            }
        };

        window.AndroidBridgePromise = {
            showAlert: function(title, content, confirmText) {
                return new Promise((resolve, reject) => {
                    const promiseId = 'alert_' + Date.now() + Math.random().toString(36).substring(2);
                    window._androidPromiseResolvers[promiseId] = resolve;
                    window._androidPromiseRejectors[promiseId] = reject;
                    AndroidBridge.showAlert(title, content, confirmText, promiseId);
                });
            },
            showPrompt: function(title, tip, defaultText, validatorJsFunction) {
                return new Promise((resolve, reject) => {
                    const promiseId = 'prompt_' + Date.now() + Math.random().toString(36).substring(2);
                    window._androidPromiseResolvers[promiseId] = resolve;
                    window._androidPromiseRejectors[promiseId] = reject;
                    AndroidBridge.showPrompt(title, tip, defaultText, validatorJsFunction, promiseId);
                });
            },
            showSingleSelection: function(title, itemsJsonString, defaultSelectedIndex) {
                return new Promise((resolve, reject) => {
                    const promiseId = 'singleSelect_' + Date.now() + Math.random().toString(36).substring(2);
                    window._androidPromiseResolvers[promiseId] = resolve;
                    window._androidPromiseRejectors[promiseId] = reject;
                    AndroidBridge.showSingleSelection(title, itemsJsonString, defaultSelectedIndex, promiseId);
                });
            },
            saveImportedCourses: function(coursesJsonString) {
                return new Promise((resolve, reject) => {
                    const promiseId = 'saveCourses_' + Date.now() + Math.random().toString(36).substring(2);
                    window._androidPromiseResolvers[promiseId] = resolve;
                    window._androidPromiseRejectors[promiseId] = reject;
                    AndroidBridge.saveImportedCourses(coursesJsonString, promiseId);
                });
            },
            saveCourseConfig: function(configJsonString) {
                return new Promise((resolve, reject) => {
                    const promiseId = 'saveConfig_' + Date.now() + Math.random().toString(36).substring(2);
                    window._androidPromiseResolvers[promiseId] = resolve;
                    window._androidPromiseRejectors[promiseId] = reject;
                    AndroidBridge.saveCourseConfig(configJsonString, promiseId);
                });
            },
            savePresetTimeSlots: function(timeSlotsJsonString) {
                return new Promise((resolve, reject) => {
                    const promiseId = 'saveTimeSlots_' + Date.now() + Math.random().toString(36).substring(2);
                    window._androidPromiseResolvers[promiseId] = resolve;
                    window._androidPromiseRejectors[promiseId] = reject;
                    AndroidBridge.savePresetTimeSlots(timeSlotsJsonString, promiseId);
                });
            },
            saveTableMeta: function(metaJsonString) {
                return new Promise((resolve, reject) => {
                    const promiseId = 'saveMeta_' + Date.now() + Math.random().toString(36).substring(2);
                    window._androidPromiseResolvers[promiseId] = resolve;
                    window._androidPromiseRejectors[promiseId] = reject;
                    AndroidBridge.saveTableMeta(metaJsonString, promiseId);
                });
            }
        };
        window.shiguangBridgePromise = window.AndroidBridgePromise;
        window.shiguangBridge = window.AndroidBridge;
    """, null)
}
