package com.shiro.classflow.ui.schedule.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiro.classflow.data.db.main.CourseWithWeeks
import com.shiro.classflow.data.db.main.TimeSlot
import androidx.compose.ui.res.stringResource
import com.shiro.classflow.R

/**
 * 冲突课程列表底部动作条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictCourseBottomSheet(
    courses: List<CourseWithWeeks>,
    timeSlots: List<TimeSlot>,
    style: ScheduleGridStyleComposed,
    onCourseClicked: (CourseWithWeeks) -> Unit,
    onDismissRequest: () -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()

    val conflictTitleColor = if (isDarkTheme) {
        style.conflictCourseColorDark
    } else {
        style.conflictCourseColor
    }

    val fallbackColorAdapted = if (isDarkTheme) {
        style.courseColorMaps.first().dark
    } else {
        style.courseColorMaps.first().light
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.title_course_conflict),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
                color = conflictTitleColor
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(courses) { courseWithWeeks ->
                    val course = courseWithWeeks.course

                    val isCustomTimeCourse = course.customStartTime != null && course.customEndTime != null

                    val startSlot = timeSlots.find { it.number == course.startSection }?.startTime ?: "N/A"
                    val endSlot = timeSlots.find { it.number == course.endSection }?.endTime ?: "N/A"

                    val colorIndex = course.colorInt.takeIf { it in style.courseColorMaps.indices }

                    val cardBaseColor = colorIndex?.let { index ->
                        val dualColor = style.courseColorMaps[index]
                        if (isDarkTheme) dualColor.dark else dualColor.light
                    } ?: fallbackColorAdapted

                    val cardColor = cardBaseColor.copy(alpha = style.courseBlockAlpha)

                    val textColor = MaterialTheme.colorScheme.onSurface

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCourseClicked(courseWithWeeks) },
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            // 课程名称
                            Text(
                                text = course.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = textColor
                            )
                            Spacer(Modifier.height(8.dp))

                            if (isCustomTimeCourse) {
                                // 自定义时间课程：直接显示时间范围
                                Text(
                                    text = "${course.customStartTime} - ${course.customEndTime}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                // 标准节次课程：显示节次和标准时间段
                                Text(
                                    text = stringResource(
                                        R.string.course_time_description,
                                        course.startSection!!, // 对应 %1$s (起始节次)
                                        course.endSection!!,   // 对应 %2$s (结束节次)
                                        startSlot,           // 对应 %3$s (起始时间)
                                        endSlot              // 对应 %4$s (结束时间)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textColor
                                )
                            }

                            // 详细信息 - 地点
                            Text(
                                text = stringResource(
                                    R.string.course_position_prefix,
                                    course.position
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor
                            )
                            // 详细信息 - 老师
                            Text(
                                text = stringResource(
                                    R.string.course_teacher_prefix,
                                    course.teacher
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor
                            )
                        }
                    }
                }
            }
        }
    }
}
