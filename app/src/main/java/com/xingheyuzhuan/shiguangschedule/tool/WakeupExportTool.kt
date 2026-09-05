package com.xingheyuzhuan.shiguangschedule.tool

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfig
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWithWeeks
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * WakeUp 课程表 (.wakeup_schedule) 导出工具。
 * 对齐 WakeUp 官方备份格式规范（5 行 JSON）：
 * 1. TimeTable 配置
 * 2. TimeDetail 时间段列表
 * 3. TableCompat 课表参数
 * 4. CourseBase 课程信息
 * 5. CourseDetail 课程时段细节
 */
object WakeupExportTool {

    private val wakeupJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val SG_COLORS = listOf(
        "FFCC99", "FFE699", "E6FF99", "CCFF99", "99FFB3", "99FFE6",
        "99FFFF", "99E6FF", "B399FF", "FF99E6", "FF99CC", "FF99B3"
    )

    @Serializable
    data class WakeupTimeTable(
        val id: Int = 1,
        val name: String = "时间表",
        val sameLen: Boolean = true,
        val courseLen: Int = 45,
        val sameBreakLen: Boolean = false,
        val theBreakLen: Int = 10
    )

    @Serializable
    data class WakeupTimeDetail(
        val node: Int,
        val startTime: String,
        val endTime: String,
        val timeTable: Int = 1
    )

    @Serializable
    data class WakeupTableCompat(
        val id: Int = 1,
        val tableName: String,
        val nodes: Int = 10,
        val background: String = "",
        val timeTable: Int = 1,
        val startDate: String,
        val maxWeek: Int = 20,
        val itemHeight: Int = 64,
        val itemAlpha: Int = 50,
        val itemTextSize: Int = 12,
        val widgetItemHeight: Int = 64,
        val widgetItemAlpha: Int = 50,
        val widgetItemTextSize: Int = 12,
        val strokeColor: Int = -2130706433,
        val widgetStrokeColor: Int = -2130706433,
        val textColor: Int = -16777216,
        val widgetTextColor: Int = -16777216,
        val courseTextColor: Int = -1,
        val widgetCourseTextColor: Int = -1,
        val showSat: Boolean = true,
        val showSun: Boolean = true,
        val sundayFirst: Boolean = false,
        val showOtherWeekCourse: Boolean = true,
        val showTime: Boolean = false,
        val type: Int = 0
    )

    @Serializable
    data class WakeupCourseBase(
        val id: Int,
        val courseName: String,
        val color: String,
        val tableId: Int = 1,
        val note: String = "",
        val credit: Double = 0.0
    )

    @Serializable
    data class WakeupCourseDetail(
        val id: Int,
        val day: Int,
        val room: String = "",
        val teacher: String = "",
        val startNode: Int,
        val step: Int,
        val startWeek: Int,
        val endWeek: Int,
        val type: Int = 0,
        val tableId: Int = 1,
        val level: Int = 0,
        val ownTime: Boolean = false,
        val startTime: String = "",
        val endTime: String = ""
    )

    data class WeekRange(val startWeek: Int, val endWeek: Int, val type: Int)

    /**
     * 将拾光课程表的离散周次列表转换为 WakeUp 的连续段（每周/单周/双周）
     */
    fun weeksToRanges(weeks: List<Int>): List<WeekRange> {
        val ws = weeks.filter { it > 0 }.distinct().sorted()
        if (ws.isEmpty()) return emptyList()

        class Run(var type: Int, val w: MutableList<Int>)

        fun canExtend(run: Run, w: Int): Boolean {
            val last = run.w.last()
            val g = w - last
            if (run.w.size == 1) {
                if (g == 1) { run.type = 0; return true }
                if (g == 2 && w % 2 == 1) { run.type = 1; return true }
                if (g == 2 && w % 2 == 0) { run.type = 2; return true }
                return false
            }
            return (run.type == 0 && g == 1) ||
                   (run.type == 1 && g == 2 && w % 2 == 1) ||
                   (run.type == 2 && g == 2 && w % 2 == 0)
        }

        val out = mutableListOf<Run>()
        var curRun: Run? = null
        for (w in ws) {
            if (curRun == null || !canExtend(curRun, w)) {
                if (curRun != null) out.add(curRun)
                curRun = Run(type = if (w % 2 == 0) 2 else 1, w = mutableListOf(w))
            } else {
                curRun.w.add(w)
            }
        }
        if (curRun != null) out.add(curRun)

        return out.map { r ->
            WeekRange(
                startWeek = r.w.first(),
                endWeek = r.w.last(),
                type = if (r.w.size == 1) 0 else r.type
            )
        }
    }

