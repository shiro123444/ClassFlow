package com.xingheyuzhuan.classflow.widget.moderate

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.xingheyuzhuan.classflow.widget.WorkManagerHelper
import com.xingheyuzhuan.classflow.widget.updateAllWidgets
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class ModerateNativeProvider : AppWidgetProvider() {
    private val scope = MainScope()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        scope.launch {
            // 触发快照更新并刷新 RemoteViews
            updateAllWidgets(context)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WorkManagerHelper.schedulePeriodicWork(context)
        scope.launch { updateAllWidgets(context) }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManagerHelper.cancelAllWork(context)
    }
}
