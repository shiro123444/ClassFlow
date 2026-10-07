package com.xingheyuzhuan.shiguangschedule.ui.components

import android.content.Context
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessAction
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessFailure
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessLayer
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.CampusChannelDecision
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuFailureDetector
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.replanCampusChannel
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.resolveCampusChannel
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.resolveCampusUseVpn
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuQueryClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.confirmedPasswordRejectionLayer
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.suggestedAction
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.unreachableOnDirectChannel
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
 * 3. 直连**一个响应都没拿到**、而这条通道又是探测定的 → 作废探测缓存**重算通道**（判据可能已经过期：
 *    刚走出校园网 WiFi、或者这条链路根本到不了学校），翻成 WebVPN 就按新通道再走一遍；
 * 4. 如果失败原因是「登录态失效」→ 再静默重建一次并重试一遍（用户无感）；
 * 5. 仍失败就把**结构化的失败原因**交给页面：需要重新登录的弹 Sheet，网络类的给内联重试 +
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
    val savedUseVpn = WbuSyncEngine.getSavedUseVpn(context)
    // 需要校园网：开了「自动校园网探测」就先快速探一次 —— 人在校园网里就直连，不绕 WebVPN。
    // 「本次改用 WebVPN」是用户的显式指定，不再探测。
    val decision = if (useWebVpnOnce) {
        CampusChannelDecision(useVpn = true, decidedByProbe = false)
    } else {
        resolveCampusChannel(context, savedUseVpn)
    }
    var useVpn = decision.useVpn

    suspend fun attempt(): CampusAccessResult<T> = try {
        CampusAccessResult.Ok(block(useVpn), useVpn)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 网络类失败按「本次实际走的通道」归类：直连失败才提示「不在校园网」并给「改用 WebVPN」
        val layer = if (useVpn) AccessLayer.WebVpnPortal else AccessLayer.CampusDirect
        CampusAccessResult.Failed(WbuFailureDetector.fromThrowable(e, layer), useVpn)
    }

    /** 本地没有该通道的会话就先静默重建（缺密码/验证码就地弹小窗，用户取消则视为 Cancelled）。 */
    suspend fun ensureLocalSession(): AccessFailure? =
        if (WbuAuthTransport.hasLocalSession(context, service, useVpn = useVpn)) {
            null
        } else {
            silentUnifiedAuthLogin(context, flowTag, useVpn, service = service)
        }

    // 1) 本地完全没有凭据：先静默重建
    ensureLocalSession()?.let { return CampusAccessResult.Failed(it, useVpn) }

    // 2) 正常请求
    var first = attempt()
    if (first !is CampusAccessResult.Failed) return first

    // 3) 直连连一个响应都没拿到，而通道是探测定的：重算一次通道，翻成 WebVPN 就按新通道再走一遍。
    //    这一步专治「判据过期」：短缓存里可能还写着「在校园网」，人却已经走出了 WiFi 范围。
    if (decision.decidedByProbe && first.failure.unreachableOnDirectChannel) {
        val replanned = replanCampusChannel(context, savedUseVpn)
        if (replanned.useVpn != useVpn) {
            useVpn = replanned.useVpn
            ensureLocalSession()?.let { return CampusAccessResult.Failed(it, useVpn) }
            first = attempt()
            if (first !is CampusAccessResult.Failed) return first
        }
    }

    // 4) 只有「登录态失效」才值得静默重建后重试；网络类问题重试一次也没有意义
    if (first.failure !is AccessFailure.SessionExpired) return first

    val failure = silentUnifiedAuthLogin(context, flowTag, useVpn, service = service)
    if (failure != null) return CampusAccessResult.Failed(failure, useVpn)
    return attempt()
}

/**
 * 新增静默场景的统一闸门：**开关开着、且本机确实存着统一认证密码**才值得自动登一次。
 *
 * 为什么要求「确实存着密码」：这条新增路径的语义是「用保存的密码登录」。
 * 如果本机没存密码，自动登录只能靠弹窗现场问用户 —— 那就不该是「进页面自动做」的动作，
 * 保持原有入口（登录 Sheet / 登录按钮）让用户自己决定更合适。
 *
 * 既有静默路径（U净出水、网页应用换票、付款码换票、图书/成绩/选课等校园服务）不受它影响：
 * 它只管这个开关新纳入的场景。
 */
fun shouldAttemptSavedPasswordLogin(context: Context): Boolean =
    WbuAuthTransport.isAutoLoginWithSavedPasswordEnabled(context) &&
        !WbuAuthTransport.getSavedPassword(context, CredentialService.UNIFIED_AUTH).isNullOrBlank()

