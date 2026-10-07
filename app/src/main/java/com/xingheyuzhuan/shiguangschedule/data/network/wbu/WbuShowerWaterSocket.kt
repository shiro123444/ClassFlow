package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * 生活服务「用水」结算推送（马影河 2-3 栋淋浴）。
 *
 * **不是 MQTT** —— U净 那套是 MQTT over WebSocket（见 [UjingMqttClient]），而 lifeService 页面
 * 用的是**裸 WebSocket 自研封装**：主题写在 URL 路径上，心跳是每 5 秒发一次字面量 `"ping"`，
 * 断线 5 秒重连（最多 5 次）。页面产物 `js/chunk-b88c7b1e.f1981e59.js` 里的用法：
 *
 * ```js
 * new Y({ url: `${frontConfig.websocket}mobile_service_platform/qrcodeElec_result/${userInfo.sno}` })
 *   .onmessage(e => { const t = JSON.parse(e.content); onFinished(t.msg, …, t.tranamt, t.minutes) })
 * ```
 *
 * 基址来自平台前端配置（实测 `GET /berserker-app/frontInfo` 里
 * `"websocket":"ws://yktfwpt.wbu.edu.cn/websocket/"`），因此这里按平台主机推导。
 *
 * 网页**没有任何轮询**（整个产物里没有 `setInterval`）：进页面只查一次 `getDevicesStatus`，
 * 之后「用水结束、本次金额、本次耗时」全靠这条推送。原生页面同款做法 ——
 * 推送拿结算明细，另外用轮询兜底（推送断了也能发现订单已结束）。
 */
class WbuShowerWaterSocket(
    private val onSettlement: (Settlement) -> Unit,
    private val onConnected: (Boolean) -> Unit = {}
) {

    /** 结算推送内容（`content` 里的 JSON）。 */
    data class Settlement(
        /** 结果标题（页面 `resultData.title`，如「成功」）。 */
        val message: String?,
        /** 本次金额（页面 `tranamt`）。 */
        val amount: Double?,
        /** 本次耗时（分钟，页面 `minutes`）。 */
        val minutes: Int?
    )

    companion object {
        private const val TAG = "WbuShowerWaterSocket"

        /** 平台前端配置里的 websocket 基址（`ws://yktfwpt.wbu.edu.cn/websocket/`）。 */
        val WS_BASE = WbuCampusCardClient.BASE_URL.replaceFirst("http", "ws") + "/websocket/"

        /** 用水结算主题前缀（URL 路径即主题）。 */
        private const val TOPIC_SETTLE = "mobile_service_platform/qrcodeElec_result"

        /** 页面心跳：每 5 秒一个字面量 ping（服务端靠它保活）。 */
        private const val HEARTBEAT_MS = 5_000L

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接不设读超时
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private var socket: WebSocket? = null
    private var heartbeat: java.util.Timer? = null

    /** 连接（[sno] 为学号，取自平台令牌载荷）。重复调用会先关掉上一条。 */
    fun connect(sno: String) {
        val id = sno.trim()
        if (id.isEmpty()) return
        close()
        val url = "$WS_BASE$TOPIC_SETTLE/$id"
        Log.i(TAG, "connecting $url")
        socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun close() {
        heartbeat?.cancel()
        heartbeat = null
        socket?.close(1000, null)
        socket = null
        onConnected(false)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "connected")
            onConnected(true)
            heartbeat = java.util.Timer("shower-water-heartbeat").apply {
                scheduleAtFixedRate(object : java.util.TimerTask() {
                    override fun run() {
                        // 页面发的是字面量 "ping"（不是协议层 ping 帧），照抄
                        runCatching { webSocket.send("ping") }
                    }
                }, HEARTBEAT_MS, HEARTBEAT_MS)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val settlement = parse(text) ?: return
            Log.i(TAG, "settlement: msg=${settlement.message} amount=${settlement.amount} min=${settlement.minutes}")
            onSettlement(settlement)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failed: ${t.message}")
            onConnected(false)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onConnected(false)
        }
    }

    /**
     * 解析推送帧。
     *
     * 页面的封装有两条路：帧里 `code/data/msg` 齐全且 `code==200` 时把 `data` 交给业务回调，
     * 否则原样回调整帧。两种形态这里都认：`{content:"{…}"}` 或 `{code:200,data:{content:"{…}"}}`。
     */
    internal fun parse(text: String): Settlement? {
        val frame = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val payload = (frame["data"] as? JsonObject) ?: frame
        val contentRaw = (payload["content"] as? JsonPrimitive)?.contentOrNull ?: return null
        val content = runCatching { Json.parseToJsonElement(contentRaw) }.getOrNull() as? JsonObject
            // content 不是 JSON 对象：整段就是标题（页面 `resultData.title` 的口径）
            ?: return Settlement(message = contentRaw.ifBlank { null }, amount = null, minutes = null)
        fun textOf(key: String) = (content[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return Settlement(
            message = textOf("msg"),
            amount = textOf("tranamt")?.toDoubleOrNull(),
            minutes = textOf("minutes")?.toDoubleOrNull()?.toInt()
        )
    }
}
