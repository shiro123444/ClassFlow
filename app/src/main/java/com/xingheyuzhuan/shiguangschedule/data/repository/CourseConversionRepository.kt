package com.xingheyuzhuan.shiguangschedule.data.repository

import android.content.Context
import android.provider.CalendarContract
import androidx.room.Transaction
import com.xingheyuzhuan.shiguangschedule.data.db.main.Course
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfig
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWeek
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWeekDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlotDao
import com.xingheyuzhuan.shiguangschedule.data.model.ScheduleGridStyle
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseImportExport.CourseConfigJsonModel
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseImportExport.CourseTableExportModel
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseImportExport.CourseTableImportModel
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseImportExport.ExportCourseJsonModel
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseImportExport.ImportCourseJsonModel
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseImportExport.TimeSlotJsonModel
import com.xingheyuzhuan.shiguangschedule.tool.CalendarAccountManager
import com.xingheyuzhuan.shiguangschedule.tool.IcsExportTool
import com.xingheyuzhuan.shiguangschedule.tool.ScheduleImageExporter
import com.xingheyuzhuan.shiguangschedule.tool.WakeupExportTool
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.UUID

class CourseConversionRepository @Inject constructor(
    private val courseDao: CourseDao,
    private val courseWeekDao: CourseWeekDao,
    private val timeSlotDao: TimeSlotDao,
    private val appSettingsRepository: AppSettingsRepository,
    private val styleSettingsRepository: StyleSettingsRepository,
    private val courseTableDao: CourseTableDao
) {
    /**
     * @param importColor 导入的颜色值（Int 或 null）。
     * @param currentStyle 当前的样式配置对象。
     * @return 最终写入数据库的颜色索引。
     */
    private fun getValidatedOrRandomColorIndex(
        importColor: Int?,
        currentStyle: ScheduleGridStyle,
        courseName: String,
        usageCounter: MutableMap<Int, Int>
    ): Int {
        if (importColor != null && importColor in currentStyle.courseColorMaps.indices) {
            usageCounter[importColor] = (usageCounter[importColor] ?: 0) + 1
            return importColor
        }

        val poolSize = currentStyle.courseColorMaps.size
        if (poolSize <= 0) return 0

        val baseIndex = (courseName.hashCode().toLong().let { kotlin.math.abs(it) } % poolSize).toInt()
        val leastUsed = (0 until poolSize).minByOrNull { idx ->
            val weight = usageCounter[idx] ?: 0
            val distancePenalty = (idx - baseIndex + poolSize) % poolSize
            weight * 100 + distancePenalty
        } ?: baseIndex

        usageCounter[leastUsed] = (usageCounter[leastUsed] ?: 0) + 1
        return leastUsed
    }

    /**
     * 从一个 JSON 课程列表导入课程。
     */
    @Transaction
    suspend fun importCoursesFromList(
        tableId: String,
        coursesJsonModel: List<ImportCourseJsonModel>
    ) {
        val currentStyle = styleSettingsRepository.styleFlow.first()
        val usageCounter = mutableMapOf<Int, Int>()

        courseDao.deleteCoursesByTableId(tableId)

        // 优化：预设 ArrayList 容量避免频繁扩容
        val courseEntities = ArrayList<Course>(coursesJsonModel.size)
        val courseWeekEntities = mutableListOf<CourseWeek>()

        coursesJsonModel.forEach { jsonCourse ->
            val courseId = UUID.randomUUID().toString()

            val courseIndex = getValidatedOrRandomColorIndex(
                importColor = jsonCourse.color,
                currentStyle = currentStyle,
                courseName = jsonCourse.name,
                usageCounter = usageCounter
            )

            courseEntities.add(
                Course(
                    id = courseId,
                    courseTableId = tableId,
                    name = jsonCourse.name,
                    teacher = jsonCourse.teacher,
                    position = jsonCourse.position,
                    day = jsonCourse.day,
                    startSection = jsonCourse.startSection,
                    endSection = jsonCourse.endSection,
                    isCustomTime = jsonCourse.isCustomTime,
                    customStartTime = jsonCourse.customStartTime,
                    customEndTime = jsonCourse.customEndTime,
                    colorInt = courseIndex,
                    remark = jsonCourse.remark
                )
            )

            jsonCourse.weeks.forEach { week ->
                courseWeekEntities.add(
                    CourseWeek(courseId = courseId, weekNumber = week)
                )
            }
        }

        if (courseEntities.isNotEmpty()) courseDao.insertAll(courseEntities)
        if (courseWeekEntities.isNotEmpty()) courseWeekDao.insertAll(courseWeekEntities)
    }

    /**
     * 从一个完整的 JSON 模型导入课表数据。
     */
    @Transaction
    suspend fun importCourseTableFromJson(
        tableId: String,
        courseTableJsonModel: CourseTableImportModel
    ) {
        val currentStyle = styleSettingsRepository.styleFlow.first()
        val usageCounter = mutableMapOf<Int, Int>()

        courseDao.deleteCoursesByTableId(tableId)
        val shouldUpdateSlots = !courseTableJsonModel.timeSlots.isNullOrEmpty()
        if (shouldUpdateSlots) {
            timeSlotDao.deleteAllTimeSlotsByCourseTableId(tableId)
        }

        val courseEntities = ArrayList<Course>(courseTableJsonModel.courses.size)
        val courseWeekEntities = mutableListOf<CourseWeek>()
        val timeSlotEntities = mutableListOf<TimeSlot>()

        courseTableJsonModel.courses.forEach { jsonCourse ->
            // ⚠️ 上游问题规避（已反馈上游，后续提交 PR）：
            // 上游 importCourseTableFromJson 沿用 JSON id（jsonCourse.id ?: UUID），
            // 空字符串 id 或目标课表残留数据（courseTableId 错乱）会导致
            // UNIQUE constraint failed: courses.id 导入失败。
            // 本实现一律重新生成 id（与上游 importCoursesFromList 语义一致），免疫主键冲突。
            val courseId = UUID.randomUUID().toString()

            val courseIndex = getValidatedOrRandomColorIndex(
                importColor = jsonCourse.color,
                currentStyle = currentStyle,
                courseName = jsonCourse.name,
                usageCounter = usageCounter
            )

            courseEntities.add(
                Course(
                    id = courseId,
                    courseTableId = tableId,
                    name = jsonCourse.name,
                    teacher = jsonCourse.teacher,
                    position = jsonCourse.position,
                    day = jsonCourse.day,
                    startSection = jsonCourse.startSection,
                    endSection = jsonCourse.endSection,
                    isCustomTime = jsonCourse.isCustomTime,
                    customStartTime = jsonCourse.customStartTime,
                    customEndTime = jsonCourse.customEndTime,
                    colorInt = courseIndex,
                    remark = jsonCourse.remark
                )
            )

            jsonCourse.weeks.forEach { week ->
                courseWeekEntities.add(
                    CourseWeek(courseId = courseId, weekNumber = week)
                )
            }
        }

        courseTableJsonModel.timeSlots?.forEach { jsonTimeSlot ->
            timeSlotEntities.add(
                TimeSlot(
                    number = jsonTimeSlot.number,
                    startTime = jsonTimeSlot.startTime,
                    endTime = jsonTimeSlot.endTime,
                    courseTableId = tableId
                )
            )
        }

        val normalizedTimeSlots = normalizeImportedTimeSlots(
            timeSlots = timeSlotEntities,
            classDuration = courseTableJsonModel.config?.defaultClassDuration ?: 45,
            breakDuration = courseTableJsonModel.config?.defaultBreakDuration ?: 10,
            tableId = tableId
        )

        if (courseEntities.isNotEmpty()) courseDao.insertAll(courseEntities)
        if (courseWeekEntities.isNotEmpty()) courseWeekDao.insertAll(courseWeekEntities)
        if (shouldUpdateSlots && normalizedTimeSlots.isNotEmpty()) timeSlotDao.insertAll(normalizedTimeSlots)

        // 配置导入逻辑保持不变...
        val configJson = courseTableJsonModel.config
        if (configJson != null) {
            val currentConfig = appSettingsRepository.getCourseConfigOnce(tableId)
            val safeFirstDayOfWeek = configJson.firstDayOfWeek.coerceIn(1, 7)
            val safeTotalWeeks = configJson.semesterTotalWeeks.coerceIn(1, 30)
            val updatedConfig = CourseTableConfig(
                courseTableId = tableId,
                showWeekends = currentConfig?.showWeekends ?: false,
                semesterStartDate = configJson.semesterStartDate,
                semesterTotalWeeks = safeTotalWeeks,
                defaultClassDuration = configJson.defaultClassDuration,
                defaultBreakDuration = configJson.defaultBreakDuration,
                firstDayOfWeek = safeFirstDayOfWeek
            )
            appSettingsRepository.insertOrUpdateCourseConfig(updatedConfig)
        }
    }

    /**
     * 从 JSON 模型更新指定课表的配置。
     * 该函数用于独立导入配置，例如通过 JS 桥接单独设置配置项。
     *
     * @param tableId 课表的 ID。
     * @param configJsonModel 包含配置数据的 JSON 模型（CourseConfigJsonModel）。
     */
    @Transaction
    suspend fun importCourseConfig(
        tableId: String,
        configJsonModel: CourseConfigJsonModel
    ) {
        val currentConfig = appSettingsRepository.getCourseConfigOnce(tableId)
        val safeFirstDayOfWeek = configJsonModel.firstDayOfWeek.coerceIn(1, 7)
        val safeTotalWeeks = configJsonModel.semesterTotalWeeks.coerceIn(1, 30)

        // 2. 构造新的配置实体
        val updatedConfig = CourseTableConfig(
            courseTableId = tableId,
            showWeekends = currentConfig?.showWeekends ?: false,
            semesterStartDate = configJsonModel.semesterStartDate,
            semesterTotalWeeks = safeTotalWeeks,
            defaultClassDuration = configJsonModel.defaultClassDuration,
            defaultBreakDuration = configJsonModel.defaultBreakDuration,
            firstDayOfWeek = safeFirstDayOfWeek
        )

        // 3. 插入或更新配置到数据库
        appSettingsRepository.insertOrUpdateCourseConfig(updatedConfig)
    }

    /**
     * 将指定课表下的所有数据导出为一个完整的 JSON 模型。
     * 包含课程和时间段。
     *
     * @param tableId 要导出的课表的 ID。
     * @return 包含课程和时间段的完整 JSON 模型。
     */
    suspend fun exportCourseTableToJson(tableId: String): CourseTableExportModel? {

        val coursesWithWeeks = courseDao.getCoursesWithWeeksByTableId(tableId).first()
        val exportCourses = coursesWithWeeks.map { courseWithWeeks ->
            val course = courseWithWeeks.course
            val weeks = courseWithWeeks.weeks.map { it.weekNumber }

            // 修正 3: 从数据库获取索引，直接导出索引编号（Int），而不是 ARGB 值。
            val colorIndex = course.colorInt

            ExportCourseJsonModel(
                // ⚠️ 上游问题规避（已反馈上游，后续提交 PR）：
                // 上游导入对空字符串 id 不防御（jsonCourse.id ?: UUID 只处理 null），
                // 历史数据中的空 id 课程导出后会破坏对方导入。此处导出时保证 id 有效。
                id = course.id.ifBlank { UUID.randomUUID().toString() },
                name = course.name,
                teacher = course.teacher,
                position = course.position,
                day = course.day,
                startSection = course.startSection,
                endSection = course.endSection,
                color = colorIndex,
                weeks = weeks,
                isCustomTime = course.isCustomTime,
                customStartTime = course.customStartTime,
                customEndTime = course.customEndTime,
                remark = course.remark
            )
        }

        val timeSlots = timeSlotDao.getTimeSlotsByCourseTableId(tableId).first()
        val exportTimeSlots = timeSlots.map { timeSlot ->
            TimeSlotJsonModel(
                number = timeSlot.number,
                startTime = timeSlot.startTime,
                endTime = timeSlot.endTime
            )
        }

        // 读取课表配置
        val courseConfig = appSettingsRepository.getCourseConfigOnce(tableId)

        val configToExport = courseConfig ?: CourseTableConfig(courseTableId = tableId)

        // 转换为不含 showWeekends 的 JSON 模型
        val exportConfig = CourseConfigJsonModel(
            semesterStartDate = configToExport.semesterStartDate,
            semesterTotalWeeks = configToExport.semesterTotalWeeks,
            defaultClassDuration = configToExport.defaultClassDuration,
            defaultBreakDuration = configToExport.defaultBreakDuration,
            firstDayOfWeek = configToExport.firstDayOfWeek
        )


        return CourseTableExportModel(
            courses = exportCourses,
            timeSlots = exportTimeSlots,
            config = exportConfig
        )
    }

    /**
     * 将指定课表下的所有课程数据导出为 ICS 日历文件的内容字符串。
     *
     * @param tableId 要导出的课表的 ID。
     * @param alarmMinutes 可选的提醒时间，单位分钟。传入null则不设置提醒。
     * @return 包含 ICS 日历文件内容的字符串，如果失败则返回 null。
     */
    suspend fun exportToIcsString(tableId: String, alarmMinutes: Int?): String? {
        val courses = courseDao.getCoursesWithWeeksByTableId(tableId).first()
        val timeSlots = timeSlotDao.getTimeSlotsByCourseTableId(tableId).first()

        // 1. 从 AppSettings 获取全局设置 (用于 skippedDates)
        val appSettings = appSettingsRepository.getAppSettingsOnce()
        val skippedDates = appSettings?.skippedDates

        // 2. 从 CourseTableConfig 获取课表配置 (用于日期和总周数)
        val courseConfig = appSettingsRepository.getCourseConfigOnce(tableId)
        val semesterStartDate = courseConfig?.semesterStartDate?.let { LocalDate.parse(it) }

        // 检查必要配置是否存在
        if (semesterStartDate == null || courseConfig.semesterTotalWeeks <= 0) {
            return null
        }

        return IcsExportTool.generateIcsFileContent(
            courses = courses,
            timeSlots = timeSlots,
            semesterStartDate = semesterStartDate,
            semesterTotalWeeks = courseConfig.semesterTotalWeeks,
            firstDayOfWeekInt = courseConfig.firstDayOfWeek,
            alarmMinutes = alarmMinutes,
            skippedDates = skippedDates
        )
    }

    /**
     * 一键同步当前课表到系统日历。
     * 先清空本应用专属日历账户下的事件，再按当前课表配置批量写入。
     */
    suspend fun syncCurrentTableToSystemCalendar(context: Context): Boolean {
        val appSettings = appSettingsRepository.getAppSettingsOnce()
        val currentTableId = appSettings.currentCourseTableId
        if (currentTableId.isEmpty()) return false

        val courses = courseDao.getCoursesWithWeeksByTableId(currentTableId).first()
        val timeSlots = timeSlotDao.getTimeSlotsByCourseTableId(currentTableId).first()
        val alarmMinutes = appSettings.remindBeforeMinutes
        val skippedDates = appSettings.skippedDates
        val courseConfig = appSettingsRepository.getCourseConfigOnce(currentTableId)
        val semesterStartDate = courseConfig?.semesterStartDate?.let {
            try {
                LocalDate.parse(it)
            } catch (_: Exception) {
                null
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                val calendarId = CalendarAccountManager.getOrCreateCalendarId(context)
                if (calendarId == -1L) return@withContext false

                val resolver = context.contentResolver
                resolver.delete(
                    CalendarContract.Events.CONTENT_URI,
                    "${CalendarContract.Events.CALENDAR_ID} = ?",
                    arrayOf(calendarId.toString())
                )

                if (semesterStartDate == null ||
                    courseConfig == null ||
                    courseConfig.semesterTotalWeeks <= 0 ||
                    courses.isEmpty()
                ) {
                    // 配置不完整或无课时：已清空旧事件也算成功
                    return@withContext true
                }

                val ops = IcsExportTool.generateCalendarOps(
                    courses = courses,
                    timeSlots = timeSlots,
                    semesterStartDate = semesterStartDate,
                    semesterTotalWeeks = courseConfig.semesterTotalWeeks,
                    firstDayOfWeekInt = courseConfig.firstDayOfWeek,
                    calendarId = calendarId,
                    alarmMinutes = alarmMinutes,
                    skippedDates = skippedDates
                )
                if (ops.isNotEmpty()) {
                    resolver.applyBatch(CalendarContract.AUTHORITY, ops)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 导出指定课表为 WakeUp 课表文件 (.wakeup_schedule)。
     */
    suspend fun exportCourseTableToWakeupFile(tableId: String, context: Context): File? = withContext(Dispatchers.IO) {
        val table = courseTableDao.getCourseTableById(tableId) ?: return@withContext null
        val courses = courseDao.getCoursesWithWeeksByTableId(tableId).first()
        val timeSlots = timeSlotDao.getTimeSlotsByCourseTableId(tableId).first()
        val courseConfig = appSettingsRepository.getCourseConfigOnce(tableId)

        val content = WakeupExportTool.generateWakeupContent(
            tableName = table.name,
            courses = courses,
            timeSlots = timeSlots,
            config = courseConfig
        )
        WakeupExportTool.createWakeupFile(context, table.name, content)
    }

    /**
     * 导出指定课表为全课表图片（无色主题、自动包含周末），并直接保存到系统相册。
     */
    suspend fun exportCourseTableToImageFile(
        tableId: String,
        context: Context,
        showDashedDivider: Boolean = false,
        autoSplitConflict: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val table = courseTableDao.getCourseTableById(tableId) ?: return@withContext false
        val courses = courseDao.getCoursesWithWeeksByTableId(tableId).first()
        val timeSlots = timeSlotDao.getTimeSlotsByCourseTableId(tableId).first()
        val courseConfig = appSettingsRepository.getCourseConfigOnce(tableId)

        val savedUri = ScheduleImageExporter.exportFullScheduleImage(
            context = context,
            tableName = table.name,
            courses = courses,
            timeSlots = timeSlots,
            config = courseConfig,
            showDashedDivider = showDashedDivider,
            autoSplitConflict = autoSplitConflict
        )
        savedUri != null
    }
}
