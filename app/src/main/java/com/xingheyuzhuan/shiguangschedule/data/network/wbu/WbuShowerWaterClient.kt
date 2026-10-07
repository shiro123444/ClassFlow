package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 一卡通「生活服务」用水接口客户端（马影河 2-3 栋淋浴 / lifeService，appId=65）。
 *
 * 这四个接口就是 `applications/lifeService` 页面自己用的那套（页面产物
 * `js/chunk-b88c7b1e.f1981e59.js`），实测（HAR + 复现）均可只带平台令牌直接调用，
 * 因此原生页面不必再经过网页：
 *
 * | 用途 | 接口 | 请求体 |
 * |---|---|---|
 * | 设备正证（判断是不是淋浴） | `POST /charge/qrCodeApp/getDevicesType` | `{imei, implid, feeitemid}` |
 * | **当前未结束的用水** | `POST /charge/qrCodeApp/getDevicesStatus` | `{implid, feeitemid}` |
 * | 开始用水（开单） | `POST /charge/qrCodeApp/consumption` | `{imei, implid, feeitemid[, port]}` |
 * | 结束用水 | `POST /charge/qrCodeApp/endConsumption` | `{imei, implid, feeitemid[, ordernum]}` |
 *
 * 实测响应（2026-10-07）：
 * - 未用水：`{"data":{"message":"暂未使用用水设备","status":0}}`
 * - 用水中：`{"data":{"imei":"17010737","startTime":"2026-10-07 23:15:40",
 *   "ordernum":"16d8884507e04ec19f6de2ede31e7823","deviceNo":"17010737","feeitemid":414,
 *   "message":"正在用水","status":1}}`
 *
 * 鉴权只有一层：`synjones-auth: bearer <平台 access_token>`（[WbuCampusCardClient.ensureValidAccessToken]），
 * 不需要 Cookie，也不需要校园网（平台公网直连）。
 */