/**
 * 用保存的凭据静默登录统一身份认证。
 *
 * - 本机没有保存统一认证密码时：弹小窗让用户输入（勾了「记住密码」会一并保存），
 *   而不是直接把用户丢进完整的登录 Sheet；
 * - 需要 WebVPN 门禁密码 / 门户短信验证码时：同样就地弹小窗补齐；
 * - 需要人机校验（门户图形验证码 / 统一认证滑块）时：就地弹对应的校验小窗，补一次即可继续；
 * - 用户取消任何一步 → 返回 [AccessFailure.Cancelled]，调用方不应报错；
 * - 服务端**确认**密码错 → 返回失败前先忘掉那份被拒的保存密码，让随后弹出的登录 Sheet 是空的。
 *
 * @param onlyWithSavedPassword 本机没存统一认证密码时**直接放弃**（返回
 *   [AccessFailure.SessionExpired]）而不弹小窗问用户。既有静默路径（U净、网页应用）用它保持
 *   「没存密码就回到登录面板」的老行为；校园服务流水线则保持 false（缺密码就地补一次更省事）。
 * @param service 目标服务。传了它就是「不只是拿到统一认证会话」：登录成功后还会把这个服务**自己的**
 *   会话建起来（教务要拿 CASTGC 换 `jw_uf`、图书馆要拿它换 OPAC 的 `PHPSESSID`）。少了这一步就会出现
 *   「静默登录明明成功了，成绩 / 学业进程 / 空教室还是提示登录已过期」，最后把用户顶进登录面板。
 * @return null 表示登录成功；否则是结构化失败原因。
 */
suspend fun silentUnifiedAuthLogin(
    context: Context,
    flowTag: String,
    viaWebVpn: Boolean,
    onlyWithSavedPassword: Boolean = false,
    service: CredentialService? = null
): AccessFailure? {
    val studentId = WbuAuthTransport.getSavedStudentId(context)
    if (studentId.isBlank()) {
        // 连学号都没有，只能让用户去完整登录流程
        return AccessFailure.SessionExpired(AccessLayer.UnifiedAuth)
    }

    val savedPassword = WbuAuthTransport.getSavedPassword(context, CredentialService.UNIFIED_AUTH)
        ?.takeIf { it.isNotBlank() }
    val password = savedPassword
        ?: if (onlyWithSavedPassword) {
            return AccessFailure.SessionExpired(AccessLayer.UnifiedAuth)
        } else {
            WbuAuthPromptBus.ask(WbuAuthPromptRequest.UnifiedAuthPassword)?.takeIf { it.isNotBlank() }
        }
        ?: return AccessFailure.Cancelled

    val engine = WbuSyncEngine(context, useVpn = viaWebVpn)
    // 人机校验就地过：门户图形验证码 / 统一认证滑块都是「补一次就能继续」的东西，
    // 不该因为它们把用户踢进完整的登录 Sheet（更不该顺手清掉保存的密码）。
    engine.portalCaptchaProvider = { captcha -> WbuAuthPromptBus.askPortalCaptcha(captcha) }
    // 短信小窗的「重新发送」要打到本次登录的引擎上；重发冷却秒数以服务端为准
    WbuAuthPromptBus.onResendSmsCode = {
        runCatching { engine.resendVpnSmsCode() }
            .getOrNull()
            ?.takeIf { it.success }
            ?.cooldownSeconds
    }
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
            },
            captchaProvider = { captcha -> WbuAuthPromptBus.askSlider(captcha) }
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return WbuFailureDetector.fromThrowable(
            e,
            if (viaWebVpn) AccessLayer.WebVpnPortal else AccessLayer.UnifiedAuth
        )
    } finally {
        WbuAuthPromptBus.onResendSmsCode = null
    }

    if (!ok) {
        val failure = engine.lastFailure ?: AccessFailure.SessionExpired(AccessLayer.UnifiedAuth)
        // 只有服务端明确说「密码不对」才忘掉保存的密码；验证码错、原因不明的拒绝、网络问题一律不动它。
        if (failure.confirmedPasswordRejectionLayer != null) {
            WbuAuthTransport.forgetRejectedPassword(context, password)
        }
        return failure
    }

    // 拿到 CASTGC 只是门票：调用方点名的那个服务，它自己的会话还没建起来
    return establishServiceSession(context, engine, service, flowTag)
}

