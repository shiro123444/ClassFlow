package com.xingheyuzhuan.classflow.ui.settings.course

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.classflow.R
import com.xingheyuzhuan.classflow.data.db.main.TimeSlot

/**
 * 封装了课程时间标题、切换开关和根据模式选择性渲染内容的 Composable。
 */
@Composable
fun TimeAreaSelector(
    day: Int,
    startSection: Int,
    endSection: Int,
    timeSlots: List<TimeSlot>,
    isCustomTime: Boolean,
    customStartTime: String,
    customEndTime: String,
    onIsCustomTimeChange: (Boolean) -> Unit,
    onDayClick: () -> Unit,
    onTimeRangeClick: () -> Unit,
    onSectionButtonClick: () -> Unit
) {
    val labelCourseTime = stringResource(R.string.label_course_time)
    val labelCustomTime = stringResource(R.string.label_custom_time)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. 标题和切换开关
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = labelCourseTime, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = labelCustomTime,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isCustomTime,
                    onCheckedChange = onIsCustomTimeChange
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (isCustomTime) {
            // 2. 自定义时间模式：星期选择器 + 时间范围合并大按钮
            CustomTimeArea(
                day = day,
                customStartTime = customStartTime,
                customEndTime = customEndTime,
                onDayClick = onDayClick,
                onTimeRangeClick = onTimeRangeClick
            )
        } else {
            // 3. 节次选择模式：单个按钮
            SectionSelectorButton(
                day = day,
                startSection = startSection,
                endSection = endSection,
                timeSlots = timeSlots,
                onButtonClick = onSectionButtonClick
            )
        }
    }
}

/**
 * 修改后的自定义时间区域：合并了时间显示逻辑
 */
@Composable
private fun CustomTimeArea(
    day: Int,
    customStartTime: String,
    customEndTime: String,
    onDayClick: () -> Unit,
    onTimeRangeClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 星期选择
        DaySelectorButton(
            selectedDay = day,
            onClick = onDayClick
        )

        // 合并后的时间范围大按钮
        Button(
            onClick = onTimeRangeClick,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            // 如果时间不为空显示范围，否则显示提示文字
            val displayTime = if (customStartTime.isNotBlank() && customEndTime.isNotBlank()) {
                "$customStartTime - $customEndTime"
            } else {
                stringResource(R.string.label_select_custom_time)
            }
            Text(
                text = displayTime,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * 节次选择模式按钮：保持原有逻辑
 */
@Composable
private fun SectionSelectorButton(
    day: Int,
    startSection: Int,
    endSection: Int,
    timeSlots: List<TimeSlot>,
    onButtonClick: () -> Unit
) {
    val days = stringArrayResource(R.array.week_days_full_names)
    val dayName = days.getOrNull(day - 1) ?: days.first()

    val sectionCount = if (endSection >= startSection) {
        stringResource(R.string.course_time_sections_count, endSection - startSection + 1)
    } else {
        ""
    }

    val sectionsText = if (timeSlots.isNotEmpty()) {
        "${startSection}-${endSection}${stringResource(R.string.label_section_range_suffix)}"
    } else {
        stringResource(R.string.label_none)
    }

    val timeInfo = "$sectionsText $sectionCount"
    val buttonText = "$dayName $timeInfo"

    Button(
        onClick = onButtonClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Text(text = buttonText, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * 星期选择按钮
 */
@Composable
private fun DaySelectorButton(
    selectedDay: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val days = stringArrayResource(R.array.week_days_full_names)
    val dayName = days.getOrNull(selectedDay - 1) ?: days.first()
    val labelSelectDay = stringResource(R.string.label_day_of_week)

    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Text(
            text = dayName.ifBlank { labelSelectDay },
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
