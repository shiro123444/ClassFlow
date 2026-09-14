package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CourseSelectionInit
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.KkxFrom
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.RetakeCourse
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.SelectedCourse
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.SelectionBatch
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.SelectionOutcome
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.TeachingClass
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.kklxToFrom
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.parseBatchStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * 学生选课客户端（jwxt 超星教务 /admin/xsd/xk 模块）。
 *
 * 与 [WbuQueryClient] 同包、复用同一 [WbuAuthTransport]，但 **强制校园网直连**（useVpn 恒为 false）：
 * 即使其它页面保存过 WebVPN 偏好（[WbuSyncEngine.getSavedUseVpn]），本客户端也不会使用。
 *
 * 错误语义：仅 3xx 重定向 / HTML 登录页 / 明确提示未登录时抛 [WbuSessionExpiredException]；
 * 业务 `ret != 0` 由各方法按调用方需求处理（例如「没有可选的教学班」不是会话失效）。
 */
class WbuCourseSelectionClient(private val context: Context) : CourseSelectionDataSource {

    internal val transport: WbuAuthTransport = WbuAuthTransport.getShared(context, false)
    private val baseUrl: String get() = transport.jwxtBase
    private val client get() = transport.client
    private val referer get() = "$baseUrl/admin/xsd/xk"

    init {
        transport.restoreCookieStore()
    }

    private fun ensureCookies() {
        if (transport.cookieStore.none { it.name == "jw_uf" }) {
            transport.restoreCookieStore()
        }
    }

    private fun sessionExpired(): WbuSessionExpiredException = WbuSessionExpiredException(
        messageResId = R.string.error_session_expired,
        message = context.getString(R.string.error_session_expired)
    )

    private fun executeRaw(request: Request): String {
        val manualClient = client.newBuilder().followRedirects(false).build()
        manualClient.newCall(request).execute().use { resp ->
            if (resp.code in 300..399) throw sessionExpired()
            val body = resp.body?.string().orEmpty()
            if (transport.looksLikeHtml(body)) throw sessionExpired()
            return body
        }
    }

    private fun executePostJson(path: String, form: FormBody): JSONObject {
        val req = Request.Builder()
            .url("$baseUrl$path")
            .post(form)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", referer)
            .build()
        val body = executeRaw(req)
        if (body.isBlank()) return JSONObject()
        return JSONObject(body)
    }

