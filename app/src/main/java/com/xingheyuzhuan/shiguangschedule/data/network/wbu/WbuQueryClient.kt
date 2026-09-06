package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import android.content.Context
import android.util.Log
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.AcademicCourse
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.AcademicCourseGroup
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.AcademicProgressData
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.AcademicProgressSummary
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.AcademicStats
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.BuildingOption
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CAMPUS_HGH_UUID
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CAMPUS_MYH_ID
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CampusOption
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.ClassroomWeeklySchedule
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CourseGrade
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.FreeClassroom
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.FreeClassroomQueryResult
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.GradeQueryResult
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.GradeStats
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.KCXZ_DICT
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.KHFS_DICT
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.STANDARD_PERIODS
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.StudentProfile
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.BookDetail
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.BorrowHistoryBook
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.BorrowedBook
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HoldingItem
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.LibraryDashboardData
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.ReaderProfile
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.RenewResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder

/**
 * 教务系统会话已过期或未认证异常
 */
class WbuSessionExpiredException(
    val messageResId: Int = com.xingheyuzhuan.shiguangschedule.R.string.error_session_expired,
    override val message: String = "教务会话已失效，请重新登录"
) : Exception(message)

/**
 * 武汉商学院教务查询服务客户端（成绩查询、空教室查询、单教室课表探测）
 */
