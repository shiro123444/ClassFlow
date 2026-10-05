package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import android.net.Uri
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import org.json.JSONObject

/**
 * 页面 `$config.pkey` 的加密实现（复刻 `js/chunk-2a43.*.js` 中 module `ea79`）。
 *
 * crypto-js 原始实现：
 * ```js
 * var r = CryptoJS.enc.Base64.parse(pkey);
 * var n = CryptoJS.enc.Utf8.parse(plain);
 * CryptoJS.AES.encrypt(n, r, { mode: ECB, padding: Pkcs7 }).toString();
 * ```
 * 即 `AES-128/ECB/PKCS7`，key 为 pkey 的 Base64 解码字节，输出标准 Base64。
 */
object YktXyyyCipher {

    private const val PKEY_BASE64 = "3n4DdO47LWH2Co/WfpbdyA=="

    fun encrypt(plain: String): String {
        val key = Base64.getDecoder().decode(PKEY_BASE64)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }
}

/**
 * 马影河 1 栋「智能控水」（yktxyyy）设备码服务端校验客户端。
 *
 * 全局扫码时，1 栋控水器是裸码，仅凭格式无法与商品条码等区分，
 * 因此复用 H5 的完整鉴权链后调用 `api/CheckKsPos` 做正证：
 * 1. 一卡通平台 access_token → `/berserker-base/redirect?appId=62` 取一次性 ticket；
 * 2. `POST /accountapi/berserker-base/user/Sso/SsoCheck`（multipart `ticket`）→ 水控账号 ano；
 * 3. `GET /waterapi/api/GetToken?info=AES(JSON{userid,userpassword,time})` → 水控 Token；
 * 4. `GET /waterapi/api/CheckKsPos?info=AES(JSON{posno,ano})&token=...`
 *    - `RetNo == 0` → 设备存在（`CostDsp` 为所属区域名，如「马影河宿舍1栋」）；
 *    - `RetNo == -4` → 无效机号。
 *
 * 注意：ticket 是一次性的；本客户端只负责“校验”，WebView 启动用的 ticket
 * 必须由调用方重新 `resolveAppLaunchUrl` 获取。
 */
class WbuYktXyyyClient(
    context: Context,
    private val cardClient: WbuCampusCardClient = WbuCampusCardClient(
        context.applicationContext,
        useVpn = false
    )
) {

    private val appContext = context.applicationContext
    private val transport = WbuAuthTransport.getShared(appContext, useVpn = false)
    private val client = transport.client

    companion object {
        const val TAG = "WbuYktXyyyClient"

        /** 独立控水基础地址。 */
        const val BASE_URL = "http://yktxyyy.wbu.edu.cn:5001"

        /** 平台为「马1栋洗浴」分配的 appId。 */
        const val APP_ID = 62

        /** H5 内写死的用户密码（`getEncryptInfo` 中的 `userpassword`）。 */
        private const val USER_PASSWORD = "kv7XjPzrDNJY0pdZ#"

        private const val SSO_CHECK_PATH = "/accountapi/berserker-base/user/Sso/SsoCheck"
    }

    sealed interface CheckResult {
        /** 服务端确认设备存在；[classLabel] 为返回的所属区域名。 */
        data class Valid(val classLabel: String?) : CheckResult

        /** 服务端明确判定机号无效。 */
        data object Invalid : CheckResult

        /** 网络/鉴权异常，无法判定。 */
        data class Unavailable(val message: String?) : CheckResult
    }

    /**
     * 校验 5 位机号是否为真实在用的控水设备。
     *
     * @throws WbuSessionExpiredException 统一认证会话失效，调用方应转为登录。
     */
    suspend fun checkPosNo(posno: String): CheckResult = withContext(Dispatchers.IO) {
        try {
            val accessToken = cardClient.ensureValidAccessToken()

            // 1. 平台 SSO 一次性 ticket（校验专用）
            val launchUrl = cardClient.resolveAppLaunchUrl(APP_ID, accessToken)
            val ticket = launchUrl
                ?.let { runCatching { Uri.parse(it).getQueryParameter("ticket") }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
                ?: return@withContext CheckResult.Unavailable("未获取到智能控水登录票据")

            // 2. 换取水控账号
            val account = ssoCheck(ticket)
                ?: return@withContext CheckResult.Unavailable("智能控水免密登录失败")

            // 3. 水控 Token
            val waterToken = getWaterToken(account)
                ?: return@withContext CheckResult.Unavailable("智能控水令牌获取失败")

            // 4. 机号正证
            val json = getJson(
                url = "$BASE_URL/waterapi/api/CheckKsPos",
                params = mapOf(
                    "info" to YktXyyyCipher.encrypt("""{"posno":"$posno","ano":"$account"}"""),
                    "token" to waterToken
                )
            ) ?: return@withContext CheckResult.Unavailable("水控服务器无响应")

            val retNo = json.optInt("RetNo", Int.MIN_VALUE)
            val retDsp = json.optString("RetDsp")
            when {
                retNo == 0 -> CheckResult.Valid(json.optString("CostDsp").takeIf { it.isNotBlank() })
                retNo == -4 || retDsp.contains("无效") -> CheckResult.Invalid
                else -> CheckResult.Unavailable(retDsp.takeIf { it.isNotBlank() })
            }
        } catch (e: WbuSessionExpiredException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "checkPosNo($posno) failed", e)
            CheckResult.Unavailable(e.localizedMessage)
        }
    }

    /** `SsoCheck`：ticket 换水控账号 ano。 */
    private fun ssoCheck(ticket: String): String? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("ticket", ticket)
            .build()

        val request = Request.Builder()
            .url(BASE_URL + SSO_CHECK_PATH)
            .header("User-Agent", transport.authUserAgent())
            .header("Accept-Language", transport.authAcceptLanguage)
            .post(body)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                    ?: return@use null
                if (json.optString("Ret") != "0") return@use null
                val user = json.optJSONObject("User") ?: return@use null
                // 服务端同时返回 ACCOUNT / Account 两种大小写
                user.optString("ACCOUNT")
                    .ifBlank { user.optString("Account") }
                    .takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** `GetToken`：ano 换水控 Token。 */
    private fun getWaterToken(account: String): String? {
        val time = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        val plain = JSONObject()
            .put("userid", account)
            .put("userpassword", USER_PASSWORD)
            .put("time", time)
            .toString()
        val json = getJson(
            url = "$BASE_URL/waterapi/api/GetToken",
            params = mapOf("info" to YktXyyyCipher.encrypt(plain))
        ) ?: return null
        if (json.optInt("RetNo", Int.MIN_VALUE) != 0) return null
        return json.optString("Token").takeIf { it.isNotBlank() }
    }

    /** 发送 GET 请求并解析 JSON（baseUrl 相对路径 waterapi/api 下）。 */
    private fun getJson(url: String, params: Map<String, String>): JSONObject? {
        val httpUrl = url.toHttpUrlOrNull()
            ?.newBuilder()
            ?.apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
            ?.build()
            ?: return null

        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", transport.authUserAgent())
            .header("Accept-Language", transport.authAcceptLanguage)
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            }
        }.getOrNull()
    }
}
