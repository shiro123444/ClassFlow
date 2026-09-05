package com.xingheyuzhuan.shiguangschedule.data.repository

import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun normalizeImportedTimeSlots(
    timeSlots: List<TimeSlot>,
    classDuration: Int,
    breakDuration: Int,
    tableId: String
): List<TimeSlot> {
    val validClassDuration = classDuration.coerceAtLeast(20)
    val validBreakDuration = breakDuration.coerceAtLeast(0)

    val parsed = timeSlots.mapNotNull { slot ->
        val start = runCatching { LocalTime.parse(slot.startTime, TIME_FORMATTER) }.getOrNull()
        val end = runCatching { LocalTime.parse(slot.endTime, TIME_FORMATTER) }.getOrNull()
        if (start == null || end == null || !end.isAfter(start)) {
            null
        } else {
            start to end
        }
    }.sortedBy { it.first }

    if (parsed.isEmpty()) {
        return emptyList()
    }

    val normalized = mutableListOf<TimeSlot>()
    var lastEnd: LocalTime? = null
    parsed.forEachIndexed { index, pair ->
        val rawStart = pair.first
        val rawEnd = pair.second

        // 仅在发生逆序/重叠时（当前节次开始时间早于上一节结束时间），才以上一节结束时间 + breakDuration 做避让推迟。
        // 若没有重叠（例如 5 分钟、10 分钟或午休长课间），100% 尊重并保留学校教务的真实开始时间。
        val start = if (lastEnd != null && rawStart.isBefore(lastEnd)) {
            lastEnd!!.plusMinutes(validBreakDuration.toLong())
        } else {
            rawStart
        }
        val minimumEnd = start.plusMinutes(validClassDuration.toLong())
        val end = if (rawEnd.isAfter(start)) rawEnd else minimumEnd

        normalized.add(
            TimeSlot(
                number = index + 1,
                startTime = start.format(TIME_FORMATTER),
                endTime = end.format(TIME_FORMATTER),
                courseTableId = tableId
            )
        )

        lastEnd = end
    }

    return normalized
}

fun generateTimeSlotsByTemplate(
    tableId: String,
    startTime: String,
    sectionCount: Int,
    classDuration: Int,
    breakDuration: Int
): List<TimeSlot> {
    val safeCount = sectionCount.coerceIn(1, 24)
    val safeClass = classDuration.coerceAtLeast(20)
    val safeBreak = breakDuration.coerceAtLeast(0)
    val firstStart = runCatching { LocalTime.parse(startTime, TIME_FORMATTER) }.getOrElse { LocalTime.of(8, 0) }

    var cursor = firstStart
    return (1..safeCount).map { number ->
        val end = cursor.plusMinutes(safeClass.toLong())
        val slot = TimeSlot(
            number = number,
            startTime = cursor.format(TIME_FORMATTER),
            endTime = end.format(TIME_FORMATTER),
            courseTableId = tableId
        )
        cursor = end.plusMinutes(safeBreak.toLong())
        slot
    }
}

