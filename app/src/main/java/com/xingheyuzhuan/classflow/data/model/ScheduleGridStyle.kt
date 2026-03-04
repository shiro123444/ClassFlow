package com.xingheyuzhuan.classflow.data.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.xingheyuzhuan.classflow.data.model.schedule_style.DualColorProto
import com.xingheyuzhuan.classflow.data.model.schedule_style.ScheduleGridStyleProto

// 1. Compose 业务模型

/**
 * 浅色和深色模式下的颜色对。
 */
data class DualColor(val light: Color, val dark: Color)

/**
 * 课表网格样式配置的业务模型
 * 所有尺寸（Dp）属性使用 Float，颜色（Color）属性使用 Long。
 */
data class ScheduleGridStyle(
    // Grid 尺寸 (单位: Float/Dp)
    val timeColumnWidthDp: Float = DEFAULT_TIME_COLUMN_WIDTH,
    val dayHeaderHeightDp: Float = DEFAULT_DAY_HEADER_HEIGHT,
    val sectionHeightDp: Float = DEFAULT_SECTION_HEIGHT,

    // CourseBlock 外观 (单位: Float/Dp & Float)
    val courseBlockCornerRadiusDp: Float = DEFAULT_BLOCK_CORNER_RADIUS,
    val courseBlockOuterPaddingDp: Float = DEFAULT_BLOCK_OUTER_PADDING,
    val courseBlockInnerPaddingDp: Float = DEFAULT_BLOCK_INNER_PADDING,
    val courseBlockAlphaFloat: Float = DEFAULT_BLOCK_ALPHA,

    // 颜色 (单位: Long/ARGB)
    val conflictCourseColorLong: Long = DEFAULT_CONFLICT_COLOR,
    val conflictCourseColorDarkLong: Long = DEFAULT_CONFLICT_COLOR_DARK,

    // 颜色列表
    val courseColorMaps: List<DualColor> = DEFAULT_COLOR_MAPS,

    val courseBlockFontScale: Float = DEFAULT_FONT_SCALE,

    val hideGridLines: Boolean = false,
    val hideSectionTime: Boolean = false,
    val hideDateUnderDay: Boolean = false,
    val showStartTime: Boolean = false,
    val hideLocation: Boolean = false,
    val hideTeacher: Boolean = false,
    val removeLocationAt: Boolean = false,
    val courseFontFamilyPreset: Int = DEFAULT_COURSE_FONT_FAMILY_PRESET,
    val glassPreset: Int = DEFAULT_GLASS_PRESET,
    val backgroundDimAlpha: Float = DEFAULT_BACKGROUND_DIM_ALPHA,
    val backgroundScale: Float = DEFAULT_BACKGROUND_SCALE,
    val backgroundOffsetX: Float = DEFAULT_BACKGROUND_OFFSET,
    val backgroundOffsetY: Float = DEFAULT_BACKGROUND_OFFSET,

    // 背景壁纸路径 (存储在私有目录下的绝对路径)
    val backgroundImagePath: String? = null
) {

    fun generateRandomColorIndex(): Int {
        if (courseColorMaps.isEmpty()) return 0
        return kotlin.random.Random.nextInt(courseColorMaps.size)
    }

    companion object {
        // --- 默认常量 ---
        internal val DEFAULT_TIME_COLUMN_WIDTH = 40f
        internal val DEFAULT_DAY_HEADER_HEIGHT = 45f
        internal val DEFAULT_SECTION_HEIGHT = 70f
        internal val DEFAULT_BLOCK_CORNER_RADIUS = 4f
        internal val DEFAULT_BLOCK_OUTER_PADDING = 1f
        internal val DEFAULT_BLOCK_INNER_PADDING = 4f
        internal val DEFAULT_BLOCK_ALPHA = 1f
        internal val DEFAULT_FONT_SCALE = 1f
        internal val DEFAULT_CONFLICT_COLOR = 0xFFFF9999L
        internal val DEFAULT_CONFLICT_COLOR_DARK = 0xFF660000L
        internal val DEFAULT_COURSE_FONT_FAMILY_PRESET = 0
        internal val DEFAULT_GLASS_PRESET = 1
        internal val DEFAULT_BACKGROUND_DIM_ALPHA = 0.2f
        internal val DEFAULT_BACKGROUND_SCALE = 1f
        internal val DEFAULT_BACKGROUND_OFFSET = 0f

        internal val DEFAULT_COLOR_MAPS = listOf(
            DualColor(light = Color(0xFFFFCC99), dark = Color(0xFF663300)),
            DualColor(light = Color(0xFFFFE699), dark = Color(0xFF664D00)),
            DualColor(light = Color(0xFFE6FF99), dark = Color(0xFF4D6600)),
            DualColor(light = Color(0xFFCCFF99), dark = Color(0xFF336600)),
            DualColor(light = Color(0xFF99FFB3), dark = Color(0xFF00661A)),
            DualColor(light = Color(0xFF99FFE6), dark = Color(0xFF00664D)),
            DualColor(light = Color(0xFF99FFFF), dark = Color(0xFF006666)),
            DualColor(light = Color(0xFF99E6FF), dark = Color(0xFF004D66)),
            DualColor(light = Color(0xFFB399FF), dark = Color(0xFF1A0066)),
            DualColor(light = Color(0xFFFF99E6), dark = Color(0xFF66004D)),
            DualColor(light = Color(0xFFFF99CC), dark = Color(0xFF660033)),
            DualColor(light = Color(0xFFFF99B3), dark = Color(0xFF66001A)),
        )

        /**
         * 默认样式对象，用于首次启动或重置样式。
         * 注意：backgroundImagePath 默认为 null，但在 ViewModel 的重置逻辑中会特殊处理以保留壁纸。
         */
        val DEFAULT = ScheduleGridStyle(
            timeColumnWidthDp = DEFAULT_TIME_COLUMN_WIDTH,
            dayHeaderHeightDp = DEFAULT_DAY_HEADER_HEIGHT,
            sectionHeightDp = DEFAULT_SECTION_HEIGHT,
            courseBlockCornerRadiusDp = DEFAULT_BLOCK_CORNER_RADIUS,
            courseBlockOuterPaddingDp = DEFAULT_BLOCK_OUTER_PADDING,
            courseBlockInnerPaddingDp = DEFAULT_BLOCK_INNER_PADDING,
            courseBlockAlphaFloat = DEFAULT_BLOCK_ALPHA,
            conflictCourseColorLong = DEFAULT_CONFLICT_COLOR,
            conflictCourseColorDarkLong = DEFAULT_CONFLICT_COLOR_DARK,
            courseColorMaps = DEFAULT_COLOR_MAPS,
            courseBlockFontScale = DEFAULT_FONT_SCALE,
            hideGridLines = false,
            hideSectionTime = false,
            hideDateUnderDay = false,
            showStartTime = false,
            hideLocation = false,
            hideTeacher = false,
            removeLocationAt = false,
            courseFontFamilyPreset = DEFAULT_COURSE_FONT_FAMILY_PRESET,
            glassPreset = DEFAULT_GLASS_PRESET,
            backgroundDimAlpha = DEFAULT_BACKGROUND_DIM_ALPHA,
            backgroundScale = DEFAULT_BACKGROUND_SCALE,
            backgroundOffsetX = DEFAULT_BACKGROUND_OFFSET,
            backgroundOffsetY = DEFAULT_BACKGROUND_OFFSET,
            backgroundImagePath = null
        )
    }
}


