package com.shiro.classflow.service

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.shiro.classflow.data.repository.AppSettingsRepository
import com.shiro.classflow.data.repository.WidgetRepository
import com.shiro.classflow.data.sync.WidgetDataSynchronizer
import com.shiro.classflow.widget.FullDataSyncWorker
import com.shiro.classflow.widget.WidgetUiUpdateWorker

/**
 * 一个自定义的 WorkerFactory，用于为我们的 Worker 提供依赖项。
 */
class AppWorkerFactory(
    private val appSettingsRepository: AppSettingsRepository,
    private val widgetRepository: WidgetRepository,
    private val widgetDataSynchronizer: WidgetDataSynchronizer
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            CourseNotificationWorker::class.java.name ->
                CourseNotificationWorker(appContext, workerParameters, appSettingsRepository, widgetRepository)
            WidgetUiUpdateWorker::class.java.name ->
                WidgetUiUpdateWorker(appContext, workerParameters)
            FullDataSyncWorker::class.java.name ->
                FullDataSyncWorker(appContext, workerParameters, widgetDataSynchronizer)
            DndSchedulerWorker::class.java.name ->
                DndSchedulerWorker(appContext, workerParameters, appSettingsRepository, widgetRepository)
            else ->
                null
        }
    }
}
