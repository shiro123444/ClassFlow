package com.xingheyuzhuan.shiguangschedule.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.xingheyuzhuan.shiguangschedule.data.db.main.AppSettingsDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfig
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfigDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableDao
import com.xingheyuzhuan.shiguangschedule.data.model.AppSettingsModel
import com.xingheyuzhuan.shiguangschedule.data.model.AutoControlMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 应用配置领域仓库（DataStore 版，与上游对齐）
 *
 * 核心职责：
 * 1. 采用 SSOT 原则，将全局偏好设置存放在 DataStore。
 * 2. 协调全局偏好设置 (DataStore) 与课表物理配置 (Room) 之间的数据流。
 * 3. 提供时间维度计算算法（周次偏移、日期回溯）。
 * 4. [migrateFromRoomOnce] 一次性将旧版 Room app_settings 数据迁移到 DataStore。
 */
@Singleton
class AppSettingsRepository @Inject constructor(
    @Named("AppSettings") private val dataStore: DataStore<Preferences>,
    private val courseTableDao: CourseTableDao,
    private val courseTableConfigDao: CourseTableConfigDao,
    @ApplicationContext private val context: android.content.Context,
    // 仅用于 Room→DataStore 一次性迁移；迁移完成后不再读写
    private val legacyAppSettingsDao: AppSettingsDao
) {
    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * 课表配置模板
     * 当 DataStore 选中的课表在数据库中尚未初始化配置时，以此模板为基础进行创建。
     */
    private val COURSE_CONFIG_TEMPLATE = CourseTableConfig(
        courseTableId = "",
        showWeekends = false,
        semesterStartDate = null,
        semesterTotalWeeks = 20,
        defaultClassDuration = 45,
        defaultBreakDuration = 10,
        firstDayOfWeek = DayOfWeek.MONDAY.value
    )

    // 应用全局设置 (DataStore)

    /**
     * 获取应用设置数据流。
     */
    fun getAppSettings(): Flow<AppSettingsModel> = dataStore.data.map { prefs ->
        val dbFirstTableId = courseTableDao.getFirstTableOnce()?.id ?: ""

        AppSettingsModel.fromPreferences(prefs, dbFirstTableId)
    }

    /**
     * 获取一次性的应用设置快照。
     */
    suspend fun getAppSettingsOnce(): AppSettingsModel {
        return getAppSettings().first()
    }

    /**
     * 更新应用设置。
     * 将对象解构并原子化地写入 DataStore。
     */
    suspend fun insertOrUpdateAppSettings(newSettings: AppSettingsModel) {
        dataStore.edit { prefs ->
            prefs[AppSettingsModel.KEY_CURRENT_COURSE_TABLE_ID] = newSettings.currentCourseTableId
            prefs[AppSettingsModel.KEY_REMINDER_ENABLED] = newSettings.reminderEnabled
            prefs[AppSettingsModel.KEY_REMIND_BEFORE_MINUTES] = newSettings.remindBeforeMinutes
            prefs[AppSettingsModel.KEY_SKIPPED_DATES] = newSettings.skippedDates
            prefs[AppSettingsModel.KEY_AUTO_MODE_ENABLED] = newSettings.autoModeEnabled
            prefs[AppSettingsModel.KEY_AUTO_CONTROL_MODE] = newSettings.autoControlMode.value
            prefs[AppSettingsModel.KEY_COMPAT_WEARABLE_SYNC] = newSettings.compatWearableSync
            prefs[AppSettingsModel.KEY_SHOW_NON_CURRENT_WEEK_COURSES] = newSettings.showNonCurrentWeekCourses
            prefs[AppSettingsModel.KEY_START_SCREEN] = newSettings.startScreen.value
            prefs[AppSettingsModel.KEY_THEME_MODE] = newSettings.themeMode.value
            prefs[AppSettingsModel.KEY_USE_DYNAMIC_COLOR] = newSettings.useDynamicColor
            prefs[AppSettingsModel.KEY_CUSTOM_LIGHT_PRIMARY] = newSettings.customLightPrimary
            prefs[AppSettingsModel.KEY_CUSTOM_DARK_PRIMARY] = newSettings.customDarkPrimary
            prefs[AppSettingsModel.KEY_USE_SAKURA_TIME_THEME] = newSettings.useSakuraTimeTheme
            prefs[AppSettingsModel.KEY_AUTO_CHECK_UPDATE] = newSettings.autoCheckUpdate
            prefs[AppSettingsModel.KEY_LAST_CHECK_UPDATE_TIME] = newSettings.lastCheckUpdateTime
            prefs[AppSettingsModel.KEY_IGNORED_UPDATE_VERSION] = newSettings.ignoredUpdateVersion
            prefs[AppSettingsModel.KEY_CUSTOM_UPDATE_API_URL] = newSettings.customUpdateApiUrl
            prefs[AppSettingsModel.KEY_UPDATE_CHANNEL] = newSettings.updateChannel
        }
    }

    /**
     * 更新当前选择的更新渠道（如 stable, beta, dev）。
     */
    suspend fun updateUpdateChannel(channel: String) {
        dataStore.edit { prefs ->
            prefs[AppSettingsModel.KEY_UPDATE_CHANNEL] = channel
        }
    }

    /**
     * 更新自定义更新服务器地址。
     */
    suspend fun updateCustomUpdateApiUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[AppSettingsModel.KEY_CUSTOM_UPDATE_API_URL] = url
        }
    }

    /**
     * 更新自动检查更新开关。
     */
    suspend fun updateAutoCheckUpdate(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[AppSettingsModel.KEY_AUTO_CHECK_UPDATE] = enabled
        }
    }

    /**
     * 更新上次检查更新时间。
     */
    suspend fun updateLastCheckUpdateTime(timeMs: Long) {
        dataStore.edit { prefs ->
            prefs[AppSettingsModel.KEY_LAST_CHECK_UPDATE_TIME] = timeMs
        }
    }

    /**
     * 更新已跳过的版本号。
     */
    suspend fun updateIgnoredUpdateVersion(version: String) {
        dataStore.edit { prefs ->
            prefs[AppSettingsModel.KEY_IGNORED_UPDATE_VERSION] = version
        }
    }

    // 课表具体物理配置 (由 Room 驱动)

    /**
     * 根据课表ID获取一次性配置快照。
     */
    suspend fun getCourseConfigOnce(tableId: String): CourseTableConfig? {
        return courseTableConfigDao.getConfigOnce(tableId)
    }

    /**
     * 根据课表ID实时获取配置数据流。
     */
    fun getCourseTableConfigFlow(courseTableId: String): Flow<CourseTableConfig?> {
        return courseTableConfigDao.getConfigById(courseTableId)
    }

    /**
     * 更新或插入特定课表的物理配置。
     */
    suspend fun insertOrUpdateCourseConfig(newConfig: CourseTableConfig) {
        val constrainedConfig = when {
            newConfig.firstDayOfWeek == DayOfWeek.SUNDAY.value -> {
                newConfig.copy(showWeekends = true)
            }
            !newConfig.showWeekends -> {
                newConfig.copy(firstDayOfWeek = DayOfWeek.MONDAY.value)
            }
            else -> newConfig
        }
        courseTableConfigDao.insertOrUpdate(constrainedConfig)
    }

    // 业务算法 (时间、周次计算)

    /**
     * 核心周次偏移算法。
     */
    fun getWeekIndexAtDate(
        targetDate: LocalDate,
        startDateStr: String?,
        firstDayOfWeekInt: Int
    ): Int? {
        if (startDateStr.isNullOrEmpty()) return null
        return try {
            val firstDayOfWeek = DayOfWeek.of(firstDayOfWeekInt)
            val alignedStartDate = LocalDate.parse(startDateStr, DATE_FORMATTER)
                .with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            val alignedTargetDate = targetDate.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            val diffWeeks = ChronoUnit.WEEKS.between(alignedStartDate, alignedTargetDate).toInt()
            diffWeeks + 1
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 基于当前数据库/DataStore状态计算当前自然周次。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun calculateCurrentWeekFromDb(): Flow<Int?> = getAppSettings().flatMapLatest { appSettings ->
        val currentCourseId = appSettings.currentCourseTableId.ifEmpty {
            return@flatMapLatest flowOf(null)
        }
        courseTableConfigDao.getConfigById(currentCourseId).map { config ->
            if (config == null) return@map null
            val rawWeek = getWeekIndexAtDate(
                targetDate = LocalDate.now(),
                startDateStr = config.semesterStartDate,
                firstDayOfWeekInt = config.firstDayOfWeek
            ) ?: return@map null
            if (rawWeek in 1..config.semesterTotalWeeks) rawWeek else null
        }
    }

    /**
     * 根据目标周数反推开学日期。
     */
    suspend fun setSemesterStartDateFromWeek(week: Int?) {
        val appSettings = getAppSettingsOnce()
        val currentCourseId = appSettings.currentCourseTableId.ifEmpty { return }

        val currentConfig = courseTableConfigDao.getConfigOnce(currentCourseId)
            ?: COURSE_CONFIG_TEMPLATE.copy(courseTableId = currentCourseId)

        val newStartDate = if (week != null) {
            calculateSemesterStartDate(week, currentConfig.firstDayOfWeek)
        } else {
            null
        }

        val updatedConfig = currentConfig.copy(semesterStartDate = newStartDate)
        courseTableConfigDao.insertOrUpdate(updatedConfig)
    }

    /**
     * 辅助函数：根据目标周数反推开学日期。
     */
    private fun calculateSemesterStartDate(week: Int, firstDayOfWeekInt: Int): String {
        val today = LocalDate.now()
        val firstDayOfWeek = DayOfWeek.of(firstDayOfWeekInt)
        val startOfThisWeek = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        val weeksToSubtract = (week - 1).toLong()
        val semesterStartDate = startOfThisWeek.minusWeeks(weeksToSubtract)
        return semesterStartDate.format(DATE_FORMATTER)
    }

    // ── Room → DataStore 一次性迁移 ──

    /**
     * 将旧版 Room app_settings 表中的数据一次性迁移到 DataStore。
     * 幂等：DataStore 已有数据时跳过。
     * 在 MyApplication.onCreate 中于首次读取设置前调用。
     */
    suspend fun migrateFromRoomOnce() {
        val prefs = dataStore.data.first()
        if (prefs.contains(AppSettingsModel.KEY_CURRENT_COURSE_TABLE_ID)) return

        val legacy = legacyAppSettingsDao.getAppSettings().first() ?: return
        val dbFirstTableId = courseTableDao.getFirstTableOnce()?.id ?: ""

        insertOrUpdateAppSettings(
            AppSettingsModel(
                currentCourseTableId = legacy.currentCourseTableId ?: dbFirstTableId,
                reminderEnabled = legacy.reminderEnabled,
                remindBeforeMinutes = legacy.remindBeforeMinutes,
                skippedDates = legacy.skippedDates ?: emptySet(),
                autoModeEnabled = legacy.autoModeEnabled,
                autoControlMode = AutoControlMode.fromString(legacy.autoControlMode),
                compatWearableSync = legacy.compatWearableSync
            )
        )
    }
}
