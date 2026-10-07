package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 洗浴控水入口解析器（全局扫码与通用链接节点共用）。
 *
 * 把「一卡通鉴权 → 设备正证 → 生成带票据深链」的流程收在一处，
 * 调用方只关心结果，不再各自拼 URL / 调接口：
 * - 1 栋（智能控水）：`CheckKsPos` 正证机号 → 平台换取一次性 ticket → 深链 yktxyyy；
 * - 2-3 栋（lifeService）：平台换取带 `synjones-auth` 的入口地址 → 追加 `scanResult` 原文。
 */
@Singleton
class CampusShowerEntryResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    sealed interface Result {
        /** 已生成可直接加载的深链；[pendingAutoScan] 非空时交给 WebAppScreen 自动执行。 */
        data class Ready(val initialUrl: String, val pendingAutoScan: String?) : Result

        /** 服务端明确判定设备机号无效。 */
        data object InvalidDevice : Result

        /** 网络 / 票据等异常，无法判定。 */
        data class Unavailable(val message: String?) : Result

        /** 本机统一认证会话不可用，需要先登录。 */
        data object NeedLogin : Result
    }

    /**
     * 马影河 1 栋：服务端正证 + 取启动票据。
     *
     * 注意：正证会消耗一张 ticket，启动前必须重新取一张。
     */
    suspend fun resolveYktXyyy(posno: String): Result {
        return try {
            val cardClient = WbuCampusCardClient(context, useVpn = false)
            val yktClient = WbuYktXyyyClient(context, cardClient)
            when (val check = yktClient.checkPosNo(posno)) {
                is WbuYktXyyyClient.CheckResult.Valid -> {
                    val token = cardClient.ensureValidAccessToken()
                    val launchUrl = cardClient.resolveAppLaunchUrl(WbuYktXyyyClient.APP_ID, token)
                    if (launchUrl.isNullOrBlank()) {
                        Result.Unavailable("未获取到智能控水入口")
                    } else {
                        Result.Ready(
                            initialUrl = launchUrl,
                            pendingAutoScan = ShowerQrLink.syntheticYktRaw(posno)
                        )
                    }
                }

                WbuYktXyyyClient.CheckResult.Invalid -> Result.InvalidDevice

                is WbuYktXyyyClient.CheckResult.Unavailable -> Result.Unavailable(check.message)
            }
        } catch (e: WbuSessionExpiredException) {
            Result.NeedLogin
        } catch (e: Exception) {
            Result.Unavailable(e.localizedMessage)
        }
    }

    /**
     * 马影河 2-3 栋：水表设备号（+ 可选端口）→ lifeService 深链。
     *
     * 设备号既可以是裸设备号，也可以是整条厂商 / 学校入口链接（`?id=` 载体，协议允许）；
     * 原文怎么拼见 [buildLifeServiceRaw]。
     */
    suspend fun resolveLifeService(imei: String, port: String? = null): Result {
        val raw = buildLifeServiceRaw(imei, port) ?: return Result.InvalidDevice
        return resolveLifeServiceRaw(raw)
    }

    /**
     * 马影河 2-3 栋：直接把扫码原文 / 服务端原文交给 lifeService 页面。
     */
    suspend fun resolveLifeServiceRaw(raw: String): Result {
        return try {
            val cardClient = WbuCampusCardClient(context, useVpn = false)
            val token = cardClient.ensureValidAccessToken()
            val launchUrl = cardClient.resolveAppLaunchUrl(ShowerQrLink.LIFE_SERVICE_APP_ID, token)
            if (launchUrl.isNullOrBlank()) {
                Result.Unavailable("未获取到生活服务入口")
            } else {
                val separator = if (launchUrl.contains("?")) "&" else "?"
                val encoded = URLEncoder.encode(raw, "UTF-8")
                Result.Ready(
                    initialUrl = "$launchUrl$separator" + "scanResult=$encoded",
                    pendingAutoScan = null
                )
            }
        } catch (e: WbuSessionExpiredException) {
            Result.NeedLogin
        } catch (e: Exception) {
            Result.Unavailable(e.localizedMessage)
        }
    }

    companion object {

        /** 水表 51 厂商链接基底（页面手输入口生成的原文形态）。 */
        private const val VENDOR_BASE = "http://4gsk.shuibiao51.com"

        /** 设备号长度上限。 */
        private const val MAX_CODE_LENGTH = 256

        /** 端口位数上限。 */
        private const val MAX_PORT_DIGITS = 8

        /**
         * 2-3 栋页面 `handleImei` 等价原文（`applications/js/chunk-b88c7b1e.f1981e59.js`）。
         *
         * 页面两个分支的语义不同，原文必须按分支喂：
         * - **不带端口**：页面走 `imei = getRequest(原文).id`，所以原文要是带 `?id=` 的链接；
         * - **带端口**：页面走 `imei = 第一段原文本身`（**不再解析 URL**），所以第一段必须是
         *   **裸设备号** —— 把厂商链接套在第一段会得到 `http://…?id=<整条链接>$#$2` 这种原文，
         *   页面会把整条链接当 imei 交给 `getDevicesType`，服务端认不出设备。
         *
         * 设备号本身是链接（协议允许把整条厂商链接当设备号）时按页面规则取其中的 `id`：
         * 不带端口直接**原文透传**（与扫码路径一致），带端口则用取到的 id 组 `<id>$#$<port>`。
         *
         * 只接受无空白、无 `$`/`#` 的设备号，避免污染页面解析。
         */
        fun buildLifeServiceRaw(imei: String, port: String? = null): String? {
            val code = imei.trim()
            if (code.isEmpty() || code.length > MAX_CODE_LENGTH) return null
            if (code.any { it.isWhitespace() || it == '$' || it == '#' }) return null

            val url = code.toHttpUrlOrNull()
            val deviceId = if (url == null) code else url.queryParameter("id")?.trim()
            if (deviceId.isNullOrEmpty()) return null
            if (deviceId.any { it.isWhitespace() || it == '$' || it == '#' }) return null

            val portPart = port?.trim().orEmpty()
            if (portPart.isEmpty()) {
                // 不带端口：链接原样透传（页面自己取 ?id=），裸设备号才补厂商链接
                return if (url == null) "$VENDOR_BASE?id=$deviceId" else code
            }
            if (portPart.length > MAX_PORT_DIGITS || !portPart.all { it.isDigit() }) return null
            return deviceId + ShowerQrLink.IMEI_PORT_SEPARATOR + portPart
        }
    }
}
