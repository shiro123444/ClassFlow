package com.xingheyuzhuan.shiguangschedule.tool

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.xingheyuzhuan.shiguangschedule.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 更新渠道（保留上游 API 兼容） */
data class UpdateChannel(val id: String, val title: String, val url: String)

/** 应用更新解析结果 */
data class ReleaseUpdateInfo(
    val latestVersionName: String,
    val latestVersionCode: Long = 0L,
    val releaseTitle: String,
    val summary: String,
    val releaseUrl: String,
    val downloadUrl: String,
    val downloadAvailable: Boolean = true,
    val expectedSize: Long? = null,
    val expectedMd5: String? = null
)

/** 更新检查与下载结果状态 */
sealed class UpdateStatus {
    data class Found(val info: ReleaseUpdateInfo) : UpdateStatus()
    data class Latest(val versionName: String) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
    data object Checking : UpdateStatus()
    data class Downloading(
        val progress: Float = -1f,
        val currentBytes: Long = 0L,
        val totalBytes: Long = 0L
    ) : UpdateStatus()
    data object Idle : UpdateStatus()
}

@Serializable
data class DownloadInfoResponse(
    val url: String = "",
    val size: Long? = null,
    val md5: String? = null
)

@Serializable
data class CustomUpdateResponse(
    val channel: String = "",
    val arch: String = "",
    @SerialName("has_update") val hasUpdate: Boolean = false,
    @SerialName("latest_version") val latestVersion: String = "",
    @SerialName("latest_version_code") val latestVersionCode: Long = 0L,
    val changelog: String = "",
    @SerialName("download_url") val downloadUrl: String = "",
    @SerialName("download_available") val downloadAvailable: Boolean = true,
    @SerialName("download_info") val downloadInfo: DownloadInfoResponse? = null
)

