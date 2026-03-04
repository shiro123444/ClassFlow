package com.xingheyuzhuan.classflow

import android.app.Application
import androidx.work.Configuration
import com.xingheyuzhuan.classflow.data.db.main.MainAppDatabase
import com.xingheyuzhuan.classflow.data.db.widget.WidgetDatabase
import com.xingheyuzhuan.classflow.data.repository.AppSettingsRepository
import com.xingheyuzhuan.classflow.data.repository.CourseConversionRepository
import com.xingheyuzhuan.classflow.data.repository.CourseTableRepository
import com.xingheyuzhuan.classflow.data.repository.StyleSettingsRepository
import com.xingheyuzhuan.classflow.data.repository.TimeSlotRepository
import com.xingheyuzhuan.classflow.data.repository.WidgetRepository
import com.xingheyuzhuan.classflow.data.repository.scheduleGridStyleDataStore
import com.xingheyuzhuan.classflow.data.sync.SyncManager
import com.xingheyuzhuan.classflow.data.sync.WidgetDataSynchronizer
import com.xingheyuzhuan.classflow.service.AppWorkerFactory
import com.xingheyuzhuan.classflow.data.db.main.TimeSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MyApplication : Application(), Configuration.Provider {

    // 主数据库
    val database: MainAppDatabase by lazy { MainAppDatabase.getDatabase(this) }
    // Widget 数据库
    val widgetDatabase: WidgetDatabase by lazy { WidgetDatabase.getDatabase(this) }


    // 样式设置仓库
    val styleSettingsRepository: StyleSettingsRepository by lazy {
        StyleSettingsRepository(scheduleGridStyleDataStore, applicationContext)
    }
    // 主数据库仓库
    val appSettingsRepository: AppSettingsRepository by lazy {
        AppSettingsRepository(
            appSettingsDao = database.appSettingsDao(),
            courseTableConfigDao = database.courseTableConfigDao()
        )
    }

    val timeSlotRepository: TimeSlotRepository by lazy {
        TimeSlotRepository(database.timeSlotDao())
    }

    val courseTableRepository: CourseTableRepository by lazy {
        CourseTableRepository(
            database.courseTableDao(),
            database.courseDao(),
            database.courseWeekDao(),
            timeSlotRepository,
            appSettingsRepository
        )
    }

    val courseConversionRepository: CourseConversionRepository by lazy {
        CourseConversionRepository(
            courseDao = database.courseDao(),
            courseWeekDao = database.courseWeekDao(),
            timeSlotDao = database.timeSlotDao(),
            appSettingsRepository = appSettingsRepository,
            styleSettingsRepository = styleSettingsRepository
        )
    }
    // Widget 数据库仓库
    val widgetRepository: WidgetRepository by lazy {
        WidgetRepository(
            widgetDatabase.widgetCourseDao(),
            widgetDatabase.widgetAppSettingsDao(),
            this
        )
    }

    // 公开 WidgetDataSynchronizer 实例
    val widgetDataSynchronizer by lazy {
        WidgetDataSynchronizer(
            appContext = this,
            appSettingsRepository = appSettingsRepository,
            courseTableRepository = courseTableRepository,
            timeSlotRepository = timeSlotRepository,
            widgetRepository = widgetRepository
        )
    }

    // WorkManager 配置，现在将 widgetDataSynchronizer 传入工厂
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(
                AppWorkerFactory(
                    appSettingsRepository = appSettingsRepository,
                    widgetRepository = widgetRepository,
                    widgetDataSynchronizer = widgetDataSynchronizer
                )
            )
            .build()

    override fun onCreate() {
        super.onCreate()

        // 在应用启动时清理临时分享文件
        clearShareTempFiles()

        // 1. 触发主数据库的初始化。
        database.courseTableDao()

        // 2. 创建并启动同步管理器。
        val syncManager = SyncManager(
            appContext = this,
            appSettingsRepository = appSettingsRepository,
            courseTableRepository = courseTableRepository,
            timeSlotRepository = timeSlotRepository,
            widgetRepository = widgetRepository
        )
        syncManager.startAllSynchronizers()

        // 3. 在应用启动时初始化离线仓库
        CoroutineScope(Dispatchers.IO).launch {
            initOfflineRepo()
            migrateLegacyDefaultTimeSlotsIfNeeded()
        }
    }

    private suspend fun migrateLegacyDefaultTimeSlotsIfNeeded() = withContext(Dispatchers.IO) {
        val tableId = appSettingsRepository.getAppSettingsOnce()?.currentCourseTableId ?: return@withContext
        val current = timeSlotRepository.getTimeSlotsByCourseTableId(tableId).first().sortedBy { it.number }
        if (!looksLikeLegacyDefaultTemplate(current)) return@withContext

        val migrated = UPDATED_WBU_DEFAULT_TIME_SLOTS.map { (number, start, end) ->
            TimeSlot(number = number, startTime = start, endTime = end, courseTableId = tableId)
        }
        timeSlotRepository.replaceAllForCourseTable(tableId, migrated)
    }

    private fun looksLikeLegacyDefaultTemplate(slots: List<TimeSlot>): Boolean {
        if (slots.size != LEGACY_WBU_DEFAULT_TIME_SLOTS.size) return false
        return slots.zip(LEGACY_WBU_DEFAULT_TIME_SLOTS).all { (slot, expected) ->
            slot.number == expected.first && slot.startTime == expected.second && slot.endTime == expected.third
        }
    }

    /**
     * 将 assets 目录下的离线仓库资源复制到内部存储，用于首次启动时的初始化。
     */
    private suspend fun initOfflineRepo() = withContext(Dispatchers.IO) {
        val repoDir = File(filesDir, "repo")

        if (!repoDir.exists()) {
            repoDir.mkdirs()
        }

        // 首次安装：完整复制所有 assets
        if (repoDir.list()?.isEmpty() != false) {
            try {
                copyAssets("offline_repo", repoDir)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        // 始终覆盖更新 WBU 脚本（确保修复后的版本生效）
        try {
            forceUpdateAssetFile(
                assetPath = "offline_repo/schools/resources/WBU/wbu_chaoxing.js",
                destFile = File(repoDir, "schools/resources/WBU/wbu_chaoxing.js")
            )
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * 强制从 assets 复制单个文件到目标路径（覆盖已有文件）。
     */
    private fun forceUpdateAssetFile(assetPath: String, destFile: File) {
        destFile.parentFile?.mkdirs()
        assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * 递归复制 assets 目录到目标目录。
     */
    private fun copyAssets(assetPath: String, destDir: File) {
        val assetList = assets.list(assetPath) ?: return

        for (item in assetList) {
            val srcItemPath = "$assetPath/$item"
            val destItem = File(destDir, item)

            try {
                assets.open(srcItemPath).use { inputStream ->
                    FileOutputStream(destItem).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: IOException) {
                destItem.mkdirs()
                copyAssets(srcItemPath, destItem)
            }
        }
    }

    /**
     * 清理用于分享的临时文件。
     */
    private fun clearShareTempFiles() {
        // 创建一个指向 "share_temp" 目录的 File 对象
        val shareTempDir = File(cacheDir, "share_temp")
        if (shareTempDir.exists() && shareTempDir.isDirectory) {
            // 如果目录存在，遍历并删除所有文件
            shareTempDir.listFiles()?.forEach { file ->
                file.delete()
            }
        }
    }

    companion object {
        private val LEGACY_WBU_DEFAULT_TIME_SLOTS = listOf(
            Triple(1, "08:00", "08:45"),
            Triple(2, "08:50", "09:35"),
            Triple(3, "09:50", "10:35"),
            Triple(4, "10:40", "11:25"),
            Triple(5, "11:30", "12:15"),
            Triple(6, "14:00", "14:45"),
            Triple(7, "14:50", "15:35"),
            Triple(8, "15:45", "16:30"),
            Triple(9, "16:35", "17:20"),
            Triple(10, "18:30", "19:15"),
            Triple(11, "19:20", "20:05"),
            Triple(12, "20:10", "20:55"),
            Triple(13, "21:10", "21:55")
        )

        private val UPDATED_WBU_DEFAULT_TIME_SLOTS = listOf(
            Triple(1, "08:30", "09:15"),
            Triple(2, "09:20", "10:05"),
            Triple(3, "10:25", "11:10"),
            Triple(4, "11:15", "12:00"),
            Triple(5, "14:00", "14:45"),
            Triple(6, "14:50", "15:35"),
            Triple(7, "15:55", "16:40"),
            Triple(8, "16:45", "17:30"),
            Triple(9, "18:30", "19:15"),
            Triple(10, "19:20", "20:05"),
            Triple(11, "20:10", "20:55"),
            Triple(12, "21:00", "21:45")
        )
    }
}
