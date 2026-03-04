package com.xingheyuzhuan.classflow.widget.tiny

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

object TinyNativeRenderer {

    fun render(context: Context, snapshot: WidgetSnapshot): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_tiny_native)

        // 1. 设置点击跳转 (保持不变)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        rv.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        // 2. 数据处理
        val allCourses = snapshot.coursesList
        val currentWeek = if (snapshot.currentWeek <= 0) null else snapshot.currentWeek
        val now = LocalTime.now()
        val todayStr = LocalDate.now().toString()

        // 核心修正：先找出今天所有的课
        val todayAllCourses = allCourses.filter { it.date == todayStr || it.date.isBlank() }
        // 再找下一节课
        val nextCourse = todayAllCourses.firstOrNull { !it.isSkipped && LocalTime.parse(it.endTime) > now }

        // 3. 状态渲染
        if (currentWeek == null) {
            showStatus(rv, context.getString(R.string.title_vacation), context.getString(R.string.widget_vacation_expecting))
            return rv
        }

        if (nextCourse != null) {
            // 有课显示逻辑 (保持不变)
            rv.setViewVisibility(R.id.container_info, View.VISIBLE)
            rv.setViewVisibility(R.id.bubble_frame, View.VISIBLE)
            rv.setViewVisibility(R.id.container_status, View.GONE)

            rv.setTextViewText(R.id.tv_course_name, nextCourse.name)
            rv.setTextViewText(R.id.tv_course_time, "${nextCourse.startTime.take(5)} - ${nextCourse.endTime.take(5)}")
            rv.setTextViewText(R.id.tv_course_position, nextCourse.position)

            val nextCourseIndex = todayAllCourses.indexOf(nextCourse)
            val remainingCount = todayAllCourses.size - nextCourseIndex
            rv.setTextViewText(R.id.tv_remaining_count, remainingCount.toString())

            val style = snapshot.style
            if (nextCourse.colorInt < style.courseColorMapsCount) {
                val colorPair = style.getCourseColorMaps(nextCourse.colorInt)
                rv.setInt(R.id.bubble_bg_image, "setColorFilter", colorPair.lightColor.toInt())
                rv.setInt(R.id.bubble_bg_image_dark, "setColorFilter", colorPair.darkColor.toInt())
            }
        } else {
            val tip = if (todayAllCourses.isEmpty()) {
                context.getString(R.string.text_no_courses_today)
            } else {
                context.getString(R.string.widget_today_courses_finished)
            }
            showStatus(rv, tip)
        }

        return rv
    }

    /**
     * 显示状态页
     * @param title 主标题
     * @param message 副标题
     */
    private fun showStatus(rv: RemoteViews, title: String, message: String? = null) {
        rv.setViewVisibility(R.id.container_info, View.GONE)
        rv.setViewVisibility(R.id.bubble_frame, View.GONE)
        rv.setViewVisibility(R.id.container_status, View.VISIBLE)

        rv.setTextViewText(R.id.tv_status_title, title)

        if (message != null) {
            rv.setTextViewText(R.id.tv_status_msg, message)
            rv.setViewVisibility(R.id.tv_status_msg, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.tv_status_msg, View.GONE)
        }
    }
}
