package com.xingheyuzhuan.classflow.ui.schedule.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.xingheyuzhuan.classflow.R
import com.xingheyuzhuan.classflow.ui.schedule.MergedCourseBlock

/**
 * 渲染单个课程块的 UI 组件。
 * 它负责展示课程信息、颜色，并处理冲突标记。
 */
@Composable
fun CourseBlock(
    mergedBlock: MergedCourseBlock,
    style: ScheduleGridStyleComposed,
    modifier: Modifier = Modifier,
    startTime: String? = null
) {
    val firstCourse = mergedBlock.courses.firstOrNull()
    val isDarkTheme = isSystemInDarkTheme() // 获取当前主题模式

    val conflictColorAdapted = if (isDarkTheme) {
        style.conflictCourseColorDark // 使用深色冲突色
    } else {
        style.conflictCourseColor // 使用浅色冲突色
    }

    // 尝试获取颜色索引 (colorInt)
    val colorIndex = firstCourse?.course?.colorInt
        // 检查索引是否在映射表范围内，否则返回 null
        ?.takeIf { it in style.courseColorMaps.indices }

    // 适配后的课程颜色，如果 colorIndex 存在
    val courseColorAdapted: Color? = colorIndex?.let { index ->
        val baseColorMap = style.courseColorMaps[index]
        if (isDarkTheme) {
            baseColorMap.dark
        } else {
            baseColorMap.light
        }
    }

    val fallbackColorAdapted: Color = if (isDarkTheme) {
        style.courseColorMaps.first().dark
    } else {
        style.courseColorMaps.first().light
    }

    val blockColor = if (mergedBlock.isConflict) {
        conflictColorAdapted.copy(alpha = style.courseBlockAlpha)
    } else {
        (courseColorAdapted ?: fallbackColorAdapted).copy(alpha = style.courseBlockAlpha)
    }

    val borderWidth = when (style.glassPreset) {
        0 -> 0.5.dp
        1 -> 1.dp
        else -> 1.5.dp
    }
    val borderAlpha = when (style.glassPreset) {
        0 -> 0.16f
        1 -> 0.32f
        else -> 0.42f
    }

    val textColor = MaterialTheme.colorScheme.onSurface
    val courseFontFamily = FontFamily.Default

    // --- 字体大小计算逻辑 (新增) ---
    // 通过将基准字号乘以缩放因子，实现全局联动
    val s13 = (13 * style.fontScale).sp
    val s12 = (12 * style.fontScale).sp
    val s10 = (10 * style.fontScale).sp

    val customStartTime = firstCourse?.course?.customStartTime
    val customEndTime = firstCourse?.course?.customEndTime
    val customTimeString = if (customStartTime != null && customEndTime != null) {
        "$customStartTime - $customEndTime"
    } else {
        null
    }
    val isCustomTimeCourse = customTimeString != null

    Box(
        modifier = modifier
            .padding(style.courseBlockOuterPadding)
            .clip(RoundedCornerShape(style.courseBlockCornerRadius))
                .border(borderWidth, Color.White.copy(alpha = borderAlpha), RoundedCornerShape(style.courseBlockCornerRadius))
            .background(color = blockColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(style.courseBlockInnerPadding)
        ) {
            if (mergedBlock.isConflict) {
                // 冲突状态下的字体缩放
                mergedBlock.courses.forEach { course ->
                    Text(
                        text = course.course.name,
                        fontSize = s12, // 使用缩放后的 12sp
                        fontWeight = FontWeight.Bold,
                        fontFamily = courseFontFamily,
                        color = textColor,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Text(
                    text = stringResource(R.string.label_conflict),
                    fontSize = s10, // 使用缩放后的 10sp
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontFamily = courseFontFamily,
                    modifier = Modifier.padding(top = 2.dp)
                )
            } else {
                // --- 1. 时间显示层 ---
                if (isCustomTimeCourse) {
                    Text(
                        text = customTimeString,
                        fontSize = s10, // 使用缩放后的 10sp
                        color = textColor.copy(alpha = 0.8f),
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = courseFontFamily,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(lineHeight = 1.em)
                    )
                } else if (style.showStartTime && startTime != null) {
                    Text(
                        text = startTime,
                        fontSize = s10, // 使用缩放后的 10sp
                        color = textColor.copy(alpha = 0.8f),
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = courseFontFamily,
                        style = TextStyle(lineHeight = 1.em)
                    )
                }

                // --- 2. 课程名称 ---
                Text(
                    text = firstCourse?.course?.name ?: "",
                    fontSize = s13,
                    fontWeight = FontWeight.Bold,
                    fontFamily = courseFontFamily,
                    color = textColor,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    style = TextStyle(lineHeight = 1.2.em)
                )

                // --- 3. 教师 (受 hideTeacher 开关控制) ---
                if (!style.hideTeacher) { // 如果不隐藏，则显示
                    val teacher = firstCourse?.course?.teacher ?: ""
                    if (teacher.isNotBlank()) {
                        Text(
                            text = teacher,
                            fontSize = s10,
                            fontFamily = courseFontFamily,
                            color = textColor,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(lineHeight = 1.em)
                        )
                    }
                }

                // --- 4. 地点 (受 hideLocation 和 removeLocationAt 开关控制) ---
                if (!style.hideLocation) { // 如果不隐藏，则显示
                    val position = firstCourse?.course?.position ?: ""
                    if (position.isNotBlank()) {
                        // 根据 removeLocationAt 决定前缀
                        val prefix = if (style.removeLocationAt) "" else "@"
                        Text(
                            text = "$prefix$position",
                            fontSize = s10,
                            fontFamily = courseFontFamily,
                            color = textColor,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(lineHeight = 1.em)
                        )
                    }
                }
            }
        }
    }
}
