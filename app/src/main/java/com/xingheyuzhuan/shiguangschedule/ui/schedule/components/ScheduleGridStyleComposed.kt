package com.xingheyuzhuan.shiguangschedule.ui.schedule.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.data.model.DualColor
import com.xingheyuzhuan.shiguangschedule.data.model.ScheduleGridStyle
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.BorderTypeProto
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.ScheduleModeProto

/**
 * 【Presentation Layer Model】
 * 将原始 Float/Long 值的 ScheduleGridStyle 转换为 Compose 强类型 Dp/Color 的包装对象。
 */
data class ScheduleGridStyleComposed(
    // Grid 尺寸 (Dp)
    val timeColumnWidth: Dp,
    val dayHeaderHeight: Dp,
    val sectionHeight: Dp,

    // CourseBlock 外观 (Dp & Float)
    val courseBlockCornerRadius: Dp,
    val courseBlockOuterPadding: Dp,
    val courseBlockInnerPadding: Dp,
    val courseBlockAlpha: Float,

    // 壁纸路径
    val backgroundImagePath: String,

    // 字体缩放比例
    val fontScale: Float,

    // 颜色 (Color)
    val courseColorMaps: List<DualColor>,

    // UI 渲染开关
    val hideGridLines: Boolean,      // 是否隐藏网格线
    val hideSectionTime: Boolean,
    val hideDateUnderDay: Boolean,
    val showStartTime: Boolean,

    val hideLocation: Boolean,       // 是否隐藏上课地点
    val hideTeacher: Boolean,        // 是否隐藏授课老师
    val removeLocationAt: Boolean,   // 是否移除地点前的 @ 符号
    val fontFamilyPreset: Int,
    val glassPreset: Int,
    val backgroundDimAlpha: Float,
    val backgroundScale: Float,
    val backgroundOffsetX: Float,
    val backgroundOffsetY: Float,
    val courseBlockBlurRadius: Dp,
    val courseBlockColorless: Boolean,

    // ── 上游同步字段 ──
    val pageTextColor: Color?,
    val courseTextColor: Color?,
    val textAlignCenterHorizontal: Boolean,
    val textAlignCenterVertical: Boolean,
    val borderType: BorderTypeProto,
    val scheduleMode: ScheduleModeProto
) {
    /** 背景遮罩模糊半径：由 0..0.8 的遮罩强度映射到 0..40dp */
    val backgroundBlurRadius: Dp
        get() = (backgroundDimAlpha * 50f).dp.coerceAtMost(40.dp)

    companion object {
        /**
         * 扩展函数：将数据模型 (Float/Long) 转换为 UI 强类型模型 (Dp/Color)。
         */
        fun ScheduleGridStyle.toComposedStyle(): ScheduleGridStyleComposed {
            return ScheduleGridStyleComposed(
                timeColumnWidth = this.timeColumnWidthDp.dp,
                dayHeaderHeight = this.dayHeaderHeightDp.dp,
                sectionHeight = this.sectionHeightDp.dp,
                courseBlockCornerRadius = this.courseBlockCornerRadiusDp.dp,
                courseBlockOuterPadding = this.courseBlockOuterPaddingDp.dp,
                courseBlockInnerPadding = this.courseBlockInnerPaddingDp.dp,
                courseBlockAlpha = this.courseBlockAlphaFloat,
                fontScale = this.courseBlockFontScale,
                courseColorMaps = this.courseColorMaps.map { dual ->
                    DualColor(
                        light = Color(dual.light.toArgb()),
                        dark = Color(dual.dark.toArgb())
                    )
                },
                hideGridLines = this.hideGridLines,
                hideSectionTime = this.hideSectionTime,
                hideDateUnderDay = this.hideDateUnderDay,
                showStartTime = this.showStartTime,
                hideLocation = this.hideLocation,
                hideTeacher = this.hideTeacher,
                removeLocationAt = this.removeLocationAt,
                fontFamilyPreset = this.courseFontFamilyPreset,
                glassPreset = this.glassPreset,
                backgroundDimAlpha = this.backgroundDimAlpha,
                backgroundScale = this.backgroundScale,
                backgroundOffsetX = this.backgroundOffsetX,
                backgroundOffsetY = this.backgroundOffsetY,
                backgroundImagePath = this.backgroundImagePath ?: "",
                courseBlockBlurRadius = this.courseBlockBlurRadiusDp.dp,
                courseBlockColorless = this.courseBlockColorless,
                pageTextColor = this.pageTextColorLong?.let { Color(it.toInt()) },
                courseTextColor = this.courseTextColorLong?.let { Color(it.toInt()) },
                textAlignCenterHorizontal = this.textAlignCenterHorizontal,
                textAlignCenterVertical = this.textAlignCenterVertical,
                borderType = this.borderType,
                scheduleMode = this.scheduleMode,
            )
        }
    }
}
