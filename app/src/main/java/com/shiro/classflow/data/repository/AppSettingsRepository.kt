package com.shiro.classflow.data.repository

import com.shiro.classflow.data.db.main.AppSettings
import com.shiro.classflow.data.db.main.AppSettingsDao
import com.shiro.classflow.data.db.main.CourseTableConfig
import com.shiro.classflow.data.db.main.CourseTableConfigDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 应用设置数据仓库，负责处理与应用设置相关的业务逻辑。
 */
class AppSettingsRepository(
    private val appSettingsDao: AppSettingsDao,
    private val courseTableConfigDao: CourseTableConfigDao
) {
    // 使用线程安全的 java.time.DateTimeFormatter
    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun normalizeFirstDayOfWeek(firstDayOfWeekInt: Int): Int {
        return firstDayOfWeekInt.coerceIn(DayOfWeek.MONDAY.value, DayOfWeek.SUNDAY.value)
    }

    /**
     * 默认的应用设置
     */
    private val DEFAULT_SETTINGS = AppSettings(
        id = 1,
        currentCourseTableId = null,
        reminderEnabled = false,
        remindBeforeMinutes = 15,
        skippedDates = null,
        autoModeEnabled = false,
        autoControlMode = "DND",
    )

    /**
     * 默认的课表配置，用于初始化或找不到配置时的回退
     */
    private val DEFAULT_COURSE_CONFIG = CourseTableConfig(
        courseTableId = UUID.randomUUID().toString(),
        showWeekends = false,
        semesterStartDate = null,
        semesterTotalWeeks = 20,
        defaultClassDuration = 45,
        defaultBreakDuration = 10,
        firstDayOfWeek = DayOfWeek.MONDAY.value
    )

    /**
     * 获取应用设置（全局设置），返回一个数据流。
     */
    fun getAppSettings(): Flow<AppSettings> {
        return appSettingsDao.getAppSettings().map { settings ->
            settings ?: DEFAULT_SETTINGS
        }
    }

    /**
     * 获取一次性的应用设置，用于不需要监听变化的场景。
     * @return 返回 AppSettings 对象，如果找不到则返回 null。
     */
    suspend fun getAppSettingsOnce(): AppSettings? {
        return appSettingsDao.getAppSettings().first()
    }

    /**
     * 根据课表ID，获取该课表的配置信息（一次性）。
     * 此函数用于不需要监听配置变化，只需要获取当前快照的场景。
     */
    suspend fun getCourseConfigOnce(tableId: String): CourseTableConfig? {
        return courseTableConfigDao.getConfigOnce(tableId)
    }

    /**
     * 根据课表 ID，实时获取该课表的配置信息，返回一个数据流。
     * 此函数专为需要监听配置变化（如 ViewModel 中的 combine 和 flatMapLatest）的场景设计。
     * * @param courseTableId 关联的课表 ID
     */
    fun getCourseTableConfigFlow(courseTableId: String): Flow<CourseTableConfig?> {
        return courseTableConfigDao.getConfigById(courseTableId)
    }

    /**
     * 通用的周次计算函数
     * 物理坐标系的核心：将任意日期映射为相对于开学日的“逻辑周次”。
     *
     * @param targetDate 想要计算的物理日期（例如 Pager 滑动到的某天）
     * @param startDateStr 开学日期的字符串 (yyyy-MM-dd)
     * @param firstDayOfWeekInt 一周起始日 (1=周一, 7=周日)
     * @return 如果未设置开学日期返回 null，代表数据不可信；否则返回物理偏移周次（从1开始）。
     */
    fun getWeekIndexAtDate(
        targetDate: LocalDate,
        startDateStr: String?,
        firstDayOfWeekInt: Int
    ): Int? {
        // 如果开学日期为空，视为不可信，不进行偏移计算
        if (startDateStr.isNullOrEmpty()) return null

        return try {
            val firstDayOfWeek = DayOfWeek.of(normalizeFirstDayOfWeek(firstDayOfWeekInt))

            // 1. 将开学日期对齐到该周的起始日（锚点）
            val alignedStartDate = LocalDate.parse(startDateStr, DATE_FORMATTER)
                .with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

            // 2. 将目标日期也对齐到该周的起始日
            val alignedTargetDate = targetDate.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

            // 3. 计算周数差。这里允许负数（开学前）和超出范围的数（放假后）
            val diffWeeks = ChronoUnit.WEEKS.between(alignedStartDate, alignedTargetDate).toInt()
            diffWeeks + 1
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 实时获取当前周的计算结果。
     */
    suspend fun calculateCurrentWeekFromDb(): Int? {
        val appSettings = appSettingsDao.getAppSettings().first() ?: return null
        val currentCourseId = appSettings.currentCourseTableId ?: return null
        val config = courseTableConfigDao.getConfigOnce(currentCourseId) ?: return null

        val rawWeek = getWeekIndexAtDate(
            targetDate = LocalDate.now(),
            startDateStr = config.semesterStartDate,
            firstDayOfWeekInt = config.firstDayOfWeek
        ) ?: return null

        // 在“设置页面”显示当前周时，通常只在有效学期内显示
        return if (rawWeek in 1..config.semesterTotalWeeks) rawWeek else null
    }

    /**
     * 根据周数反向推算开学日期。
     */
    suspend fun setSemesterStartDateFromWeek(week: Int?) {
        val appSettings = appSettingsDao.getAppSettings().first() ?: return
        val currentCourseId = appSettings.currentCourseTableId ?: return

        val currentConfig = courseTableConfigDao.getConfigOnce(currentCourseId)
            ?: DEFAULT_COURSE_CONFIG.copy(courseTableId = currentCourseId)

        val newStartDate = if (week != null) {
            calculateSemesterStartDate(week, currentConfig.firstDayOfWeek)
        } else {
            null
        }

        val updatedConfig = currentConfig.copy(semesterStartDate = newStartDate)
        courseTableConfigDao.insertOrUpdate(updatedConfig)
    }

    suspend fun insertOrUpdateAppSettings(newSettings: AppSettings) {
        appSettingsDao.insertOrUpdate(newSettings)
    }

    suspend fun insertOrUpdateCourseConfig(newConfig: CourseTableConfig) {
        courseTableConfigDao.insertOrUpdate(newConfig)
    }

    /**
     * 辅助函数：根据目标周数反推开学日期。
     */
    private fun calculateSemesterStartDate(week: Int, firstDayOfWeekInt: Int): String {
        val today = LocalDate.now()
        val firstDayOfWeek = DayOfWeek.of(normalizeFirstDayOfWeek(firstDayOfWeekInt))
        val startOfThisWeek = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        val weeksToSubtract = (week - 1).toLong()
        val semesterStartDate = startOfThisWeek.minusWeeks(weeksToSubtract)
        return semesterStartDate.format(DATE_FORMATTER)
    }
}
