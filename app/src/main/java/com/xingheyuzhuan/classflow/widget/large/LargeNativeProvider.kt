package com.xingheyuzhuan.classflow.widget.large

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.xingheyuzhuan.classflow.widget.WorkManagerHelper
import com.xingheyuzhuan.classflow.widget.updateAllWidgets
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * 课程表（双栏/多课版）原生小组件接收器
 */
class LargeNativeProvider : AppWidgetProvider() {
    private val scope = MainScope()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        scope.launch {
            // 触发全局组件刷新
            updateAllWidgets(context)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 启动后台定时任务
        WorkManagerHelper.schedulePeriodicWork(context)
        scope.launch { updateAllWidgets(context) }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // 清理任务
        WorkManagerHelper.cancelAllWork(context)
    }
}
