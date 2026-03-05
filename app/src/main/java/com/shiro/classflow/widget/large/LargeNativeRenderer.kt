package com.shiro.classflow.widget.large

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

object LargeNativeRenderer {

    fun render(context: Context, snapshot: WidgetSnapshot): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_large_native)

        // 1. 设置点击跳转逻辑
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        rv.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        // 2. 数据准备
        val currentWeek = if (snapshot.currentWeek <= 0) null else snapshot.currentWeek
        val allCourses = snapshot.coursesList
        val now = LocalTime.now()
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val todayStr = today.toString()
        val tomorrowStr = tomorrow.toString()

        // 3. 默认头部信息
        val dateFormatter = DateTimeFormatter.ofPattern("M.dd E", Locale.getDefault())
        rv.setTextViewText(R.id.tv_header_title, today.format(dateFormatter))
        currentWeek?.let {
            rv.setTextViewText(R.id.tv_current_week, context.getString(R.string.status_current_week_format, it))
        }

        // 情况 A：假期判断 (全屏模式)
        if (currentWeek == null) {
            showStatus(rv, context.getString(R.string.title_vacation), context.getString(R.string.widget_vacation_expecting), isFullCover = true)
            return rv
        }

        // 4. 核心调度逻辑 (优先级：今天剩余 > 明天预告 > 无课/课完状态)

        // A. 筛选今天还没上的课
        val todayRemaining = allCourses.filter {
            (it.date == todayStr || it.date.isBlank()) && !it.isSkipped &&
                    try { LocalTime.parse(it.endTime) > now } catch (e: Exception) { true }
        }

        // B. 筛选明天课程
        val tomorrowCourses = allCourses.filter { it.date == tomorrowStr && !it.isSkipped }

        // C. 执行逻辑调度
        val (displayCourses, isShowingTomorrow, totalCount) = when {
            todayRemaining.isNotEmpty() -> {
                // 状态 1：显示今日剩余 (最多展示 6 节)
                Triple(todayRemaining.take(6), false, todayRemaining.size)
            }
            tomorrowCourses.isNotEmpty() -> {
                // 状态 2：今日已无课，显示明日预告
                rv.setTextViewText(R.id.tv_header_title, context.getString(R.string.widget_tomorrow_course_preview))
                Triple(tomorrowCourses.take(6), true, tomorrowCourses.size)
            }
            else -> {
                // 状态 3：今明两天都没课，精准判断文案
                val hasCoursesToday = allCourses.any { it.date == todayStr || it.date.isBlank() }
                val tip = if (!hasCoursesToday) {
                    context.getString(R.string.text_no_courses_today) // 今天本来就没课
                } else {
                    context.getString(R.string.widget_today_courses_finished) // 今天有课但上完了，明天也没课
                }
                showStatus(rv, tip, null, isFullCover = false)
                return rv
            }
        }

        // 5. 渲染课程列表
        rv.setViewVisibility(R.id.inner_content_card, View.VISIBLE)
        rv.setViewVisibility(R.id.container_status, View.GONE)
        rv.setViewVisibility(R.id.container_course_content, View.VISIBLE)
        rv.setViewVisibility(R.id.tv_footer, View.VISIBLE)

        // 清空容器
        rv.removeAllViews(R.id.container_left_column)
        rv.removeAllViews(R.id.container_right_column)

        var leftCount = 0
        var rightCount = 0

        displayCourses.forEachIndexed { i, course ->
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

            // 奇偶分发 3x2 布局
            if (i % 2 == 0) {
                rv.addView(R.id.container_left_column, itemRv)
                leftCount++
                if (i + 2 < displayCourses.size) {
                    rv.addView(R.id.container_left_column, RemoteViews(context.packageName, R.layout.widget_divider_horizontal))
                }
            } else {
                rv.addView(R.id.container_right_column, itemRv)
                rightCount++
                if (i + 2 < displayCourses.size) {
                    rv.addView(R.id.container_right_column, RemoteViews(context.packageName, R.layout.widget_divider_horizontal))
                }
            }
        }

        // 6. 补齐占位符 (Large 规格每列 3 个)
        for (j in leftCount until 3) rv.addView(R.id.container_left_column, createPlaceholder(context))
        for (j in rightCount until 3) rv.addView(R.id.container_right_column, createPlaceholder(context))

        // 7. 设置页脚文本
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
            if (msg != null) {
                rv.setTextViewText(R.id.tv_full_status_msg, msg)
                rv.setViewVisibility(R.id.tv_full_status_msg, View.VISIBLE)
            } else {
                rv.setViewVisibility(R.id.tv_full_status_msg, View.GONE)
            }
        } else {
            // 3. 局部状态显示（如今日课完）
            rv.setViewVisibility(R.id.inner_content_card, View.VISIBLE)
            rv.setViewVisibility(R.id.container_course_content, View.GONE)
            rv.setViewVisibility(R.id.tv_footer, View.GONE)
            rv.setViewVisibility(R.id.container_full_status, View.GONE)

            rv.setViewVisibility(R.id.container_status, View.VISIBLE)
            rv.setTextViewText(R.id.tv_status_title, title)
            if (msg != null) {
                rv.setTextViewText(R.id.tv_status_msg, msg)
                rv.setViewVisibility(R.id.tv_status_msg, View.VISIBLE)
            } else {
                rv.setViewVisibility(R.id.tv_status_msg, View.GONE)
            }
        }
    }

    private fun createPlaceholder(context: Context): RemoteViews {
        val p = RemoteViews(context.packageName, R.layout.widget_item_course_common)
        p.setViewVisibility(R.id.course_indicator, View.INVISIBLE)
        p.setViewVisibility(R.id.course_indicator_dark, View.INVISIBLE)
        p.setViewVisibility(R.id.tv_course_name, View.INVISIBLE)
        p.setViewVisibility(R.id.tv_course_position, View.INVISIBLE)
        p.setViewVisibility(R.id.tv_course_time, View.INVISIBLE)
        p.setViewVisibility(R.id.tv_course_teacher, View.INVISIBLE)
        return p
    }
}
