// File: IcsExportTool.kt

package com.xingheyuzhuan.shiguangschedule.tool

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.CalendarContract
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.db.main.Course
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWithWeeks
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

object IcsExportTool {

    private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

    private val dayOfWeekMap = mapOf(
        1 to DayOfWeek.MONDAY,
        2 to DayOfWeek.TUESDAY,
        3 to DayOfWeek.WEDNESDAY,
        4 to DayOfWeek.THURSDAY,
        5 to DayOfWeek.FRIDAY,
        6 to DayOfWeek.SATURDAY,
        7 to DayOfWeek.SUNDAY
    )

    /**
     * 遍历学期内所有课程实例，供 ICS 导出与系统日历同步共用。
     * 日期推算与上游一致：将开学日对齐到用户设置的一周起始日，再按周次推算（上游同步）。
     */
    private inline fun processCourseInstances(
        courses: List<CourseWithWeeks>,
        timeSlots: List<TimeSlot>,
        semesterStartDate: LocalDate,
        semesterTotalWeeks: Int,
        firstDayOfWeekInt: Int,
        skippedDates: Set<String>? = null,
        action: (course: Course, startDateTime: LocalDateTime, endDateTime: LocalDateTime) -> Unit
    ) {
        val timeSlotMap = timeSlots.associateBy { it.number }

        // 确定周首基准：对齐到用户设置的一周起始日（上游同步）
        val firstDayOfWeek = DayOfWeek.of(firstDayOfWeekInt)
        val alignedSemesterStart = semesterStartDate.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

        courses.forEach { courseWithWeeks ->
            val course = courseWithWeeks.course
            val weeks = courseWithWeeks.weeks.map { it.weekNumber }

            val startTime: LocalTime
            val endTime: LocalTime

            if (course.isCustomTime) {
                val customStartTimeStr = course.customStartTime
                val customEndTimeStr = course.customEndTime
                if (customStartTimeStr == null || customEndTimeStr == null) return@forEach
                try {
                    startTime = LocalTime.parse(customStartTimeStr, TIME_FORMATTER)
                    endTime = LocalTime.parse(customEndTimeStr, TIME_FORMATTER)
                } catch (_: Exception) {
                    return@forEach
                }
            } else {
                val startSectionTimeStr = timeSlotMap[course.startSection]?.startTime
                val endSectionTimeStr = timeSlotMap[course.endSection]?.endTime
                if (startSectionTimeStr == null || endSectionTimeStr == null) return@forEach
                try {
                    startTime = LocalTime.parse(startSectionTimeStr, TIME_FORMATTER)
                    endTime = LocalTime.parse(endSectionTimeStr, TIME_FORMATTER)
                } catch (_: Exception) {
                    return@forEach
                }
            }

            val dayOfWeek = dayOfWeekMap[course.day] ?: return@forEach

            weeks.forEach { week ->
                // 从对齐后的周首基准推算（上游同步）
                val date = alignedSemesterStart.plusWeeks((week - 1).toLong())
                    .plusDays(dayOfWeek.value.toLong() - 1)

                val weekNumberFromStart = ChronoUnit.DAYS.between(alignedSemesterStart, date) / 7 + 1
                if (weekNumberFromStart > semesterTotalWeeks) return@forEach
                if (skippedDates?.contains(date.toString()) == true) return@forEach

                action(
                    course,
                    LocalDateTime.of(date, startTime),
                    LocalDateTime.of(date, endTime)
                )
            }
        }
    }