// 2. Proto ⇔ Compose 转换扩展函数

fun DualColorProto.toCompose(): DualColor {
    return DualColor(
        light = Color(this.lightColor.toInt()),
        dark = Color(this.darkColor.toInt())
    )
}

fun DualColor.toProto(): DualColorProto {
    return DualColorProto.newBuilder()
        .setLightColor(this.light.toArgb().toLong())
        .setDarkColor(this.dark.toArgb().toLong())
        .build()
}

/**
 * Protobuf -> ScheduleGridStyle 转换函数
 */
fun ScheduleGridStyleProto.toCompose(): ScheduleGridStyle {
    val d = ScheduleGridStyle.DEFAULT

    return ScheduleGridStyle(
        // 1. 基础布局尺寸
        timeColumnWidthDp = if (hasTimeColumnWidthDp()) timeColumnWidthDp else d.timeColumnWidthDp,
        dayHeaderHeightDp = if (hasDayHeaderHeightDp()) dayHeaderHeightDp else d.dayHeaderHeightDp,
        sectionHeightDp = if (hasSectionHeightDp()) sectionHeightDp else d.sectionHeightDp,

        // 2. 课程块外观
        courseBlockCornerRadiusDp = if (hasCourseBlockCornerRadiusDp()) courseBlockCornerRadiusDp else d.courseBlockCornerRadiusDp,
        courseBlockOuterPaddingDp = if (hasCourseBlockOuterPaddingDp()) courseBlockOuterPaddingDp else d.courseBlockOuterPaddingDp,
        courseBlockInnerPaddingDp = if (hasCourseBlockInnerPaddingDp()) courseBlockInnerPaddingDp else d.courseBlockInnerPaddingDp,

        // 3. 透明度与缩放
        courseBlockAlphaFloat = if (hasCourseBlockAlphaFloat()) courseBlockAlphaFloat else d.courseBlockAlphaFloat,
        courseBlockFontScale = if (hasCourseBlockFontScale()) courseBlockFontScale else d.courseBlockFontScale,

        // 4. 颜色配置
        conflictCourseColorLong = if (hasConflictCourseColorLong()) conflictCourseColorLong else d.conflictCourseColorLong,
        conflictCourseColorDarkLong = if (hasConflictCourseColorDarkLong()) conflictCourseColorDarkLong else d.conflictCourseColorDarkLong,

        // 5. 其他列表和布尔值
        courseColorMaps = if (this.courseColorMapsList.isEmpty()) d.courseColorMaps else this.courseColorMapsList.map { it.toCompose() },
        hideGridLines = this.hideGridLines,
        hideSectionTime = this.hideSectionTime,
        hideDateUnderDay = this.hideDateUnderDay,
        showStartTime = this.showStartTime,
        hideLocation = this.hideLocation,
        hideTeacher = this.hideTeacher,
        removeLocationAt = this.removeLocationAt,
        courseFontFamilyPreset = if (hasCourseFontFamilyPreset()) courseFontFamilyPreset.coerceIn(0, 3) else d.courseFontFamilyPreset,
        glassPreset = if (hasGlassPreset()) glassPreset.coerceIn(0, 2) else d.glassPreset,
        backgroundDimAlpha = if (hasBackgroundDimAlpha()) backgroundDimAlpha.coerceIn(0f, 0.8f) else d.backgroundDimAlpha,
        backgroundScale = if (hasBackgroundScale()) backgroundScale.coerceIn(0.8f, 5f) else d.backgroundScale,
        backgroundOffsetX = if (hasBackgroundOffsetX()) backgroundOffsetX.coerceIn(-1f, 1f) else d.backgroundOffsetX,
        backgroundOffsetY = if (hasBackgroundOffsetY()) backgroundOffsetY.coerceIn(-1f, 1f) else d.backgroundOffsetY,

        // 6. 背景图路径映射 (空字符串转 null)
        backgroundImagePath = if (this.backgroundImagePath.isNullOrEmpty()) null else this.backgroundImagePath
    )
}

