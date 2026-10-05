package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

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
     * 马影河 2-3 栋：水表 51 设备号（+ 可选端口）→ lifeService 深链。
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

        /**
         * 2-3 栋页面 `handleImei` 等价原文：
         * `http://4gsk.shuibiao51.com?id=<imei>`，多路设备再拼 `$#$<port>`。
         *
         * 只接受无空白、无 `$`/`#` 的设备号，避免污染页面解析。
         */
        fun buildLifeServiceRaw(imei: String, port: String? = null): String? {
            val id = imei.trim()
            if (id.isEmpty() || id.length > 256) return null
            if (id.any { it.isWhitespace() || it == '$' || it == '#' }) return null

            val base = "http://4gsk.shuibiao51.com?id=$id"
            val portPart = port?.trim().orEmpty()
            if (portPart.isEmpty()) return base
            if (portPart.length > 8 || !portPart.all { it.isDigit() }) return null
            return base + "\$#\$" + portPart
        }
    }
}
