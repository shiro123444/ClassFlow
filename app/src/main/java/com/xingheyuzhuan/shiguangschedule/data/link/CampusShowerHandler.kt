package com.xingheyuzhuan.shiguangschedule.data.link

import android.content.Context
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.link.stringField
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubActionHandler
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubApplyResult
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubEnvelope
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubFact
import com.xingheyuzhuan.shiguangschedule.data.model.link.LinkHubType
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.WebAppId
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.CampusShowerEntryResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject

/**
 * `campus_shower` payload 解析（纯函数，便于单测）。
 *
 * 字段契约（`LINK_HUB_PROTOCOL.md` 第 3 节）：
 * - `system = "yktxyyy"`：马影河 1 栋「智能控水」，必填 `posno`（1~5 位数字机号）；
 * - `system = "life_service"`：马影河 2-3 栋 lifeService（水表 51），必填 `imei`，
 *   可选 `port`（多路控水器，1~8 位数字）。
 */
object CampusShowerPayload {

    const val SYSTEM_YKT_XYYY = "yktxyyy"
    const val SYSTEM_LIFE_SERVICE = "life_service"

    sealed interface Parsed {
        data class YktXyyy(val posno: String) : Parsed
        data class LifeService(val imei: String, val port: String?) : Parsed
    }

    fun parse(payload: JsonObject): Parsed? {
        return when (val system = payload.stringField("system")?.trim()?.lowercase()) {
            SYSTEM_YKT_XYYY -> {
                val posno = payload.stringField("posno")?.trim().orEmpty()
                if (posno.isEmpty() || posno.length > 5 || !posno.all { it.isDigit() }) {
                    null
                } else {
                    Parsed.YktXyyy(posno = posno.toInt().toString())
                }
            }

            SYSTEM_LIFE_SERVICE -> {
                val imei = payload.stringField("imei")?.trim().orEmpty()
                val port = payload.stringField("port")?.trim()?.takeIf { it.isNotEmpty() }
                val validPort = port == null || (port.length <= 8 && port.all { it.isDigit() })
                val validImei = imei.isNotEmpty() && imei.length <= 256 &&
                    imei.none { it.isWhitespace() || it == '$' || it == '#' }
                if (!validImei || !validPort) {
                    null
                } else {
                    Parsed.LifeService(imei = imei, port = port)
                }
            }

            else -> null
        }
    }
}

/**
 * `campus_shower`：把通用链接节点直接落到一卡通洗浴流程。
 *
 * - 服务端短码与内嵌载荷均**免确认**（[autoApply] + [autoApplyInline]），解析成功即跳转；
 * - 设备存在性仍由各自后端裁决（1 栋 `CheckKsPos`、2-3 栋 lifeService）；
 * - 该入口来自 NFC / 外部链接而非相机扫码，失败文案不含「扫描」（见 [R.string.link_hub_error_shower_invalid]）。
 */
@Singleton
class CampusShowerHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: CampusShowerEntryResolver
) : LinkHubActionHandler {

    override val type: String = LinkHubType.CAMPUS_SHOWER

    override val autoApply: Boolean = true

    override val autoApplyInline: Boolean = true

    override fun summarize(envelope: LinkHubEnvelope): List<LinkHubFact> {
        val parsed = CampusShowerPayload.parse(envelope.payload) ?: return emptyList()
        val system = when (parsed) {
            is CampusShowerPayload.Parsed.YktXyyy ->
                context.getString(R.string.link_hub_shower_system_ykt)

            is CampusShowerPayload.Parsed.LifeService ->
                context.getString(R.string.link_hub_shower_system_life)
        }
        val device = when (parsed) {
            is CampusShowerPayload.Parsed.YktXyyy -> parsed.posno
            is CampusShowerPayload.Parsed.LifeService ->
                listOfNotNull(parsed.imei, parsed.port?.let { "port $it" }).joinToString(" · ")
        }
        return listOf(
            LinkHubFact(R.string.link_hub_fact_shower_system, system),
            LinkHubFact(R.string.link_hub_fact_shower_device, device)
        )
    }

    override suspend fun apply(envelope: LinkHubEnvelope): LinkHubApplyResult {
        val parsed = CampusShowerPayload.parse(envelope.payload)
            ?: return LinkHubApplyResult.Failed(R.string.link_hub_error_invalid)

        val result = when (parsed) {
            is CampusShowerPayload.Parsed.YktXyyy -> resolver.resolveYktXyyy(parsed.posno)
            is CampusShowerPayload.Parsed.LifeService ->
                resolver.resolveLifeService(parsed.imei, parsed.port)
        }

        return when (result) {
            is CampusShowerEntryResolver.Result.Ready -> {
                // 已正证是淋浴：走原生用水页；其它设备类型继续交给网页容器
                val native = result.nativeShower
                if (native != null) {
                    LinkHubApplyResult.OpenShowerWater(
                        deviceId = native.deviceId,
                        port = native.port,
                        implid = native.implid,
                        feeitemid = native.feeitemid,
                        webFallbackUrl = result.initialUrl
                    )
                } else {
                    LinkHubApplyResult.OpenWebApp(
                        appId = WebAppId.CAMPUS_CARD.name,
                        initialUrl = result.initialUrl,
                        pendingAutoScan = result.pendingAutoScan
                    )
                }
            }

            CampusShowerEntryResolver.Result.InvalidDevice ->
                LinkHubApplyResult.Failed(R.string.link_hub_error_shower_invalid)

            CampusShowerEntryResolver.Result.NeedLogin ->
                LinkHubApplyResult.Failed(
                    R.string.err_need_unified_auth_session,
                    retryable = true,
                    needsLogin = true
                )

            is CampusShowerEntryResolver.Result.Unavailable ->
                LinkHubApplyResult.Failed(R.string.link_hub_error_network, retryable = true)
        }
    }
}
