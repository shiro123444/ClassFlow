package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.util.Base64
import android.util.Log
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * ids 统一认证(CAS)客户端：只提供 authserver 认证原语，不含任何教务(/admin)语义。
 *
 * CAS 成功后的「JWXT 会话引导」(bootstrap)由 [WbuSyncEngine] 负责，从而维持依赖方向
 * 教务 → CAS，无反向依赖。
 */
internal class IdsCasClient(
    private val transport: WbuAuthTransport,
) {
    private val client = transport.client
    private val cookieStore = transport.cookieStore
    private val casRandom = SecureRandom()

    /**
     * 是否需要客户端重写 CAS 回跳 host。
     * 触发条件：走 WebVPN 且「统一认证经过WebVPN」关闭 —— 此时 ids 走公网、服务端代理不介入，
     * CAS 回跳到 service（真实 `jwxt.wbu.edu.cn/admin/caslogin?ticket=...`）不会自动被改写为代理宿主，
     * 校内(bwxt)不可达，需客户端把回跳 host 重写为教务代理宿主。
     * 注：service 参数本身必须保持真实地址，不能改。
     */
    private fun needRewriteCasRedirect(): Boolean =
        transport.useVpn && !WbuAuthTransport.getIdsViaWebVpn(transport.context)

    /**
     * 把 CAS 回跳到真实教务地址的 Location 重写为 WebVPN 代理宿主。
     * 仅当 Location 指向 `jwxt.wbu.edu.cn` 且触发条件成立时替换 scheme+host+port（跟随 WebVPN 设置）。
     * 否则原样返回该 Location。
     */
    private fun rewriteJwxtRedirectLocation(location: String): String {
        if (!needRewriteCasRedirect()) return location
        val rewritten = runCatching {
            val loc = location.toHttpUrlOrNull() ?: return@runCatching location
            val isJwxt = loc.host == "jwxt.wbu.edu.cn"
            if (!isJwxt) return@runCatching location
            // 用教务代理宿主：scheme/端口跟随「使用 https 访问 WebVPN」开关
            val proxy = transport.jwxtProxyBase().toHttpUrlOrNull() ?: return@runCatching location
            loc.newBuilder()
                .scheme(proxy.scheme)
                .host(proxy.host)
                .port(proxy.port)
                .build()
                .toString()
        }.getOrNull() ?: location
        if (rewritten != location) {
            Log.i("IdsCasClient", "Rewrote CAS redirect to jwxt proxy: $location -> $rewritten")
        }
        return rewritten
    }

    /**
     * 以「手动跟随重定向」方式提交 CAS 登录表单。
     * 流程：
     *  1. [followRedirects=false] 发起 POST，拿到第一个 302/200；
     *  2. 若是 302 → 取 Location；命中真实教务地址且满足条件时经 [rewriteJwxtRedirectLocation] 重写为代理宿主，
     *     再 GET 该地址（消费 ticket），返回是否已离开登录页。
     *  3. 若 200 且仍在 /authserver/login → 登录页（密码/验证码错误）。
     *  4. 否则（已离开登录页）→ 成功。
     */
    private suspend fun postCasFormManualFollow(
        loginReq: Request,
        flowTag: String,
        consumeTicket: Boolean = true
    ): CasPasswordLoginResult = withContext(Dispatchers.IO) {
        try {
            val manualClient = client.newBuilder().followRedirects(false).build()
            val postResp = manualClient.newCall(loginReq).execute()
            postResp.use {
                val body = it.body?.string().orEmpty()
                when {
                    it.isRedirect -> {
                        val location = it.header("Location")
                        if (location.isNullOrBlank()) {
                            Log.w("IdsCasClient", "$flowTag CAS redirect without Location")
                            CasPasswordLoginResult(false, "登录跳转异常（无 Location）", LocalLoginFailure.CREDENTIALS)
                        } else {
                            val st = Regex("""[?&]ticket=([^&]+)""").find(location)?.groupValues?.getOrNull(1)
                            if (!consumeTicket) {
                                // 不跟随消费 ticket（如仅为了拿 ST 解析学号，避免请求个人中心落地页）
                                Log.i("IdsCasClient", "$flowTag CAS redirected 302 with ticket; skip consuming landing page as requested")
                                CasPasswordLoginResult(success = true, landingUrl = location, stTicket = st)
                            } else {
                                val rewritten = rewriteJwxtRedirectLocation(location)
                                val target = rewritten.toHttpUrlOrNull()
                                if (target == null) {
                                    Log.w("IdsCasClient", "$flowTag CAS redirect Location invalid: $rewritten")
                                    CasPasswordLoginResult(false, "登录跳转异常（Location 无效）", LocalLoginFailure.CREDENTIALS)
                                } else {
                                    val followRes = followToLeavingLogin(rewritten, flowTag)
                                    followRes.copy(stTicket = st ?: followRes.stTicket)
                                }
                            }
                        }
                    }
                    finalUrlStaysOnLogin(it.request.url.toString()) -> {
                        val err = extractCasError(body)
                        CasPasswordLoginResult(false, err.ifBlank { "登录失败，请检查账号或验证码" }, LocalLoginFailure.CREDENTIALS)
                    }
                    else -> CasPasswordLoginResult(success = true)
                }
            }
        } catch (e: Exception) {
            Log.e("IdsCasClient", "$flowTag CAS login request exception", e)
            CasPasswordLoginResult(false, "网络异常: ${e.message}", LocalLoginFailure.CREDENTIALS)
        }
    }

    /** GET 目标地址（含消费 ticket），返回是否已离开登录页；成功时带回落地页 HTML。
     *  禁止原生自动跟随 302，若遇到重定向（如已认证换票回跳），必须先经 rewriteJwxtRedirectLocation 改写后再跟随！
     */
    private suspend fun followToLeavingLogin(url: String, flowTag: String): CasPasswordLoginResult =
        withContext(Dispatchers.IO) {
            try {
                var currentUrl = url
                var hops = 0
                val manualClient = client.newBuilder().followRedirects(false).build()

                while (hops < 5) {
                    hops++
                    val resp = manualClient.newCall(Request.Builder().url(currentUrl).get().build()).execute()
                    val code = resp.code
                    val body = resp.body?.string().orEmpty()
                    resp.close()

                    if (code in 300..399) {
                        val loc = resp.header("Location")
                        if (loc.isNullOrBlank()) {
                            Log.w("IdsCasClient", "$flowTag follow redirect missing Location")
                            return@withContext CasPasswordLoginResult(false, "跳转异常（无 Location）", LocalLoginFailure.CREDENTIALS)
                        }
                        val rewritten = rewriteJwxtRedirectLocation(loc)
                        Log.d("IdsCasClient", "$flowTag follow 30x to $rewritten")
                        currentUrl = rewritten
                        continue
                    }

                    // 最终非 30x 落地响应
                    if (finalUrlStaysOnLogin(currentUrl)) {
                        Log.w("IdsCasClient", "$flowTag CAS redirect landed back on login. url=$currentUrl")
                        return@withContext CasPasswordLoginResult(false, extractCasError(body).ifBlank { "登录失败，请重试" }, LocalLoginFailure.CREDENTIALS)
                    } else {
                        return@withContext CasPasswordLoginResult(success = true, landingUrl = currentUrl, landingHtml = body)
                    }
                }
                Log.w("IdsCasClient", "$flowTag too many redirects during followToLeavingLogin")
                CasPasswordLoginResult(false, "重定向次数过多", LocalLoginFailure.CREDENTIALS)
            } catch (e: Exception) {
                Log.e("IdsCasClient", "$flowTag CAS follow exception", e)
                CasPasswordLoginResult(false, "网络异常: ${e.message}", LocalLoginFailure.CREDENTIALS)
            }
        }

    private fun finalUrlStaysOnLogin(url: String): Boolean = url.contains("/authserver/login")

    data class CasLoginPage(
        val hiddenFields: Map<String, String>,
        val pwdEncryptSalt: String,
        val needCaptcha: Boolean
    )

    /** 获取 CAS 密码登录页：解析表单隐藏域、盐值、是否需要验证码。
     *  使用 followRedirects(false)，如果访问即发生 302（说明已持有 CASTGC），返回 null 由外层 followToLeavingLogin 处理。
     */
    suspend fun fetchCasLoginPage(idsLoginUrl: String, flowTag: String, clearAuthCookies: Boolean = true): CasLoginPage? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (clearAuthCookies) {
                    transport.clearAuthCookies(idsLoginUrl.toHttpUrlOrNull()?.host ?: return@runCatching null)
                }
                val manualClient = client.newBuilder().followRedirects(false).build()
                manualClient.newCall(
                    Request.Builder().url(idsLoginUrl).get().build()
                ).execute().use { pageResp ->
                    if (pageResp.isRedirect) {
                        Log.i("IdsCasClient", "$flowTag CAS login page returned 30x redirect (session likely authenticated).")
                        return@use null
                    }
                    val loginHtml = pageResp.body?.string().orEmpty()
                    if (loginHtml.isBlank()) {
                        Log.w("IdsCasClient", "$flowTag CAS login page is blank. idsLoginUrl=$idsLoginUrl")
                        return@use null
                    }

                    val doc = Jsoup.parse(loginHtml)
                    val pwdForm = doc.selectFirst("form#pwdFromId") ?: run {
                        val candidates = doc.select("form").filter { form ->
                            val hasUsername = form.select("input[name=username]").isNotEmpty()
                            val hasPassword = form.select("input[name=password], input#password").isNotEmpty()
                            val looksPwdForm = form.id().contains("pwd", ignoreCase = true) ||
                                form.attr("class").contains("pwd", ignoreCase = true)
                            hasUsername && (hasPassword || looksPwdForm)
                        }
                        if (candidates.size == 1) {
                            Log.w(
                                "IdsCasClient",
                                "$flowTag CAS missing #pwdFromId; using strict fallback form id='${candidates[0].id()}'"
                            )
                            candidates[0]
                        } else {
                            val formSummary = doc.select("form").joinToString(" | ") { form ->
                                val id = form.id().ifBlank { "<no-id>" }
                                val hasUser = form.select("input[name=username]").isNotEmpty()
                                val hasPwd = form.select("input[name=password], input#password").isNotEmpty()
                                "id=$id user=$hasUser pwd=$hasPwd"
                            }
                            Log.w("IdsCasClient", "$flowTag CAS form is ambiguous. forms=[$formSummary]")
                            Log.d("IdsCasClient", "$flowTag CAS snippet=${loginHtml.take(800)}")
                            return@use null
                        }
                    }

                    val hiddenFields = mutableMapOf<String, String>()
                    pwdForm.select("input[type=hidden][name]").forEach { input ->
                        val key = input.attr("name")
                        if (key.isNotBlank()) hiddenFields[key] = input.attr("value")
                    }

                    val pwdEncryptSalt = pwdForm.selectFirst("#pwdEncryptSalt")?.attr("value").orEmpty()
                    val needCaptcha = Regex("""needCaptcha\s*=\s*['\"]?true['\"]?""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(loginHtml)

                    CasLoginPage(
                        hiddenFields = hiddenFields,
                        pwdEncryptSalt = pwdEncryptSalt,
                        needCaptcha = needCaptcha
                    )
                }
            }.getOrNull()
        }

    /** 查询指定学号是否需要滑块验证码。 */
    suspend fun checkNeedCaptcha(username: String, origin: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$origin/authserver/checkNeedCaptcha.htl?username=${URLEncoder.encode(username, "UTF-8")}&_=${System.currentTimeMillis()}"
            val req = Request.Builder()
                .url(url)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", "$origin/authserver/login")
                .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                .get()
                .build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            runCatching { JSONObject(body).optBoolean("isNeed", false) }.getOrDefault(false)
        } catch (e: Exception) {
            Log.w("IdsCasClient", "checkNeedCaptcha failed", e)
            false
        }
    }

    /** 获取滑块验证码图片数据。 */
    suspend fun fetchSliderCaptcha(origin: String): SliderCaptchaData? = withContext(Dispatchers.IO) {
        try {
            val url = "$origin/authserver/common/openSliderCaptcha.htl?_=${System.currentTimeMillis()}"
            val req = Request.Builder()
                .url(url)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", "$origin/authserver/login")
                .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                .get()
                .build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            val json = JSONObject(body)
            SliderCaptchaData(
                smallImageBase64 = json.optString("smallImage"),
                bigImageBase64 = json.optString("bigImage"),
                tagWidth = json.optInt("tagWidth", 50)
            )
        } catch (e: Exception) {
            Log.w("IdsCasClient", "fetchSliderCaptcha failed", e)
            null
        }
    }

    /** 提交滑块位置，返回是否验证通过。 */
    suspend fun verifySliderCaptcha(origin: String, moveLength: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val form = FormBody.Builder()
                .add("canvasLength", "280")
                .add("moveLength", moveLength.toString())
                .build()
            val req = Request.Builder()
                .url("$origin/authserver/common/verifySliderCaptcha.htl")
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", "$origin/authserver/login")
                .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                .post(form)
                .build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            runCatching { JSONObject(body).optInt("errorCode") == 1 }.getOrDefault(false)
        } catch (e: Exception) {
            Log.w("IdsCasClient", "verifySliderCaptcha failed", e)
            false
        }
    }

    /** 滑块验证码完整流程：获取图片 → 用户操作 → 校验。 */
    suspend fun solveSliderCaptcha(
        origin: String,
        flowTag: String,
        captchaProvider: SliderCaptchaProvider
    ): Boolean {
        val maxAttempts = WbuAuthTransport.MAX_CAPTCHA_ATTEMPTS
        var attempts = 0
        while (attempts < maxAttempts) {
            attempts++
            val captcha = fetchSliderCaptcha(origin)
            if (captcha == null) {
                Log.w("IdsCasClient", "$flowTag fetch slider captcha failed (attempt $attempts)")
                continue
            }
            when (val result = captchaProvider(captcha)) {
                is SliderCaptchaResult.Cancel -> {
                    Log.w("IdsCasClient", "$flowTag captcha cancelled by user")
                    return false
                }
                is SliderCaptchaResult.Refresh -> {
                    Log.i("IdsCasClient", "$flowTag captcha refresh requested")
                    continue
                }
                is SliderCaptchaResult.Move -> {
                    val ok = verifySliderCaptcha(origin, result.moveLength)
                    if (ok) {
                        Log.i("IdsCasClient", "$flowTag captcha verified on attempt $attempts")
                        return true
                    }
                    Log.w("IdsCasClient", "$flowTag captcha verify failed (attempt $attempts)")
                }
            }
        }
        Log.w("IdsCasClient", "$flowTag captcha attempts exhausted")
        return false
    }

    // ------------------- 密码登录（纯 CAS，不含教务 bootstrap） -------------------

    /**
     * 用账号密码提交 CAS 登录表单。返回是否已离开登录页（即认证被接受）。
     * 认证被接受后的教务会话引导由 [WbuSyncEngine.bootstrapJwxtSession] 负责。
     */
    suspend fun casPasswordLogin(
        studentId: String,
        password: String,
        idsLoginUrl: String,
        flowTag: String,
        captchaProvider: SliderCaptchaProvider? = null,
        clearAuthCookies: Boolean = true,
        consumeTicket: Boolean = true
    ): CasPasswordLoginResult = withContext(Dispatchers.IO) {
        val origin = idsLoginUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }

        // 如果已有有效 CASTGC，无需再次拉取页面解析表单，直接请求换票
        val hasTgc = cookieStore.any { it.name == "CASTGC" && !it.value.isBlank() }
        if (hasTgc && !clearAuthCookies) {
            Log.i("IdsCasClient", "$flowTag 已持有本次事务的 CASTGC，直接请求换票无需重复登录表单")
            val autoResp = followToLeavingLogin(idsLoginUrl, flowTag)
            if (autoResp.success) return@withContext autoResp
            Log.w("IdsCasClient", "$flowTag 持有 CASTGC 换票未成功，回退重新解析登录页表单...")
        }

        val page = fetchCasLoginPage(idsLoginUrl, flowTag, clearAuthCookies = clearAuthCookies)
            ?: run {
                return@withContext CasPasswordLoginResult(false, "无法获取登录参数", LocalLoginFailure.CREDENTIALS)
            }

        var captchaNeeded = page.needCaptcha
        if (!captchaNeeded && !origin.isNullOrBlank() && captchaProvider != null) {
            captchaNeeded = checkNeedCaptcha(studentId, origin)
        }

        if (captchaNeeded) {
            if (captchaProvider == null || origin.isNullOrBlank()) {
                Log.w("IdsCasClient", "$flowTag CAS requires captcha but no provider available")
                return@withContext CasPasswordLoginResult(false, "需要滑块验证码但无可用交互", LocalLoginFailure.CAPTCHA)
            }
            val solved = solveSliderCaptcha(origin, flowTag, captchaProvider)
            if (!solved) {
                Log.w("IdsCasClient", "$flowTag CAS captcha not solved")
                return@withContext CasPasswordLoginResult(false, "滑块验证未通过", LocalLoginFailure.CREDENTIALS)
            }
            Log.i("IdsCasClient", "$flowTag CAS captcha verified, submit with original execution")
        }

        val hiddenFields = page.hiddenFields
        val encryptedCasPassword = encryptCasPassword(password, page.pwdEncryptSalt)
        val formBuilder = FormBody.Builder()
        hiddenFields
            .filterKeys { it != "password" && it != "passwordText" && it != "username" }
            .forEach { (k, v) -> formBuilder.add(k, v) }
        formBuilder.add("username", studentId)
        formBuilder.add("password", encryptedCasPassword)
        if (!hiddenFields.containsKey("_eventId")) formBuilder.add("_eventId", "submit")
        if (!hiddenFields.containsKey("cllt")) formBuilder.add("cllt", "userNameLogin")
        if (!hiddenFields.containsKey("dllt")) formBuilder.add("dllt", "generalLogin")

        val loginPostReq = Request.Builder()
            .url(idsLoginUrl)
            .post(formBuilder.build())
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .addHeader("Referer", idsLoginUrl)
            .apply { if (!origin.isNullOrBlank()) addHeader("Origin", origin) }
            .build()

        postCasFormManualFollow(loginPostReq, flowTag, consumeTicket = consumeTicket)
    }

    // ------------------- 登录页表单解析（execution/lt） -------------------

    /**
     * 解析指定 form id 的登录页，提取 execution / lt（动态码/二维码用）。
     */
    suspend fun fetchLoginForm(idsLoginUrl: String, formId: String): AuthForm? = withContext(Dispatchers.IO) {
        runCatching {
            transport.clearAuthCookies(idsLoginUrl.toHttpUrlOrNull()?.host ?: return@runCatching null)
            client.newCall(Request.Builder().url(idsLoginUrl).get().build()).execute().use { pageResp ->
                val html = pageResp.body?.string().orEmpty()
                if (html.isBlank()) {
                    Log.w("IdsCasClient", "CAS login page blank for form=$formId")
                    return@use null
                }
                val doc = Jsoup.parse(html)
                val scope: Element = doc.selectFirst("form#$formId")
                    ?: doc.let {
                        Log.w("IdsCasClient", "CAS form $formId not found; fallback to whole-page inputs")
                        it
                    }
                val hidden = mutableMapOf<String, String>()
                scope.select("input[name]").forEach { input ->
                    val key = input.attr("name")
                    if (key.isNotBlank() && !hidden.containsKey(key)) hidden[key] = input.attr("value")
                }
                val execution = hidden["execution"]
                    ?: extractInputValue(html, "execution")
                    ?: extractScriptVar(html, "execution")
                if (execution.isNullOrBlank()) {
                    Log.w("IdsCasClient", "CAS form $formId no execution. url=$idsLoginUrl")
                    return@use null
                }
                val ltValue = hidden["lt"]
                    ?: extractInputValue(html, "lt")
                    ?: extractScriptVar(html, "lt")
                    ?: ""
                AuthForm(execution, ltValue)
            }
        }.getOrNull()
    }

    // ------------------- 手机动态码 -------------------

    /** 发送动态码：解析登录页 → 滑块验证 → 发送短信。 */
    suspend fun sendDynamicCode(
        studentId: String,
        flowTag: String,
        captchaProvider: SliderCaptchaProvider?
    ): DynamicCodeSendResult = withContext(Dispatchers.IO) {
        val authBase = transport.idsBase()
        val service = URLEncoder.encode(transport.casServiceTarget, "UTF-8")
        val loginUrl = "$authBase/authserver/login?service=$service"
        val form = fetchLoginForm(loginUrl, "phoneFromId") ?: run {
            Log.w("IdsCasClient", "$flowTag dynamicCode: cannot parse login form")
            return@withContext DynamicCodeSendResult.Failure("无法获取登录参数，请重试")
        }

        if (captchaProvider != null) {
            val ok = solveSliderCaptcha(authBase, "$flowTag-DYNAMIC-SLIDER", captchaProvider)
            if (!ok) {
                Log.w("IdsCasClient", "$flowTag dynamicCode: slider captcha not solved")
                return@withContext DynamicCodeSendResult.Failure("滑块验证未通过")
            }
        }

        val url = "$authBase/authserver/dynamicCode/getDynamicCode.htl"
        transport.injectAuthLocaleCookie(authBase)
        val req = Request.Builder()
            .url(url)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Referer", loginUrl)
            .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
            .post(FormBody.Builder()
                .add("mobile", studentId)
                .add("captcha", "")
                .build())
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                when (val code = json?.optString("code").orEmpty()) {
                    "success" -> DynamicCodeSendResult.Success(form)
                    "captchaError" -> {
                        val msg = json?.optString("message").orEmpty()
                        DynamicCodeSendResult.Failure(if (msg.isBlank()) "验证码错误" else msg)
                    }
                    "timeExpire" -> {
                        val wait = parseWaitSeconds(json?.opt("time"))
                        DynamicCodeSendResult.Failure("发送过于频繁", waitSeconds = wait)
                    }
                    else -> {
                        val msg = json?.optString("message").orEmpty()
                        DynamicCodeSendResult.Failure(if (msg.isBlank()) "发送验证码失败，请重试" else msg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("IdsCasClient", "$flowTag dynamicCode send exception", e)
            DynamicCodeSendResult.Failure("发送验证码失败，请重试")
        }
    }

    /** 仅获取动态码登录表单参数，不发短信。 */
    suspend fun obtainDynamicCodeForm(flowTag: String): AuthForm? = withContext(Dispatchers.IO) {
        val authBase = transport.idsBase()
        val service = URLEncoder.encode(transport.casServiceTarget, "UTF-8")
        fetchLoginForm("$authBase/authserver/login?service=$service", "phoneFromId")
    }

    /** 用动态码完成 CAS 登录（返回是否已离开登录页）。教务 bootstrap 由调用方负责。 */
    suspend fun casDynamicCodeLogin(
        studentId: String,
        code: String,
        prep: AuthForm,
        flowTag: String
    ): CasPasswordLoginResult = withContext(Dispatchers.IO) {
        val authBase = transport.idsBase()
        val service = URLEncoder.encode(transport.casServiceTarget, "UTF-8")
        val postUrl = "$authBase/authserver/login?service=$service"
        val formBuilder = FormBody.Builder()
            .add("username", studentId)
            .add("dynamicCode", code)
            .add("captcha", "")
            .add("_eventId", "submit")
            .add("cllt", "dynamicLogin")
            .add("dllt", "generalLogin")
            .add("lt", prep.lt)
            .add("execution", prep.execution)

        val loginReq = Request.Builder()
            .url(postUrl)
            .post(formBuilder.build())
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .addHeader("Referer", postUrl)
            .addHeader("Origin", authBase)
            .build()

        try {
            postCasFormManualFollow(loginReq, flowTag)
        } catch (e: Exception) {
            Log.e("IdsCasClient", "$flowTag dynamicCode login exception", e)
            CasPasswordLoginResult(false, "网络异常: ${e.message}", LocalLoginFailure.CREDENTIALS)
        }
    }

    // ------------------- 二维码 -------------------

    /** 开始二维码登录：解析 qr 登录页 → 获取 uuid → 生成二维码内容。 */
    suspend fun startQrLogin(flowTag: String): QrSession? = withContext(Dispatchers.IO) {
        runCatching {
            val authBase = transport.qrBase()
            val service = URLEncoder.encode(transport.casServiceTarget, "UTF-8")
            val qrPageUrl = "$authBase/authserver/login?type=qrcode&service=$service"
            val form = fetchLoginForm(qrPageUrl, "qrLoginForm") ?: run {
                Log.w("IdsCasClient", "$flowTag qr: cannot parse login form")
                return@runCatching null
            }
            val tokenUrl = "$authBase/authserver/qrCode/getToken?ts=${System.currentTimeMillis()}"
            val tokenReq = Request.Builder()
                .url(tokenUrl)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", qrPageUrl)
                .get()
                .build()
            val uuid = client.newCall(tokenReq).execute().use { it.body?.string().orEmpty().trim() }
            if (uuid.isBlank()) {
                Log.w("IdsCasClient", "$flowTag qr uuid blank")
                return@runCatching null
            }
            val content = "$authBase/authserver/qrCode/qrCodeLogin.do?uuid=$uuid"
            QrSession(uuid, content, form.execution, form.lt, authBase)
        }.getOrNull()
    }

    /** 轮询二维码状态。 */
    suspend fun pollQrStatus(session: QrSession): QrStatus = withContext(Dispatchers.IO) {
        try {
            val statusUrl = "${session.authBaseUrl}/authserver/qrCode/getStatus.htl?ts=${System.currentTimeMillis()}&uuid=${session.uuid}"
            val req = Request.Builder()
                .url(statusUrl)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", "${session.authBaseUrl}/authserver/login?type=qrcode")
                .get()
                .build()
            val raw = client.newCall(req).execute().use { it.body?.string().orEmpty().trim() }
            when (raw.toIntOrNull()) {
                0 -> QrStatus.WAIT
                1 -> QrStatus.SUCCESS
                2 -> QrStatus.CONFIRM
                3 -> QrStatus.EXPIRED
                else -> QrStatus.ERROR
            }
        } catch (e: Exception) {
            Log.w("IdsCasClient", "pollQrStatus failed", e)
            QrStatus.ERROR
        }
    }

    /** 手机扫码确认后提交登录（返回是否已离开登录页）。教务 bootstrap 由调用方负责。 */
    suspend fun casCompleteQrLogin(session: QrSession): CasPasswordLoginResult = withContext(Dispatchers.IO) {
        val service = URLEncoder.encode(transport.casServiceTarget, "UTF-8")
        val postUrl = "${session.authBaseUrl}/authserver/login?display=qrLogin&service=$service"
        val form = FormBody.Builder()
            .add("lt", session.lt)
            .add("uuid", session.uuid)
            .add("cllt", "qrLogin")
            .add("dllt", "generalLogin")
            .add("execution", session.execution)
            .add("_eventId", "submit")
            .add("rmShown", "1")
            .build()
        val loginReq = Request.Builder()
            .url(postUrl)
            .post(form)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .addHeader("Referer", "${session.authBaseUrl}/authserver/login?type=qrcode")
            .addHeader("Origin", session.authBaseUrl)
            .build()

        try {
            postCasFormManualFollow(loginReq, "QR")
        } catch (e: Exception) {
            Log.e("IdsCasClient", "qr login exception", e)
            CasPasswordLoginResult(false, "网络异常: ${e.message}", LocalLoginFailure.CREDENTIALS)
        }
    }

    // ------------------- 加密 / 解析基元 -------------------

    private fun encryptCasPassword(password: String, salt: String): String {
        val trimmedSalt = salt.trim()
        if (trimmedSalt.isEmpty()) return password
        return runCatching {
            val keyBytes = trimmedSalt.toByteArray(Charsets.UTF_8)
            val ivText = randomAesString(16)
            val ivBytes = ivText.toByteArray(Charsets.UTF_8)
            val plain = randomAesString(64) + password
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }.getOrElse {
            Log.w("IdsCasClient", "CAS password encryption failed, fallback to plain password", it)
            password
        }
    }

    private fun randomAesString(len: Int): String {
        val chars = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
        val out = StringBuilder(len)
        repeat(len) {
            val idx = casRandom.nextInt(chars.length)
            out.append(chars[idx])
        }
        return out.toString()
    }

    private fun extractInputValue(html: String, name: String): String? {
        val m = Regex("""(?:name|id)\s*=\s*["']${Regex.escape(name)}["'][^>]*value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)
        return m?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractScriptVar(html: String, name: String): String? {
        val m = Regex("""${Regex.escape(name)}\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)
        return m?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseWaitSeconds(raw: Any?): Int {
        return when (raw) {
            is Number -> raw.toInt().coerceAtLeast(1)
            is String -> raw.toIntOrNull()?.coerceAtLeast(1) ?: 120
            else -> 120
        }
    }

    /**
     * 从 CAS 登录页提取真实错误文案（#formErrorTip2 / #formErrorTip / #showErrorTip）。
     * 不扫描原始 HTML 关键词，避免命中验证码图片 alt 标签误报。
     */
    fun extractCasErrorMessage(html: String): String? {
        if (html.isBlank()) return null
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null
        val selectors = listOf("#formErrorTip2", "#formErrorTip", "#showErrorTip")
        for (sel in selectors) {
            val text = doc.selectFirst(sel)?.text()?.trim()?.takeIf { it.isNotBlank() }
            if (text != null) return text
        }
        return null
    }

    fun extractCasError(html: String): String {
        return extractCasErrorMessage(html) ?: ""
    }

    /**
     * 用已有的 ST 票据调用 serviceValidate 解析真实学号。
     */
    suspend fun validateTicketForStudentId(idsBaseUrl: String, st: String, serviceUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val encodedService = URLEncoder.encode(serviceUrl, "UTF-8")
            val validateUrl = "$idsBaseUrl/authserver/serviceValidate?ticket=${URLEncoder.encode(st, "UTF-8")}&service=$encodedService"
            client.newCall(Request.Builder().url(validateUrl).get().build()).execute().use { resp ->
                val xml = resp.body?.string().orEmpty()
                val user = Regex("""<cas:user>(.*?)</cas:user>""", RegexOption.IGNORE_CASE)
                    .find(xml)?.groupValues?.getOrNull(1)?.trim()
                if (!user.isNullOrBlank()) {
                    Log.i("IdsCasClient", "Resolved standard student ID from CAS: $user")
                    user
                } else null
            }
        }.getOrNull()
    }

    /**
     * 通过 CAS 协议从 ids 获取当前会话登录用户的真实学号（参考 webvpn_notes.md §九）。
     * 无论用户使用学号、工号还是别名登录，<cas:user> 永远是标准学号。
     */
    suspend fun fetchStudentIdFromCas(idsBaseUrl: String, serviceUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val encodedService = URLEncoder.encode(serviceUrl, "UTF-8")
            val loginCheckUrl = "$idsBaseUrl/authserver/login?service=$encodedService"
            // 请求 ST 票据（不自动跟随跳转）
            val manualClient = client.newBuilder().followRedirects(false).build()
            val st = manualClient.newCall(Request.Builder().url(loginCheckUrl).get().build()).execute().use { resp ->
                if (resp.code in 300..399) {
                    val loc = resp.header("Location").orEmpty()
                    Regex("""[?&]ticket=([^&]+)""").find(loc)?.groupValues?.getOrNull(1)
                } else null
            } ?: return@runCatching null

            validateTicketForStudentId(idsBaseUrl, st, serviceUrl)
        }.getOrNull()
    }

    companion object {
        /** 「ids 走 WebVPN」：默认关闭。 */
        fun getIdsViaWebVpn(context: android.content.Context): Boolean =
            WbuAuthTransport.getIdsViaWebVpn(context)
        fun setIdsViaWebVpn(context: android.content.Context, enabled: Boolean) =
            WbuAuthTransport.setIdsViaWebVpn(context, enabled)

        /** 「二维码走 WebVPN」：默认关闭。 */
        fun getQrViaWebVpn(context: android.content.Context): Boolean =
            WbuAuthTransport.getQrViaWebVpn(context)
        fun setQrViaWebVpn(context: android.content.Context, enabled: Boolean) =
            WbuAuthTransport.setQrViaWebVpn(context, enabled)

        /** 是否发送英语验证码。 */
        fun getSendEnglishSms(context: android.content.Context): Boolean =
            WbuAuthTransport.getSendEnglishSms(context)
        fun setSendEnglishSms(context: android.content.Context, enabled: Boolean) =
            WbuAuthTransport.setSendEnglishSms(context, enabled)

        /** 「IDS addr not from Jwxt」：为 true 时不从教务登录页发现 CAS 链接，直接用 ids 基址构造。默认关闭。 */
        fun getIdsAddrNotFromJwxt(context: android.content.Context): Boolean =
            WbuAuthTransport.getIdsAddrNotFromJwxt(context)
        fun setIdsAddrNotFromJwxt(context: android.content.Context, enabled: Boolean) =
            WbuAuthTransport.setIdsAddrNotFromJwxt(context, enabled)
    }
}

/** CAS 密码/动态码/二维码登录结果。成功时 [message] 为空。[landingHtml] 为 CAS 落地页 HTML（若可取得）。[stTicket] 为 302 截获的 Service Ticket（若存在）。 */
data class CasPasswordLoginResult(
    val success: Boolean,
    val message: String = "",
    val failure: LocalLoginFailure = LocalLoginFailure.CREDENTIALS,
    val landingUrl: String? = null,
    val landingHtml: String? = null,
    val stTicket: String? = null
)
