package com.xingheyuzhuan.shiguangschedule.data.link

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubActionHandler
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubApplyResult
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubEnvelope
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubFact
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubType
import com.xingheyuzhuan.shiguangschedule.data.model.link.stringField
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `text`：把 `payload.text` 复制到剪贴板。
 *
 * 用于「分享一段口令/说明」这类小配置，不需要任何权限。
 */
@Singleton
class TextHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : LinkHubActionHandler {

    override val type: String = LinkHubType.TEXT

    override fun summarize(envelope: LinkHubEnvelope): List<LinkHubFact> {
        val text = envelope.payload.stringField("text").orEmpty()
        return listOf(LinkHubFact(R.string.link_hub_fact_text_preview, text.take(80)))
    }

    override suspend fun apply(envelope: LinkHubEnvelope): LinkHubApplyResult {
        val text = envelope.payload.stringField("text").orEmpty()
        if (text.isBlank()) return LinkHubApplyResult.Failed(R.string.link_hub_error_invalid)
        return runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ClassFlow", text))
        }.fold(
            onSuccess = { LinkHubApplyResult.Done(R.string.link_hub_applied_text) },
            onFailure = { LinkHubApplyResult.Failed(R.string.link_hub_error_invalid) }
        )
    }
}
