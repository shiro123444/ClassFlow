package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import android.util.Log
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CampusPayMethod
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.PayCodeBatch
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.PayCodeQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 平台（服务端）明确告知「这次取不到付款码」：支付方式未开通、卡挂失/冻结/过期、接口维护等。
 *
 * 与网络失败区分开：[serverMessage] 是服务端原话，可以直接展示（例如「当前时段暂停服务」）；
 * 上游据此决定是「提示 + 换支付方式」还是「重试」。
 */
class PayCodeUnavailableException(val serverMessage: String?) : Exception(serverMessage)

/**
 * 一卡通「校园码 / 付款码」数据客户端（新中新 berserker 一卡通移动服务平台）。
 *
 * 三条接口（与平台 H5 `campusCode` 页面完全一致）：
 * 1. `GET /berserker-app/ykt/tsm/codebarPayinfo` —— 支付方式列表（校园卡 / 电子账户 / 签约银行卡）+ 余额；
 * 2. `GET /berserker-app/ykt/tsm/batchGetBarCodeGet?account&payacc&paytype` —— 一次取一批码
 *    （`{expires, barcode:[…]}`），页面顺序消费，用完再取；
 * 3. （未接入）`offlienPar` 脱机码：`voucherStatus = 1` 的支付方式由客户端本地用平台私钥生成，
 *    原生暂不接管，见 [CampusPayMethod.needsVoucherCode]。
 *
 * 鉴权沿用平台 JWT：`synjones-auth: bearer <access_token>`，access_token 由
 * [WbuCampusCardClient.ensureValidAccessToken] 维护（refresh_token 续期不顶号）。
 */
