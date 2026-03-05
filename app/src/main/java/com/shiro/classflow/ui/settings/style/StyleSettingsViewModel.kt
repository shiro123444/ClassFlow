package com.shiro.classflow.ui.settings.style

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.shiro.classflow.MyApplication
import com.shiro.classflow.data.db.main.Course
import com.shiro.classflow.data.db.main.CourseWithWeeks
import com.shiro.classflow.data.db.main.TimeSlot
import com.shiro.classflow.data.model.DualColor
import com.shiro.classflow.data.repository.AppSettingsRepository
import com.shiro.classflow.data.repository.StyleSettingsRepository
import com.shiro.classflow.ui.schedule.MergedCourseBlock
import com.shiro.classflow.ui.schedule.WeeklyScheduleUiState
import com.shiro.classflow.ui.schedule.components.ScheduleGridStyleComposed
import com.shiro.classflow.ui.schedule.components.ScheduleGridStyleComposed.Companion.toComposedStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class StyleSettingsViewModel(
    private val styleRepository: StyleSettingsRepository,
    private val appSettingsRepository: AppSettingsRepository
) : ViewModel() {

    // Collapsible section state: sections 0 (Interface) and 2 (Course Block) expanded by default
    private val _expandedSections = MutableStateFlow(setOf(0, 2))
    val expandedSections: StateFlow<Set<Int>> = _expandedSections.asStateFlow()

    fun toggleSection(index: Int) {
        _expandedSections.update { current ->
            if (index in current) current - index else current + index
        }
    }

    // 订阅样式设置
    val styleState: StateFlow<ScheduleGridStyleComposed?> = styleRepository.styleFlow
        .map { it.toComposedStyle() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val demoUiState: StateFlow<WeeklyScheduleUiState> = appSettingsRepository.getAppSettings()
        .flatMapLatest { settings ->
            val configFlow = settings.currentCourseTableId?.let { tableId ->
                appSettingsRepository.getCourseTableConfigFlow(tableId)
            } ?: flowOf(null)

            combine(configFlow, styleRepository.styleFlow) { config, currentStyle ->
                WeeklyScheduleUiState(
                    style = currentStyle,
                    currentMergedCourses = createDemoCourses(),
                    timeSlots = createDemoTimeSlots(),
                    showWeekends = config?.showWeekends ?: true,
                    totalWeeks = 20,
                    isSemesterSet = true,
                    semesterStartDate = java.time.LocalDate.now(),
                    firstDayOfWeek = 1,
                    currentWeekNumber = 1
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = WeeklyScheduleUiState(showWeekends = true)
        )

    // --- 背景壁纸管理 API (完善的垃圾处理) ---

    /**
     * 更新或设置壁纸
     * 逻辑：
     * 1. 尝试删除旧图片文件以节省空间。
     * 2. 生成一个全新的随机文件名 (UUID)。
     * 3. 将新图拷贝到私有目录并更新数据库。
     */
    fun updateWallpaper(
        context: Context,
        uri: Uri,
        onCompleted: (() -> Unit)? = null
    ) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val currentStyle = styleRepository.getStyleOnce()
            val currentPath = currentStyle.backgroundImagePath ?: ""

            if (currentPath.isNotEmpty()) {
                val oldFile = File(currentPath)
                if (oldFile.exists()) {
                    oldFile.delete()
                }
            }

            val newFileName = "wallpaper_${UUID.randomUUID()}.jpg"
            val newFile = File(context.filesDir, newFileName)

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                newFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            styleRepository.setBackgroundImagePath(newFile.absolutePath)
            if (onCompleted != null) {
                launch(Dispatchers.Main) { onCompleted() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 彻底移除壁纸
     * 逻辑：根据数据库记录的路径删除物理文件，然后清空记录。
     */
    fun removeWallpaper(context: Context) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val currentStyle = styleRepository.getStyleOnce()
            val path = currentStyle.backgroundImagePath ?: ""

            if (path.isNotEmpty()) {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
            styleRepository.setBackgroundImagePath("")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 恢复默认设置 (但保护/保留壁纸)
     * 调用 Repository 中特殊处理过的重置函数，保留当前的背景图路径。
     */
    fun resetStyleSettings() = viewModelScope.launch {
        styleRepository.resetAllStyleSettingsExceptWallpaper()
    }

    /**
     * 彻底重置所有 (包括壁纸)
     */
    fun resetEverything(context: Context) = viewModelScope.launch(Dispatchers.IO) {
        // 先调用移除壁纸逻辑清理物理文件
        removeWallpaper(context)
        // 再重置数据库所有项
        styleRepository.resetAllStyleSettings()
    }
    // --- 尺寸与边距 API ---
    fun updateSectionHeight(height: Float) = viewModelScope.launch { styleRepository.setSectionHeight(height) }
    fun updateTimeColumnWidth(width: Float) = viewModelScope.launch { styleRepository.setTimeColumnWidth(width) }

    /** 更新日表头高度 (DayHeader) */
    fun updateDayHeaderHeight(height: Float) = viewModelScope.launch {
        styleRepository.setDayHeaderHeight(height)
    }

    fun updateCornerRadius(radius: Float) = viewModelScope.launch { styleRepository.setCourseBlockCornerRadius(radius) }
    fun updateOuterPadding(padding: Float) = viewModelScope.launch { styleRepository.setCourseBlockOuterPadding(padding) }

    /** 更新课程块内部填充 (InnerPadding) */
    fun updateInnerPadding(padding: Float) = viewModelScope.launch {
        styleRepository.setCourseBlockInnerPadding(padding)
    }

    fun updateAlpha(alpha: Float) = viewModelScope.launch { styleRepository.setCourseBlockAlpha(alpha) }
    fun updateGlassPreset(preset: Int) = viewModelScope.launch { styleRepository.setGlassPreset(preset) }
    fun updateBackgroundDimAlpha(alpha: Float) = viewModelScope.launch { styleRepository.setBackgroundDimAlpha(alpha) }
    fun updateBackgroundScale(scale: Float) = viewModelScope.launch { styleRepository.setBackgroundScale(scale) }
    fun updateBackgroundOffsetX(offset: Float) = viewModelScope.launch { styleRepository.setBackgroundOffsetX(offset) }
    fun updateBackgroundOffsetY(offset: Float) = viewModelScope.launch { styleRepository.setBackgroundOffsetY(offset) }

    fun updateWallpaperTransform(scale: Float, offsetX: Float, offsetY: Float) = viewModelScope.launch {
        styleRepository.setBackgroundTransform(scale, offsetX, offsetY)
    }

    fun resetWallpaperTransform() = viewModelScope.launch {
        styleRepository.setBackgroundTransform(1f, 0f, 0f)
    }

    fun applyGlassPreset(preset: Int) = viewModelScope.launch {
        val safePreset = preset.coerceIn(0, 2)
        styleRepository.setGlassPreset(safePreset)
        when (safePreset) {
            0 -> {
                styleRepository.setCourseBlockAlpha(0.95f)
                styleRepository.setCourseBlockCornerRadius(8f)
                styleRepository.setBackgroundDimAlpha(0.08f)
            }
            1 -> {
                styleRepository.setCourseBlockAlpha(0.72f)
                styleRepository.setCourseBlockCornerRadius(16f)
                styleRepository.setBackgroundDimAlpha(0.2f)
            }
            else -> {
                styleRepository.setCourseBlockAlpha(0.62f)
                styleRepository.setCourseBlockCornerRadius(20f)
                styleRepository.setBackgroundDimAlpha(0.32f)
            }
        }
    }

    // --- UI 渲染开关 API ---

    /** 更新是否隐藏网格线 */
    fun updateHideGridLines(hide: Boolean) = viewModelScope.launch {
        styleRepository.setHideGridLines(hide)
    }

    /** 更新是否隐藏左侧时间列的具体时间 */
    fun updateHideSectionTime(hide: Boolean) = viewModelScope.launch {
        styleRepository.setHideSectionTime(hide)
    }

    /** 更新是否隐藏星期栏下的日期 */
    fun updateHideDateUnderDay(hide: Boolean) = viewModelScope.launch {
        styleRepository.setHideDateUnderDay(hide)
    }

    /** 更新是否在课程块内显示开始时间 */
    fun updateShowStartTime(show: Boolean) = viewModelScope.launch {
        styleRepository.setShowStartTime(show)
    }

    fun updateConflictColor(color: Color, isDark: Boolean) = viewModelScope.launch {
        styleRepository.setConflictCourseColorLong(color.toArgb().toLong(), isDark)
    }

    /** * 更新课程块字体的缩放比例
     * @param scale 缩放倍数，通常范围在 0.5 - 2.0 之间
     */
    fun updateCourseBlockFontScale(scale: Float) = viewModelScope.launch {
        styleRepository.setCourseBlockFontScale(scale)
    }

    fun updateCourseFontFamilyPreset(preset: Int) = viewModelScope.launch {
        styleRepository.setCourseFontFamilyPreset(preset)
    }

    /** 更新是否隐藏课程块内的上课地点 */
    fun updateHideLocation(hide: Boolean) = viewModelScope.launch {
        styleRepository.setHideLocation(hide)
    }

    /** 更新是否隐藏课程块内的授课老师 */
    fun updateHideTeacher(hide: Boolean) = viewModelScope.launch {
        styleRepository.setHideTeacher(hide)
    }

    /** 更新是否移除地点前的 "@" 符号 */
    fun updateRemoveLocationAt(remove: Boolean) = viewModelScope.launch {
        styleRepository.setRemoveLocationAt(remove)
    }

    /**
     * 更新普通课程的主色
     * @param index UI 传递过来的颜色索引
     * @param color 新颜色
     * @param isDark 是否为深色模式下的颜色
     */
    fun updatePrimaryColor(index: Int, color: Color, isDark: Boolean) = viewModelScope.launch {
        // 1. 获取当前 Repository 中最新的样式快照
        val currentStyle = styleRepository.getStyleOnce()
        // 2. 将 Proto 转出的 List 转换为 MutableList 以便修改
        val updatedMaps = currentStyle.courseColorMaps.toMutableList()

        // 3. 安全检查：如果索引越界（比如初始列表为空），则填充默认值
        if (index >= updatedMaps.size) {
            repeat(index - updatedMaps.size + 1) {
                updatedMaps.add(DualColor(light = Color.Gray, dark = Color.Gray))
            }
        }

        // 4. 更新对应索引位置的颜色
        val oldPair = updatedMaps[index]
        updatedMaps[index] = if (isDark) {
            oldPair.copy(dark = color)
        } else {
            oldPair.copy(light = color)
        }

        // 5. 调用 Repository 的 setCourseColorMaps 接口写回 DataStore
        styleRepository.setCourseColorMaps(updatedMaps)
    }

    //  演示数据构造
    private fun createDemoCourses(): List<MergedCourseBlock> {
        val dummyTableId = UUID.randomUUID().toString()
        val slots = createDemoTimeSlots()

        /**
         * 修正后的逻辑位置计算：
         * 为了让预览界面和实际课表对齐，这里必须模拟 [timeToLogicalScale] 的计算结果。
         * startSection = (逻辑节次 - 1)
         */
        fun getLogicalPosition(timeStr: String): Float {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            val time = java.time.LocalTime.parse(timeStr, formatter)

            // 模拟 ViewModel 的逻辑：找到所属节次
            val slot = slots.find {
                val s = java.time.LocalTime.parse(it.startTime, formatter)
                val e = java.time.LocalTime.parse(it.endTime, formatter)
                !time.isBefore(s) && !time.isAfter(e)
            }

            return if (slot != null) {
                val sTime = java.time.LocalTime.parse(slot.startTime, formatter)
                val eTime = java.time.LocalTime.parse(slot.endTime, formatter)
                val duration = java.time.temporal.ChronoUnit.MINUTES.between(sTime, eTime).coerceAtLeast(1)
                val elapsed = java.time.temporal.ChronoUnit.MINUTES.between(sTime, time)
                // 结果：(节次序号 + 进度) - 1.0 (因为 UI 坐标从 0 开始)
                (slot.number.toFloat() + (elapsed.toFloat() / duration.toFloat())) - 1f
            } else {
                // 如果在课间，简单返回最近的节次起止点（预览数据简化处理）
                val nextSlot = slots.find { java.time.LocalTime.parse(it.startTime, formatter).isAfter(time) }
                (nextSlot?.number?.minus(1)?.toFloat() ?: 0f)
            }
        }

        // 1. 普通课程展示 (周一 1-2节)
        // 占满第 1 节和第 2 节，逻辑坐标应该是从 0.0 到 2.0
        val courseA = Course(UUID.randomUUID().toString(), dummyTableId, "普通课程展示", "张老师", "教A-101", 1, 1, 2, false, null, null, 0)

        // 2. 自定义时间演示 (周二 09:50 - 11:30)
        // 假设第 3 节是 10:20 开始，那么 09:50 会被计算在第 2 节和第 3 节之间
        val courseB = Course(UUID.randomUUID().toString(), dummyTableId, "精准渲染演示", "系统", "1:1还原", 2, null, null, true, "09:50", "11:30", 1)

        // 3. 冲突课程 (周三 1-2节)
        val courseC1 = Course(UUID.randomUUID().toString(), dummyTableId, "冲突课程 A", "王老师", "302", 3, 1, 2, false, null, null, 2)
        val courseC2 = Course(UUID.randomUUID().toString(), dummyTableId, "冲突课程 B", "赵老师", "405", 3, 1, 2, false, null, null, 3)

        return listOf(
            // 普通课程：第 1 节起(0.0)，第 2 节止(2.0)
            MergedCourseBlock(
                day = 1,
                startSection = 0f,
                endSection = 2f,
                courses = listOf(CourseWithWeeks(courseA, emptyList()))
            ),

            // 自定义时间：动态计算逻辑位置
            MergedCourseBlock(
                day = 2,
                startSection = getLogicalPosition("09:50"),
                endSection = getLogicalPosition("11:30"),
                courses = listOf(CourseWithWeeks(courseB, emptyList()))
            ),

            // 冲突课程：第 1 节起(0.0)，第 2 节止(2.0)
            MergedCourseBlock(
                day = 3,
                startSection = 0f,
                endSection = 2f,
                courses = listOf(CourseWithWeeks(courseC1, emptyList()), CourseWithWeeks(courseC2, emptyList())),
                isConflict = true
            )
        )
    }

    private fun createDemoTimeSlots(): List<TimeSlot> = listOf(
        TimeSlot(1, "08:20", "09:05", "demo"),
        TimeSlot(2, "09:15", "10:00", "demo"),
        TimeSlot(3, "10:20", "11:05", "demo"),
        TimeSlot(4, "11:15", "12:00", "demo"),
        TimeSlot(5, "14:00", "14:45", "demo"),
        TimeSlot(6, "14:55", "15:40", "demo"),
        TimeSlot(7, "16:00", "16:45", "demo"),
        TimeSlot(8, "16:55", "17:40", "demo")
    )
}

object StyleSettingsViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as MyApplication
        return StyleSettingsViewModel(
            styleRepository = application.styleSettingsRepository,
            appSettingsRepository = application.appSettingsRepository
        ) as T
    }
}