    private fun executeGetJson(path: String): JSONObject {
        val req = Request.Builder()
            .url("$baseUrl$path")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", referer)
            .get()
            .build()
        val body = executeRaw(req)
        if (body.isBlank()) return JSONObject()
        return JSONObject(body)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * 选课页初始化：学生信息 + 批次列表 + 批次加密串
     */
    override suspend fun queryInit(): Result<CourseSelectionInit> = withContext(Dispatchers.IO) {
        ensureCookies()
        runCatching {
            val json = executePostJson("/admin/xsd/xk/listV2", FormBody.Builder().build())
            val msg = json.optString("msg", "")
            if (msg.contains("登录") || msg.contains("未认证")) throw sessionExpired()

            val data = json.optJSONObject("data")
            val pcencs = data?.optJSONObject("pcencs")
            val batches = mutableListOf<SelectionBatch>()

            val mapsList = data?.optJSONArray("mapsList")
            if (mapsList != null) {
                for (i in 0 until mapsList.length()) {
                    val b = mapsList.optJSONObject(i) ?: continue
                    val pcid = b.optString("xkgzid", "").trim().ifBlank { b.optString("pcid", "").trim() }
                    if (pcid.isBlank()) continue
                    val kklx = b.optString("kklx", "").trim()
                    val from = kklxToFrom(kklx)
                    batches.add(
                        SelectionBatch(
                            pcid = pcid,
                            name = b.optString("xkgzMc", "").trim().ifBlank { b.optString("xkgzmc", "").trim() },
                            kklx = kklx,
                            from = from,
                            stage = parseBatchStage(b.optString("type", "")),
                            countdownMs = b.optLong("time", 0L),
                            pcenc = pcencs?.optString(pcid, "") ?: "",
                            isLottery = b.optString("xkms", "").trim() == "2",
                            allowWaitlist = b.optString("sfkqhbxk", "").trim() == "1",
                            promptMessage = b.optString("xkPromptMessage", "").trim(),
                            quotaPerBatch = b.optInt("cqbmms", 0),
                            isSupported = from != KkxFrom.UNSUPPORTED
                        )
                    )
                }
            }

            CourseSelectionInit(
                studentId = data?.optString("xsxh", "") ?: "",
                studentName = data?.optString("xsxm", "") ?: "",
                xkxnxq = data?.optString("xkxnxq", "") ?: "",
                batches = batches,
                hideQuota = data?.optString("xsdxksfycyxrl", "")?.trim() == "1"
            )
        }
    }

    /**
     * 教学班列表（服务端一次性全量返回，筛选与排序在客户端进行）
     */
    override suspend fun queryClasses(
        from: KkxFrom,
        pcid: String,
        pcenc: String
    ): Result<List<TeachingClass>> = withContext(Dispatchers.IO) {
        ensureCookies()
        runCatching {
            val fb = FormBody.Builder()
                .add("from", from.raw)
                .add("pcid", pcid)
                .add("gridtype", "jqgrid")
            if (pcenc.isNotBlank()) fb.add("pcenc", pcenc)

            val json = executePostJson("/admin/xsd/xk/listjxbDataV2", fb.build())
            val ret = json.optInt("ret", -1)
            if (ret != 0) {
                throw IOException(json.optString("msg", "").ifBlank { "查询教学班失败 (ret=$ret)" })
            }
            val arr = json.optJSONObject("data")?.optJSONArray("initData") ?: JSONArray()
            val list = mutableListOf<TeachingClass>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(parseTeachingClass(o))
            }
            list
        }
    }

    /**
     * 选课 / 报名（抽签冲突需二次确认时返回 needConfirm=true）
     */
    override suspend fun selectClass(
        jxbid: String,
        pcid: String,
        zjxbid: String,
        sfqc: Boolean
    ): Result<SelectionOutcome> = withContext(Dispatchers.IO) {
        ensureCookies()
        runCatching {
            val fb = FormBody.Builder()
                .add("jxbid", jxbid)
                .add("pcid", pcid)
            if (zjxbid.isNotBlank()) fb.add("zjxbid", zjxbid)
            if (sfqc) fb.add("sfqc", "1")

            val json = executePostJson("/admin/xsd/xk/xsdXkV2", fb.build())
            val ret = json.optInt("ret", -1)
            val msg = json.optString("msg", "")
            val needConfirm = json.optJSONObject("extend")?.optBoolean("needConfirm", false) ?: false
            val idempotent = msg.contains("已选") || msg.contains("已报名")
            SelectionOutcome(
                success = (ret == 0 && !needConfirm) || idempotent,
                needConfirm = needConfirm,
                message = msg
            )
        }
    }

    /**
     * 退课 / 退选 / 取消报名
     */
    override suspend fun dropClass(jxbid: String, pcid: String): Result<String> =
        withContext(Dispatchers.IO) {
            ensureCookies()
            runCatching {
                val fb = FormBody.Builder()
                    .add("jxbid", jxbid)
                    .add("pcid", pcid)
                val json = executePostJson("/admin/xsd/xk/xsdTkV2", fb.build())
                val ret = json.optInt("ret", -1)
                val msg = json.optString("msg", "")
                if (ret != 0) throw IOException(msg.ifBlank { "退课失败 (ret=$ret)" })
                msg
            }
        }

    /**
     * 取消候补
     */
    override suspend fun cancelWaitlist(jxbid: String, pcid: String): Result<String> =
        withContext(Dispatchers.IO) {
            ensureCookies()
            runCatching {
                val fb = FormBody.Builder()
                    .add("jxbid", jxbid)
                    .add("pcid", pcid)
                val json = executePostJson("/admin/xsd/xk/qxhbxk", fb.build())
                val ret = json.optInt("ret", -1)
                val msg = json.optString("msg", "")
                if (ret != 0) throw IOException(msg.ifBlank { "取消候补失败 (ret=$ret)" })
                msg
            }
        }

