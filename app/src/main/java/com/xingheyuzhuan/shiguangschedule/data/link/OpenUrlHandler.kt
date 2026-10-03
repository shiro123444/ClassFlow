package com.xingheyuzhuan.shiguangschedule.data.link

import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubActionHandler
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubApplyResult
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubEnvelope
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubFact
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubType
import com.xingheyuzhuan.shiguangschedule.data.model.link.stringField
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * `open`：在内置 WebView 打开 `payload.url`。
 *
 * 只接受 http/https；确认卡片会展示目标域名，实际打开仍需用户点「应用」。
 */
@Singleton
class OpenUrlHandler @Inject constructor() : LinkHubActionHandler {

    override val type: String = LinkHubType.OPEN

    override fun summarize(envelope: LinkHubEnvelope): List<LinkHubFact> {
        val url = envelope.payload.stringField("url")?.trim().orEmpty()
        val host = url.toHttpUrlOrNull()?.host ?: return emptyList()
        return listOf(LinkHubFact(R.string.link_hub_fact_target_host, host))
    }

    override suspend fun apply(envelope: LinkHubEnvelope): LinkHubApplyResult {
        val url = envelope.payload.stringField("url")?.trim().orEmpty()
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || (parsed.scheme != "http" && parsed.scheme != "https")) {
            return LinkHubApplyResult.Failed(R.string.link_hub_error_invalid)
        }
        return LinkHubApplyResult.OpenUrl(url)
    }
}