/**
 * 统一认证登录成功后，把**服务自己的**会话也建起来。
 *
 * 统一认证（CASTGC）只是门票：教务要拿它换 `jw_uf`，图书馆要拿它换 OPAC 的 `PHPSESSID`。
 * 过去静默登录走到「拿到 CASTGC」就收工，于是会话过期后成绩 / 学业进程 / 空教室这些页面
 * 会出现「静默登录成功了、页面还是说登录已过期」，最后把用户顶进登录面板。
 */
private suspend fun establishServiceSession(
    context: Context,
    engine: WbuSyncEngine,
    service: CredentialService?,
    flowTag: String
): AccessFailure? = when (service) {
    CredentialService.JIAOWU -> {
        val ok = runCatching { engine.exchangeCastgcForJwxtSession(flowTag) }.getOrDefault(false)
        if (ok) null else (engine.lastFailure ?: AccessFailure.SessionExpired(AccessLayer.Service))
    }

    CredentialService.LIBRARY -> runCatching {
        // 库存里可能还躺着上一次那个已经死掉的 PHPSESSID，必须强制换一个新的
        WbuQueryClient(context, useVpn = engine.useVpn).ensureOpacSession(forceRefresh = true)
    }.fold(
        onSuccess = { null },
        onFailure = { WbuFailureDetector.fromThrowable(it, AccessLayer.Service) }
    )

    else -> null
}

/**
 * 打开「课表导入 / 一键同步」面板前的准备。
 *
 * 开了「自动使用保存的密码登录」且本地**登录态确实缺失**时，先用保存的密码静默登录一次 ——
 * 之后面板里就只差用户点一下确认，不必再输一次密码。登录态本来就齐就什么都不做
 * （面板里那些导入偏好还要留给用户看）。
 *
 * @return null 表示可以直接打开面板；否则是静默登录的失败原因 ——
 *         [AccessFailure.needsRelogin] 时照常打开面板（面板本身就是登录入口），
 *         其余（网络 / 协议 / 用户取消）只该提示一句，不要把用户推到登录面板前。
 * @param onLoginStart 真的要发起静默登录前回调一次（调用方用来提示「正在用保存的凭据登录…」）。
 */
suspend fun prepareWbuImportWithSavedPassword(
    context: Context,
    flowTag: String,
    onLoginStart: suspend () -> Unit = {}
): AccessFailure? {
    // 开关关着 / 本机没存统一认证密码：这条路径本来就不会登录，连校园网探测都不该做
    if (!shouldAttemptSavedPasswordLogin(context)) return null
    return prepareImportLoginWithChannel(
        context = context,
        flowTag = flowTag,
        // 课表同步 / 导入是需要校园网的：同 [campusAccess]，先在校园网内优先直连再决定通道，
        // 否则会出现「明明在校园网，却先绕 WebVPN 建了一遍会话」。
        useVpn = resolveCampusUseVpn(context, WbuSyncEngine.getSavedUseVpn(context)),
        onLoginStart = onLoginStart
    )
}

/**
 * 同 [prepareWbuImportWithSavedPassword]，但通道由调用方给定。
 *
 * 单击同步 / 单击导入这条路径上「通道」与「会话」必须出自同一个结论（见 [prepareWbuSyncSession]）：
 * 各探一次不只是白多花时间，还可能得出两个不同答案，于是「按直连建的会话被拿去走 WebVPN」。
 */
private suspend fun prepareImportLoginWithChannel(
    context: Context,
    flowTag: String,
    useVpn: Boolean,
    onLoginStart: suspend () -> Unit
): AccessFailure? {
    if (!shouldAttemptSavedPasswordLogin(context)) return null
    val hasLocalSession = WbuAuthTransport.hasLocalSession(context, CredentialService.JIAOWU, useVpn = useVpn) ||
        WbuAuthTransport.hasLocalSession(context, CredentialService.UNIFIED_AUTH, useVpn = useVpn)
    if (hasLocalSession) return null
    onLoginStart()
    return silentUnifiedAuthLogin(
        context = context,
        flowTag = flowTag,
        viaWebVpn = useVpn,
        // 上面已经确认存着密码，这里再兜一层：绝不因为「缺密码」在进页面时弹窗
        onlyWithSavedPassword = true,
        // 课表同步 / 导入要的是**教务**会话，只看 CASTGC 不够
        service = CredentialService.JIAOWU
    )
}

/**
 * 课表同步 / 导入要用的**教务会话**准备（单击路径专用）。
 *
 * 先试「现有会话还能不能用」，不行就用保存的密码**静默登录一次**再说。这一步很关键：
 * WebVPN 模式下「本地还躺着 CASTGC / jw_uf / TWFID」只能说明**曾经**登录过，
 * 门禁 TWFID 过期是常态；只看「本地有没有凭据」就下结论，结果就是
 * 「明明存着密码，点一下同步却要等几秒，然后被叫去登录」。
 *
 * 静默登录缺门禁密码 / 短信验证码 / 滑块时都会就地弹小窗（进程级），用户不必去登录面板。
 *
 * @return null 表示教务会话已就绪（可以发请求了）；否则是失败原因。
 */