class UpdateChecker(private val context: Context) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 更新渠道列表（上游 API 兼容） */
        val UPDATE_CHANNELS = listOf(
            UpdateChannel("custom", "官方更新服务", BuildConfig.UPDATE_API_URL)
        )

        /**
         * 清理过期的安装包垃圾：
         * 1. 临时下载未完成的 .tmp 文件
         * 2. 版本号 <= 当前应用版本的已安装 APK 文件
         * 3. 损坏或无法解析的残留安装包
         */
        fun clearOutdatedUpdateFiles(context: Context) {
            runCatching {
                val updatesDir = File(context.cacheDir, "updates")
                if (!updatesDir.exists() || !updatesDir.isDirectory) return@runCatching

                val currentCode = BuildConfig.VERSION_CODE
                updatesDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        if (file.name.endsWith(".tmp")) {
                            file.delete()
                        } else if (file.name.endsWith(".apk")) {
                            val pkgInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                            val apkVersionCode = if (pkgInfo != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    pkgInfo.longVersionCode
                                } else {
                                    @Suppress("DEPRECATION")
                                    pkgInfo.versionCode.toLong()
                                }
                            } else {
                                -1L
                            }
                            // 若此安装包版本 <= 当前运行版本，说明已安装升级完成或属于旧包，直接清理
                            if (apkVersionCode in 0..currentCode) {
                                file.delete()
                            }
                        }
                    }
                }
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 获取当前设备的 CPU 架构
     */
    fun getDeviceArch(): String {
        val supportedSplits = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        return Build.SUPPORTED_ABIS.firstOrNull { it in supportedSplits }
            ?: Build.SUPPORTED_ABIS.firstOrNull()
            ?: "arm64-v8a"
    }

    /**
     * 检查更新
     * @param customApiUrl 自定义接口地址（若为空则使用 BuildConfig.UPDATE_API_URL）
     * @param channel 更新渠道（如 stable, beta, dev，默认 stable）
     */
    suspend fun checkUpdate(
        customApiUrl: String? = null,
        channel: String = "stable"
    ): UpdateStatus = withContext(Dispatchers.IO) {
        val apiUrl = customApiUrl?.trim()?.takeIf { it.isNotBlank() } ?: BuildConfig.UPDATE_API_URL.trim()
        if (apiUrl.isBlank()) {
            return@withContext UpdateStatus.Error("未配置更新服务器地址，无法检查更新")
        }

        try {
            val arch = getDeviceArch()
            val parsedUrl = apiUrl.toHttpUrlOrNull()
            val finalUrl = if (parsedUrl != null) {
                parsedUrl.newBuilder()
                    .setQueryParameter("version_code", BuildConfig.VERSION_CODE.toString())
                    .setQueryParameter("channel", channel)
                    .setQueryParameter("arch", arch)
                    .build()
                    .toString()
            } else {
                val separator = if (apiUrl.contains("?")) "&" else "?"
                "$apiUrl${separator}version_code=${BuildConfig.VERSION_CODE}&channel=$channel&arch=$arch"
            }

            val request = Request.Builder()
                .url(finalUrl)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "ClassFlow-Android/${BuildConfig.VERSION_NAME}")
                .build()

            val jsonString = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("服务器响应异常: HTTP ${response.code}")
                response.body.string()
            }

            val updateResponse = json.decodeFromString<CustomUpdateResponse>(jsonString)

            if (!updateResponse.hasUpdate) {
                return@withContext UpdateStatus.Latest(BuildConfig.VERSION_NAME)
            }

            val remoteVersion = normalizeVersionName(updateResponse.latestVersion)
            val isNewerCode = updateResponse.latestVersionCode > BuildConfig.VERSION_CODE
            val isNewerName = compareVersion(remoteVersion, BuildConfig.VERSION_NAME) > 0

            // 优先依据 latestVersionCode 对比；若未提供则依据语义版本号对比
            if (updateResponse.latestVersionCode > 0) {
                if (!isNewerCode) {
                    return@withContext UpdateStatus.Latest(BuildConfig.VERSION_NAME)
                }
            } else if (!isNewerName) {
                return@withContext UpdateStatus.Latest(BuildConfig.VERSION_NAME)
            }

            val effectiveDownloadUrl = updateResponse.downloadInfo?.url?.trim()?.takeIf { it.isNotBlank() }
                ?: updateResponse.downloadUrl.trim()

            val versionDisplayName = if (remoteVersion.isNotBlank()) remoteVersion else "v${updateResponse.latestVersionCode}"

            // 如果服务端明确标记下载不可用
            if (!updateResponse.downloadAvailable) {
                return@withContext UpdateStatus.Error("新版本 $versionDisplayName 已发布，但针对当前架构 ($arch) 的安装包暂未开放下载")
            }

            if (effectiveDownloadUrl.isBlank()) {
                return@withContext UpdateStatus.Error("更新信息中未提供有效的下载地址")
            }

            val info = ReleaseUpdateInfo(
                latestVersionName = versionDisplayName,
                latestVersionCode = updateResponse.latestVersionCode,
                releaseTitle = "发现新版本 $versionDisplayName",
                summary = updateResponse.changelog.trim(),
                releaseUrl = effectiveDownloadUrl,
                downloadUrl = effectiveDownloadUrl,
                downloadAvailable = updateResponse.downloadAvailable,
                expectedSize = updateResponse.downloadInfo?.size,
                expectedMd5 = updateResponse.downloadInfo?.md5?.trim()?.takeIf { it.isNotBlank() }
            )

            UpdateStatus.Found(info)
        } catch (e: Exception) {
            UpdateStatus.Error("检查更新失败: ${e.message ?: "未知网络错误"}")
        }
    }

    /**
     * 流式下载更新包，并在完成后尝试安装
     * 无论远程 URL 后缀为何，本地均严格以 .apk 后缀保存
     * 若未提供 expectedSize 或 expectedMd5，则不进行校验
     */
    suspend fun downloadAndInstallUpdate(
        downloadUrl: String,
        versionName: String,
        expectedSize: Long? = null,
        expectedMd5: String? = null,
        onProgress: ((progress: Float, currentBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(downloadUrl)
                .addHeader("User-Agent", "ClassFlow-Android/${BuildConfig.VERSION_NAME}")
                .build()

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            // 清理旧更新文件与临时文件
            updatesDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".apk") || file.name.endsWith(".tmp"))) {
                    runCatching { file.delete() }
                }
            }

            val safeVersion = versionName.replace(Regex("[^0-9A-Za-z._-]"), "_")
            // 严格保存为 .apk 后缀
            val targetApk = File(updatesDir, "classflow-update-$safeVersion.apk")
            val tempFile = File(updatesDir, "classflow-update-$safeVersion.apk.tmp")

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载失败: HTTP ${response.code}")
                val body = response.body
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var lastNotifyTime = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastNotifyTime >= 80 || downloadedBytes == totalBytes) {
                                lastNotifyTime = now
                                val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else -1f
                                withContext(Dispatchers.Main) {
                                    onProgress?.invoke(progress, downloadedBytes, totalBytes)
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }

            if (targetApk.exists()) targetApk.delete()
            if (!tempFile.renameTo(targetApk)) {
                tempFile.copyTo(targetApk, overwrite = true)
                tempFile.delete()
            }

            // 校验文件大小 (若有配置且 > 0)
            if (expectedSize != null && expectedSize > 0L) {
                if (targetApk.length() != expectedSize) {
                    val actualSize = targetApk.length()
                    targetApk.delete()
                    throw IOException("安装包大小校验失败: 预期 $expectedSize 字节，实际 $actualSize 字节")
                }
            }

            // 校验 MD5 (若有配置且不为空)
            if (!expectedMd5.isNullOrBlank()) {
                val actualMd5 = calculateFileMd5(targetApk)
                if (!actualMd5.equals(expectedMd5.trim(), ignoreCase = true)) {
                    targetApk.delete()
                    throw IOException("安装包 MD5 校验不匹配: 预期 $expectedMd5，实际 $actualMd5")
                }
            }

            withContext(Dispatchers.Main) {
                if (canRequestPackageInstalls()) {
                    installApk(targetApk)
                }
            }
            targetApk
        }
    }

    /**
     * 计算文件 MD5 哈希值
     */
    private fun calculateFileMd5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 检查是否具备安装未知应用权限 (Android 8.0+)
     */
    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * 引导跳转至未知应用安装权限授权页面
     */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * 调起系统应用包安装器
     */
    fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /**
     * 获取指定版本已下载完成的 APK 安装包
     */
    fun getDownloadedApk(versionName: String): File? {
        val updatesDir = File(context.cacheDir, "updates")
        val safeVersion = versionName.replace(Regex("[^0-9A-Za-z._-]"), "_")
        val targetApk = File(updatesDir, "classflow-update-$safeVersion.apk")
        return if (targetApk.exists() && targetApk.length() > 0) targetApk else null
    }

    private fun normalizeVersionName(version: String): String {
        return version.trim().removePrefix("v").substringBefore("+").trim()
    }

    private fun compareVersion(remote: String, local: String): Int {
        val remoteParts = remote.split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: 0 }
        val localParts = local.split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: 0 }

        val maxSize = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until maxSize) {
            val left = remoteParts.getOrElse(index) { 0 }
            val right = localParts.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }
}
