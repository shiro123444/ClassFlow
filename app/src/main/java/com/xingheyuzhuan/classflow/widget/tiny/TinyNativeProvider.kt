package com.xingheyuzhuan.classflow.widget.tiny

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.xingheyuzhuan.classflow.widget.WorkManagerHelper
import com.xingheyuzhuan.classflow.widget.updateAllWidgets
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class TinyNativeProvider : AppWidgetProvider() {
    private val scope = MainScope()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        scope.launch {
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