    /**
     * 生成 ICS 日历文件的内容字符串。
     */
    fun generateIcsFileContent(
        context: Context,
        courses: List<CourseWithWeeks>,
        timeSlots: List<TimeSlot>,
        semesterStartDate: LocalDate,
        semesterTotalWeeks: Int,
        firstDayOfWeekInt: Int,
        alarmMinutes: Int? = null,
        skippedDates: Set<String>? = null
    ): String {
        val icsContent = StringBuilder()

        icsContent.append("BEGIN:VCALENDAR\r\n")
        icsContent.append("VERSION:2.0\r\n")
        icsContent.append("PRODID:-//ClassFlow//ZH\r\n")
        icsContent.append("BEGIN:VTIMEZONE\r\n")
        icsContent.append("TZID:Asia/Shanghai\r\n")
        icsContent.append("BEGIN:STANDARD\r\n")
        icsContent.append("DTSTART:19700101T000000\r\n")
        icsContent.append("TZOFFSETFROM:+0800\r\n")
        icsContent.append("TZOFFSETTO:+0800\r\n")
        icsContent.append("END:STANDARD\r\n")
        icsContent.append("END:VTIMEZONE\r\n")

        processCourseInstances(
            courses, timeSlots, semesterStartDate, semesterTotalWeeks, firstDayOfWeekInt, skippedDates
        ) { course, startDateTime, endDateTime ->
            icsContent.append("BEGIN:VEVENT\r\n")
            icsContent.append("UID:${UUID.randomUUID()}@classflow.com\r\n")
            icsContent.append("DTSTAMP:${formatDateTimeUtc(LocalDateTime.now())}\r\n")
            icsContent.append("DTSTART;TZID=Asia/Shanghai:${formatDateTimeLocal(startDateTime)}\r\n")
            icsContent.append("DTEND;TZID=Asia/Shanghai:${formatDateTimeLocal(endDateTime)}\r\n")
            icsContent.append("SUMMARY:${escapeText(course.name)}\r\n")
            icsContent.append("LOCATION:${escapeText(course.position)}\r\n")
            icsContent.append("DESCRIPTION:${escapeText(context.getString(R.string.format_export_ics_teacher, course.teacher))}\r\n")

            if (alarmMinutes != null && alarmMinutes in 0..60) {
                icsContent.append("BEGIN:VALARM\r\n")
                icsContent.append("ACTION:DISPLAY\r\n")
                icsContent.append("DESCRIPTION:${context.getString(R.string.export_ics_reminder)}\r\n")
                icsContent.append("TRIGGER:-PT${alarmMinutes}M\r\n")
                icsContent.append("END:VALARM\r\n")
            }

            icsContent.append("END:VEVENT\r\n")
        }

        icsContent.append("END:VCALENDAR\r\n")
        return icsContent.toString()
    }

    /**
     * 生成同步到 Android 系统日历的批量插入指令。
     */
    fun generateCalendarOps(
        context: Context,
        courses: List<CourseWithWeeks>,
        timeSlots: List<TimeSlot>,
        semesterStartDate: LocalDate,
        semesterTotalWeeks: Int,
        firstDayOfWeekInt: Int,
        calendarId: Long,
        alarmMinutes: Int? = null,
        skippedDates: Set<String>? = null
    ): ArrayList<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>()

        processCourseInstances(
            courses, timeSlots, semesterStartDate, semesterTotalWeeks, firstDayOfWeekInt, skippedDates
        ) { course, start, end ->
            val startMillis = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endMillis = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val eventOpIndex = ops.size
            val description = context.getString(R.string.format_export_ics_teacher, course.teacher)

            ops.add(
                ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                    .withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
                    .withValue(CalendarContract.Events.TITLE, course.name)
                    .withValue(CalendarContract.Events.EVENT_LOCATION, course.position)
                    .withValue(CalendarContract.Events.DESCRIPTION, description)
                    .withValue(CalendarContract.Events.DTSTART, startMillis)
                    .withValue(CalendarContract.Events.DTEND, endMillis)
                    .withValue(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
                    .withValue(CalendarContract.Events.HAS_ALARM, if (alarmMinutes != null) 1 else 0)
                    .build()
            )

            if (alarmMinutes != null && alarmMinutes >= 0) {
                ops.add(
                    ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                        .withValueBackReference(CalendarContract.Reminders.EVENT_ID, eventOpIndex)
                        .withValue(CalendarContract.Reminders.MINUTES, alarmMinutes)
                        .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                        .build()
                )
            }
        }
        return ops
    }

    private fun formatDateTimeLocal(dateTime: LocalDateTime): String {
        return dateTime.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
    }

    private fun formatDateTimeUtc(dateTime: LocalDateTime): String {
        val utcDateTime = dateTime.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime()
        return utcDateTime.format(DATE_TIME_FORMATTER)
    }

    private fun escapeText(text: String): String {
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
    }
}