    /**
     * 查询子教学班 ID 列表（size > 1 时需要让用户选择）
     */
    override suspend fun queryChildClasses(
        jxbid: String,
        pcid: String,
        pcenc: String,
        from: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        ensureCookies()
        runCatching {
            val fb = FormBody.Builder()
                .add("jxbid", jxbid)
                .add("pcid", pcid)
                .add("from", from)
            if (pcenc.isNotBlank()) fb.add("pcenc", pcenc)
            val json = executePostJson("/admin/xsd/xk/queryChildClassesV2", fb.build())
            val arr = json.optJSONObject("extend")?.optJSONArray("zjxb") ?: JSONArray()
            val ids = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isNotBlank()) ids.add(v)
            }
            ids
        }
    }

    /**
     * 已选课程列表
     */
    override suspend fun querySelectedCourses(): Result<List<SelectedCourse>> =
        withContext(Dispatchers.IO) {
            ensureCookies()
            runCatching {
                val json = executePostJson(
                    "/admin/xsd/yxkccx/listYxkc?gridtype=jqgrid",
                    FormBody.Builder()
                        .add("page.pn", "1")
                        .add("page.size", "200")
                        .build()
                )
                val arr = json.optJSONArray("results") ?: JSONArray()
                val list = mutableListOf<SelectedCourse>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        SelectedCourse(
                            id = o.optString("id", "").trim(),
                            kcbh = o.optString("kcbh", "").trim(),
                            kcmc = o.optString("kcmc", "").trim(),
                            jxbmc = o.optString("jxbmc", "").trim(),
                            jxbbh = o.optString("jxbbh", "").trim(),
                            kcid = o.optString("kcid", "").trim(),
                            kcxz = o.optString("kcxz", "").trim(),
                            kclbName = o.optString("kclb", "").trim(),
                            kcgs = o.optString("kcgs", "").trim(),
                            xf = o.optString("xf", "").trim(),
                            zxs = o.optString("zxs", "").trim(),
                            classTime = o.optString("sksjdd", "").trim(),
                            jxbzc = o.optString("jxbzc", "").trim(),
                            teacher = o.optString("rkjs", "").trim(),
                            xnxq = o.optString("xnxq", "").trim(),
                            xkfs = o.optString("xkfs", "").trim(),
                            xklx = o.optString("xklx", "").trim()
                        )
                    )
                }
                list
            }
        }

    /**
     * 重修/补修：待选课程列表
     */
    override suspend fun queryRetakeBind(pcid: String): Result<List<RetakeCourse>> =
        withContext(Dispatchers.IO) {
            ensureCookies()
            runCatching {
                val json = executeGetJson("/admin/xsd/xk/listJxb/cxxk/bind?pcid=${encode(pcid)}")
                val arr = json.optJSONArray("data")
                    ?: json.optJSONArray("results")
                    ?: json.optJSONObject("data")?.optJSONArray("rows")
                    ?: JSONArray()
                val list = mutableListOf<RetakeCourse>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        RetakeCourse(
                            cxmdid = o.optString("cxmdid", "").trim(),
                            kcid = o.optString("kcid", "").trim(),
                            kcmc = o.optString("kcmc", "").trim(),
                            xnxq = o.optString("xnxq", "").trim(),
                            xf = o.optString("xf", "").trim()
                        )
                    )
                }
                list
            }
        }

    /**
     * 重修/补修：某门课程可选教学班
     */
    override suspend fun queryRetakeClasses(
        cxmdid: String,
        kcid: String,
        pcid: String
    ): Result<List<TeachingClass>> = withContext(Dispatchers.IO) {
        ensureCookies()
        runCatching {
            val json = executeGetJson(
                "/admin/xsd/xk/listJxb/cxxk/jxb?cxmdid=${encode(cxmdid)}&kcid=${encode(kcid)}&pcid=${encode(pcid)}"
            )
            val arr = json.optJSONArray("data")
                ?: json.optJSONArray("results")
                ?: json.optJSONObject("data")?.optJSONArray("rows")
                ?: JSONArray()
            val list = mutableListOf<TeachingClass>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(parseTeachingClass(o))
            }
            list
        }
    }

    /**
     * 重修选课
     */
    override suspend fun retakeSelect(
        jxbid: String,
        fjxbid: String,
        kcid: String,
        cxmdid: String,
        pcid: String
    ): Result<SelectionOutcome> = withContext(Dispatchers.IO) {
        ensureCookies()
        runCatching {
            val fb = FormBody.Builder()
                .add("jxbid", jxbid)
                .add("fjxbid", fjxbid)
                .add("kcid", kcid)
                .add("cxmdid", cxmdid)
                .add("pcid", pcid)
            val json = executePostJson("/admin/xsd/xk/cxxk/th", fb.build())
            val msg = json.optString("msg", "")
            val code = json.optInt("code", json.optInt("ret", -1))
            if (code != 0 && code != 200 && !msg.contains("成功")) {
                throw IOException(msg.ifBlank { "重修选课失败" })
            }
            SelectionOutcome(success = true, needConfirm = false, message = msg)
        }
    }

    /**
     * 重修退课
     */
    override suspend fun retakeDrop(
        jxbid: String,
        kcid: String,
        cxmdid: String,
        pcid: String
    ): Result<String> = withContext(Dispatchers.IO) {
        ensureCookies()
        runCatching {
            val fb = FormBody.Builder()
                .add("jxbid", jxbid)
                .add("kcid", kcid)
                .add("cxmdid", cxmdid)
                .add("pcid", pcid)
            val json = executePostJson("/admin/xsd/xk/cxxk/thtk", fb.build())
            val msg = json.optString("msg", "")
            val code = json.optInt("code", json.optInt("ret", -1))
            if (code != 0 && code != 200 && !msg.contains("成功")) {
                throw IOException(msg.ifBlank { "重修退课失败" })
            }
            msg
        }
    }

    private fun parseTeachingClass(o: JSONObject): TeachingClass {
        val quotaText = o.optString("yxrl", "").trim()
        val parts = quotaText.split("/")
        val selected = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: -1
        val parsedCapacity = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: -1
        val declaredCapacity = o.optString("bjrs", "").trim().toIntOrNull() ?: -1
        val capacity = if (parsedCapacity > 0) parsedCapacity else declaredCapacity
        val isFull = when {
            quotaText.contains("满") -> true
            selected >= 0 && capacity > 0 -> selected >= capacity
            else -> false
        }
        return TeachingClass(
            jxbid = o.optString("id", "").trim(),
            kcbh = o.optString("kcbh", "").trim(),
            kcmc = o.optString("kcmc", "").trim(),
            xf = o.optString("xf", "").trim(),
            status = o.optString("status", "0").trim(),
            conflict = o.optString("sfct", "0").trim().toIntOrNull() ?: 0,
            quotaText = quotaText,
            selectedCount = selected,
            capacity = capacity,
            isFull = isFull,
            classTime = o.optString("sksjdd", "").trim(),
            classTimeCodes = o.optString("sksjddstr", "").trim(),
            kcxz = o.optString("kcxz", "").trim(),
            kclbName = o.optString("kclbname", "").trim().ifBlank { o.optString("kclb", "").trim() },
            kcgs = o.optString("kcgs", "").trim(),
            jxms = o.optString("jxms", "").trim(),
            campusName = o.optString("kkxqmc", "").trim(),
            teacher = o.optString("teacher", "").trim(),
            jxbmc = o.optString("jxbmc", "").trim(),
            jxbbh = o.optString("jxbbh", "").trim(),
            ksxs = o.optString("ksxs", "").trim(),
            kclx = o.optString("kclx", "").trim(),
            jxbzc = o.optString("jxbzc", "").trim(),
            bz = o.optString("bz", "").trim(),
            waitlistJxbid = o.optString("hbjxb", "").trim()
        )
    }
}