class WbuQueryClient(
    private val context: Context,
    val useVpn: Boolean = WbuSyncEngine.getSavedUseVpn(context) ?: false
) {
    internal val transport: WbuAuthTransport = WbuAuthTransport.getShared(context, useVpn)
    private val baseUrl: String get() = transport.jwxtBase
    private val client get() = transport.client

    init {
        transport.restoreCookieStore()
    }

    private fun ensureCookies() {
        if (transport.cookieStore.none { it.name == "jw_uf" }) {
            transport.restoreCookieStore()
        }
    }

    /**
     * 校验当前教务会话是否依然可用
     */
    suspend fun checkSession(): Boolean = withContext(Dispatchers.IO) {
        ensureCookies()
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/admin/xsd/xsdcjcx/getCurrentXnxq?sf_request_type=ajax")
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()

            val manualClient = client.newBuilder().followRedirects(false).build()
            manualClient.newCall(req).execute().use { resp ->
                if (resp.code in 300..399) return@use false
                val body = resp.body?.string().orEmpty()
                if (transport.looksLikeHtml(body) || body.isBlank()) return@use false
                val obj = runCatching { JSONObject(body) }.getOrNull() ?: return@use false
                obj.optInt("ret", -1) == 0 && obj.optString("data", "").isNotBlank()
            }
        }.getOrDefault(false)
    }

    /**
     * 获取历史可选学期列表（优先从课表页下拉框解析）
     */
    suspend fun fetchSemesterList(): List<String> = withContext(Dispatchers.IO) {
        ensureCookies()
        val result = mutableListOf<String>()
        runCatching {
            val queryUrl = "$baseUrl/admin/xsd/pkgl/xskb/queryKbForXsd"
            val req = Request.Builder().url(queryUrl).get().build()
            val html = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            if (html.isNotBlank() && !transport.looksLikeHtml(html).not()) {
                val doc = Jsoup.parse(html)
                doc.select("#xnxq1 option").forEach { opt ->
                    val v = opt.attr("value").trim()
                    if (v.isNotBlank() && !result.contains(v)) {
                        result.add(v)
                    }
                }
            }
        }
        result
    }

    /**
     * 动态拉取校区列表（带兜底）
     */
    suspend fun fetchCampusList(): List<CampusOption> = withContext(Dispatchers.IO) {
        val fallback = listOf(
            CampusOption(CAMPUS_HGH_UUID, "后官湖校区"),
            CampusOption(CAMPUS_MYH_ID, "马影河校区"),
            CampusOption("", "全部校区")
        )

        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/admin/api/jcsj/xqsj/getXqList")
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && !transport.looksLikeHtml(body)) {
                    val json = JSONObject(body)
                    if (json.optInt("ret") == 0) {
                        val arr = json.optJSONArray("data") ?: JSONArray()
                        val list = mutableListOf<CampusOption>()
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val id = item.optString("id", "").trim()
                            val name = item.optString("xqmc", "").trim()
                            if (id.isNotBlank() && name.isNotBlank()) {
                                list.add(CampusOption(id, name))
                            }
                        }
                        if (list.isNotEmpty()) {
                            list.add(CampusOption("", "全部校区"))
                            return@withContext list
                        }
                    }
                }
            }
            fallback
        }.getOrElse { fallback }
    }

    /**
     * 获取指定周次周一至周日对应的阳历日期（格式如 "11.20"）
     * 对应接口：/admin/system/jxzy/jsxx/getXqrqxx
     */
    suspend fun fetchWeekDates(week: Int, xnxq: String = ""): Map<Int, String>? = withContext(Dispatchers.IO) {
        ensureCookies()
        runCatching {
            val url = "$baseUrl/admin/system/jxzy/jsxx/getXqrqxx"
            val form = FormBody.Builder()
                .add("zcStr", week.toString())
            if (xnxq.isNotBlank()) form.add("xnxq", xnxq)

            val req = Request.Builder()
                .url(url)
                .post(form.build())
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && !transport.looksLikeHtml(body)) {
                    val json = JSONObject(body)
                    if (json.optInt("ret") == 0) {
                        val arr = json.optJSONArray("data") ?: JSONArray()
                        val map = mutableMapOf<Int, String>()
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val day = item.optString("xq").toIntOrNull() ?: continue
                            val dateStr = item.optString("rq", "")
                            if (dateStr.isNotBlank()) {
                                map[day] = dateStr
                            }
                        }
                        return@withContext map
                    }
                }
                null
            }
        }.getOrNull()
    }

    /**
     * 查询学生成绩
     */
    suspend fun queryGrades(
        startXnxq: String = "2020-2021-1",
        endXnxq: String = "2030-2031-2",
        kcmc: String = "",
        sfjg: String = "",
        kcxz: String = "",
        page: Int = 1,
        pageSize: Int = 200
    ): Result<GradeQueryResult> = withContext(Dispatchers.IO) {
        ensureCookies()
        runCatching {
            val url = "$baseUrl/admin/xsd/xsdcjcx/xsdQueryXscjList?gridtype=jqgrid"
            val formBuilder = FormBody.Builder()
                .add("page.pn", page.toString())
                .add("page.size", pageSize.toString())
                .add("sort", "xnxq")
                .add("order", "desc")
                .add("queryFields", "id,xnxq,kcmc,xf,kcxz,kclx,ksxs,kcgs,xdxz,kclb,cjfxms,zhcj,jd,hdxf,tscjzwmc,sfbk,cjlrjsxm,kcsx,fxcj,kkyxmc,")

            if (startXnxq.isNotBlank()) formBuilder.add("startXnxq", startXnxq)
            if (endXnxq.isNotBlank()) formBuilder.add("endXnxq", endXnxq)
            if (kcmc.isNotBlank()) formBuilder.add("kcmc", kcmc)
            if (sfjg.isNotBlank()) formBuilder.add("sfjg", sfjg)
            if (kcxz.isNotBlank()) formBuilder.add("kcxz", kcxz)

            val req = Request.Builder()
                .url(url)
                .post(formBuilder.build())
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$baseUrl/admin/xsd/xsdcjcx/qbcjcx")
                .build()

            val manualClient = client.newBuilder().followRedirects(false).build()
            val resp = manualClient.newCall(req).execute()
            if (resp.code in 300..399) {
                throw WbuSessionExpiredException(
                    messageResId = com.xingheyuzhuan.shiguangschedule.R.string.error_session_expired,
                    message = context.getString(com.xingheyuzhuan.shiguangschedule.R.string.error_session_expired)
                )
            }

            val raw = resp.body?.string().orEmpty()
            if (transport.looksLikeHtml(raw) || raw.contains("登录")) {
                throw WbuSessionExpiredException(
                    messageResId = com.xingheyuzhuan.shiguangschedule.R.string.error_session_expired,
                    message = context.getString(com.xingheyuzhuan.shiguangschedule.R.string.error_session_expired)
                )
            }

            val json = JSONObject(raw)
            val ret = json.optInt("ret", -1)
            val msg = json.optString("msg", "ok")
            if (ret != 0 && json.has("ret")) {
                throw IOException(msg.ifBlank { "查询成绩失败 (ret=$ret)" })
            }

            val total = json.optInt("total", 0)
            val resultsArr = json.optJSONArray("results") ?: JSONArray()
            val courses = mutableListOf<CourseGrade>()

            var totalXf = 0.0
            var totalPassedXf = 0.0
            var sumWeightedScore = 0.0
            var sumScoreXf = 0.0
            var sumXfjd = 0.0
            var passedCount = 0
            var failedCount = 0

            for (i in 0 until resultsArr.length()) {
                val item = resultsArr.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                val xnxqVal = item.optString("xnxq", "")
                val rawKcmc = item.optString("kcmc", "")
                // 清洗中括号课程编号，如 [BD0800052]程序设计实训 -> 程序设计实训
                val cleanName = rawKcmc.replace(Regex("^\\[.*?\\]"), "").trim().ifBlank { rawKcmc }
                val kcbh = item.optString("kcbh", "")
                val xf = item.optDouble("xf", 0.0)
                val hdxf = item.optDouble("hdxf", 0.0)
                val zhcj = item.optString("zhcj", "")
                val xfjd = item.optDouble("xfjd", 0.0)
                val kcxzVal = item.optString("kcxz", "")
                val propertyName = KCXZ_DICT[kcxzVal] ?: "其他"
                val teacher = item.optString("cjlrjsxm", "")
                val khfs = item.optString("khfs", "")
                val examMethod = KHFS_DICT[khfs] ?: if (khfs.isNotBlank()) "考核" else ""
                val xdxz = item.optString("xdxz", "")
                val studyNature = when (xdxz) {
                    "1" -> "初修"
                    "2" -> "重修"
                    else -> ""
                }
                val isMakeup = item.optString("sfbk", "0") == "1"

                // 及格判断：hdxf > 0，或综合成绩数字 >= 60，或非不及格/旷考字眼
                val isScorePassed = zhcj.toDoubleOrNull()?.let { it >= 60.0 }
                    ?: (hdxf > 0.0 || (!zhcj.contains("不及格") && !zhcj.contains("缺考") && !zhcj.contains("作弊") && zhcj.isNotBlank()))

                courses.add(
                    CourseGrade(
                        id = id,
                        xnxq = xnxqVal,
                        kcbh = kcbh,
                        courseName = cleanName,
                        credit = xf,
                        earnedCredit = hdxf,
                        score = zhcj,
                        gradePoint = xfjd,
                        propertyCode = kcxzVal,
                        propertyName = propertyName,
                        teacher = teacher,
                        examMethod = examMethod,
                        studyNature = studyNature,
                        isMakeup = isMakeup,
                        isPassed = isScorePassed
                    )
                )

                totalXf += xf
                totalPassedXf += hdxf
                sumXfjd += xfjd
                if (isScorePassed) passedCount++ else failedCount++

                val numericScore = zhcj.toDoubleOrNull()
                if (numericScore != null && xf > 0) {
                    sumWeightedScore += numericScore * xf
                    sumScoreXf += xf
                }
            }

            val weightedGpa = if (totalXf > 0) Math.round((sumXfjd / totalXf) * 100.0) / 100.0 else 0.0
            val weightedScore = if (sumScoreXf > 0) Math.round((sumWeightedScore / sumScoreXf) * 100.0) / 100.0 else 0.0

            GradeQueryResult(
                ret = 0,
                msg = msg,
                total = total,
                courses = courses,
                stats = GradeStats(
                    totalCredits = Math.round(totalXf * 10.0) / 10.0,
                    earnedCredits = Math.round(totalPassedXf * 10.0) / 10.0,
                    weightedGpa = weightedGpa,
                    weightedScore = weightedScore,
                    courseCount = courses.size,
                    passedCount = passedCount,
                    failedCount = failedCount
                )
            )
        }
    }

    /**
     * 查询空教室列表
     */
    suspend fun queryFreeClassrooms(
        campusId: String,
        buildingCode: String = "",
        roomType: String = "",
        roomName: String = "",
        week: Int?,
        dayOfWeek: String, // 支持单个星期如 "1" 或逗号分隔的多选如 "1,2,3"
        sections: String = "",
        queryType: String = "1", // "1": 按节次, "2": 按时间段
        beginTime: String = "",
        endTime: String = "",
        page: Int = 1,
        pageSize: Int = 50,
        xnxq: String = ""
    ): Result<FreeClassroomQueryResult> = withContext(Dispatchers.IO) {
        ensureCookies()
        runCatching {
            val url = "$baseUrl/admin/system/jxzy/jsxx/getKxjscx?gridtype=jqgrid"

            // 修复教务系统后官湖传 1 的 bug
            val realCampusId = when (campusId) {
                "1", "HGH" -> CAMPUS_HGH_UUID
                "MYH" -> CAMPUS_MYH_ID
                "ALL" -> ""
                else -> campusId
            }

            val formBuilder = FormBody.Builder()
                .add("page.pn", page.toString())
                .add("page.size", pageSize.toString())
                .add("sort", "id")
                .add("order", "asc")
                .add("type", queryType)
                .add("xqdm", realCampusId)
                .add("jxldm", buildingCode)
                .add("jslx", roomType)
                .add("jsmc", roomName)
                .add("zcStr", week?.toString().orEmpty())
                .add("xqStr", dayOfWeek)

            if (queryType == "1") {
                formBuilder.add("jcStr", sections)
            } else {
                formBuilder.add("begintime", beginTime)
                formBuilder.add("endtime", endTime)
            }

            formBuilder.add("queryFields", "id,jsbh,jsmc,jslx,zdskrnrs,jxlmc,jxldm,szlc,gnqmc,xqmc,xqdm,")

            if (xnxq.isNotBlank()) {
                formBuilder.add("xnxq", xnxq)
            }

            val req = Request.Builder()
                .url(url)
                .post(formBuilder.build())
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$baseUrl/admin/system/jxzy/jsxx/queryForXsd")
                .build()

            val manualClient = client.newBuilder().followRedirects(false).build()
            val resp = manualClient.newCall(req).execute()
            if (resp.code in 300..399) {
                throw WbuSessionExpiredException(
                    messageResId = com.xingheyuzhuan.shiguangschedule.R.string.error_session_expired,
                    message = context.getString(com.xingheyuzhuan.shiguangschedule.R.string.error_session_expired)
                )
            }

            val raw = resp.body?.string().orEmpty()
            if (transport.looksLikeHtml(raw) || raw.contains("登录")) {
                throw WbuSessionExpiredException(
                    messageResId = com.xingheyuzhuan.shiguangschedule.R.string.error_session_expired,
                    message = context.getString(com.xingheyuzhuan.shiguangschedule.R.string.error_session_expired)
                )
            }

            val json = JSONObject(raw)
            val total = json.optInt("total", 0)
            val totalPages = json.optInt("totalPages", 1)
            val resultsArr = json.optJSONArray("results") ?: JSONArray()
            val classrooms = mutableListOf<FreeClassroom>()

            for (i in 0 until resultsArr.length()) {
                val item = resultsArr.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                val jsbh = item.optString("jsbh", "")
                val jsmc = item.optString("jsmc", "")
                val cleanRoomName = jsmc.replace(Regex("\\(.*?\\)"), "").trim()
                val jxlmc = item.optString("jxlmc", "未知楼栋")
                val jxldm = item.optString("jxldm", "")
                val xqmc = item.optString("xqmc", "")
                val xqdm = item.optString("xqdm", "")
                val capacity = item.optInt("zdskrnrs", 0)
                val floor = item.optString("szlc", "")
                val jslx = item.optString("jslx", "")
                val gnqmc = item.optString("gnqmc", "").ifBlank {
                    com.xingheyuzhuan.shiguangschedule.data.model.wbu.JSLX_DICT[jslx] ?: "普通教室"
                }

                classrooms.add(
                    FreeClassroom(
                        id = id,
                        jsbh = jsbh,
                        jsmc = jsmc,
                        cleanRoomName = cleanRoomName,
                        jxlmc = jxlmc,
                        jxldm = jxldm,
                        xqmc = xqmc,
                        xqdm = xqdm,
                        capacity = capacity,
                        floor = floor,
                        roomType = gnqmc,
                        gnqmc = gnqmc
                    )
                )
            }

            FreeClassroomQueryResult(
                ret = 0,
                msg = "ok",
                total = total,
                totalPages = totalPages,
                classrooms = classrooms
            )
        }
    }

    /**
     * 单个教室探测整周（7天 × 5大节 = 35个时段）课表占用/空闲情况
     * 点开详情抽屉后，由用户手动点击触发
     */
    suspend fun probeClassroomWeeklySchedule(
        roomName: String,
        week: Int,
        xnxq: String = "",
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Result<ClassroomWeeklySchedule> = withContext(Dispatchers.IO) {
        ensureCookies()
        runCatching {
            val cleanName = roomName.replace(Regex("\\(.*?\\)"), "").trim()
            val totalPeriods = 7 * STANDARD_PERIODS.size // 35
            var completedCount = 0

            // 限制并发度为 8，既保证探测高效迅速，又防止把教务后端冲垮
            val semaphore = Semaphore(8)
            val scheduleMatrix = mutableMapOf<Int, MutableMap<Int, Boolean>>()
            for (day in 1..7) {
                scheduleMatrix[day] = mutableMapOf()
            }

            val tasks = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

            for (day in 1..7) {
                for (period in STANDARD_PERIODS) {
                    val task = async {
                        val isFree = semaphore.withPermit {
                            runCatching {
                                val url = "$baseUrl/admin/system/jxzy/jsxx/getKxjscx?gridtype=jqgrid"
                                val form = FormBody.Builder()
                                    .add("page.pn", "1")
                                    .add("page.size", "1")
                                    .add("type", "1")
                                    .add("jsmc", cleanName)
                                    .add("zcStr", week.toString())
                                    .add("xqStr", day.toString())
                                    .add("jcStr", period.jcStr)

                                if (xnxq.isNotBlank()) form.add("xnxq", xnxq)

                                val req = Request.Builder()
                                    .url(url)
                                    .post(form.build())
                                    .header("X-Requested-With", "XMLHttpRequest")
                                    .build()

                                client.newCall(req).execute().use { resp ->
                                    val body = resp.body?.string().orEmpty()
                                    val json = JSONObject(body)
                                    json.optInt("total", 0) > 0
                                }
                            }.getOrDefault(false)
                        }

                        synchronized(scheduleMatrix) {
                            scheduleMatrix[day]?.put(period.periodId, isFree)
                            completedCount++
                            onProgress?.invoke(completedCount, totalPeriods)
                        }
                        Unit
                    }
                    tasks.add(task)
                }
            }

            tasks.awaitAll()

            ClassroomWeeklySchedule(
                roomName = cleanName,
                week = week,
                schedule = scheduleMatrix
            )
        }
    }

    /**
     * 查询学业完成度与课程进程
     * 并行请求 5 个核心接口：
     * 1. /admin/xsd/xskp/xskp?fasz=2 (学生名片)
     * 2. /admin/xsd/xskp/xyqk?fasz=2 (学业统计与排名)
     * 3. /admin/xsd/xskp/xywcd?fasz=2 (培养方案完成度百分比)
     * 4. /admin/xsd/xskp/xyjc?fasz=2 (课程性质树)
     * 5. /admin/xsd/xskp/xyjc?fasz=3 (学年学期推进树)
     */
    suspend fun queryAcademicProgress(): Result<AcademicProgressData> = withContext(Dispatchers.IO) {
        ensureCookies()
        runCatching {
            fun executeGetJson(path: String): JSONObject {
                val req = Request.Builder()
                    .url("$baseUrl$path")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "$baseUrl/admin/xsd/xskp?fasz=2")
                    .get()
                    .build()

                val manualClient = client.newBuilder().followRedirects(false).build()
                return manualClient.newCall(req).execute().use { resp ->
                    if (resp.code in 300..399) {
                        throw WbuSessionExpiredException(
                            messageResId = com.xingheyuzhuan.shiguangschedule.R.string.error_session_expired,
                            message = context.getString(com.xingheyuzhuan.shiguangschedule.R.string.error_session_expired)
                        )
                    }
                    val body = resp.body?.string().orEmpty()
                    if (transport.looksLikeHtml(body) || body.contains("登录")) {
                        throw WbuSessionExpiredException(
                            messageResId = com.xingheyuzhuan.shiguangschedule.R.string.error_session_expired,
                            message = context.getString(com.xingheyuzhuan.shiguangschedule.R.string.error_session_expired)
                        )
                    }
                    val json = JSONObject(body)
                    if (json.optInt("ret", -1) == -1) {
                        throw WbuSessionExpiredException(
                            messageResId = com.xingheyuzhuan.shiguangschedule.R.string.error_session_expired,
                            message = json.optString("msg", context.getString(com.xingheyuzhuan.shiguangschedule.R.string.error_session_expired))
                        )
                    }
                    json
                }
            }

            kotlinx.coroutines.coroutineScope {
                val studentDeferred = async { executeGetJson("/admin/xsd/xskp/xskp?fasz=2") }
                val statsDeferred = async { executeGetJson("/admin/xsd/xskp/xyqk?fasz=2") }
                val progressDeferred = async { executeGetJson("/admin/xsd/xskp/xywcd?fasz=2") }
                val natureDeferred = async { executeGetJson("/admin/xsd/xskp/xyjc?fasz=2") }
                val semesterDeferred = async { executeGetJson("/admin/xsd/xskp/xyjc?fasz=3") }

                val studentJson = studentDeferred.await()
                val statsJson = statsDeferred.await()
                val progressJson = progressDeferred.await()
                val natureJson = natureDeferred.await()
                val semesterJson = semesterDeferred.await()

                // 1. 解析学生基本信息
                val studentObj = studentJson.optJSONObject("data") ?: JSONObject()
                val studentProfile = StudentProfile(
                    id = studentObj.optString("id", ""),
                    studentId = studentObj.optString("xh", ""),
                    name = studentObj.optString("xm", ""),
                    gender = studentObj.optString("xb", ""),
                    ethnicity = studentObj.optString("mz", ""),
                    gradeYear = studentObj.optString("sznj", ""),
                    college = studentObj.optString("skyx", ""),
                    major = studentObj.optString("zymc", ""),
                    className = studentObj.optString("bjmc", ""),
                    expectedGradDate = studentObj.optString("yjbyrq", "")
                )

                // 2. 解析学业指标与专业排名
                val statsObj = statsJson.optJSONObject("data") ?: JSONObject()
                val academicStats = AcademicStats(
                    gpa = statsObj.optDouble("gpa").takeIf { !it.isNaN() },
                    averageScore = statsObj.optDouble("pjcj").takeIf { !it.isNaN() },
                    majorRank = statsObj.optString("gpazypm", ""),
                    earnedCredits = statsObj.optDouble("hdzxf", 0.0),
                    failedCourseCount = statsObj.optInt("bjgms", 0),
                    retakeCourseCount = statsObj.optInt("cxmcs", 0),
                    selectedCourseCount = statsObj.optInt("yxkms", 0)
                )

                // 3. 解析学分完成度百分比
                val progressObj = progressJson.optJSONObject("data") ?: JSONObject()
                val xfwcdPercent = progressObj.optDouble("xfwcd", 0.0)

                // 4. 解析课程组辅助函数
                fun parseCourseGroups(json: JSONObject): List<AcademicCourseGroup> {
                    val dataArr = json.optJSONArray("data") ?: JSONArray()
                    val groups = mutableListOf<AcademicCourseGroup>()

                    for (i in 0 until dataArr.length()) {
                        val groupObj = dataArr.optJSONObject(i) ?: continue
                        val nodeId = groupObj.optString("nodeId", "")
                        val nodeName = groupObj.optString("nodeName", "")
                        val groupEarnedCredits = groupObj.optDouble("hdxf", 0.0)

                        val kcArr = groupObj.optJSONArray("kcList") ?: JSONArray()
                        val courseList = mutableListOf<AcademicCourse>()
                        var groupPlanCredits = 0.0

                        for (j in 0 until kcArr.length()) {
                            val cObj = kcArr.optJSONObject(j) ?: continue
                            val planXf = cObj.optString("xf", "0").toDoubleOrNull() ?: 0.0
                            val earnedXf = cObj.optString("hdxf", "0").toDoubleOrNull() ?: 0.0
                            groupPlanCredits += planXf

                            courseList.add(
                                AcademicCourse(
                                    courseCode = cObj.optString("kcbh", ""),
                                    courseName = cObj.optString("kcmc", ""),
                                    college = cObj.optString("kkyx", ""),
                                    planCredit = planXf,
                                    earnedCredit = earnedXf,
                                    category = cObj.optString("kclb", ""),
                                    nature = cObj.optString("kcxz", ""),
                                    score = cObj.optString("zhcj", ""),
                                    gpa = cObj.optString("jd", ""),
                                    status = cObj.optString("wczt", "")
                                )
                            )
                        }

                        groups.add(
                            AcademicCourseGroup(
                                nodeId = nodeId,
                                nodeName = nodeName,
                                earnedCredits = groupEarnedCredits,
                                planCredits = Math.round(groupPlanCredits * 100.0) / 100.0,
                                courses = courseList
                            )
                        )
                    }
                    return groups
                }

                val natureGroups = parseCourseGroups(natureJson)
                val semesterGroups = parseCourseGroups(semesterJson)

                // 5. 聚合全大学全周期课程进度统计（基于 semesterGroups 或 natureGroups）
                var totalPlan = 0
                var totalCompleted = 0
                var totalStudying = 0
                var totalUncompleted = 0

                val countGroups = if (semesterGroups.isNotEmpty()) semesterGroups else natureGroups
                countGroups.forEach { g ->
                    g.courses.forEach { c ->
                        totalPlan++
                        when {
                            c.isCompleted -> totalCompleted++
                            c.isStudying -> totalStudying++
                            else -> totalUncompleted++
                        }
                    }
                }

                val summary = AcademicProgressSummary(
                    completionPercentage = xfwcdPercent,
                    totalPlanCourses = totalPlan,
                    totalCompletedCourses = totalCompleted,
                    totalStudyingCourses = totalStudying,
                    totalUncompletedCourses = totalUncompleted
                )

                AcademicProgressData(
                    student = studentProfile,
                    stats = academicStats,
                    summary = summary,
                    natureGroups = natureGroups,
                    semesterGroups = semesterGroups
                )
            }
        }
    }

    // -------------------------------------------------------------
    // 图书馆（汇文 OPAC）接口与数据解析
    // -------------------------------------------------------------

    private val opacBaseUrl: String get() = transport.opacBase()

    /**
     * 确保持有有效的 OPAC 会话 (PHPSESSID)
     * 若未持有或已失效，通过 CASTGC 向 CAS 换票；若无有效 CASTGC 则抛出 WbuSessionExpiredException
     */
    suspend fun ensureOpacSession(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        transport.restoreCookieStore()
        val existingSession = transport.cookieStore.find { it.name == "PHPSESSID" && !it.value.isBlank() }
        if (existingSession != null && !forceRefresh) {
            // 验证会话是否真正可用
            val testReq = Request.Builder()
                .url("$opacBaseUrl/reader/redr_info.php")
                .get()
                .build()
            val manualClient = client.newBuilder().followRedirects(false).build()
            val valid = runCatching {
                manualClient.newCall(testReq).execute().use { resp ->
                    resp.code == 200 && !resp.header("Location").orEmpty().contains("login")
                }
            }.getOrDefault(false)

            if (valid) return@withContext existingSession.value
        }

        // 需要通过 CASTGC 换票
        val tgcCookie = transport.cookieStore.find { it.name == "CASTGC" && !it.value.isBlank() }
        if (tgcCookie == null) {
            Log.w("WbuQueryClient", "No CASTGC found in cookie store for OPAC authentication")
            throw WbuSessionExpiredException(message = "统一认证会话已过期，请重新登录")
        }

        // 1. 请求 CAS 获取重定向到 OPAC 的 ST ticket
        // 核心关键点：汇文 OPAC 后端 phpCAS 核销 ST 时，其向 CAS 声明的自身原始服务地址始终是
        // https://opac.wbu.edu.cn/reader/hwthau.php（若传 WebVPN 镜像地址会导致 CAS 校验 service 不匹配抛出 500 Authentication failed!）
        val opacService = "https://opac.wbu.edu.cn/reader/hwthau.php"
        val casLoginUrl = "${transport.idsBase()}/authserver/login?service=${URLEncoder.encode(opacService, "UTF-8")}"

        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val casReq = Request.Builder()
            .url(casLoginUrl)
            .header("User-Agent", transport.authUserAgent())
            .header("Accept-Language", transport.authAcceptLanguage)
            .get()
            .build()

        val location = noRedirectClient.newCall(casReq).execute().use { resp ->
            resp.header("Location")
        }

        if (location.isNullOrBlank() || !location.contains("ticket=")) {
            Log.w("WbuQueryClient", "CAS failed to grant ST ticket for OPAC; redirect location=$location")
            throw WbuSessionExpiredException(message = "统一认证票据失效，请重新登录")
        }

        // 2. 将 CAS 回跳的目标地址映射到当前网络通道（WebVPN 下重写为代理子域以穿透校外网关）
        val targetHwthauUrl = if (useVpn) {
            val queryPart = location.substringAfter("?", "")
            val querySuffix = if (queryPart.isNotBlank()) "?$queryPart" else ""
            "$opacBaseUrl/reader/hwthau.php$querySuffix"
        } else {
            location
        }

        Log.i("WbuQueryClient", "Accessing OPAC hwthau to exchange ticket: $targetHwthauUrl (useVpn=$useVpn)")

        // 访问 OPAC 登录跳转链接，禁用重定向以捕获第一跳的 Set-Cookie: PHPSESSID
        val opacAuthReq = Request.Builder()
            .url(targetHwthauUrl)
            .header("User-Agent", transport.authUserAgent())
            .header("Accept-Language", transport.authAcceptLanguage)
            .get()
            .build()

        var phpSessId: String? = null
        var nextLocation: String? = null
        noRedirectClient.newCall(opacAuthReq).execute().use { resp ->
            nextLocation = resp.header("Location")
            val cookies = okhttp3.Cookie.parseAll(resp.request.url, resp.headers)
            phpSessId = cookies.find { it.name == "PHPSESSID" }?.value
        }

        if (phpSessId.isNullOrBlank()) {
            phpSessId = transport.cookieStore.find { it.name == "PHPSESSID" && !it.value.isBlank() }?.value
        }

        if (phpSessId.isNullOrBlank()) {
            Log.e("WbuQueryClient", "Failed to obtain PHPSESSID from OPAC login redirect")
            throw IOException("未能从图书馆系统获取有效会话")
        }

        // 3. 必须携带新领取的 PHPSESSID 循环跟随重定向直至落地（如 hwthau.php -> redr_info.php），
        // 促使 OPAC 服务端真正将已认证的读者身份写入该 PHPSESSID 对应的 Session 存储！
        var currentHopUrl = if (!nextLocation.isNullOrBlank()) {
            transport.resolveAbsoluteUrl(opacBaseUrl, nextLocation)
        } else {
            "$opacBaseUrl/reader/redr_info.php"
        }

        var hops = 0
        while (hops < 5) {
            hops++
            val followReq = Request.Builder()
                .url(currentHopUrl)
                .header("User-Agent", transport.authUserAgent())
                .get()
                .build()

            val (hopCode, hopLoc) = noRedirectClient.newCall(followReq).execute().use { r ->
                Pair(r.code, r.header("Location"))
            }

            if (hopCode in 300..399 && !hopLoc.isNullOrBlank()) {
                currentHopUrl = transport.resolveAbsoluteUrl(opacBaseUrl, hopLoc)
            } else {
                break
            }
        }

        transport.persistCookieStore()
        phpSessId
    }

    /**
     * 查询读者个人信息与借阅概况
     */
    suspend fun queryReaderProfile(): ReaderProfile = withContext(Dispatchers.IO) {
        ensureOpacSession()

        // 1. 请求 /reader/redr_info.php
        val infoReq = Request.Builder()
            .url("$opacBaseUrl/reader/redr_info.php")
            .get()
            .build()

        val infoHtml = client.newCall(infoReq).execute().use { resp ->
            if (resp.header("Location").orEmpty().contains("login")) {
                throw WbuSessionExpiredException(message = "图书馆会话已过期，请重新登录")
            }
            resp.body?.string().orEmpty()
        }

        val nameMatch = Regex("""<font color="blue">([^<]+)</font>\s*<strong><a href="\.\./reader/logout\.php"""").find(infoHtml)
        val name = nameMatch?.groupValues?.get(1)?.trim().orEmpty()

        val profileNameMatch = Regex("""<span class="profile-name">([^<]+)</span>""").find(infoHtml)
        val profileName = profileNameMatch?.groupValues?.get(1)?.trim().orEmpty()

        val maxLendMatch = Regex("""<span class="bigger-170">\s*(\d+)\s*</span>\s*<br />\s*<span class="text">\s*最多可借""").find(infoHtml)
        val maxLend = maxLendMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val overdueMatch = Regex("""超期图书</div>\s*<span class="infobox-data-number">(\d+)</span>""").find(infoHtml)
        val overdue = overdueMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

        // 2. 请求 /reader/redr_info_rule.php
        val ruleReq = Request.Builder()
            .url("$opacBaseUrl/reader/redr_info_rule.php")
            .get()
            .build()

        val ruleHtml = client.newCall(ruleReq).execute().use { resp ->
            resp.body?.string().orEmpty()
        }

        val certNoMatch = Regex("""证件号：\s*</span>\s*([^<\t\r\n]+)""", RegexOption.IGNORE_CASE).find(ruleHtml)
        val deptMatch = Regex("""工作单位：\s*</span>\s*([^<\t\r\n]+)""", RegexOption.IGNORE_CASE).find(ruleHtml)
        val rTypeMatch = Regex("""读者类型：\s*</span>\s*([^<\t\r\n]+)""", RegexOption.IGNORE_CASE).find(ruleHtml)
        val totalBorrowMatch = Regex("""累计借书：\s*</span>\s*(\d+)\s*册次""", RegexOption.IGNORE_CASE).find(ruleHtml)
        val certEndDateMatch = Regex("""失效日期：\s*</span>\s*([0-9\*\-]+)""", RegexOption.IGNORE_CASE).find(ruleHtml)

        ReaderProfile(
            name = name.ifBlank { profileName },
            certNo = certNoMatch?.groupValues?.get(1)?.trim().orEmpty(),
            department = deptMatch?.groupValues?.get(1)?.trim().orEmpty(),
            readerType = rTypeMatch?.groupValues?.get(1)?.trim() ?: "学生(本科)",
            maxLend = maxLend,
            overdue = overdue,
            totalBorrow = totalBorrowMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            certEndDate = certEndDateMatch?.groupValues?.get(1)?.trim().orEmpty()
        )
    }

    /**
     * 查询当前在借图书列表
     */
    suspend fun queryCurrentBorrows(): List<BorrowedBook> = withContext(Dispatchers.IO) {
        ensureOpacSession()

        val req = Request.Builder()
            .url("$opacBaseUrl/reader/book_lst.php")
            .get()
            .build()

        val html = client.newCall(req).execute().use { resp ->
            if (resp.header("Location").orEmpty().contains("login")) {
                throw WbuSessionExpiredException(message = "图书馆会话已过期，请重新登录")
            }
            resp.body?.string().orEmpty()
        }

        val doc = Jsoup.parse(html)
        val table = doc.selectFirst("table") ?: return@withContext emptyList()
        val rows = table.select("tr")
        if (rows.size <= 1) return@withContext emptyList()

        val books = mutableListOf<BorrowedBook>()
        val today = java.time.LocalDate.now()

        for (i in 1 until rows.size) {
            val cols = rows[i].select("td, th")
            if (cols.size < 7) continue

            val barcode = cols[0].text().trim()
            val titleCol = cols[1]
            val fullTitle = titleCol.text().trim()

            // 提取 marc_no
            val marcLink = titleCol.selectFirst("a[href*='marc_no']")?.attr("href").orEmpty()
            val marcNoMatch = Regex("""marc_no=([a-zA-Z0-9%_\-=+]+)""").find(marcLink)
            val marcNo = marcNoMatch?.groupValues?.get(1).orEmpty()

            var title = fullTitle
            var author = ""
            if (fullTitle.contains(" / ")) {
                val parts = fullTitle.split(" / ", limit = 2)
                title = parts[0].trim()
                author = parts.getOrNull(1)?.trim().orEmpty()
            }

            val borrowDate = cols[2].text().trim()
            val dueDate = cols[3].text().trim()
            val renewCount = cols[4].text().trim().toIntOrNull() ?: 0
            val location = cols[5].text().trim()
            val attachment = cols[6].text().trim()

            // 提取续借 check token
            val renewCol = if (cols.size > 7) cols[7].html() else ""
            val checkMatch = Regex("""getInLib\('([^']+)','([^']+)','([^']+)'\)""").find(renewCol)
            val renewCheck = checkMatch?.groupValues?.get(2).orEmpty()
            val canRenew = checkMatch != null

            var daysRemaining: Int? = null
            var isOverdue = false
            if (dueDate.isNotBlank()) {
                runCatching {
                    val dueLocalDate = java.time.LocalDate.parse(dueDate)
                    val diff = java.time.temporal.ChronoUnit.DAYS.between(today, dueLocalDate).toInt()
                    daysRemaining = diff
                    isOverdue = diff < 0
                }
            }

            books.add(
                BorrowedBook(
                    barcode = barcode,
                    title = title,
                    author = author,
                    borrowDate = borrowDate,
                    dueDate = dueDate,
                    daysRemaining = daysRemaining,
                    isOverdue = isOverdue,
                    renewCount = renewCount,
                    location = location,
                    attachment = attachment,
                    marcNo = marcNo,
                    renewCheck = renewCheck,
                    canRenew = canRenew
                )
            )
        }

        books
    }

    /**
     * 查询历史借阅记录（默认全部拉取）
     */
    suspend fun queryBorrowHistory(all: Boolean = true, page: Int? = null): List<BorrowHistoryBook> = withContext(Dispatchers.IO) {
        ensureOpacSession()

        val url = when {
            all -> "$opacBaseUrl/reader/book_hist.php?para_string=all"
            page != null -> "$opacBaseUrl/reader/book_hist.php?page=$page"
            else -> "$opacBaseUrl/reader/book_hist.php"
        }

        val req = Request.Builder()
            .url(url)
            .get()
            .build()

        val html = client.newCall(req).execute().use { resp ->
            if (resp.header("Location").orEmpty().contains("login")) {
                throw WbuSessionExpiredException(message = "图书馆会话已过期，请重新登录")
            }
            resp.body?.string().orEmpty()
        }

        val doc = Jsoup.parse(html)
        val table = doc.selectFirst("table") ?: return@withContext emptyList()
        val rows = table.select("tr")
        if (rows.size <= 1) return@withContext emptyList()

        val history = mutableListOf<BorrowHistoryBook>()

        for (i in 1 until rows.size) {
            val cols = rows[i].select("td, th")
            if (cols.size < 7) continue

            val idx = cols[0].text().trim()
            val barcode = cols[1].text().trim()
            val titleCol = cols[2]
            val title = titleCol.text().trim()
            val marcLink = titleCol.selectFirst("a[href*='marc_no']")?.attr("href").orEmpty()
            val marcNoMatch = Regex("""marc_no=([a-zA-Z0-9%_\-=+]+)""").find(marcLink)
            val marcNo = marcNoMatch?.groupValues?.get(1).orEmpty()

            val author = cols[3].text().trim()
            val borrowDate = cols[4].text().trim()
            val returnDate = cols[5].text().trim()
            val location = cols[6].text().trim()

            history.add(
                BorrowHistoryBook(
                    index = idx,
                    barcode = barcode,
                    title = title,
                    author = author,
                    borrowDate = borrowDate,
                    returnDate = returnDate,
                    location = location,
                    marcNo = marcNo
                )
            )
        }

        history
    }

    /**
     * 单本图书续借
     */
    suspend fun renewBook(barcode: String, check: String): RenewResult = withContext(Dispatchers.IO) {
        ensureOpacSession()

        val timestamp = System.currentTimeMillis()
        val renewUrl = "$opacBaseUrl/reader/ajax_renew.php?bar_code=${URLEncoder.encode(barcode, "UTF-8")}&check=${URLEncoder.encode(check, "UTF-8")}&captcha=&time=$timestamp"

        val req = Request.Builder()
            .url(renewUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()

        val html = client.newCall(req).execute().use { resp ->
            resp.body?.string().orEmpty()
        }

        val cleanMsg = Jsoup.parse(html).text().trim()
        val isSuccess = cleanMsg.contains("续借成功")

        RenewResult(
            success = isSuccess,
            message = cleanMsg.ifBlank { if (isSuccess) "续借成功" else "续借失败" }
        )
    }

    /**
     * 获取图书详细元数据（ISBN、分类号、出版社、馆藏纸质副本分布）
     */
    suspend fun queryBookDetail(marcNo: String): BookDetail = withContext(Dispatchers.IO) {
        ensureOpacSession()

        val req = Request.Builder()
            .url("$opacBaseUrl/opac/item.php?marc_no=${URLEncoder.encode(marcNo, "UTF-8")}")
            .get()
            .build()

        val html = client.newCall(req).execute().use { resp ->
            resp.body?.string().orEmpty()
        }

        val doc = Jsoup.parse(html)
        val metadata = mutableMapOf<String, String>()

        doc.select("dl").forEach { dl ->
            val dt = dl.selectFirst("dt")?.text()?.replace(":", "")?.replace("：", "")?.trim().orEmpty()
            val dd = dl.selectFirst("dd")?.text()?.trim().orEmpty()
            if (dt.isNotBlank() && dd.isNotBlank()) {
                metadata[dt] = dd
            }
        }

        val isbnRaw = metadata["ISBN及定价"].orEmpty()
        val isbnCleanMatch = Regex("""(\d[\d\-]+)""").find(isbnRaw)
        val cleanIsbn = isbnCleanMatch?.groupValues?.get(1)?.replace("-", "").orEmpty()

        val holdings = mutableListOf<HoldingItem>()
        val tables = doc.select("table")
        for (tbl in tables) {
            val text = tbl.text()
            if (text.contains("索书号") && text.contains("条码号")) {
                val rows = tbl.select("tr")
                for (i in 1 until rows.size) {
                    val cols = rows[i].select("td, th")
                    if (cols.size >= 5) {
                        holdings.add(
                            HoldingItem(
                                callNo = cols[0].text().trim(),
                                barcode = cols[1].text().trim(),
                                location = cols[3].text().trim(),
                                status = cols[4].text().trim()
                            )
                        )
                    }
                }
                break
            }
        }

        val coverUrl = if (cleanIsbn.isNotBlank()) {
            "https://covers.openlibrary.org/b/isbn/$cleanIsbn-M.jpg"
        } else ""

        BookDetail(
            title = metadata["题名/责任者"].orEmpty(),
            publisher = metadata["出版发行项"].orEmpty(),
            isbn = isbnRaw,
            cleanIsbn = cleanIsbn,
            callNo = metadata["中图法分类号"].orEmpty(),
            summary = metadata["提要文摘附注"].orEmpty(),
            coverUrl = coverUrl,
            holdings = holdings
        )
    }

    /**
     * 聚合查询图书馆仪表盘全部数据（读者概况 + 当前在借 + 借阅历史）
     */
    suspend fun queryLibraryDashboard(): LibraryDashboardData = withContext(Dispatchers.IO) {
        kotlinx.coroutines.coroutineScope {
            val profileDeferred = async { queryReaderProfile() }
            val currentDeferred = async { queryCurrentBorrows() }
            val historyDeferred = async { queryBorrowHistory(all = true) }

            val profile = profileDeferred.await()
            val current = currentDeferred.await()
            val history = historyDeferred.await()

            LibraryDashboardData(
                profile = profile,
                currentBorrows = current,
                historyBorrows = history
            )
        }
    }
}