    /**
     * 生成 5 行格式的 .wakeup_schedule 文本内容
     */
    fun generateWakeupContent(
        tableName: String,
        courses: List<CourseWithWeeks>,
        timeSlots: List<TimeSlot>,
        config: CourseTableConfig?
    ): String {
        val classDuration = config?.defaultClassDuration ?: 45
        val breakDuration = config?.defaultBreakDuration ?: 10
        val tt = WakeupTimeTable(
            id = 1,
            name = "时间表",
            courseLen = classDuration,
            sameBreakLen = true,
            theBreakLen = breakDuration
        )

        val defaultSlots = listOf(
            "08:00" to "08:45", "08:55" to "09:40", "10:00" to "10:45", "10:55" to "11:40",
            "14:00" to "14:45", "14:55" to "15:40", "16:00" to "16:45", "16:55" to "17:40",
            "19:00" to "19:45", "19:55" to "20:40", "21:00" to "21:45", "21:55" to "22:40"
        )

        val tds = if (timeSlots.isNotEmpty()) {
            timeSlots.sortedBy { it.number }.map {
                WakeupTimeDetail(
                    node = it.number,
                    startTime = it.startTime,
                    endTime = it.endTime,
                    timeTable = 1
                )
            }
        } else {
            defaultSlots.mapIndexed { index, pair ->
                WakeupTimeDetail(
                    node = index + 1,
                    startTime = pair.first,
                    endTime = pair.second,
                    timeTable = 1
                )
            }
        }

        val maxSectionFromCourses = courses.mapNotNull { it.course.endSection }.maxOrNull() ?: 10
        val maxSectionFromSlots = tds.map { it.node }.maxOrNull() ?: 10
        val maxNode = maxOf(maxSectionFromSlots, maxSectionFromCourses)
        val startDateStr = config?.semesterStartDate?.takeIf { it.isNotBlank() } ?: LocalDate.now().toString()
        val totalWeeks = config?.semesterTotalWeeks?.coerceIn(1, 60) ?: 20
        val sundayFirst = (config?.firstDayOfWeek ?: 1) == 7
        val hasSat = courses.any { it.course.day == 6 }
        val hasSun = courses.any { it.course.day == 7 }

        val tc = WakeupTableCompat(
            id = 1,
            tableName = tableName,
            nodes = maxNode,
            timeTable = 1,
            startDate = startDateStr,
            maxWeek = totalWeeks,
            sundayFirst = sundayFirst,
            showSat = hasSat,
            showSun = hasSun
        )

        // 课程聚类：按名称、颜色、备注映射到 WakeupCourseBase
        val courseBaseMap = mutableMapOf<String, WakeupCourseBase>()
        val courseBases = mutableListOf<WakeupCourseBase>()
        val courseDetails = mutableListOf<WakeupCourseDetail>()
        var nextId = 0

        for (cw in courses) {
            val c = cw.course
            val colorIdx = c.colorInt.coerceIn(0, SG_COLORS.size - 1)
            val hexColor = "#" + SG_COLORS[colorIdx]
            val remark = c.remark ?: ""
            val key = "${c.name}|$colorIdx|$remark"

            val cb = courseBaseMap.getOrPut(key) {
                val newCb = WakeupCourseBase(
                    id = nextId++,
                    courseName = c.name,
                    color = hexColor,
                    tableId = 1,
                    note = remark,
                    credit = 0.0
                )
                courseBases.add(newCb)
                newCb
            }

            val startSection = c.startSection ?: 1
            val endSection = c.endSection ?: startSection
            val step = (endSection - startSection + 1).coerceAtLeast(1)
            val weeks = cw.weeks.map { it.weekNumber }
            val ranges = weeksToRanges(weeks)
            val custom = c.isCustomTime && !c.customStartTime.isNullOrBlank() && !c.customEndTime.isNullOrBlank()

            for (r in ranges) {
                courseDetails.add(
                    WakeupCourseDetail(
                        id = cb.id,
                        day = c.day.coerceIn(1, 7),
                        room = c.position,
                        teacher = c.teacher,
                        startNode = startSection,
                        step = step,
                        startWeek = r.startWeek,
                        endWeek = r.endWeek,
                        type = r.type,
                        tableId = 1,
                        level = 0,
                        ownTime = custom,
                        startTime = if (custom) (c.customStartTime ?: "") else "",
                        endTime = if (custom) (c.customEndTime ?: "") else ""
                    )
                )
            }
        }

        val l1 = wakeupJson.encodeToString(tt)
        val l2 = wakeupJson.encodeToString(tds)
        val l3 = wakeupJson.encodeToString(tc)
        val l4 = wakeupJson.encodeToString(courseBases)
        val l5 = wakeupJson.encodeToString(courseDetails)

        return "$l1\n$l2\n$l3\n$l4\n$l5"
    }

    /**
     * 将课表写入缓存 share_temp 文件
     */
    fun createWakeupFile(context: Context, tableName: String, content: String): File {
        val shareTempDir = File(context.cacheDir, "share_temp").apply {
            if (!exists()) mkdirs()
        }
        val safeTableName = tableName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "课表" }
        val file = File(shareTempDir, "$safeTableName.wakeup_schedule")
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    /**
     * 调用系统打开方式打开 .wakeup_schedule 文件，优先调用 WakeUp 课程表
     */
    fun openWithWakeup(context: Context, uri: Uri, fileName: String) {
        val pm = context.packageManager
        val wakeupPackage = "com.suda.yzune.wakeupschedule"

        val targetIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            setPackage(wakeupPackage)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (targetIntent.resolveActivity(pm) != null) {
            context.startActivity(targetIntent)
        } else {
            val generalIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(generalIntent, "选择打开方式（WakeUp 课程表）").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }
}
