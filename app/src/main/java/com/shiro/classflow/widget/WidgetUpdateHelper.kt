package com.shiro.classflow.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.shiro.classflow.data.db.widget.WidgetDatabase
import com.shiro.classflow.data.model.ScheduleGridStyle
import com.shiro.classflow.data.model.toProto
import com.shiro.classflow.data.repository.WidgetRepository
import com.shiro.classflow.data.repository.scheduleGridStyleDataStore
import com.shiro.classflow.widget.compact.CompactNativeProvider
import com.shiro.classflow.widget.compact.CompactNativeRenderer
import com.shiro.classflow.widget.double_days.DoubleDaysNativeProvider
import com.shiro.classflow.widget.double_days.DoubleDaysNativeRenderer
import com.shiro.classflow.widget.large.LargeNativeProvider
import com.shiro.classflow.widget.large.LargeNativeRenderer
import com.shiro.classflow.widget.moderate.ModerateNativeProvider
import com.shiro.classflow.widget.moderate.ModerateNativeRenderer
import com.shiro.classflow.widget.tiny.TinyNativeProvider
import com.shiro.classflow.widget.tiny.TinyNativeRenderer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * 小组件统一分发中心
 * 负责从 Repository 提取数据并分发给所有 5 种规格的原生 Renderer
 */
suspend fun updateAllWidgets(context: Context) {
    try {
        // 1. 初始化数据库和仓库
        val widgetDb = WidgetDatabase.getDatabase(context)
        val repository = WidgetRepository(
            widgetCourseDao = widgetDb.widgetCourseDao(),
            widgetAppSettingsDao = widgetDb.widgetAppSettingsDao(),
            context = context
        )

        // 2. 准备基础数据
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)

        // 获取今日和明日的所有课程快照 (超时时间 3秒)
        val dbCourses = withTimeoutOrNull(3000L) {
            repository.getWidgetCoursesByDateRange(today.toString(), tomorrow.toString()).first()
        } ?: emptyList() // 超时则返回空列表

        // 获取当前周 (超时时间 2秒)
        val currentWeek = withTimeoutOrNull(2000L) {
            repository.getCurrentWeekFlow().first()
        } ?: 0

        // 获取样式 (超时时间 2秒)
        val currentStyle = withTimeoutOrNull(2000L) {
            context.scheduleGridStyleDataStore.data.first()
        }

        // 样式保底逻辑
        val finalStyleToSync = if (currentStyle == null || currentStyle.courseColorMapsCount == 0) {
            ScheduleGridStyle.DEFAULT.toProto()
        } else {
            currentStyle
        }

        // 3. 构造数据快照 (Protobuf)
        val snapshot = WidgetSnapshot.newBuilder().apply {
            this.currentWeek = currentWeek
            this.style = finalStyleToSync

            // 遍历数据库实体并转为 Proto 格式
            dbCourses.forEach { course ->
                val courseProto = WidgetCourseProto.newBuilder()
                    .setId(course.id)
                    .setName(course.name)
                    .setTeacher(course.teacher)
                    .setPosition(course.position)
                    .setStartTime(course.startTime)
                    .setEndTime(course.endTime)
                    .setColorInt(course.colorInt)
                    .setIsSkipped(course.isSkipped)
                    .setDate(course.date)
                    .build()
                addCourses(courseProto)
            }
        }.build()

        // 4. 定义所有原生尺寸的映射列表
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val nativeConfigs = listOf(
            TinyNativeProvider::class.java to TinyNativeRenderer::render,
            CompactNativeProvider::class.java to CompactNativeRenderer::render,
            ModerateNativeProvider::class.java to ModerateNativeRenderer::render,
            DoubleDaysNativeProvider::class.java to DoubleDaysNativeRenderer::render,
            LargeNativeProvider::class.java to LargeNativeRenderer::render
        )

        // 5. 统一分发更新
        nativeConfigs.forEach { (providerClass, renderFunc) ->
            val componentName = ComponentName(context, providerClass)
            val ids = appWidgetManager.getAppWidgetIds(componentName)
            if (ids.isNotEmpty()) {
                val remoteViews = renderFunc(context, snapshot)
                ids.forEach { id ->
                    appWidgetManager.updateAppWidget(id, remoteViews)
                }
                Log.d("WidgetUpdateHelper", "成功刷新规格 ${providerClass.simpleName}: ${ids.size}个实例")
            }
        }

    } catch (e: Exception) {
        Log.e("WidgetUpdateHelper", "更新流程异常: ${e.stackTraceToString()}")
    }
}