class WbuPayCodeClient(
    private val context: Context,
    private val useVpn: Boolean = false
) {

    private val transport: WbuAuthTransport = WbuAuthTransport.getShared(context, useVpn)
    private val client = transport.client

    companion object {
        private const val TAG = "WbuPayCodeClient"
        private const val BASE_URL = WbuCampusCardClient.BASE_URL
        private const val PATH_PAY_INFO = "/berserker-app/ykt/tsm/codebarPayinfo"
        private const val PATH_PAY_CODE = "/berserker-app/ykt/tsm/batchGetBarCodeGet"

        /** 平台按 `synAccessSource` 区分宿主，H5 宿主固定为 `h5`。 */
        private const val ACCESS_SOURCE = "h5"

        /** 单码有效期兜底：平台没给 `expires` 时按 60 秒处理（H5 `codeRefreshTime` 默认值）。 */
        private const val DEFAULT_EXPIRES_SECONDS = PayCodeQueue.DEFAULT_SLOT_SECONDS

        /** 单码有效期的合理区间，防止服务端异常值把页面卡死或刷爆。 */
        private val EXPIRES_RANGE = 5..600
    }

    /**
     * 查询该账号下可用的支付方式（含余额）。
     *
     * @throws WbuSessionExpiredException 平台令牌失效（上游应用 refresh_token / 静默重登续一次）
     * @throws PayCodeUnavailableException 服务端明确拒绝（如该账号没有一卡通账号）
     */
    suspend fun queryPayMethods(accessToken: String): List<CampusPayMethod> = withContext(Dispatchers.IO) {
        val json = getJson(accessToken, PATH_PAY_INFO)
        val array = json.optJSONArray("data") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { parsePayMethod(it) }
        }
    }

    /**
     * 取一批付款码（顺序消费，页面展示队首）。
     *
     * @throws PayCodeUnavailableException 服务端返回空码表（未开通 / 卡状态异常 / 维护中）
     */
    suspend fun fetchPayCodes(accessToken: String, method: CampusPayMethod): PayCodeBatch =
        withContext(Dispatchers.IO) {
            val json = getJson(
                accessToken,
                PATH_PAY_CODE,
                params = mapOf(
                    "account" to method.account,
                    "payacc" to method.payacc,
                    "paytype" to method.paytype
                )
            )

            val data = json.optJSONObject("data")
            val codes = data?.optJSONArray("barcode").toStringList()
            if (codes.isEmpty()) {
                // 平台把「取不到码」的原因放在 data.errmsg，其次才是顶层 msg
                throw PayCodeUnavailableException(
                    data?.optString("errmsg").takeIf { !it.isNullOrBlank() }
                        ?: json.optString("msg").takeIf { !it.isNullOrBlank() }
                )
            }

            PayCodeBatch(
                expiresSeconds = data?.optInt("expires", DEFAULT_EXPIRES_SECONDS)
                    ?.coerceIn(EXPIRES_RANGE) ?: DEFAULT_EXPIRES_SECONDS,
                codes = codes
            )
        }

    // ------------------- 内部实现 -------------------

    private fun getJson(
        accessToken: String,
        path: String,
        params: Map<String, String> = emptyMap()
    ): JSONObject {
        val url = (BASE_URL + path).toHttpUrlOrNull()
            ?.newBuilder()
            ?.apply {
                params.forEach { (name, value) -> addQueryParameter(name, value) }
                addQueryParameter("synAccessSource", ACCESS_SOURCE)
            }
            ?.build()
            ?: throw IOException("付款码接口地址非法: $path")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", transport.authUserAgent())
            .header("Accept-Language", transport.authAcceptLanguage)
            .header("synjones-auth", "bearer $accessToken")
            .header("synAccessSource", ACCESS_SOURCE)
            .get()
            .build()

        val (httpCode, body) = client.newCall(request).execute().use { response ->
            response.code to response.body.string()
        }

        // 401 = 令牌无效（被顶号 / 过期 / 换票前的临时态），交给上游续期重试
        if (httpCode == 401) {
            Log.w(TAG, "付款码接口 401: $body")
            throw WbuSessionExpiredException(AccessLayer.UnifiedAuth)
        }
        if (httpCode != 200) {
            Log.w(TAG, "付款码接口 HTTP $httpCode: $body")
            throw IOException("付款码接口返回 HTTP $httpCode")
        }

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw IOException("付款码接口返回了非 JSON 内容")
        if (json.optInt("code") != 200) {
            val message = json.optString("msg").takeIf { it.isNotBlank() }
            Log.w(TAG, "付款码接口业务失败: ${json.optInt("code")} $message")
            throw PayCodeUnavailableException(message)
        }
        return json
    }

    /**
     * 解析单个支付方式。
     *
     * 余额口径与平台 H5 一致：校园卡 = `(db_balance + unsettle_amount) / 100`，
     * 电子账户 = `accinfo_balance / 100`（单位分），签约银行卡没有余额。
     */
    private fun parsePayMethod(json: JSONObject): CampusPayMethod? {
        val account = json.optString("account")
        val payacc = json.optString("payacc")
        if (account.isBlank() || payacc.isBlank()) return null

        val code = json.optString("code").uppercase()
        val balance = when (code) {
            "CARD" -> (json.optDouble("db_balance", 0.0) + json.optDouble("unsettle_amount", 0.0)) / 100.0
            "ACCOUNT" -> json.optDouble("accinfo_balance", 0.0) / 100.0
            else -> null
        }

        return CampusPayMethod(
            id = json.optInt("id"),
            name = json.optString("name").ifBlank { code },
            code = code,
            account = account,
            payacc = payacc,
            paytype = json.optString("paytype"),
            status = json.optInt("status"),
            voucherStatus = json.optInt("voucherStatus"),
            balance = balance,
            bankCardTail = json.optString("bandacc").takeIf { it.isNotBlank() }?.let(::maskAccount),
            notShowType = json.optString("notShowType").takeIf { it.isNotBlank() },
            website = json.optString("website").takeIf { it.isNotBlank() }
        )
    }

    /** 账号脱敏：只留末 4 位，其余用 `*` 顶掉（平台 H5 同款处理，不把完整卡号摆上屏）。 */
    private fun maskAccount(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length <= 4) return trimmed
        return "*".repeat(trimmed.length - 4) + trimmed.takeLast(4)
    }
}

/** `JSONArray` → `List<String>`（跳过空项，兼容平台偶尔插入的占位）。 */
private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        optString(index).takeIf { it.isNotBlank() }
    }
}
