package com.shiro.classflow.ui.schedule.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import com.shiro.classflow.R
import com.shiro.classflow.data.db.main.TimeSlot
import com.shiro.classflow.ui.schedule.MergedCourseBlock

/**
 * 绘图模型接口
 * startSection/endSection 基于逻辑节次坐标（0.0 代表第一节课顶部）
 */
interface ISchedulable {
    val columnIndex: Int
    val startSection: Float
    val endSection: Float
    val rawData: MergedCourseBlock
}

@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
@Composable
fun ScheduleGrid(
    style: ScheduleGridStyleComposed,
    dates: List<String>,
    timeSlots: List<TimeSlot>,
    mergedCourses: List<MergedCourseBlock>,
    showWeekends: Boolean,
    todayIndex: Int,
    firstDayOfWeek: Int,
    onCourseBlockClicked: (MergedCourseBlock) -> Unit,
    onGridCellClicked: (Int, Int) -> Unit,
    onTimeSlotClicked: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = maxWidth

        // 1. 处理日期与星期排序
        val weekDays = stringArrayResource(R.array.week_days_full_names).toList()
        val reorderedWeekDays = rearrangeDays(weekDays, firstDayOfWeek)
        val displayDays = if (showWeekends) reorderedWeekDays else reorderedWeekDays.take(5)

        // 2. 计算尺寸
        val cellWidth = (screenWidth - style.timeColumnWidth) / displayDays.size
        val viewportBodyHeight = (maxHeight - style.dayHeaderHeight).coerceAtLeast(style.sectionHeight * 9)
        val effectiveSectionHeight = (viewportBodyHeight / 9).coerceAtLeast(48.dp)
        val totalGridHeight = effectiveSectionHeight * timeSlots.size
        val gridLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)

        // 3. 转换绘图数据
        val schedulables = mergedCourses.mapNotNull { block ->
            val displayIdx = mapDayToDisplayIndex(block.day, firstDayOfWeek, showWeekends)
            if (displayIdx == -1) return@mapNotNull null
            object : ISchedulable {
                override val columnIndex = displayIdx
                override val startSection = block.startSection
                override val endSection = block.endSection
                override val rawData = block
            }
        }

        Column(Modifier.fillMaxSize()) {
            DayHeader(style, displayDays, dates, cellWidth, todayIndex, gridLineColor)

            Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                TimeColumn(
                    style = style,
                    timeSlots = timeSlots,
                    sectionHeight = effectiveSectionHeight,
                    onTimeSlotClicked = onTimeSlotClicked,
                    modifier = Modifier.height(totalGridHeight),
                    lineColor = gridLineColor
                )

                Box(Modifier.height(totalGridHeight).weight(1f)) {
                    // 控制主体网格线显示
                    if (!style.hideGridLines) {
                        GridLines(displayDays.size, timeSlots.size, cellWidth, totalGridHeight, effectiveSectionHeight, gridLineColor)
                    }

                    ClickableGrid(displayDays.size, timeSlots.size, cellWidth, effectiveSectionHeight) { dayIdx, sec ->
                        onGridCellClicked(mapDisplayIndexToDay(dayIdx, firstDayOfWeek), sec)
                    }

                    schedulables.forEach { item ->
                        val topOffset = item.startSection * effectiveSectionHeight
                        val blockHeight = (item.endSection - item.startSection) * effectiveSectionHeight

                        Box(
                            modifier = Modifier
                                .offset(x = item.columnIndex * cellWidth, y = topOffset)
                                .size(width = cellWidth, height = blockHeight)
                                .padding(style.courseBlockOuterPadding)
                                .clickable { onCourseBlockClicked(item.rawData) }
                        ) {
                            CourseBlock(
                                mergedBlock = item.rawData,
                                style = style,
                                startTime = item.rawData.courses.firstOrNull()?.course?.let {
                                    if(it.isCustomTime) it.customStartTime
                                    else timeSlots.find { ts -> ts.number == it.startSection }?.startTime
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// 子组件

@Composable
private fun DayHeader(style: ScheduleGridStyleComposed, displayDays: List<String>, dates: List<String>, cellWidth: Dp, todayIndex: Int, lineColor: Color) {
    Row(Modifier.fillMaxWidth().height(style.dayHeaderHeight).drawBehind {
        // 表头底部横线
        if (!style.hideGridLines) {
            drawLine(lineColor, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        }
    }) {
        Spacer(Modifier.width(style.timeColumnWidth).fillMaxHeight().drawBehind {
            // 时间轴右侧分割线
            if (!style.hideGridLines) {
                drawLine(lineColor, Offset(size.width, 0f), Offset(size.width, size.height), 1f)
            }
        })
        displayDays.forEachIndexed { index, day ->
            Box(Modifier.width(cellWidth).fillMaxHeight()
                .background(if (index == todayIndex) MaterialTheme.colorScheme.primaryContainer.copy(0.4f) else Color.Transparent)
                .drawBehind {
                    if (!style.hideGridLines) {
                        drawLine(lineColor, Offset(size.width, 0f), Offset(size.width, size.height), 1f)
                    }
                }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 统一使用 onSurface，不加高亮判断
                    Text(
                        text = day,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!style.hideDateUnderDay && dates.size > index) {
                        Text(
                            text = dates[index],
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeColumn(
    style: ScheduleGridStyleComposed,
    timeSlots: List<TimeSlot>,
    sectionHeight: Dp,
    onTimeSlotClicked: () -> Unit,
    modifier: Modifier,
    lineColor: Color
) {
    Column(modifier.width(style.timeColumnWidth).drawBehind {
        if (!style.hideGridLines) {
            drawLine(lineColor, Offset(size.width, 0f), Offset(size.width, size.height), 1f)
        }
    }) {
        timeSlots.forEach { slot ->
            Column(Modifier.fillMaxWidth().height(sectionHeight).clickable { onTimeSlotClicked() }.drawBehind {
                if (!style.hideGridLines) {
                    drawLine(lineColor, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                }
            }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(
                    text = slot.number.toString(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!style.hideSectionTime) {
                    Text(
                        text = slot.startTime,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = slot.endTime,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun GridLines(dayCount: Int, slotCount: Int, cellWidth: Dp, totalHeight: Dp, sectionHeight: Dp, lineColor: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val h = totalHeight.toPx()
        repeat(dayCount) { i ->
            val x = i * cellWidth.toPx()
            drawLine(lineColor, Offset(x, 0f), Offset(x, h), 1f)
        }
        repeat(slotCount) { i ->
            val y = i * sectionHeight.toPx()
            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), 1f)
        }
    }
}

@Composable
private fun ClickableGrid(dayCount: Int, slotCount: Int, cellWidth: Dp, sectionHeight: Dp, onClick: (Int, Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        for (sec in 1..slotCount) {
            Row(Modifier.fillMaxWidth().height(sectionHeight)) {
                repeat(dayCount) { idx ->
                    Spacer(Modifier.width(cellWidth).fillMaxHeight().clickable { onClick(idx, sec) })
                }
            }
        }
    }
}

//  辅助逻辑

private fun rearrangeDays(originalDays: List<String>, firstDayOfWeek: Int): List<String> {
    val startIndex = (firstDayOfWeek - 1).coerceIn(0, 6)
    return originalDays.subList(startIndex, originalDays.size) + originalDays.subList(0, startIndex)
}

private fun mapDayToDisplayIndex(courseDay: Int, firstDayOfWeek: Int, showWeekends: Boolean): Int {
    val idx = (courseDay - firstDayOfWeek + 7) % 7
    return if (idx >= if (showWeekends) 7 else 5) -1 else idx
}

private fun mapDisplayIndexToDay(idx: Int, firstDayOfWeek: Int): Int = (firstDayOfWeek - 1 + idx) % 7 + 1
