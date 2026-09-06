package com.xingheyuzhuan.shiguangschedule

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.TimeSlotRepository
import com.xingheyuzhuan.shiguangschedule.data.sync.SyncManager
import com.xingheyuzhuan.shiguangschedule.tool.UpdateChecker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject

@HiltAndroidApp
class MyApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncManager: SyncManager
    @Inject lateinit var appSettingsRepository: AppSettingsRepository
    @Inject lateinit var timeSlotRepository: TimeSlotRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // 在应用启动时清理临时分享文件与已安装过期的更新安装包
        clearShareTempFiles()
        UpdateChecker.clearOutdatedUpdateFiles(this)

        // Room → DataStore 一次性迁移（幂等：DataStore 已有数据时跳过）
        CoroutineScope(Dispatchers.IO).launch {
            appSettingsRepository.migrateFromRoomOnce()
        }

        // 创建并启动同步管理器（由 Hilt 注入后直接使用）
        syncManager.startAllSynchronizers()

        // 在应用启动时初始化离线仓库
        CoroutineScope(Dispatchers.IO).launch {
            initOfflineRepo()
            migrateLegacyDefaultTimeSlotsIfNeeded()
        }
    }

    private suspend fun migrateLegacyDefaultTimeSlotsIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = getSharedPreferences("app_migration", MODE_PRIVATE)
        if (prefs.getBoolean("legacy_time_slots_migrated", false)) {
            return@withContext
        }

        val tableId = appSettingsRepository.getAppSettingsOnce()?.currentCourseTableId ?: return@withContext
        val current = timeSlotRepository.getTimeSlotsByCourseTableId(tableId).first().sortedBy { it.number }
        if (!looksLikeLegacyDefaultTemplate(current)) {
            // 已不是旧版完全一致的初始占位模板，标记为已完成，避免后续误覆盖用户导入的校区时间
            prefs.edit().putBoolean("legacy_time_slots_migrated", true).apply()
            return@withContext
        }

        val migrated = UPDATED_WBU_DEFAULT_TIME_SLOTS.map { (number, start, end) ->
            TimeSlot(number = number, startTime = start, endTime = end, courseTableId = tableId)
        }
        timeSlotRepository.replaceAllForCourseTable(tableId, migrated)
        prefs.edit().putBoolean("legacy_time_slots_migrated", true).apply()
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
