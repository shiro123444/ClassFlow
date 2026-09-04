package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 校园网环境探测。
 *
 * 通过访问校园网认证页（仅校内可达、响应快且压力小）判断是否处于校园网，
 * 避免探测 jwxt（可能卡顿）或依赖 SSID（需权限）/网段（不可靠）。
 *
 * 校园网是瞬时、会变的状态，因此**不做长缓存**：每次 [refresh] 都实时探测，
 * 并把最新结果发布到 [campusState]，让 UI（登录框提示）与同步逻辑保持一致。
 *
 * 探测语义：能收到任意 HTTP 响应（登录页 200 / 跳转 3xx 等）即视为校园网；
 * 超时 / 连接被拒 / DNS 失败视为非校园网。
 */
object WbuNetworkProbe {

    // 校园网认证页，校内专用、外部不可达；作为「是否处于校园网」的轻量探测目标
    private const val CAMPUS_PORTAL_URL = "http://172.16.90.162/"

    private const val CONNECT_TIMEOUT_MS = 4000L
    private const val READ_TIMEOUT_MS = 4000L
    private const val CALL_TIMEOUT_MS = 6000L

    // 最近一次探测结果。null 表示尚未探测到。
    private val _campusState = MutableStateFlow<Boolean?>(null)
    val campusState: StateFlow<Boolean?> = _campusState.asStateFlow()

    // 独立短超时 client，不复用 WbuSyncEngine 的 15s client
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * 实时探测当前是否处于校园网，并更新 [campusState]。
     * 开始时先置 null（UI 显示「检测中」），避免复用上一次结果；
     * 返回探测到的结果（boolean）。
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        _campusState.value = null // 进入检测中
        val reachable = runCatching {
            probeClient.newCall(Request.Builder().url(CAMPUS_PORTAL_URL).get().build())
                .execute()
                .use { true } // 能拿到任意响应即在校内
        }.getOrDefault(false)
        _campusState.value = reachable
        reachable
    }
}
