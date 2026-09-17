package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * U净 业务与设备交互客户端（基于实测研究笔记 ykt_notes.md 第 10/12/15/16/17 节与实测 HAR）。
 *
 * 核心链路：
 * 1. 一卡通 platformToken -> 解析应用启动地址 (appId 59 饮水 / 45 洗衣) 带出一次性 ticket；
 * 2. parseUrlV2 -> 取得平台 OAuth 授权地址；
 * 3. 平台 OAuth authorize (带 synjones-auth) -> 取得 code；
 * 4. authV2 -> 换得 U净 专属 token；
 * 5. 饮水机：changeWithScan -> currentInfo -> createWaterOrder (orderType=11) -> waterOrderDetail。
 * 6. 洗衣机：scanWasherCode。
 */
class WbuUjingClient(
    private val context: Context,
    private val cardClient: WbuCampusCardClient = WbuCampusCardClient(context, useVpn = false)
) {
    private val transport: WbuAuthTransport = WbuAuthTransport.getShared(context, false)

    /** 复用统一传输层的证书策略与 Cookie 管理，但禁止自动跟随重定向以便读取 Location。 */
    private val httpClient: OkHttpClient = transport.client.newBuilder()
        .followRedirects(false)
        .build()

    var ujingToken: String? = null
        private set

    var currentUser: UjingUser? = null
        private set

    companion object {
        private const val TAG = "WbuUjingClient"
        const val UJING_API = "https://phoenix.ujing.online:443/api/v1"
        const val UJING_CLIENT_PATH = "wechat_work"
        const val APP_TYPE = 21 // 武汉商学院租户 ID
        const val WATER_APP_ID = 59
        const val WASHER_APP_ID = 45
        const val ORDER_TYPE_SCAN = 11 // 武汉企业微信渠道 = 扫码/一卡通免密

        private const val UA = "Mozilla/5.0 (Linux; Android 16; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130 Mobile Safari/537.36 wxwork/4.1.36 MicroMessenger/7.0.1"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        val TERMINAL_STATUS = setOf(50, 51, 54, 62, 63, 64, 65)

        fun orderStatusName(status: Int): String = when (status) {
            0 -> "已创建"
            10 -> "开始取水"
            30 -> "结账中"
            50 -> "取水正常完成"
            51 -> "设备离线"
            54 -> "未授权"
            62 -> "结账异常"
            63 -> "手动结束"
            64 -> "提前结束"
            65 -> "支付失败结束"
            else -> if (status >= 50) "订单已结束" else "处理中 ($status)"
        }
    }

    class UjingApiException(val code: Int, override val message: String) : IOException(message)

    data class UjingUser(
        val id: String,
        val nickName: String?,
        val mobile: String?,
        val lbUserId: Long?
    )

    data class WaterServiceSubject(
        val subjectId: Long,
        val subjectName: String,
        val storeName: String?,
        val isSmartCard: Boolean,
        val balanceCents: Long,
        val forceRecharge: Long,
        val rechargeTipAmount: Long
    )

    data class WaterOrderResult(
        val orderId: Long,
        val orderNo: String?,
        val deviceId: String?,
        val orderType: Int
    )

    data class WaterOrderDetail(
        val orderId: Long,
        val orderNo: String?,
        val orderStatus: Int,
        val orderStatusName: String,
        val storeName: String?,
        val deviceNo: String?,
        val orderTypeName: String?,
        val payTypeName: String?,
        val hotWaterMl: Int,
        val warmWaterMl: Int,
        val payPrice: Double,
        val isTerminal: Boolean
    )

    data class WasherScanResult(
        val online: Boolean,
        val lastUseDeviceId: String?,
        val rawData: JSONObject
    )

    private fun ujHeaders(appCode: String = "COA"): Map<String, String> = buildMap {
        put("Content-Type", "application/json; charset=utf-8")
        put("x-app-code", appCode)
        put("x-app-version", "1.0.34")
        put("Origin", "https://static.ujing.com.cn")
        put("Referer", "https://static.ujing.com.cn/")
        put("User-Agent", UA)
        ujingToken?.let { put("Authorization", "Bearer $it") }
    }

    /**
     * 建立 U净 会话（跨平台 SSO 换票）。
     *
     * @param platformToken 一卡通平台的 access_token
     * @param appId 平台子应用 ID（59=饮水，45=洗衣）
     */
    suspend fun connect(platformToken: String, appId: Int = WATER_APP_ID): UjingUser = withContext(Dispatchers.IO) {
        // 1. 获取带 ticket 的平台启动跳转地址
        val launchUrl = cardClient.resolveAppLaunchUrl(appId, platformToken)
            ?: throw IOException("未能获取平台子应用 (appId=$appId) 启动地址")

        val appCode = if (appId == WASHER_APP_ID) "BO" else "COA"

        // 2. parseUrlV2 认票，返回平台 OAuth 授权地址
        val parseBody = JSONObject().apply {
            put("url", launchUrl)
            put("appType", APP_TYPE)
        }
        val parseReq = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/third-party/parseUrlV2")
            .post(parseBody.toString().toRequestBody(JSON_MEDIA))
            .apply { ujHeaders(appCode).forEach { (k, v) -> header(k, v) } }
            .build()

        val authUrl = httpClient.newCall(parseReq).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            val data = json.optJSONObject("data") ?: throw IOException("parseUrlV2 响应异常: $bodyStr")
            data.optString("authUrl").takeIf { it.isNotBlank() }
                ?: throw IOException("parseUrlV2 未返回授权地址: $bodyStr")
        }

        // 3. 携带平台身份访问 OAuth 授权地址，换取 code
        val sep = if (authUrl.contains("?")) "&" else "?"
        val authedUrl = "$authUrl${sep}synjones-auth=" + URLEncoder.encode("bearer $platformToken", "UTF-8")
        val codeReq = Request.Builder()
            .url(authedUrl)
            .header("User-Agent", UA)
            .header("synAccessSource", "h5")
            .get()
            .build()

        val code = httpClient.newCall(codeReq).execute().use { resp ->
            val location = resp.header("Location").orEmpty()
            val match = Regex("[?&]code=([^&#]+)").find(location)
            match?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
                ?: throw IOException("平台 OAuth 未签发 code (status=${resp.code}, Location=$location)")
        }

        // 4. authV2 使用 code 换取 U净 独立 Token
        val authBody = JSONObject().apply {
            put("code", code)
            put("appType", APP_TYPE)
            put("loginWay", 1)
            put("userId", 0)
        }
        val authReq = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/third-party/authV2")
            .post(authBody.toString().toRequestBody(JSON_MEDIA))
            .apply { ujHeaders(appCode).forEach { (k, v) -> header(k, v) } }
            .build()

        httpClient.newCall(authReq).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            val data = json.optJSONObject("data") ?: throw IOException("authV2 换票异常: $bodyStr")
            val token = data.optString("token")
            if (token.isBlank()) throw IOException("authV2 未返回 token: $bodyStr")

            ujingToken = token
            val user = UjingUser(
                id = data.optString("id"),
                nickName = data.optString("nickName").takeIf { it.isNotBlank() },
                mobile = data.optString("mobile").takeIf { it.isNotBlank() },
                lbUserId = data.optLong("lbUserId", 0L).takeIf { it > 0 }
            )
            currentUser = user
            user
        }
    }

    /**
     * 绑定扫码贴纸对应的取水点（服务主体）。
     */
    suspend fun bindWaterPoint(cd: String): WaterServiceSubject = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("cd", cd) }
        val req = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/water/serviceSubject/changeWithScan")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .apply { ujHeaders("COA").forEach { (k, v) -> header(k, v) } }
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            if (json.optInt("code") != 0) {
                val msg = json.optString("message").ifBlank { "绑定取水点失败" }
                throw IOException(msg)
            }
            val data = json.optJSONObject("data") ?: throw IOException("绑定取水点未返回数据")
            WaterServiceSubject(
                subjectId = data.optLong("newServiceSubjectId"),
                subjectName = data.optString("newServiceSubjectName"),
                storeName = data.optString("storeName").takeIf { it.isNotBlank() },
                isSmartCard = data.optInt("waterForceSmartCard", 0) == 1,
                balanceCents = data.optLong("balance", 0L),
                forceRecharge = data.optLong("forceRecharge", 0L),
                rechargeTipAmount = data.optLong("rechargeTipAmount", 0L)
            )
        }
    }

    /**
     * 查询当前取水点信息与一卡通免密状态。
     */
    suspend fun getCurrentWaterInfo(): WaterServiceSubject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/water/serviceSubject/currentInfo")
            .get()
            .apply { ujHeaders("COA").forEach { (k, v) -> header(k, v) } }
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            val data = json.optJSONObject("data") ?: throw IOException("获取取水点信息失败")
            WaterServiceSubject(
                subjectId = data.optLong("ServiceSubjectId"),
                subjectName = data.optString("ServiceSubjectName"),
                storeName = null,
                isSmartCard = data.optInt("waterForceSmartCard", 0) == 1,
                balanceCents = data.optLong("balance", 0L),
                forceRecharge = data.optLong("forceRecharge", 0L),
                rechargeTipAmount = data.optLong("rechargeTipAmount", 0L)
            )
        }
    }

    /**
     * 下单出水（orderType=11 武汉企微扫码/免密出水）。
     * 若遇到 2040231「设备正在使用中或者结账中」，自动重试。
     */
    suspend fun dispenseWater(cd: String, retries: Int = 4, gapMillis: Long = 3000L): WaterOrderResult = withContext(Dispatchers.IO) {
        var lastMsg = ""
        for (attempt in 0 until retries) {
            val body = JSONObject().apply {
                put("deviceId", cd)
                put("orderType", ORDER_TYPE_SCAN)
            }
            val req = Request.Builder()
                .url("$UJING_API/$UJING_CLIENT_PATH/water/createWaterOrder")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .apply { ujHeaders("COA").forEach { (k, v) -> header(k, v) } }
                .build()

            val outcome = httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val code = json.optInt("code")
                if (code == 0) {
                    val data = json.optJSONObject("data") ?: throw IOException("出水订单创建未返回数据")
                    return@use Result.success(
                        WaterOrderResult(
                            orderId = data.optLong("orderId"),
                            orderNo = data.optString("orderNo"),
                            deviceId = data.optString("deviceId"),
                            orderType = data.optInt("orderType", ORDER_TYPE_SCAN)
                        )
                    )
                }
                val msg = json.optString("message").ifBlank { "出水下单失败 (code=$code)" }
                Result.failure(UjingApiException(code, msg))
            }

            if (outcome.isSuccess) {
                return@withContext outcome.getOrThrow()
            }

            val err = outcome.exceptionOrNull() as? UjingApiException
            val code = err?.code ?: 0
            lastMsg = err?.message ?: "未知错误"
            if (code != 2040231) {
                // 非设备忙状态直接抛错退出
                throw IOException(lastMsg)
            }

            Log.i(TAG, "Device $cd busy (2040231), retry ${attempt + 1}/$retries in ${gapMillis}ms")
            delay(gapMillis)
        }

        throw IOException(lastMsg.ifBlank { "设备正在使用中，请稍后重试" })
    }

    /**
     * 查询订单详情与实时状态。
     */
    suspend fun fetchOrderDetail(orderId: Long): WaterOrderDetail = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("orderId", orderId) }
        val req = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/water/waterOrderDetail")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .apply { ujHeaders("COA").forEach { (k, v) -> header(k, v) } }
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            val data = json.optJSONObject("data") ?: throw IOException("获取订单详情失败: $bodyStr")
            val status = data.optInt("orderStatus", -1)
            val statusName = data.optString("orderStatusName").ifBlank { orderStatusName(status) }
            val terminal = status in TERMINAL_STATUS

            WaterOrderDetail(
                orderId = orderId,
                orderNo = data.optString("orderNo"),
                orderStatus = status,
                orderStatusName = statusName,
                storeName = data.optString("storeName").takeIf { it.isNotBlank() },
                deviceNo = data.optString("deviceNo").takeIf { it.isNotBlank() },
                orderTypeName = data.optString("orderTypeName").takeIf { it.isNotBlank() },
                payTypeName = data.optString("payTypeName").takeIf { it.isNotBlank() },
                hotWaterMl = data.optInt("hotWaterML", 0),
                warmWaterMl = data.optInt("warmWaterML", 0),
                payPrice = data.optDouble("payPrice", 0.0),
                isTerminal = terminal
            )
        }
    }

    /**
     * 洗衣机设备码核验（扫描二维码原文）。
     * 对应 U净 washer-h5 的 devices/scanWasherCode 接口。
     */
    suspend fun scanWasherCode(qrcode: String): WasherScanResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("qrCode", qrcode)
            put("scanWay", 0)
        }
        val req = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/devices/scanWasherCode")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .apply { ujHeaders("BO").forEach { (k, v) -> header(k, v) } }
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            if (json.optInt("code") != 0) {
                val msg = json.optString("message").ifBlank { "洗衣机扫码未成功 (code=${json.optInt("code")})" }
                throw IOException(msg)
            }
            val data = json.optJSONObject("data") ?: throw IOException("洗衣机扫码未返回数据: $bodyStr")
            val result = data.optJSONObject("result")
            val online = result?.optBoolean("createOrderEnabled", true) ?: true
            val deviceId = result?.optString("deviceId").takeIf { !it.isNullOrBlank() }

            WasherScanResult(
                online = online,
                lastUseDeviceId = deviceId,
                rawData = data
            )
        }
    }
}
