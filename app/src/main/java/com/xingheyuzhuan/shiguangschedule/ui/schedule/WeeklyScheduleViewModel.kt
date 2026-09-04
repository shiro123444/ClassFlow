package com.xingheyuzhuan.shiguangschedule.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.db.main.Course
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTable
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfig
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWeek
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWithWeeks
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import com.xingheyuzhuan.shiguangschedule.data.model.ScheduleGridStyle
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.ScheduleModeProto
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGridStyleComposed
import com.xingheyuzhuan.shiguangschedule.data.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 课表展示块：封装单次或冲突课程
 * startSection/endSection：逻辑节次偏移量（0.0 代表第一节课顶部）
 */
data class MergedCourseBlock(
    val day: Int,
    val startSection: Float,
    val endSection: Float,
    val courses: List<CourseWithWeeks>,
    val needsProportionalRendering: Boolean = false,
    val isVisualDemoted: Boolean = false,
    val nonActiveRanges: List<Pair<Float, Float>> = emptyList() // 子列位置信息（上游同步）
)

data class WeeklyScheduleUiState(
    val style: ScheduleGridStyle = ScheduleGridStyle(),
    val showWeekends: Boolean = false,
    val totalWeeks: Int = 20,
    val timeSlots: List<TimeSlot> = emptyList(),
    val courseCache: Map<String, List<MergedCourseBlock>> = emptyMap(),
    val currentMergedCourses: List<MergedCourseBlock> = emptyList(),
    val isSemesterSet: Boolean = false,
    val semesterStartDate: LocalDate? = null,
    val firstDayOfWeek: Int = DayOfWeek.MONDAY.value,
    val weekIndexInPager: Int? = null,
    val weekTitle: String = "",
    val currentWeekNumber: Int? = null,
    val currentSectionIndex: Int = -1, // 当前所处的节次（1-based，用于时间轴高亮，上游同步）
    val pagerMondayDate: LocalDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
    val tableId: String? = null,
    val useSakuraTimeTheme: Boolean = false, // Sakura 时间色板开关（玻璃光边仅在该模式下显示）
    val floatingCourse: CourseWithWeeks? = null, // 跨周挂起的课程（上游同步）
    val floatingSourceWeek: Int? = null // 挂起课程的来源周次
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WeeklyScheduleViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val courseTableRepository: CourseTableRepository,
    private val timeSlotRepository: TimeSlotRepository,
    private val styleSettingsRepository: StyleSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeeklyScheduleUiState())
    val uiState: StateFlow<WeeklyScheduleUiState> = _uiState.asStateFlow()

    private val _pagerMondayDate = MutableStateFlow(
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    )

    private val appSettingsFlow = appSettingsRepository.getAppSettings()
    private val styleFlow = styleSettingsRepository.styleFlow

    private val courseTableConfigFlow = appSettingsFlow.flatMapLatest { settings ->
        settings.currentCourseTableId?.let { tableId ->
            appSettingsRepository.getCourseTableConfigFlow(tableId)
        } ?: flowOf(null)
    }

    private val timeSlotsFlow = appSettingsFlow.flatMapLatest { settings ->
        settings.currentCourseTableId?.let { tableId ->
            timeSlotRepository.getTimeSlotsByCourseTableId(tableId)
        } ?: flowOf(emptyList())
    }

    /**
     * 实现三周滑动窗口预加载
     * 监听当前页日期，同时拉取 [前一周, 本周, 后一周] 的数据并转为 Map 缓存
     */
    private val currentCoursesFlow = combine(
        _pagerMondayDate,
        appSettingsFlow,
        courseTableConfigFlow,
        timeSlotsFlow,
        styleFlow // 依赖样式流：切换 24h/节次模式后课程块坐标需重算
    ) { date, settings, config, slots, style ->
        val tableId = settings.currentCourseTableId
        if (tableId != null && config != null) {
            // 定义窗口日期列表
            val window = listOf(date.minusWeeks(1), date, date.plusWeeks(1))

            // 为窗口内的每一周开启数据监听并合并成 Map
            combine(window.map { day ->
                val pageWeekNum = appSettingsRepository.getWeekIndexAtDate(
                    targetDate = day,
                    startDateStr = config.semesterStartDate,
                    firstDayOfWeekInt = config.firstDayOfWeek
                )

                val isWithinSemester = pageWeekNum != null && pageWeekNum in 1..config.semesterTotalWeeks

                // "显示非本周课程"开关：开启且处于学期内 → 展示当前页周次及之后的全部课程；否则仅展示本周课程
                val coursesFlow = if (settings.showNonCurrentWeekCourses && isWithinSemester) {
                    courseTableRepository.getCoursesWithWeeksByTableId(tableId).map { allCourses ->
                        allCourses.filter { cw ->
                            cw.weeks.any { it.weekNumber >= pageWeekNum }
                        }
                    }
                } else {
                    courseTableRepository.getCoursesWithWeeksByDate(tableId, day, config)
                }

                coursesFlow.map { courses ->
                    day.toString() to mergeCourses(courses, slots, pageWeekNum ?: -1, style.scheduleMode)
                }
            }) { results -> results.toMap() }
        } else {
            flowOf(emptyMap())
        }
    }.flatMapLatest { it }

    private var stringProvider: ((Int, Array<out Any>) -> String)? = null

    fun setStringProvider(provider: (Int, Array<out Any>) -> String) {
        this.stringProvider = provider
    }

    init {
        viewModelScope.launch {
            val configAndTimeFlow = combine(
                appSettingsFlow, courseTableConfigFlow, styleFlow, _pagerMondayDate
            ) { settings, config, style, mondayDate ->
                ScheduleConfigPackage(settings, config, style, mondayDate)
            }

            combine(configAndTimeFlow, currentCoursesFlow, timeSlotsFlow) { configPkg, cache, timeSlots ->
                val config = configPkg.config
                val startDate = config?.semesterStartDate?.let { LocalDate.parse(it) }
                val firstDayOfWeekInt = (config?.firstDayOfWeek ?: DayOfWeek.MONDAY.value).coerceIn(1, 7)
                val totalWeeks = config?.semesterTotalWeeks ?: 20

                val currentWeekNum = appSettingsRepository.getWeekIndexAtDate(
                    targetDate = LocalDate.now(),
                    startDateStr = config?.semesterStartDate,
                    firstDayOfWeekInt = firstDayOfWeekInt
                )

                val currentSectionIndex = calculateCurrentSectionIndex(timeSlots)

                val weekIndex = appSettingsRepository.getWeekIndexAtDate(
                    targetDate = configPkg.mondayDate,
                    startDateStr = config?.semesterStartDate,
                    firstDayOfWeekInt = firstDayOfWeekInt
                )

                // 修正颜色（仅针对本周课程做检查以减小负担）
                val currentWeekCourses = cache[configPkg.mondayDate.toString()] ?: emptyList()
                fixInvalidCourseColors(currentWeekCourses.flatMap { it.courses }, configPkg.style)

                val previousState = _uiState.value

                WeeklyScheduleUiState(
                    style = configPkg.style,
                    showWeekends = config?.showWeekends ?: false,
                    totalWeeks = totalWeeks,
                    courseCache = cache, // 注入全量缓存
                    currentMergedCourses = cache[configPkg.mondayDate.toString()] ?: emptyList(),
                    timeSlots = timeSlots,
                    isSemesterSet = startDate != null,
                    semesterStartDate = startDate,
                    firstDayOfWeek = firstDayOfWeekInt,
                    weekIndexInPager = weekIndex,
                    weekTitle = generateTitle(weekIndex, startDate, totalWeeks),
                    currentWeekNumber = currentWeekNum,
                    currentSectionIndex = currentSectionIndex,
                    pagerMondayDate = configPkg.mondayDate,
                    tableId = configPkg.settings.currentCourseTableId,
                    useSakuraTimeTheme = configPkg.settings.useSakuraTimeTheme,
                    // 挂起课程状态在数据刷新时保留（上游同步）
                    floatingCourse = previousState.floatingCourse,
                    floatingSourceWeek = previousState.floatingSourceWeek
                )
            }.collect { _uiState.value = it }
        }
    }

    private fun generateTitle(weekIndex: Int?, startDate: LocalDate?, totalWeeks: Int): String {
        val today = LocalDate.now()
        val provider = stringProvider ?: return "..."
        return when {
            startDate == null -> "ClassFlow"
            today.isBefore(startDate) -> provider(R.string.title_vacation_until_start, arrayOf(ChronoUnit.DAYS.between(today, startDate).toString()))
            weekIndex != null && weekIndex in 1..totalWeeks -> provider(R.string.title_current_week, arrayOf(weekIndex.toString()))
            else -> provider(R.string.title_vacation, emptyArray())
        }
    }

    fun updatePagerDate(newDate: LocalDate) = _pagerMondayDate.update { newDate }

    fun switchCourseTable(tableId: String) {
        viewModelScope.launch {
            val currentSettings = appSettingsRepository.getAppSettingsOnce()
            val newSettings = currentSettings.copy(currentCourseTableId = tableId)
            appSettingsRepository.insertOrUpdateAppSettings(newSettings)
        }
    }

    /**
     * 核心统一时间换算器：将任意 [LocalTime] 转化为网格上的 Float 纵坐标（上游同步）
     * @return 距离网格最顶部的浮点偏置量（1.0f 代表第 1 个格子的顶部起点）
     */
    private fun timeToGridScale(
        time: LocalTime,
        timeSlots: List<TimeSlot>,
        mode: ScheduleModeProto
    ): Float {
        return when (mode) {
            ScheduleModeProto.TIME_24H_MODE -> {
                val currentMinutes = time.hour * 60 + time.minute
                val hourOffset = currentMinutes.toFloat() / 60f
                1.0f + hourOffset
            }
            ScheduleModeProto.SECTION_MODE -> {
                if (timeSlots.isEmpty()) return 1.0f
                val formatter = DateTimeFormatter.ofPattern("HH:mm")
                val sortedSlots = timeSlots.sortedBy { it.number }

                val firstSlotStart = LocalTime.parse(sortedSlots.first().startTime, formatter)
                val lastSlotEnd = LocalTime.parse(sortedSlots.last().endTime, formatter)

                if (!time.isAfter(firstSlotStart)) return 1.0f
                if (!time.isBefore(lastSlotEnd)) return (sortedSlots.size + 1).toFloat()

                val currentSlot = sortedSlots.find {
                    val s = LocalTime.parse(it.startTime, formatter)
                    val e = LocalTime.parse(it.endTime, formatter)
                    !time.isBefore(s) && !time.isAfter(e)
                }

                if (currentSlot != null) {
                    val sTime = LocalTime.parse(currentSlot.startTime, formatter)
                    val eTime = LocalTime.parse(currentSlot.endTime, formatter)
                    val duration = ChronoUnit.MINUTES.between(sTime, eTime).coerceAtLeast(1)
                    return currentSlot.number.toFloat() + (ChronoUnit.MINUTES.between(sTime, time).toFloat() / duration)
                }

                val nextSlot = sortedSlots.find { LocalTime.parse(it.startTime, formatter).isAfter(time) }
                nextSlot?.number?.toFloat() ?: (sortedSlots.size + 1).toFloat()
            }
        }
    }

    /**
     * 反向坐标时间换算器（上游同步）
     * 将 Layout 的浮点偏移量 (0f..maxSection) 完美逆向转换为真实的物理 LocalTime
     */
    private fun gridScaleToTime(
        gridSection: Float,
        timeSlots: List<TimeSlot>,
        mode: ScheduleModeProto
    ): LocalTime {
        return when (mode) {
            ScheduleModeProto.TIME_24H_MODE -> {
                val totalMinutes = (gridSection * 60f).toInt().coerceIn(0, 24 * 60 - 1)
                val hour = totalMinutes / 60
                val minute = totalMinutes % 60
                LocalTime.of(hour, minute)
            }
            ScheduleModeProto.SECTION_MODE -> {
                if (timeSlots.isEmpty()) return LocalTime.of(8, 0)
                val sortedSlots = timeSlots.sortedBy { it.number }
                val formatter = DateTimeFormatter.ofPattern("HH:mm")

                val targetScale = gridSection + 1.0f
                val integerPart = targetScale.toInt()
                val fraction = targetScale - integerPart

                val matchedSlot = sortedSlots.find { it.number == integerPart }
                if (matchedSlot != null) {
                    val sTime = LocalTime.parse(matchedSlot.startTime, formatter)
                    val eTime = LocalTime.parse(matchedSlot.endTime, formatter)
                    val totalDuration = ChronoUnit.MINUTES.between(sTime, eTime)
                    val addedMinutes = (fraction * totalDuration).toLong()
                    sTime.plusMinutes(addedMinutes)
                } else {
                    if (integerPart < sortedSlots.first().number) {
                        LocalTime.parse(sortedSlots.first().startTime, formatter)
                    } else {
                        LocalTime.parse(sortedSlots.last().endTime, formatter)
                    }
                }
            }
        }
    }

    /**
     * 进入跨周移动暂存状态（上游同步）
     */
    fun enterFloatingMode(course: CourseWithWeeks, sourceWeek: Int) {
        _uiState.update {
            it.copy(
                floatingCourse = course,
                floatingSourceWeek = sourceWeek
            )
        }
    }

    /**
     * 全清空或取消挂起队列（上游同步）
     */
    fun exitFloatingMode() {
        _uiState.update {
            it.copy(
                floatingCourse = null,
                floatingSourceWeek = null
            )
        }
    }

    /**
     * 配合跨周结算的最终持久化落地更新（上游同步）
     */
    fun updateCourseTimeByFloatingGesture(
        targetWeek: Int,
        targetDay: Int,
        startSection: Float,
        endSection: Float,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                val courseWrapper = state.floatingCourse ?: return@launch
                val sourceWeek = state.floatingSourceWeek ?: return@launch
                val mode = with(ScheduleGridStyleComposed) { state.style.toComposedStyle() }.scheduleMode
                val slots = state.timeSlots

                val currentSettings = appSettingsRepository.getAppSettingsOnce()
                val tableId = currentSettings.currentCourseTableId
                if (tableId.isBlank()) return@launch

                val originalCourse = courseWrapper.course
                val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

                val updatedCourseForTime = if (mode == ScheduleModeProto.TIME_24H_MODE) {
                    val baseStartTime = gridScaleToTime(startSection, slots, mode)
                    val origStart = LocalTime.parse(originalCourse.customStartTime ?: "08:00", timeFormatter)
                    val origEnd = LocalTime.parse(originalCourse.customEndTime ?: "09:00", timeFormatter)
                    val originalDurationMinutes = ChronoUnit.MINUTES.between(origStart, origEnd).coerceAtLeast(1)
                    val newStartTime = baseStartTime
                    val startMinutesFromMidnight = newStartTime.hour * 60 + newStartTime.minute
                    val rawEndMinutes = startMinutesFromMidnight + originalDurationMinutes

                    val (finalEndTime, isTruncatedToMidnight) = if (rawEndMinutes >= 1440) {
                        LocalTime.of(23, 59) to true
                    } else {
                        newStartTime.plusMinutes(originalDurationMinutes) to false
                    }
                    val calcStartSection = newStartTime.hour + 1

                    val finalEndSection = if (isTruncatedToMidnight) {
                        24
                    } else {
                        val calcEndSection = if (finalEndTime.minute > 0) finalEndTime.hour + 1 else finalEndTime.hour
                        if (calcEndSection == 0) 24 else calcEndSection
                    }

                    originalCourse.copy(
                        day = targetDay,
                        isCustomTime = true,
                        customStartTime = newStartTime.format(timeFormatter),
                        customEndTime = finalEndTime.format(timeFormatter),
                        startSection = calcStartSection.coerceIn(1, 24),
                        endSection = finalEndSection.coerceIn(1, 24)
                    )
                } else {
                    val newStartSection = startSection.toInt().coerceIn(1, slots.size)
                    val newEndSection = endSection.toInt().coerceIn(1, slots.size)
                    if (newStartSection > newEndSection) return@launch

                    originalCourse.copy(
                        day = targetDay,
                        isCustomTime = false,
                        customStartTime = null,
                        customEndTime = null,
                        startSection = newStartSection,
                        endSection = newEndSection
                    )
                }

                val isNoPositionChange = originalCourse.day == updatedCourseForTime.day &&
                        originalCourse.startSection == updatedCourseForTime.startSection &&
                        originalCourse.endSection == updatedCourseForTime.endSection &&
                        originalCourse.customStartTime == updatedCourseForTime.customStartTime &&
                        originalCourse.customEndTime == updatedCourseForTime.customEndTime

                if (sourceWeek == targetWeek && isNoPositionChange) {
                    return@launch
                }

                val isSingleWeek = courseWrapper.weeks.size <= 1

                if (isSingleWeek) {
                    val weekNumbers = listOf(targetWeek)
                    courseTableRepository.upsertCourse(updatedCourseForTime, weekNumbers)
                } else {
                    val remainingWeeks = courseWrapper.weeks
                        .map { it.weekNumber }
                        .filter { it != sourceWeek }
                    courseTableRepository.upsertCourse(originalCourse, remainingWeeks)

                    val clonedNewId = UUID.randomUUID().toString()
                    val finalClonedCourse = updatedCourseForTime.copy(id = clonedNewId)
                    courseTableRepository.upsertCourse(finalClonedCourse, listOf(targetWeek))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _uiState.update {
                    it.copy(
                        floatingCourse = null,
                        floatingSourceWeek = null
                    )
                }
                onComplete()
            }
        }
    }

    /**
     * 统一持久化调度手势调课方法（拆分并更新单周/多周周次逻辑，上游同步）
     */
    fun updateCourseTimeByGesture(
        courseId: String,
        targetDay: Int,
        startSection: Float,
        endSection: Float,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                val mode = with(ScheduleGridStyleComposed) { state.style.toComposedStyle() }.scheduleMode
                val slots = state.timeSlots
                val currentWeek = state.weekIndexInPager ?: state.currentWeekNumber ?: return@launch

                val currentSettings = appSettingsRepository.getAppSettingsOnce()
                val tableId = currentSettings.currentCourseTableId
                if (tableId.isBlank()) return@launch

                val allCoursesWithWeeks = courseTableRepository.getCoursesWithWeeksByTableId(tableId).firstOrNull() ?: return@launch
                val targetWrapper = allCoursesWithWeeks.find { it.course.id == courseId } ?: return@launch
                val originalCourse = targetWrapper.course

                val updatedCourseForTime = if (mode == ScheduleModeProto.TIME_24H_MODE) {
                    val newStartTime = gridScaleToTime(startSection, slots, mode)
                    val newEndTime = gridScaleToTime(endSection, slots, mode)
                    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

                    originalCourse.copy(
                        day = targetDay,
                        isCustomTime = true,
                        customStartTime = newStartTime.format(timeFormatter),
                        customEndTime = newEndTime.format(timeFormatter),
                        startSection = (startSection.toInt() + 1).coerceIn(1, 24),
                        endSection = (endSection.toInt() + 1).coerceIn(1, 24)
                    )
                } else {
                    val newStartSection = (startSection.toInt() + 1).coerceIn(1, slots.size)
                    val newEndSection = endSection.toInt().coerceIn(1, slots.size)
                    if (newStartSection > newEndSection) return@launch

                    originalCourse.copy(
                        day = targetDay,
                        isCustomTime = false,
                        customStartTime = null,
                        customEndTime = null,
                        startSection = newStartSection,
                        endSection = newEndSection
                    )
                }
                val isNoPositionChange = originalCourse.day == updatedCourseForTime.day &&
                        originalCourse.startSection == updatedCourseForTime.startSection &&
                        originalCourse.endSection == updatedCourseForTime.endSection &&
                        originalCourse.customStartTime == updatedCourseForTime.customStartTime &&
                        originalCourse.customEndTime == updatedCourseForTime.customEndTime

                if (isNoPositionChange) {
                    return@launch
                }

                val isSingleWeek = targetWrapper.weeks.size <= 1

                if (isSingleWeek) {
                    val weekNumbers = targetWrapper.weeks.map { it.weekNumber }
                    courseTableRepository.upsertCourse(updatedCourseForTime, weekNumbers)
                } else {
                    val remainingWeeks = targetWrapper.weeks
                        .map { it.weekNumber }
                        .filter { it != currentWeek }
                    courseTableRepository.upsertCourse(originalCourse, remainingWeeks)

                    val clonedNewId = UUID.randomUUID().toString()
                    val finalClonedCourse = updatedCourseForTime.copy(id = clonedNewId)
                    courseTableRepository.upsertCourse(finalClonedCourse, listOf(currentWeek))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                onComplete()
            }
        }
    }

    /**
     * 计算当前时间落在第几节（1-based），不在任何节次内返回 -1（上游同步）
     */
    private fun calculateCurrentSectionIndex(timeSlots: List<TimeSlot>): Int {
        if (timeSlots.isEmpty()) return -1
        val now = LocalTime.now()
        val currentMinutes = now.hour * 60 + now.minute

        timeSlots.forEachIndexed { index, slot ->
            val startParts = slot.startTime.split(":")
            val endParts = slot.endTime.split(":")

            if (startParts.size == 2 && endParts.size == 2) {
                val startMinutes = startParts[0].toInt() * 60 + startParts[1].toInt()
                val endMinutes = endParts[0].toInt() * 60 + endParts[1].toInt()

                if (currentMinutes in startMinutes until endMinutes) {
                    return index + 1
                }
            }
        }
        return -1
    }

    private fun fixInvalidCourseColors(courses: List<CourseWithWeeks>, style: ScheduleGridStyle) {
        viewModelScope.launch {
            val validRange = style.courseColorMaps.indices
            courses.forEach { cw ->
                if (cw.course.colorInt !in validRange) {
                    courseTableRepository.updateCourseColor(cw.course.id, style.generateRandomColorIndex())
                }
            }
        }
    }

    /**
     * 无损展平排版调度引擎（上游同步）：重叠课程按子列并排，每门课独立块
     */
    fun mergeCourses(
        courses: List<CourseWithWeeks>,
        timeSlots: List<TimeSlot>,
        currentWeek: Int,
        mode: ScheduleModeProto = ScheduleModeProto.SECTION_MODE
    ): List<MergedCourseBlock> {
        if (timeSlots.isEmpty() && mode == ScheduleModeProto.SECTION_MODE) return emptyList()

        val maxSection = if (mode == ScheduleModeProto.TIME_24H_MODE) 24f else timeSlots.size.toFloat()
        val limit = maxSection + 1.0f
        val minSafeHeight = if (mode == ScheduleModeProto.TIME_24H_MODE) 0.0f else 0.3f

        val normalizedList = courses.mapNotNull { cw ->
            try {
                val c = cw.course
                val formatter = DateTimeFormatter.ofPattern("HH:mm")

                val (sTime, eTime) = if (c.isCustomTime) {
                    LocalTime.parse(c.customStartTime ?: return@mapNotNull null) to
                            LocalTime.parse(c.customEndTime ?: return@mapNotNull null)
                } else {
                    val startSlot = timeSlots.find { it.number == c.startSection } ?: return@mapNotNull null
                    val endSlot = timeSlots.find { it.number == c.endSection } ?: return@mapNotNull null
                    LocalTime.parse(startSlot.startTime, formatter) to LocalTime.parse(endSlot.endTime, formatter)
                }

                val s = timeToGridScale(sTime, timeSlots, mode)
                val e = timeToGridScale(eTime, timeSlots, mode)

                var finalStart = s
                var finalEnd = e
                if (finalStart >= limit) {
                    finalEnd = limit
                    finalStart = limit - minSafeHeight
                } else if (finalEnd <= 1.0f) {
                    finalStart = 1.0f
                    finalEnd = 1.0f + minSafeHeight
                }

                if (finalEnd - finalStart < minSafeHeight) {
                    if (finalEnd + minSafeHeight <= limit) {
                        finalEnd = finalStart + minSafeHeight
                    } else {
                        finalStart = finalEnd - minSafeHeight
                    }
                }

                NormalizedCourse(cw, finalStart.coerceIn(1.0f, limit - 0.1f), finalEnd.coerceIn(1.0f + 0.1f, limit))
            } catch (e: Exception) { null }
        }

        val result = mutableListOf<MergedCourseBlock>()

        normalizedList.groupBy { it.raw.course.day }.forEach { (day, dailyCourses) ->
            if (dailyCourses.isEmpty()) return@forEach

            val sorted = dailyCourses.sortedWith(
                compareBy<NormalizedCourse> { it.start }.thenByDescending { it.end - it.start }
            )

            val currentClusters = mutableListOf<MutableList<NormalizedCourse>>()

            for (item in sorted) {
                val targetCluster = currentClusters.find { cluster ->
                    cluster.any { existing ->
                        item.start < existing.end - 0.01f && item.end > existing.start + 0.01f
                    }
                }
                if (targetCluster != null) {
                    targetCluster.add(item)
                } else {
                    currentClusters.add(mutableListOf(item))
                }
            }

            for (cluster in currentClusters) {
                val columnEnds = mutableListOf<Float>()
                val itemToColumnIndex = mutableMapOf<NormalizedCourse, Int>()

                for (item in cluster) {
                    var assignedIndex = -1
                    for (i in columnEnds.indices) {
                        if (columnEnds[i] <= item.start + 0.01f) {
                            assignedIndex = i
                            columnEnds[i] = item.end
                            break
                        }
                    }
                    if (assignedIndex == -1) {
                        columnEnds.add(item.end)
                        assignedIndex = columnEnds.size - 1
                    }
                    itemToColumnIndex[item] = assignedIndex
                }

                val totalSubColumns = columnEnds.size

                for (item in cluster) {
                    val cw = item.raw
                    val isCurrentWeekActive = cw.weeks.any { it.weekNumber == currentWeek }
                    val myColumnIndex = itemToColumnIndex[item] ?: 0

                    result.add(
                        MergedCourseBlock(
                            day = day,
                            startSection = (item.start - 1f).coerceIn(0f, maxSection),
                            endSection = (item.end - 1f).coerceIn(0f, maxSection),
                            courses = listOf(cw),
                            needsProportionalRendering = (mode == ScheduleModeProto.TIME_24H_MODE) || cw.course.isCustomTime,
                            isVisualDemoted = !isCurrentWeekActive,
                            nonActiveRanges = listOf(myColumnIndex.toFloat() to totalSubColumns.toFloat())
                        )
                    )
                }
            }
        }
        return result
    }

    suspend fun getTableById(tableId: String): CourseTable? {
        return courseTableRepository.getCourseTableById(tableId)
    }

    suspend fun getCourseConfigOnce(tableId: String): CourseTableConfig? {
        return appSettingsRepository.getCourseConfigOnce(tableId)
    }

    suspend fun getAllCourseTables(): List<CourseTable> {
        return courseTableRepository.getAllCourseTables().first()
    }

    suspend fun createAndSwitchTable(
        name: String,
        studentId: String? = null,
        semesterCode: String? = null
    ): CourseTable {
        val newTable = courseTableRepository.createNewCourseTable(
            name = name,
            studentId = studentId,
            semesterCode = semesterCode
        )
        switchCourseTable(newTable.id)
        return newTable
    }

    suspend fun updateTableMeta(
        tableId: String,
        name: String? = null,
        studentId: String? = null,
        semesterCode: String? = null,
        isArchived: Boolean? = null
    ) {
        val table = courseTableRepository.getCourseTableById(tableId) ?: return
        val updated = table.copy(
            name = name ?: table.name,
            studentId = studentId ?: table.studentId,
            semesterCode = semesterCode ?: table.semesterCode,
            isArchived = isArchived ?: table.isArchived
        )
        courseTableRepository.updateCourseTable(updated)
    }

    suspend fun importCourses(courses: List<CourseWithWeeks>, targetTableId: String? = null) {
        val currentTableId = targetTableId ?: _uiState.value.tableId ?: uiState.value.tableId ?: run {
            courses.firstOrNull()?.course?.courseTableId ?: return
        }
        
        // 由于安全起见以及 WBU 是全量课表返回，先获取这个课表当前所有的课程ID然后删掉
        // 为了方便，直接利用 courseTableRepository 先删除
        val existingCourses = courseTableRepository.getCoursesWithWeeksByTableId(currentTableId).firstOrNull() ?: emptyList()
        if (existingCourses.isNotEmpty()) {
            val idsToDelete = existingCourses.map { it.course.id }
            courseTableRepository.deleteCoursesByIds(idsToDelete)
        }

        // 然后批量插入最新拿到的所有课程
        courses.forEach { courseWithWeeks ->
            val courseToInsert = courseWithWeeks.course.copy(courseTableId = currentTableId)
            val weeks = courseWithWeeks.weeks.map { it.weekNumber }
            courseTableRepository.upsertCourse(courseToInsert, weeks)
        }
    }

    /** 写入学期配置（开学日期/总周数）；config 为 null 时跳过。 */
    suspend fun applySemesterConfig(
        config: com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSemesterConfig?,
        targetTableId: String? = null
    ) {
        if (config == null) return
        val tableId = targetTableId ?: _uiState.value.tableId ?: uiState.value.tableId ?: return
        val current = appSettingsRepository.getCourseConfigOnce(tableId)
        val updated = (current ?: com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfig(courseTableId = tableId)).copy(
            semesterStartDate = config.semesterStartDate ?: current?.semesterStartDate,
            semesterTotalWeeks = config.semesterTotalWeeks
        )
        appSettingsRepository.insertOrUpdateCourseConfig(updated)
    }
}

/** 归一化课程：原始课程 + 网格坐标（上游同步） */
private data class NormalizedCourse(val raw: CourseWithWeeks, val start: Float, val end: Float)

private data class ScheduleConfigPackage(
    val settings: com.xingheyuzhuan.shiguangschedule.data.model.AppSettingsModel,
    val config: com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfig?,
    val style: ScheduleGridStyle,
    val mondayDate: LocalDate
)
