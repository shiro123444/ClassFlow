package com.xingheyuzhuan.classflow.widget.moderate

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.xingheyuzhuan.classflow.MainActivity
import com.xingheyuzhuan.classflow.R
import com.xingheyuzhuan.classflow.widget.WidgetSnapshot
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object ModerateNativeRenderer {

    fun render(context: Context, snapshot: WidgetSnapshot): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_moderate_native)

        // 1. 设置点击跳转
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

        // 3. 头部信息（先按今日渲染，如果是显示明天预告，后面会覆盖）
        val dateFormatter = DateTimeFormatter.ofPattern("M.dd E", Locale.getDefault())
        rv.setTextViewText(R.id.tv_header_title, today.format(dateFormatter))
        currentWeek?.let {
            rv.setTextViewText(R.id.tv_current_week, context.getString(R.string.status_current_week_format, it))
        }

        // 情况 A：假期处理
        if (currentWeek == null) {
            showStatus(rv, context.getString(R.string.title_vacation), context.getString(R.string.widget_vacation_expecting), isFullCover = true)
            return rv
        }

        // 4. 核心调度逻辑 (优先级：今天剩余 > 明天课程 > 无课状态)

        // A. 筛选今天还没上的课
        val todayRemaining = allCourses.filter {
            (it.date == todayStr || it.date.isBlank()) && !it.isSkipped &&
                    try { LocalTime.parse(it.endTime) > now } catch (e: Exception) { true }
        }

        // B. 筛选明天的课
        val tomorrowCourses = allCourses.filter { it.date == tomorrowStr && !it.isSkipped }

        val (displayCourses, isShowingTomorrow, totalCount) = when {
            todayRemaining.isNotEmpty() -> {
                Triple(todayRemaining.take(4), false, todayRemaining.size)
            }
            tomorrowCourses.isNotEmpty() -> {
                // 覆盖头部标题为“明日预告”
                rv.setTextViewText(R.id.tv_header_title, context.getString(R.string.widget_tomorrow_course_preview))
                Triple(tomorrowCourses.take(4), true, tomorrowCourses.size)
            }
            else -> {
                // 状态 3：今明两天都没课，处理文案
                val hasCoursesToday = allCourses.any { it.date == todayStr || it.date.isBlank() }
                val tip = if (!hasCoursesToday) context.getString(R.string.text_no_courses_today)
                else context.getString(R.string.widget_today_courses_finished)

                showStatus(rv, tip, null, isFullCover = false)
                return rv // 直接返回
            }
        }

        // 5. 渲染课程列表 (包含 Placeholder 逻辑)
        rv.setViewVisibility(R.id.inner_content_card, View.VISIBLE)
        rv.setViewVisibility(R.id.container_status, View.GONE)
        rv.setViewVisibility(R.id.container_course_content, View.VISIBLE)
        rv.setViewVisibility(R.id.tv_footer, View.VISIBLE)

        rv.removeAllViews(R.id.container_left_column)
        rv.removeAllViews(R.id.container_right_column)

        var leftCount = 0
        var rightCount = 0

        displayCourses.forEachIndexed { i, course ->
            val itemRv = RemoteViews(context.packageName, R.layout.widget_item_course_common)
            itemRv.setTextViewText(R.id.tv_course_name, course.name)
            itemRv.setTextViewText(R.id.tv_course_position, course.position)
            itemRv.setTextViewText(R.id.tv_course_time, "${course.startTime.take(5)}-${course.endTime.take(5)}")

            val style = snapshot.style
            if (course.colorInt < style.courseColorMapsCount) {
                val colors = style.getCourseColorMaps(course.colorInt)
                itemRv.setInt(R.id.course_indicator, "setBackgroundColor", colors.lightColor.toInt())
                itemRv.setInt(R.id.course_indicator_dark, "setBackgroundColor", colors.darkColor.toInt())
            }

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

        // 补齐占位符 (2x2 布局稳定性的灵魂)
        for (j in leftCount until 2) rv.addView(R.id.container_left_column, createPlaceholder(context))
        for (j in rightCount until 2) rv.addView(R.id.container_right_column, createPlaceholder(context))

        // 页脚统计
        val footerRes = if (isShowingTomorrow) R.string.widget_remaining_courses_format_tomorrow
        else R.string.widget_remaining_courses_format_today
        rv.setTextViewText(R.id.tv_footer, context.getString(footerRes, totalCount))

        return rv
    }

    private fun showStatus(rv: RemoteViews, title: String, msg: String?, isFullCover: Boolean) {
        if (isFullCover) {
            // --- 全屏模式 (用于假期) ---
            // 1. 隐藏主卡片，显示全屏容器
            rv.setViewVisibility(R.id.inner_content_card, View.GONE)
            rv.setViewVisibility(R.id.container_full_status, View.VISIBLE)

            // 2. 给全屏 ID 赋值
            rv.setTextViewText(R.id.tv_full_status_title, title)
            if (msg != null) {
                rv.setTextViewText(R.id.tv_full_status_msg, msg)
                rv.setViewVisibility(R.id.tv_full_status_msg, View.VISIBLE)
            } else {
                rv.setViewVisibility(R.id.tv_full_status_msg, View.GONE)
            }
        } else {
            // --- 局部模式 (用于今日课完/无课) ---
            // 1. 确保主卡片可见，且全屏容器必须隐藏（防止遮挡）
            rv.setViewVisibility(R.id.inner_content_card, View.VISIBLE)
            rv.setViewVisibility(R.id.container_full_status, View.GONE)

            // 2. 隐藏列表和页脚，显示卡片内部的状态页
            rv.setViewVisibility(R.id.container_course_content, View.GONE)
            rv.setViewVisibility(R.id.tv_footer, View.GONE)
            rv.setViewVisibility(R.id.container_status, View.VISIBLE)

            // 3. 给局部 ID 赋值
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
