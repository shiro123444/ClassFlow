package com.shiro.classflow.data.repository

import com.shiro.classflow.data.db.main.TimeSlot
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
    var cursor = parsed.first().first
    parsed.forEachIndexed { index, pair ->
        val rawStart = pair.first
        val rawEnd = pair.second

        // Preserve real world long breaks (e.g. lunch) and only fix overlaps.
        val start = if (rawStart.isAfter(cursor) || rawStart == cursor) rawStart else cursor
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

        cursor = end.plusMinutes(validBreakDuration.toLong())
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

