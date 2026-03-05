package com.shiro.classflow.widget.compact

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.shiro.classflow.MainActivity
import com.shiro.classflow.R
import com.shiro.classflow.widget.WidgetSnapshot
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object CompactNativeRenderer {

    fun render(context: Context, snapshot: WidgetSnapshot): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_today_compact_native)

        // 1. 设置点击根布局跳转 App
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        // 2. 数据准备
        val now = LocalTime.now()
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val todayStr = today.toString()
        val tomorrowStr = tomorrow.toString()
        val allCourses = snapshot.coursesList
        val currentWeek = if (snapshot.currentWeek <= 0) null else snapshot.currentWeek

        // 3. 头部信息渲染
        val dateFormatter = DateTimeFormatter.ofPattern("E", Locale.getDefault())
        rv.setTextViewText(R.id.tv_header_title, today.format(dateFormatter))
        currentWeek?.let {
            rv.setTextViewText(R.id.tv_current_week, context.getString(R.string.status_current_week_format, it))
        }

        // 情况 A：假期处理 (全屏模式)
        if (currentWeek == null) {
            showStatus(rv, context.getString(R.string.title_vacation), context.getString(R.string.widget_vacation_expecting), isFullCover = true)
            return rv
        }

        // 4. 核心调度逻辑 (优先级：今天剩余 > 明天预告 > 无课/课完状态)

        // A. 筛选今天剩余课程
        val todayRemaining = allCourses.filter {
            (it.date == todayStr || it.date.isBlank()) && !it.isSkipped &&
                    try { LocalTime.parse(it.endTime) > now } catch (e: Exception) { true }
        }

        // B. 筛选明天课程
        val tomorrowCourses = allCourses.filter { it.date == tomorrowStr && !it.isSkipped }

        val (displayCourses, isShowingTomorrow, totalCount) = when {
            todayRemaining.isNotEmpty() -> {
                // 状态 1：显示今日剩余
                Triple(todayRemaining.take(2), false, todayRemaining.size)
            }
            tomorrowCourses.isNotEmpty() -> {
                // 状态 2：今日已无课，显示明日预告
                rv.setTextViewText(R.id.tv_header_title, context.getString(R.string.widget_tomorrow_course_preview))
                Triple(tomorrowCourses.take(2), true, tomorrowCourses.size)
            }
            else -> {
                // 状态 3：今明两天都没课，修正文案判断逻辑
                val hasCoursesToday = allCourses.any { it.date == todayStr || it.date.isBlank() }
                val tip = if (!hasCoursesToday) {
                    context.getString(R.string.text_no_courses_today) // 今天本来就没课 (如周末)
                } else {
                    context.getString(R.string.widget_today_courses_finished) // 今天有课但上完了，且明天也没课
                }
                showStatus(rv, tip, "", isFullCover = false)
                return rv
            }
        }

        // 5. 渲染课程内容
        rv.setViewVisibility(R.id.inner_content_card, View.VISIBLE)
        rv.setViewVisibility(R.id.container_status, View.GONE)
        rv.setViewVisibility(R.id.container_courses, View.VISIBLE)
        rv.setViewVisibility(R.id.tv_footer, View.VISIBLE)

        rv.removeAllViews(R.id.container_courses)
        displayCourses.forEachIndexed { index, course ->
            val itemRv = RemoteViews(context.packageName, R.layout.widget_item_course_common)
            itemRv.setTextViewText(R.id.tv_course_name, course.name)
            itemRv.setTextViewText(R.id.tv_course_position, course.position)
            itemRv.setTextViewText(R.id.tv_course_time, "${course.startTime.take(5)}-${course.endTime.take(5)}")

            if (course.teacher.isNotBlank()) {
                itemRv.setViewVisibility(R.id.tv_course_teacher, View.VISIBLE)
                itemRv.setTextViewText(R.id.tv_course_teacher, course.teacher)
            } else {
                itemRv.setViewVisibility(R.id.tv_course_teacher, View.GONE)
            }

            val style = snapshot.style
            if (course.colorInt < style.courseColorMapsCount) {
                val colorPair = style.getCourseColorMaps(course.colorInt)
                itemRv.setInt(R.id.course_indicator, "setBackgroundColor", colorPair.lightColor.toInt())
                itemRv.setInt(R.id.course_indicator_dark, "setBackgroundColor", colorPair.darkColor.toInt())
            }

            rv.addView(R.id.container_courses, itemRv)
            if (index == 0 && displayCourses.size > 1) {
                rv.addView(R.id.container_courses, RemoteViews(context.packageName, R.layout.widget_divider_horizontal))
            }
        }

        // 6. 页脚统计
        val footerRes = if (isShowingTomorrow) R.string.widget_remaining_courses_format_tomorrow
        else R.string.widget_remaining_courses_format_today
        rv.setTextViewText(R.id.tv_footer, context.getString(footerRes, totalCount))

        return rv
    }

    private fun showStatus(rv: RemoteViews, title: String, msg: String?, isFullCover: Boolean) {
        if (isFullCover) {
            rv.setViewVisibility(R.id.inner_content_card, View.GONE)
            rv.setViewVisibility(R.id.container_full_status, View.VISIBLE)
            rv.setTextViewText(R.id.tv_full_status_title, title)
            if (!msg.isNullOrBlank()) {
                rv.setTextViewText(R.id.tv_full_status_msg, msg)
                rv.setViewVisibility(R.id.tv_full_status_msg, View.VISIBLE)
            } else {
                rv.setViewVisibility(R.id.tv_full_status_msg, View.GONE)
            }
        } else {
            rv.setViewVisibility(R.id.container_full_status, View.GONE)
            rv.setViewVisibility(R.id.inner_content_card, View.VISIBLE)
            rv.setViewVisibility(R.id.container_courses, View.GONE)
            rv.setViewVisibility(R.id.tv_footer, View.GONE)
            rv.setViewVisibility(R.id.container_status, View.VISIBLE)
            rv.setTextViewText(R.id.tv_status_title, title)
            if (!msg.isNullOrBlank()) {
                rv.setTextViewText(R.id.tv_status_msg, msg)
                rv.setViewVisibility(R.id.tv_status_msg, View.VISIBLE)
            } else {
                rv.setViewVisibility(R.id.tv_status_msg, View.GONE)
            }
        }
    }
}
