package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 一卡通移动服务平台（新中新 berserker / 慧新e校）数据与鉴权客户端。
 *
 * 基础基址：http://yktfwpt.wbu.edu.cn
 *
 * 核心机制（基于实测研究笔记 ykt_notes.md）：
 * 1. 平台支持公网直连（无需 WebVPN 或校园网）；
 * 2. 鉴权采用金智 CAS OAuth2.0，利用统一身份认证 CASTGC 免密换取平台 JWT 令牌；
 * 3. 平台对同一账号为单活跃会话策略（最后登录者有效），但使用 refresh_token 刷新不会顶号；
 * 4. 业务访问携带 synjones-auth: bearer <access_token>。
 */
class WbuCampusCardClient(
    private val context: Context,
    val useVpn: Boolean = false
) {
    private val transport: WbuAuthTransport = WbuAuthTransport.getShared(context, useVpn)
    private val client = transport.client

    companion object {
        const val TAG = "WbuCampusCardClient"
        const val BASE_URL = "http://yktfwpt.wbu.edu.cn"
        const val RESULT_PATH = "/plat/?name=loginTransit"
        const val OAUTH_BASIC = "bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm06bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm1fc2VjcmV0"
        const val CAS_CLIENT_ID = "1524429607647793152"
    }

    data class TokenResult(
        val accessToken: String,
        val refreshToken: String?,
        val expiresIn: Long,
        val sno: String?,
        val name: String?,
        val uuid: String?
    )

    /**
     * 校验当前平台 access_token 是否仍然有效（非 401 已经在其他设备登录）。
     */
    suspend fun checkSession(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext false
        try {
            val req = Request.Builder()
                .url("$BASE_URL/berserker-base/user?refresh=1")
                .header("User-Agent", transport.authUserAgent())
                .header("Accept-Language", transport.authAcceptLanguage)
                .header("synjones-auth", "bearer $accessToken")
                .header("synAccessSource", "h5")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.code != 200) return@withContext false
                val bodyStr = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(bodyStr) }.getOrNull()
                json?.optInt("code") == 200
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkSession failed", e)
            false
        }
    }

    /**
     * 使用 refresh_token 刷新获取新的 access_token（不会顶掉其他设备！）。
     */
    suspend fun refreshToken(refreshToken: String): TokenResult? = withContext(Dispatchers.IO) {
        if (refreshToken.isBlank()) return@withContext null
        try {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("scope", "all")
                .add("logintype", "sso")
                .build()

            val req = Request.Builder()
                .url("$BASE_URL/berserker-auth/oauth/token")
                .header("User-Agent", transport.authUserAgent())
                .header("Accept-Language", transport.authAcceptLanguage)
                .header("Authorization", "Basic $OAUTH_BASIC")
                .header("synAccessSource", "h5")
                .post(form)
                .build()

            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (resp.code != 200) {
                    Log.w(TAG, "refreshToken returned HTTP ${resp.code}: $bodyStr")
                    return@withContext null
                }
                val json = JSONObject(bodyStr)
                val token = json.optString("access_token")
                if (token.isNullOrBlank()) return@withContext null

                TokenResult(
                    accessToken = token,
                    refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken,
                    expiresIn = json.optLong("expires_in", 6047999),
                    sno = json.optString("sno").takeIf { it.isNotBlank() },
                    name = json.optString("name").takeIf { it.isNotBlank() },
                    uuid = json.optString("uuid").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshToken exception", e)
            null
        }
    }

    /**
     * 主动向平台发起登出请求（使服务端作废该 access_token 会话）。
     */
    suspend fun logout(accessToken: String) = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext
        try {
            val url = "$BASE_URL/berserker-base/redirect?type=logout&synjones-auth=$accessToken&loginFrom=h5&synAccessSource=h5"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", transport.authUserAgent())
                .header("Accept-Language", transport.authAcceptLanguage)
                .get()
                .build()

            client.newBuilder().followRedirects(false).build().newCall(req).execute().close()
        } catch (e: Exception) {
            Log.w(TAG, "logout exception", e)
        }
    }

    /**
     * 使用统一身份认证 (CASTGC) 免密换取一卡通移动服务平台 Token。
     *
     * 遵循 ykt_notes.md 的 CAS OAuth2 链路：
     * 1. GET /berserker-auth/cas/oauth2?resultUrl=...
     * 2. CAS oauth2.0/authorize -> login?service=... (带 CASTGC 换 ST)
     * 3. oauth2.0/callbackAuthorize -> 若首次需要则提交授权确认页 -> 重定向回 /cas/oauth2url 换取 ticket
     * 4. POST /berserker-auth/oauth/token (username=ticket, password=ticket, grant_type=password, logintype=sso)
     */
    suspend fun loginWithCasTgc(): TokenResult = withContext(Dispatchers.IO) {
        transport.restoreCookieStore()

        val hasTgc = transport.cookieStore.any { it.name == "CASTGC" && it.value.isNotBlank() }
        if (!hasTgc) {
            throw WbuSessionExpiredException(message = "统一身份认证已失效，请重新登录")
        }

        val noRedirectClient = client.newBuilder().followRedirects(false).build()

        val startUrl = "$BASE_URL/berserker-auth/cas/oauth2?resultUrl=${URLEncoder.encode(BASE_URL + RESULT_PATH, "UTF-8")}"
        var currentUrl = startUrl
        var ticket: String? = null
        var consentHandled = false

        for (hop in 0 until 15) {
            val req = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", transport.authUserAgent())
                .header("Accept-Language", transport.authAcceptLanguage)
                .get()
                .build()

            val resp = noRedirectClient.newCall(req).execute()
            val code = resp.code
            val location = resp.header("Location").orEmpty()
            val body = resp.body?.string().orEmpty()
            resp.close()

            ticket = pickTicket(currentUrl, location) ?: ticket

            if (code in 300..399 && location.isNotBlank()) {
                val nextUrl = currentUrl.toHttpUrlOrNull()?.resolve(location)?.toString() ?: location
                currentUrl = nextUrl
                continue
            }

            // 检查是否为 OAuth2 授权确认页（首次登录可能需要）
            if (!consentHandled && body.contains("scope") && body.contains("<form")) {
                val posted = trySubmitConsent(body, currentUrl)
                if (posted != null) {
                    consentHandled = true
                    val (postLocation, _) = posted
                    ticket = pickTicket(postLocation) ?: ticket
                    if (postLocation.isNotBlank()) {
                        val nextUrl = currentUrl.toHttpUrlOrNull()?.resolve(postLocation)?.toString() ?: postLocation
                        currentUrl = nextUrl
                        continue
                    }
                }
            }

            if (!ticket.isNullOrBlank()) {
                break
            }

            if (body.contains("pwdFromId") || body.contains("pwdEncryptSalt") || body.contains("authserver/login")) {
                throw WbuSessionExpiredException(message = "统一身份认证凭据已失效，请重新登录")
            }

            throw IOException("未取到一卡通 SSO 票据 (终止于 $currentUrl, status=$code)")
        }

        val finalTicket = ticket ?: throw IOException("SSO 重定向链结束仍未获取到有效票据")

        // 用 ticket 换平台 access_token
        val form = FormBody.Builder()
            .add("username", finalTicket)
            .add("password", finalTicket)
            .add("grant_type", "password")
            .add("scope", "all")
            .add("loginFrom", "h5")
            .add("logintype", "sso")
            .add("device_token", "h5")
            .build()

        val tokenReq = Request.Builder()
            .url("$BASE_URL/berserker-auth/oauth/token")
            .header("User-Agent", transport.authUserAgent())
            .header("Accept-Language", transport.authAcceptLanguage)
            .header("Authorization", "Basic $OAUTH_BASIC")
            .header("synAccessSource", "h5")
            .post(form)
            .build()

        val tokenResp = client.newCall(tokenReq).execute()
        val tokenBody = tokenResp.body?.string().orEmpty()
        tokenResp.close()

        val json = runCatching { JSONObject(tokenBody) }.getOrNull()
            ?: throw IOException("换取一卡通令牌失败: $tokenBody")

        val accessToken = json.optString("access_token")
        if (accessToken.isNullOrBlank()) {
            val msg = json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optString("msg").takeIf { it.isNotBlank() } ?: "未知错误"
            throw IOException("换取一卡通令牌失败: $msg")
        }

        TokenResult(
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresIn = json.optLong("expires_in", 6047999),
            sno = json.optString("sno").takeIf { it.isNotBlank() },
            name = json.optString("name").takeIf { it.isNotBlank() },
            uuid = json.optString("uuid").takeIf { it.isNotBlank() }
        )
    }

    private fun pickTicket(vararg urls: String): String? {
        for (u in urls) {
            if (u.isBlank() || !u.contains("loginTransit")) continue
            val match = Regex("[?&]ticket=([^&]+)").find(u)
            if (match != null) {
                val rawTicket = match.groupValues[1]
                return URLDecoder.decode(rawTicket, "UTF-8")
            }
        }
        return null
    }

    private fun trySubmitConsent(html: String, pageUrl: String): Pair<String, String>? {
        val formMatch = Regex("<form[^>]*action=\"([^\"]+)\"[^>]*>(.*?)</form>", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return null
        val action = formMatch.groupValues[1].replace("&amp;", "&")
        val formBody = formMatch.groupValues[2]

        val formBuilder = FormBody.Builder()
        val inputMatches = Regex("<input[^>]*>").findAll(formBody)
        for (inp in inputMatches) {
            val text = inp.value
            val name = Regex("name=\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: continue
            val value = Regex("value=\"([^\"]*)\"").find(text)?.groupValues?.get(1).orEmpty()
            val type = Regex("type=\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: "text"

            if (type == "hidden" || (type == "checkbox" && text.contains("checked"))) {
                formBuilder.add(name, value)
            }
        }

        val targetUrl = pageUrl.toHttpUrlOrNull()?.resolve(action)?.toString() ?: action
        val req = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", transport.authUserAgent())
            .header("Accept-Language", transport.authAcceptLanguage)
            .header("Referer", pageUrl)
            .post(formBuilder.build())
            .build()

        val resp = client.newBuilder().followRedirects(false).build().newCall(req).execute()
        val loc = resp.header("Location").orEmpty()
        val body = resp.body?.string().orEmpty()
        resp.close()
        return loc to body
    }

    /**
     * 构造供 WebView 打开的一卡通平台主页 URL（注入 ?synjones-auth=<access_token>）。
     */
    fun buildLaunchUrl(accessToken: String): String {
        return "$BASE_URL/plat/?synjones-auth=$accessToken"
    }

    /**
     * 解析 JWT Payload（学号与姓名）。
     */
    fun decodeJwt(token: String): JSONObject? {
        return runCatching {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = parts[1]
            val padded = payload + "=".repeat((-payload.length) % 4)
            val decodedBytes = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
            JSONObject(String(decodedBytes, Charsets.UTF_8))
        }.getOrNull()
    }
}
