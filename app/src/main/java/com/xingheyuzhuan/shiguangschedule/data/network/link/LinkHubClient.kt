package com.xingheyuzhuan.shiguangschedule.data.network.link

import com.xingheyuzhuan.shiguangschedule.BuildConfig
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubEnvelope
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubProtocol
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 服务端短码节点（线格式 A）的取回结果。
 *
 * 服务端契约见 `LINK_HUB_PROTOCOL.md` 第 3 节。
 */
sealed interface LinkHubFetchResult {

    /** 拿到合法信封（动作类型是否认识由 UI / handler 注册表判断）。 */
    data class Ok(val envelope: LinkHubEnvelope) : LinkHubFetchResult

    /**
     * 服务端没按 JSON 返回（`/url/` 被别的页面接走了、或部署还没更新）。
     *
     * 注意：**不要**拿这个响应去开内置 WebView —— 那种页面往往自己会跳 `intent://`，
     * WebView 不认识该 scheme，只会渲染出「网页无法打开 / ERR_UNKNOWN_URL_SCHEME」。
     * 由 UI 提示 + 重试，并提供「用浏览器打开」的逃生口即可。
     */
    data object NotJson : LinkHubFetchResult

    /** 节点不存在（404）。 */
    data object NotFound : LinkHubFetchResult

    /** 已撤销 / 已过期（410）。 */
    data object Expired : LinkHubFetchResult

    /** 服务端协议版本高于本 App 认识的范围。 */
    data object UnsupportedVersion : LinkHubFetchResult

    /** 网络异常 / 超时 / 重定向到站外等，可重试。 */
    data object NetworkError : LinkHubFetchResult

    /** 服务端返回了非 2xx（除 404/410），可重试。 */
    data class ServerError(val status: Int) : LinkHubFetchResult

    /** 2xx 但响应体不是可解析的信封，可重试。 */
    data object Malformed : LinkHubFetchResult
}

/**
 * 响应分类：把「状态码 + Content-Type + 响应体」映射成 [LinkHubFetchResult]。
 *
 * 抽成纯函数便于单测（不需要网络）。
 */
object LinkHubResponseClassifier {

    private val json = Json { ignoreUnknownKeys = true }

    fun classify(status: Int, contentType: String?, body: String): LinkHubFetchResult {
        when (status) {
            404 -> return LinkHubFetchResult.NotFound
            410 -> return LinkHubFetchResult.Expired
        }
        if (status !in 200..299) return LinkHubFetchResult.ServerError(status)

        val looksJson = contentType?.contains("json", ignoreCase = true) == true
        if (!looksJson) return LinkHubFetchResult.NotJson

        val envelope = runCatching { json.decodeFromString<LinkHubEnvelope>(body) }.getOrNull()
            ?: return LinkHubFetchResult.Malformed
        if (envelope.v != LinkHubProtocol.VERSION) return LinkHubFetchResult.UnsupportedVersion
        if (envelope.type.isBlank()) return LinkHubFetchResult.Malformed
        return LinkHubFetchResult.Ok(envelope)
    }
}

/**
 * 通用链接节点的服务端客户端（线格式 A）。
 *
 * 约定：
 * - 只请求 [LinkHubUrl.serverBase] 下的 `/url/{code}`，带 `Accept: application/json` 与 App UA；
 * - **不跟随重定向**（避免被引到站外/内网），3xx 视为失败；
 * - 不缓存（撤销语义优先），不把响应体写进日志（可能含代理口令）。
 */
@Singleton
class LinkHubClient @Inject constructor() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun fetch(code: String, origin: String? = null): LinkHubFetchResult = withContext(Dispatchers.IO) {
        // 走 /api/ 读接口，而不是分享链接本身：`/url/{code}` 通常被浏览器侧的落地页接管，
        // 做不了 Accept 协商，App 会拿到 HTML（详见 buildApiUrl 的注释）
        val url = LinkHubUrl.buildApiUrl(code = code, origin = origin)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "ClassFlow/${BuildConfig.VERSION_NAME} (Android)")
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type")
                val body = response.body.string()
                LinkHubResponseClassifier.classify(
                    status = response.code,
                    contentType = contentType,
                    body = body
                )
            }
        }.getOrElse { LinkHubFetchResult.NetworkError }
    }
}