class WbuShowerWaterClient(
    context: Context,
    private val cardClient: WbuCampusCardClient = WbuCampusCardClient(context, useVpn = false)
) {

    private val transport: WbuAuthTransport = WbuAuthTransport.getShared(context, false)
    private val httpClient = transport.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "WbuShowerWaterClient"

        /** 淋浴（lifeService 设备类型，页面 `deviceCodeDict` 里的 `101:{label:"淋浴"}`）。 */
        const val DEVICES_TYPE_SHOWER = 101

        /** 设备类型 → 展示名，取自页面 `deviceList`（用于「设备不匹配」这类提示）。 */
        val DEVICES_TYPE_NAMES = mapOf(
            0 to "洗浴",
            1 to "洗鞋机",
            2 to "饮水机",
            4 to "电吹风",
            5 to "烘干机",
            7 to "洗衣机",
            10 to "洗烘机",
            101 to "淋浴",
            19901 to "充电桩"
        )

        private const val PATH_DEVICES_TYPE = "/charge/qrCodeApp/getDevicesType"
        private const val PATH_DEVICES_STATUS = "/charge/qrCodeApp/getDevicesStatus"
        private const val PATH_CONSUMPTION = "/charge/qrCodeApp/consumption"
        private const val PATH_END_CONSUMPTION = "/charge/qrCodeApp/endConsumption"

        /**
         * 页面 axios 请求拦截器给每个 JSON 请求体塞的字段
         * （`synAccessSource: sessionStorage.agentType || "h5"`）；自建 WebView 里 agentType 恒为 `h5`，
         * 而 2026-10-07 实测正是这条链路开出的水单，所以原生页沿用 `h5`。
         */
        private const val ACCESS_SOURCE = "h5"

        private val JSON_MEDIA = "application/json;charset=UTF-8".toMediaType()

        /**
         * 从平台子应用启动地址里取出计费上下文。
         *
         * 启动地址形如
         * `/applications/lifeService?_dt=101&_implid=63_101&feeitemid=414&appId=65&…`：
         * `feeitemid` 是缴费项，`_implid` 是「实现 id」（`63_101` = implid 63 对应设备类型 101）。
         * 两个都必须是平台给的，不能写死 —— 换成别的缴费项（洗衣机等）就是另一组值。
         */
        fun parseBilling(launchUrl: String?): Billing? {
            val url = launchUrl?.trim().orEmpty()
            if (url.isBlank()) return null
            val feeitemid = queryParam(url, "feeitemid") ?: return null
            val implidRaw = queryParam(url, "_implid") ?: return null
            val implid = implidRaw.substringBefore('_').trim()
            if (implid.isBlank() || feeitemid.isBlank()) return null
            return Billing(implid = implid, feeitemid = feeitemid)
        }

        /**
         * 从平台令牌（JWT）载荷里取学号。
         *
         * 结算推送的主题是 `…/qrcodeElec_result/<sno>`，页面用的就是 `userInfo.sno`；
         * 而平台令牌载荷里本来就有它（实测载荷：`{"sno":"250594036","name":"…","account":"…"}`），
         * 因此原生页面不必再多请求一次 `/berserker-base/user`。
         */
        fun parseSno(token: String?): String? {
            val payload = token?.trim()?.split('.')?.getOrNull(1) ?: return null
            if (payload.isBlank()) return null
            val decoded = runCatching {
                val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
                String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
            }.getOrNull() ?: return null
            val json = runCatching { Json.parseToJsonElement(decoded) }.getOrNull() as? JsonObject ?: return null
            fun textOf(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            return textOf("sno") ?: textOf("account")
        }

        /** 取查询参数（不做 URL 解码：这几个值都是纯数字 / 短标识）。 */
        private fun queryParam(url: String, name: String): String? =
            url.substringAfter('?', "")
                .split('&')
                .firstOrNull { it.startsWith("$name=") }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        private fun bodyOf(
            vararg pairs: Pair<String, Any?>,
            billing: Billing,
            deviceId: String? = null
        ): JSONObject = JSONObject().apply {
            deviceId?.let { put("imei", it) }
            put("implid", billing.implid)
            // feeitemid 接口收数字也收字符串，实测页面发的是数字；能转就转，转不了就原样发。
            put("feeitemid", billing.feeitemid.toIntOrNull() ?: billing.feeitemid)
            pairs.forEach { (key, value) -> if (value != null) put(key, value) }
            put("synAccessSource", ACCESS_SOURCE)
        }
    }

    /** 计费上下文（缴费项 + 实现 id），来自平台子应用启动地址。 */
    data class Billing(val implid: String, val feeitemid: String)

    /**
     * 设备正证结果。
     *
     * [hasGears] 表示服务端给了**档位价格**（`prices` 非空）：淋浴这一项实测是不给的
     * （2026-10-07 实测 `{"devicesType":101,"devicesName":"洗浴"}`，没有 `prices` 字段），
     * 所以页面直接开单；万一以后服务端配上档位，网页那边会先弹档位让用户选，原生页暂时不接管
     * （调用方据此回退到网页，见 `CampusShowerEntryResolver`）。
     */
    data class DeviceCheck(
        val devicesType: Int,
        val devicesName: String?,
        val hasGears: Boolean = false
    ) {
        val isShower: Boolean get() = devicesType == DEVICES_TYPE_SHOWER
    }

    /**
     * 当前未结束的用水（`getDevicesStatus`）。
     *
     * [status] 0 = 暂未使用；实测 1 = 正在用水。页面只区分「0 与非 0」，这里同样以 [active] 为准。
     * 接口按「缴费项」查，即**这个账号在这个缴费项下有没有没结束的用水**，并给出在用设备
     * （[deviceNo] / [imei]）、订单号 [ordernum] 与开始时间 [startTime]。
     */
    data class Usage(
        val status: Int,
        val message: String?,
        val deviceNo: String?,
        val ordernum: String?,
        val startTime: String?,
        val feeitemid: String?
    ) {
        val active: Boolean get() = status != 0
    }

    /** 开始用水的结果（`consumption` 的 data）。 */
    data class StartResult(
        val deviceNo: String?,
        val status: Int?,
        val message: String?
    )

    /** 服务端明确拒绝（业务码非 200 / 400）。 */
    class ShowerApiException(val code: Int, override val message: String) : IOException(message)

    /** 取一个可用的平台令牌（内部会按需刷新 / 静默重登）。 */
    suspend fun accessToken(): String = cardClient.ensureValidAccessToken()

    /** 设备正证：这个设备在**这个缴费项**下是什么类型（淋浴 = [DEVICES_TYPE_SHOWER]）。 */
    suspend fun getDevicesType(
        token: String,
        deviceId: String,
        billing: Billing
    ): DeviceCheck = withContext(Dispatchers.IO) {
        val data = post(PATH_DEVICES_TYPE, token, bodyOf(billing = billing, deviceId = deviceId))
            ?: throw IOException("设备校验未返回数据")
        DeviceCheck(
            devicesType = data.optInt("devicesType"),
            devicesName = data.optString("devicesName").takeIf { it.isNotBlank() },
            hasGears = (data.optJSONArray("prices")?.length() ?: 0) > 0
        )
    }

    /** 查询当前未结束的用水（0 = 没有）。 */
    suspend fun getUsage(token: String, billing: Billing): Usage = withContext(Dispatchers.IO) {
        val data = post(PATH_DEVICES_STATUS, token, bodyOf(billing = billing))
            ?: throw IOException("用水状态未返回数据")
        Usage(
            status = data.optInt("status"),
            message = data.optString("message").takeIf { it.isNotBlank() },
            deviceNo = data.optString("deviceNo").takeIf { it.isNotBlank() },
            ordernum = data.optString("ordernum").takeIf { it.isNotBlank() },
            startTime = data.optString("startTime").takeIf { it.isNotBlank() },
            feeitemid = data.opt("feeitemid")?.toString()
        )
    }

    /** 开始用水（开一单）。多路控水器要带 [port]。 */
    suspend fun startUse(
        token: String,
        deviceId: String,
        billing: Billing,
        port: String? = null
    ): StartResult = withContext(Dispatchers.IO) {
        val data = post(
            PATH_CONSUMPTION,
            token,
            bodyOf("port" to port?.takeIf { it.isNotBlank() }, billing = billing, deviceId = deviceId)
        ) ?: throw IOException("开始用水未返回数据")
        StartResult(
            deviceNo = data.optString("deviceNo").takeIf { it.isNotBlank() },
            status = if (data.has("status")) data.optInt("status") else null,
            message = data.optString("message").takeIf { it.isNotBlank() }
        )
    }

    /** 结束用水。[ordernum] 接口可省略（实测只带 imei + 计费上下文即可）。 */
    suspend fun endUse(
        token: String,
        deviceId: String,
        billing: Billing,
        ordernum: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val data = post(
            PATH_END_CONSUMPTION,
            token,
            bodyOf("ordernum" to ordernum?.takeIf { it.isNotBlank() }, billing = billing, deviceId = deviceId)
        )
        data?.optString("message")?.takeIf { it.isNotBlank() }
    }

    /**
     * POST 一个 JSON 接口，返回 `data` 对象（可能为 null）。
     *
     * 错误口径与平台其它接口一致：401 → [WbuSessionExpiredException]（上层弹登录）；
     * 业务 `code != 200` → [ShowerApiException]，`msg` 直接就是页面 `$toast` 显示的那句话。
     */
    private fun post(path: String, token: String, body: JSONObject): JSONObject? {
        val req = Request.Builder()
            .url(WbuCampusCardClient.BASE_URL + path)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("synjones-auth", "bearer $token")
            .header("synAccessSource", ACCESS_SOURCE)
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", transport.authUserAgent())
            .header("Accept-Language", transport.authAcceptLanguage)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) {
                Log.i(TAG, "shower api $path unauthorized (${resp.code})")
                throw WbuSessionExpiredException(AccessLayer.Service)
            }
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: throw IOException("接口返回不是 JSON：$path")
            val code = json.optInt("code", resp.code)
            if (resp.code != 200 || code != 200) {
                val msg = json.optString("msg").ifBlank { json.optString("message") }
                    .ifBlank { "请求失败（$code）" }
                Log.w(TAG, "shower api $path failed code=$code msg=$msg")
                throw ShowerApiException(code, msg)
            }
            return json.optJSONObject("data")
        }
    }
}
