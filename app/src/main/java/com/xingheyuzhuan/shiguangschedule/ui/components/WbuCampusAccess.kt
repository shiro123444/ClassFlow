package com.xingheyuzhuan.shiguangschedule.ui.components

import android.content.Context
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessAction
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessFailure
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessLayer
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuFailureDetector
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.suggestedAction
import kotlinx.coroutines.CancellationException

/**
 * 校园服务的统一访问结果。
 *
 * 设计目标：把「要不要重新登录」「要不要改用 WebVPN」「要不要就地重试」从各页面各自判断，
 * 收敛成一处策略 —— 页面只负责把 [failure] 渲染成文案和按钮。
 */
sealed interface CampusAccessResult<out T> {

    /** 成功取到数据；[useVpn] 是本次实际使用的通道。 */
    data class Ok<T>(val value: T, val useVpn: Boolean) : CampusAccessResult<T>

    /** 失败；[useVpn] 是本次实际使用的通道，用于判断是否还值得提示「改用 WebVPN」。 */
    data class Failed(
        val failure: AccessFailure,
        val useVpn: Boolean
    ) : CampusAccessResult<Nothing>
}

/**
 * 校园服务统一访问流水线（图书馆 / 成绩 / 空教室 / 学业进程 / 选课 …）：
 *
 * 1. 本地没有任何会话凭据 → 先用保存的凭据**静默重建一次**（缺密码、缺验证码就地弹小窗补齐）；
 * 2. 发请求；
 * 3. 如果失败原因是「登录态失效」→ 再静默重建一次并重试一遍（用户无感）；
 * 4. 仍失败就把**结构化的失败原因**交给页面：需要重新登录的弹 Sheet，网络类的给内联重试 +
 *    「改用 WebVPN」。
 *
 * 这样就不会再出现「开了 WebVPN 却说检查开关」「会话失效却显示没有数据」这类含糊状态。
 *
 * @param useWebVpnOnce 单次改用 WebVPN：只影响本次访问，**不写全局开关**。
 */
suspend fun <T> campusAccess(
    context: Context,
    service: CredentialService,
    flowTag: String,
    useWebVpnOnce: Boolean = false,
    block: suspend (useVpn: Boolean) -> T
): CampusAccessResult<T> {
    val savedUseVpn = WbuSyncEngine.getSavedUseVpn(context) ?: false
    val useVpn = useWebVpnOnce || savedUseVpn

    suspend fun attempt(): CampusAccessResult<T> = try {
        CampusAccessResult.Ok(block(useVpn), useVpn)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 网络类失败按「本次实际走的通道」归类：直连失败才提示「不在校园网」并给「改用 WebVPN」
        val layer = if (useVpn) AccessLayer.WebVpnPortal else AccessLayer.CampusDirect
        CampusAccessResult.Failed(WbuFailureDetector.fromThrowable(e, layer), useVpn)
    }

    // 1) 本地完全没有凭据：先静默重建（缺密码/验证码就地弹小窗，用户取消则视为 Cancelled）
    if (!WbuAuthTransport.hasLocalSession(context, service, useVpn = useVpn)) {
        val failure = silentUnifiedAuthLogin(context, flowTag, useVpn)
        if (failure != null) return CampusAccessResult.Failed(failure, useVpn)
    }

    // 2) 正常请求
    val first = attempt()
    if (first !is CampusAccessResult.Failed) return first

    // 3) 只有「登录态失效」才值得静默重建后重试；网络类问题重试一次也没有意义
    if (first.failure !is AccessFailure.SessionExpired) return first

    val failure = silentUnifiedAuthLogin(context, flowTag, useVpn)
    if (failure != null) return CampusAccessResult.Failed(failure, useVpn)
    return attempt()
}

/**
 * 用保存的凭据静默登录统一身份认证。
 *
 * - 本机没有保存统一认证密码时：弹小窗让用户输入（勾了「记住密码」会一并保存），
 *   而不是直接把用户丢进完整的登录 Sheet；
 * - 需要 WebVPN 门禁密码 / 门户短信验证码时：同样就地弹小窗补齐；
 * - 用户取消任何一步 → 返回 [AccessFailure.Cancelled]，调用方不应报错。
 *
 * @return null 表示登录成功；否则是结构化失败原因。
 */
suspend fun silentUnifiedAuthLogin(
    context: Context,
    flowTag: String,
    viaWebVpn: Boolean
): AccessFailure? {
    val studentId = WbuAuthTransport.getSavedStudentId(context)
    if (studentId.isBlank()) {
        // 连学号都没有，只能让用户去完整登录流程
        return AccessFailure.SessionExpired(AccessLayer.UnifiedAuth)
    }

    val password = WbuAuthTransport.getSavedPassword(context, CredentialService.UNIFIED_AUTH)
        ?.takeIf { it.isNotBlank() }
        ?: WbuAuthPromptBus.ask(WbuAuthPromptRequest.UnifiedAuthPassword)?.takeIf { it.isNotBlank() }
        ?: return AccessFailure.Cancelled

    val engine = WbuSyncEngine(context, useVpn = viaWebVpn)
    val ok = try {
        engine.loginUnifiedAuthOnly(
            studentId = studentId,
            password = password,
            viaWebVpn = viaWebVpn,
            flowTag = flowTag,
            vpnPasswordProvider = {
                WbuAuthTransport.getSavedVpnPassword(context)?.takeIf { it.isNotBlank() }
                    ?: WbuAuthPromptBus.ask(WbuAuthPromptRequest.VpnPassword)
            },
            smsCodeProvider = { maskedPhone, isStillValid, sendInterval, promptText ->
                WbuAuthPromptBus.ask(
                    WbuAuthPromptRequest.SmsCode(
                        maskedPhone = maskedPhone,
                        isStillValid = isStillValid,
                        sendInterval = sendInterval,
                        promptText = promptText
                    )
                )
            }
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return WbuFailureDetector.fromThrowable(
            e,
            if (viaWebVpn) AccessLayer.WebVpnPortal else AccessLayer.UnifiedAuth
        )
    }

    return if (ok) null else (engine.lastFailure ?: AccessFailure.SessionExpired(AccessLayer.UnifiedAuth))
}

/** 失败原因是否应该给一个「改用 WebVPN」按钮。 */
fun AccessFailure.shouldOfferWebVpnOnce(useVpn: Boolean): Boolean =
    suggestedAction(webVpnEnabled = useVpn) == AccessAction.UseWebVpnOnce