suspend fun prepareJiaowuSessionForSync(
    context: Context,
    engine: WbuSyncEngine,
    flowTag: String
): AccessFailure? {
    if (engine.ensureJwxtSessionWithExistingCredentials()) return null
    val failure = silentUnifiedAuthLogin(
        context = context,
        flowTag = flowTag,
        viaWebVpn = engine.useVpn,
        // 上面这轮已经确认过「本地存着密码」才会走到单击同步，这里再兜一层：绝不弹「要密码」小窗
        onlyWithSavedPassword = true,
        // 要的是教务会话：静默登录成功后会把 CASTGC 换成 jw_uf 并引导会话
        service = CredentialService.JIAOWU
    )
    if (failure != null) return failure
    // 静默登录已经把该建的会话建好了（含 WebVPN 门禁），直接放行 —— 不再多打一次探活请求
    return null
}

/** [prepareWbuSyncSession] 的结果：会话就绪、可以发请求，或者为什么没准备好。 */
sealed interface WbuSyncSession {

    /** 会话已就绪；[engine] 的通道就是**最终**（可能重算过）的那一条，直接拿它发请求。 */
    data class Ready(val engine: WbuSyncEngine) : WbuSyncSession

    /** 没准备好：需要重新登录 / 网络不通 / 用户取消。 */
    data class Failed(val failure: AccessFailure) : WbuSyncSession
}

/**
 * 「一键同步 / 单击导入」发请求前的统一准备：把**通道**和**教务会话**一次办齐。
 *
 * 顺序是刻意的，每一步都对应一类真实故障：
 * 1. 本地登录态缺失 → 先用保存的密码静默登录一次（缺门禁密码 / 短信 / 滑块就地弹小窗）；
 * 2. 需要校园网的流程先决定通道：开了「自动校园网探测」且人在校园网内就直接连，不绕 WebVPN；
 * 3. 现有会话可能只是「看着还在」（WebVPN 门禁 TWFID 过期尤其常见）→ 不行就用保存的密码再登一次；
 * 4. 直连**一个响应都没拿到**、而通道又是探测定的 → 判定「在校园网内」的那次探测多半已经过期
 *    （刚走出 WiFi 范围，短缓存里还写着在校内），作废缓存**重算通道**，翻成 WebVPN 就重来一遍。
 *
 * 第 4 步是单击同步 / 导入特有的：这两条路径没有登录面板兜底，一次假失败就直接变成
 * 「操作没成功」的提示，而用户其实什么都没做错。**重算只做一次**，不会来回换通道。
 *
 * @param onLoginStart 真的要发起静默登录前回调一次（调用方用来提示「正在用保存的凭据登录…」）；
 *   重算通道后重试时可能再回调一次，调用方按「再提示一遍同样的状态」处理即可。
 */
suspend fun prepareWbuSyncSession(
    context: Context,
    flowTag: String,
    onLoginStart: suspend () -> Unit = {}
): WbuSyncSession {
    val savedUseVpn = WbuSyncEngine.getSavedUseVpn(context)
    var decision = resolveCampusChannel(context, savedUseVpn)

    suspend fun prepare(): Pair<WbuSyncEngine, AccessFailure?> {
        val engine = WbuSyncEngine(context = context, useVpn = decision.useVpn)
        val failure = prepareImportLoginWithChannel(context, flowTag, decision.useVpn, onLoginStart)
            ?: prepareJiaowuSessionForSync(context, engine, flowTag)
        return engine to failure
    }

    var (engine, failure) = prepare()
    if (failure != null && decision.decidedByProbe && failure.unreachableOnDirectChannel) {
        val replanned = replanCampusChannel(context, savedUseVpn)
        if (replanned.useVpn != decision.useVpn) {
            decision = replanned
            val retried = prepare()
            engine = retried.first
            failure = retried.second
        }
    }
    return if (failure == null) WbuSyncSession.Ready(engine) else WbuSyncSession.Failed(failure)
}

/** 失败原因是否应该给一个「改用 WebVPN」按钮。 */
fun AccessFailure.shouldOfferWebVpnOnce(useVpn: Boolean): Boolean =
    suggestedAction(webVpnEnabled = useVpn) == AccessAction.UseWebVpnOnce
