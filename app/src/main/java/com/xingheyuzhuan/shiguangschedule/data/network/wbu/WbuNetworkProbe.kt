package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * 校园网环境探测。
 *
 * 通过访问校园网认证页（仅校内可达、响应快且压力小）判断是否处于校园网，
 * 避免探测 jwxt（可能卡顿）或依赖 SSID（需权限）/网段（不可靠）。
 *
 * 探测语义：能连上/收到任意 HTTP 响应（登录页 200 / 跳转 3xx 等）即视为校园网；
 * 连不上 / 超时 / 连接被拒 / DNS 失败视为非校园网。
 *
 * 两种探测：
 * - [refresh]：**用户正在等着看结果**的那次探测（登录面板里切直连 / 切 WebVPN、WebView 首屏），
 *   超时放宽（4s）、不缓存，宁可慢一点也不误判；
 * - [fastRefresh]：**站在请求路径上**的探测（「自动校园网探测」给成绩 / 选课 / 同步这类
 *   需要校园网的流程选通道），短超时 + 短缓存 —— 用户点开页面后是看着 Loading 等它的。
 *
 * 校外的连接是**黑洞**（SYN 直接丢包，不回 RST 也不报不可达），所以校外失败只能靠超时收场：
 * 快速探测因此刻意只做一次 TCP 连接（比 HTTP GET 少一个来回、超时更短），
 * 并且在「不在 WiFi 上」时直接判定校外 —— 校内认证页是私网地址，蜂窝网络根本到不了，
 * 这一条把校外用流量时最长的那次等待直接变成 0。
 */
object WbuNetworkProbe {

    // 校园网认证页，校内专用、外部不可达；作为「是否处于校园网」的轻量探测目标
    private const val CAMPUS_PORTAL_HOST = "172.16.90.162"
    private const val CAMPUS_PORTAL_PORT = 80
    private const val CAMPUS_PORTAL_URL = "http://172.16.90.162/"

    private const val CONNECT_TIMEOUT_MS = 4000L
    private const val READ_TIMEOUT_MS = 4000L
    private const val CALL_TIMEOUT_MS = 6000L

    // 快速探测：只连一下 TCP（不发 HTTP），超时也压到毫秒级
    private const val FAST_TCP_TIMEOUT_MS = 700

    /**
     * 快速探测结果的短缓存时长。
     *
     * 校园网是瞬时状态，缓存不能长；但只要**网络本身没变**（WiFi 与蜂窝切换、换 AP 都会
     * 通过 [watchNetworkChanges] 立刻作废缓存），这十几秒内的结论就没有理由重算一遍 ——
     * 一次「打开成绩页」可能连做十几次需要选通道的请求，逐个探测既没必要也更慢。
     */
    private const val FAST_CACHE_TTL_MS = 60_000L

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

    // 快速探测的短缓存（值 + 写入时刻，单调时钟）
    @Volatile
    private var fastCacheValue: Boolean? = null

    @Volatile
    private var fastCacheAtMs: Long = 0L

    @Volatile
    private var networkWatcherRegistered = false

    /**
     * 实时探测当前是否处于校园网，并更新 [campusState]。
     * 开始时先置 null（UI 显示「检测中」），避免复用上一次结果；
     * 返回探测到的结果（boolean）。
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        _campusState.value = null // 进入检测中
        val reachable = probeHttp(probeClient)
        _campusState.value = reachable
        reachable
    }

    /**
     * 快速探测（短超时 + 短缓存），供「自动校园网探测」在请求路径上使用。
     *
     * 与 [refresh] 的差别：
     * - 在**只可能是校外**的网络上（纯蜂窝 / 无网）直接判定「不在校园网」：校园认证页是私网地址，
     *   蜂窝链路路由不到它，没必要白等一次超时（带 VPN / 网络共享 / 有线时不算，见
     *   [mightBeOnCampusNetwork]）；
     * - 只做一次 TCP 连接（700ms），不发 HTTP 请求；
     * - 结果缓存 60s，且网络一变立刻作废（[watchNetworkChanges]）；
     * - **不把 [campusState] 置成「检测中」**：它是给后台流程选通道用的，
     *   不该让正在看的登录面板提示闪一下。
     *
     * 结果同样发布到 [campusState]（校园网状态是一致的），让登录面板的提示不至于过期。
     */
    suspend fun fastRefresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        watchNetworkChanges(appContext)