/**
 * ScheduleGridStyle -> Protobuf 转换 (用于写入)
 */
fun ScheduleGridStyle.toProto(): ScheduleGridStyleProto {
    return ScheduleGridStyleProto.newBuilder().apply {
        timeColumnWidthDp = this@toProto.timeColumnWidthDp
        dayHeaderHeightDp = this@toProto.dayHeaderHeightDp
        sectionHeightDp = this@toProto.sectionHeightDp
        courseBlockCornerRadiusDp = this@toProto.courseBlockCornerRadiusDp
        courseBlockOuterPaddingDp = this@toProto.courseBlockOuterPaddingDp
        courseBlockInnerPaddingDp = this@toProto.courseBlockInnerPaddingDp
        courseBlockAlphaFloat = this@toProto.courseBlockAlphaFloat
        conflictCourseColorLong = this@toProto.conflictCourseColorLong
        conflictCourseColorDarkLong = this@toProto.conflictCourseColorDarkLong
        courseBlockFontScale = this@toProto.courseBlockFontScale

        addAllCourseColorMaps(this@toProto.courseColorMaps.map { it.toProto() })

        hideGridLines = this@toProto.hideGridLines
        hideSectionTime = this@toProto.hideSectionTime
        hideDateUnderDay = this@toProto.hideDateUnderDay
        showStartTime = this@toProto.showStartTime
        hideLocation = this@toProto.hideLocation
        hideTeacher = this@toProto.hideTeacher
        removeLocationAt = this@toProto.removeLocationAt
        courseFontFamilyPreset = this@toProto.courseFontFamilyPreset
        glassPreset = this@toProto.glassPreset
        backgroundDimAlpha = this@toProto.backgroundDimAlpha
        backgroundScale = this@toProto.backgroundScale
        backgroundOffsetX = this@toProto.backgroundOffsetX
        backgroundOffsetY = this@toProto.backgroundOffsetY

        // 将 null 映射回空字符串写入 Proto
        backgroundImagePath = this@toProto.backgroundImagePath ?: ""
    }.build()
}
