package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * U净 订单实时状态长连接客户端（基于实测研究笔记 ykt_notes.md 第 17 节）。
 *
 * 底层协议：MQTT 3.1.1 over WebSocket (WSS)。
 * Broker: `wss://emq.ujing.online:443/mqtt`
 * 订阅主题：`device/<deviceId>`
 *
 * 采用 OkHttp WebSocket 承载，零第三方 MQTT SDK 依赖。
 */
class UjingMqttClient(
    private val deviceId: String,
    private val targetOrderId: Long? = null,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "UjingMqttClient"
        private const val BROKER_URL = "wss://emq.ujing.online:443/mqtt"
        private const val KEEP_ALIVE_SECONDS = 30
    }

    data class MqttOrderStatus(
        val orderId: Long?,
        val status: Int,
        val remainTime: Int?,
        val rawJson: JSONObject
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接不设读取超时
        .build()

    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var isConnected = false

    private val _statusFlow = MutableSharedFlow<MqttOrderStatus>(extraBufferCapacity = 16)
    val statusFlow: SharedFlow<MqttOrderStatus> = _statusFlow.asSharedFlow()

    private val _connectionState = MutableSharedFlow<Boolean>(replay = 1)
    val connectionState: SharedFlow<Boolean> = _connectionState.asSharedFlow()

    /**
     * 启动 MQTT 连接并订阅设备状态。
     */
    fun start() {
        if (deviceId.isBlank()) {
            Log.w(TAG, "Cannot start MQTT client: empty deviceId")
            return
        }

        val request = Request.Builder()
            .url(BROKER_URL)
            .header("Sec-WebSocket-Protocol", "mqtt")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected, sending MQTT CONNECT...")
                sendMqttConnect(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMqttPacket(webSocket, bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code / $reason")
                stopPing()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed")
                isConnected = false
                _connectionState.tryEmit(false)
                stopPing()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure", t)
                isConnected = false
                _connectionState.tryEmit(false)
                stopPing()
            }
        })
    }

    /**
     * 停止连接并释放资源。
     */
    fun stop() {
        stopPing()
        isConnected = false
        runCatching {
            webSocket?.close(1000, "Client stopped")
        }
        webSocket = null
    }

    private fun startPing(ws: WebSocket) {
        stopPing()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(KEEP_ALIVE_SECONDS * 1000L)
                if (isConnected) {
                    sendMqttPing(ws)
                }
            }
        }
    }

    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }

    // ==================== MQTT 3.1.1 协议编解码 ====================

    private fun sendMqttConnect(ws: WebSocket) {
        val clientId = "cf_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val varHeaderAndPayload = Buffer().apply {
            // Protocol Name "MQTT"
            writeShort(4)
            writeUtf8("MQTT")
            // Protocol Level 4 (3.1.1)
            writeByte(4)
            // Connect Flags: Clean Session = 1
            writeByte(0x02)
            // Keep Alive (seconds)
            writeShort(KEEP_ALIVE_SECONDS)
            // Client ID
            writeShort(clientId.length)
            writeUtf8(clientId)
        }

        val packet = Buffer().apply {
            writeByte(0x10) // Packet Type 1 (CONNECT)
            writeRemainingLength(varHeaderAndPayload.size.toInt())
            writeAll(varHeaderAndPayload)
        }

        ws.send(packet.readByteString())
    }

    private fun sendMqttSubscribe(ws: WebSocket, topic: String) {
        val payload = Buffer().apply {
            writeShort(1) // Packet ID = 1
            writeShort(topic.length)
            writeUtf8(topic)
            writeByte(0x00) // Requested QoS 0
        }

        val packet = Buffer().apply {
            writeByte(0x82) // Packet Type 8 (SUBSCRIBE), flags = 0010
            writeRemainingLength(payload.size.toInt())
            writeAll(payload)
        }

        ws.send(packet.readByteString())
    }

    private fun sendMqttPing(ws: WebSocket) {
        val packet = Buffer().apply {
            writeByte(0xC0) // PINGREQ
            writeByte(0x00) // Remaining length = 0
        }
        ws.send(packet.readByteString())
    }

    private fun handleMqttPacket(ws: WebSocket, bytes: ByteString) {
        val buffer = Buffer().apply { write(bytes) }
        if (buffer.exhausted()) return

        val headerByte = buffer.readByte().toInt() and 0xFF
        val packetType = headerByte ushr 4
        val remainingLength = buffer.readRemainingLength()

        when (packetType) {
            2 -> { // CONNACK
                val sessionPresent = buffer.readByte()
                val returnCode = buffer.readByte().toInt()
                if (returnCode == 0) {
                    Log.i(TAG, "MQTT connected successfully (CONNACK 0), subscribing device/$deviceId")
                    isConnected = true
                    _connectionState.tryEmit(true)
                    startPing(ws)
                    sendMqttSubscribe(ws, "device/$deviceId")
                } else {
                    Log.w(TAG, "MQTT connect refused with code $returnCode")
                }
            }
            9 -> { // SUBACK
                val packetId = buffer.readShort()
                Log.i(TAG, "MQTT subscribed acknowledged for device/$deviceId (packetId=$packetId)")
            }
            3 -> { // PUBLISH
                val qos = (headerByte and 0x06) ushr 1
                val topicLength = buffer.readShort().toInt() and 0xFFFF
                val topic = buffer.readUtf8(topicLength.toLong())
                if (qos > 0) {
                    buffer.readShort() // skip packet identifier
                }
                val payloadStr = buffer.readUtf8()
                handlePublishPayload(topic, payloadStr)
            }
            13 -> { // PINGRESP
                // 心跳应答正常
            }
            else -> {
                Log.d(TAG, "Received MQTT packet type $packetType")
            }
        }
    }

    private fun handlePublishPayload(topic: String, payload: String) {
        try {
            val json = JSONObject(payload)
            val status = json.optInt("status", -1)
            val orderId = json.optLong("orderId", 0L).takeIf { it > 0 }
            val remainTime = if (json.has("remainTime")) json.optInt("remainTime") else null

            // 若指定了 targetOrderId，只认对应订单的消息
            if (targetOrderId != null && orderId != null && targetOrderId != orderId) {
                return
            }

            if (status >= 0) {
                Log.i(TAG, "MQTT push orderStatus=$status, orderId=$orderId, remainTime=$remainTime")
                val item = MqttOrderStatus(
                    orderId = orderId,
                    status = status,
                    remainTime = remainTime,
                    rawJson = json
                )
                scope.launch {
                    _statusFlow.emit(item)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse MQTT publish payload: $payload", e)
        }
    }

    private fun Buffer.writeRemainingLength(value: Int) {
        var v = value
        do {
            var encodedByte = v % 128
            v /= 128
            if (v > 0) {
                encodedByte = encodedByte or 128
            }
            writeByte(encodedByte)
        } while (v > 0)
    }

    private fun Buffer.readRemainingLength(): Int {
        var multiplier = 1
        var value = 0
        var digit: Int
        do {
            digit = readByte().toInt() and 0xFF
            value += (digit and 127) * multiplier
            multiplier *= 128
        } while ((digit and 128) != 0)
        return value
    }
}