        if (!mightBeOnCampusNetwork(appContext)) return@withContext publishFast(false)

        val now = SystemClock.elapsedRealtime()
        fastCacheValue?.let { cached ->
            if (now - fastCacheAtMs < FAST_CACHE_TTL_MS) return@withContext cached
        }
        publishFast(probeTcp())
    }

    /**
     * 忘掉快速探测的短缓存：用户刚改完相关设置（如「自动校园网探测」）或网络刚变过，
     * 下一次访问理应重新判断，而不是继续用旧结果。
     */
    fun invalidateFastCache() {
        fastCacheValue = null
        fastCacheAtMs = 0L
    }

    /**
     * 「需要校园网」的流程（教务 / 图书馆 / 选课 …）在发起访问前探一次校园网。
     *
     * 开了「自动校园网探测」用 [fastRefresh]（快速、带短缓存），
     * 关掉则沿用原来的 [refresh]（只增不减：关掉即回到加这个开关之前的行为）。
     *
     * 不需要校园网的应用（一卡通 / U净 / 付款码 / `directOnly` 的网页应用）不要调用它。
     */
    suspend fun probeForCampusFlow(context: Context): Boolean =
        if (WbuAuthTransport.isAutoCampusProbeEnabled(context)) fastRefresh(context) else refresh()

    private fun publishFast(value: Boolean): Boolean {
        fastCacheValue = value
        fastCacheAtMs = SystemClock.elapsedRealtime()
        _campusState.value = value
        return value
    }

    /**
     * 网络一变（WiFi ↔ 蜂窝、换 AP / 换 WiFi）就把快速探测的缓存作废。
     * 注册失败（权限被限制等）不算错误：退化成只用 60s 的短缓存。
     */
    private fun watchNetworkChanges(context: Context) {
        if (networkWatcherRegistered) return
        synchronized(this) {
            if (networkWatcherRegistered) return
            networkWatcherRegistered = true
            runCatching {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return
                manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = invalidateFastCache()

                    override fun onLost(network: Network) = invalidateFastCache()

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) = invalidateFastCache()
                })
            }
        }
    }

    /**
     * 当前这条网络**可能是校园网**吗？
     *
     * 只有「纯蜂窝」才敢直接判定「不是校园网」：校内认证页（`172.16.90.162`）是私网地址，
     * 蜂窝链路根本路由不到它。**注意这只是「能不能走校园网直连」，不是「能不能用校园服务」** ——
     * 判定为「不是校园网」只会让流程改走 WebVPN（本来就是给校外用的那条通道），不会变成不能用。
     *
     * 其它传输方式一律**真去探一次**，因为它们都可能碰到校园网：
     * - WiFi：校园网的主入口；
     * - 以太网 / USB / 蓝牙网络共享：把自己的手机接到「已经连上校园网的设备」上（宿舍/实验室很常见）；
     * - VPN：蜂窝 + 学校 SSL VPN（如 EasyConnect）同样能到校内，这时直连也是可用的。
     *
     * 拿不到网络信息时**保守返回 true**：宁可多探一次（多等 0.7s），
     * 也不要把身在校园网的用户误判成校外、白绕一圈 WebVPN。
     */
    private fun mightBeOnCampusNetwork(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
        capabilities != null && (
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
            )
    }.getOrDefault(true)

    /** 只连一下 TCP：能建立连接即视为在校内（少一个 HTTP 来回，超时也更短）。 */
    private fun probeTcp(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(CAMPUS_PORTAL_HOST, CAMPUS_PORTAL_PORT), FAST_TCP_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    private fun probeHttp(client: OkHttpClient): Boolean = runCatching {
        client.newCall(Request.Builder().url(CAMPUS_PORTAL_URL).get().build())
            .execute()
            .use { true } // 能拿到任意响应即在校内
    }.getOrDefault(false)
}
