package com.shiro.classflow.tool

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.shiro.classflow.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** GitHub Release 解析结果 */
data class ReleaseUpdateInfo(
    val latestVersionName: String,
    val releaseTitle: String,
    val summary: String,
    val releaseUrl: String,
    val downloadUrl: String
)

/** 更新检查结果状态 */
sealed class UpdateStatus {
    data class Found(val info: ReleaseUpdateInfo) : UpdateStatus()
    data class Latest(val versionName: String) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
    object Checking : UpdateStatus()
    object Downloading : UpdateStatus()
    object Idle : UpdateStatus()
}

@Serializable
data class GithubReleaseResponse(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GithubAssetResponse> = emptyList(),
)

@Serializable
data class GithubAssetResponse(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = ""
)

class UpdateChecker(private val context: Context) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private const val GITHUB_RELEASE_LATEST_API =
            "https://api.github.com/repos/shiro123444/ClassFlow/releases/latest"
        const val GITHUB_RELEASES_URL =
            "https://github.com/shiro123444/ClassFlow/releases"
    }

    private val httpClient = OkHttpClient.Builder().build()
    private val currentFlavorId = BuildConfig.CURRENT_FLAVOR_ID

    suspend fun checkUpdate(): UpdateStatus = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_RELEASE_LATEST_API)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", "ClassFlow-Android")
                .build()

            val releaseJson = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GitHub Release 接口请求失败: ${response.code}")
                response.body.string()
            }

            val release = json.decodeFromString<GithubReleaseResponse>(releaseJson)
            val remoteVersion = normalizeVersionName(
                if (release.tagName.isNotBlank()) release.tagName else release.name
            )

            if (compareVersion(remoteVersion, BuildConfig.VERSION_NAME) <= 0) {
                return@withContext UpdateStatus.Latest(BuildConfig.VERSION_NAME)
            }

            val downloadUrl = selectBestApkAssetUrl(release.assets)
                ?: return@withContext UpdateStatus.Error("未找到可用的 APK 安装包资源")

            val info = ReleaseUpdateInfo(
                latestVersionName = remoteVersion,
                releaseTitle = release.name.ifBlank { remoteVersion },
                summary = release.body.trim(),
                releaseUrl = release.htmlUrl.ifBlank { GITHUB_RELEASES_URL },
                downloadUrl = downloadUrl
            )

            UpdateStatus.Found(info)
        } catch (e: Exception) {
            UpdateStatus.Error("检查更新失败: ${e.message ?: "未知错误"}")
        }
    }

    suspend fun downloadAndInstallUpdate(downloadUrl: String, versionName: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(downloadUrl)
                    .addHeader("User-Agent", "ClassFlow-Android")
                    .build()

                val apkFile = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("下载更新包失败: ${response.code}")
                    val body = response.body

                    val dir = File(context.cacheDir, "share_temp").apply { mkdirs() }
                    val safeVersion = versionName.replace(Regex("[^0-9A-Za-z._-]"), "_")
                    val target = File(dir, "classflow-update-$safeVersion.apk")

                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    target
                }

                withContext(Dispatchers.Main) {
                    installApk(apkFile)
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun selectBestApkAssetUrl(assets: List<GithubAssetResponse>): String? {
        val apkAssets = assets.filter { it.name.lowercase().endsWith(".apk") }
        if (apkAssets.isEmpty()) return null

        val abi = getDeviceAbi()
        val flavor = if (currentFlavorId == "dev") "dev" else "prod"

        return apkAssets
            .maxByOrNull { scoreAssetName(it.name.lowercase(), abi, flavor) }
            ?.browserDownloadUrl
    }

    private fun scoreAssetName(name: String, abi: String, flavor: String): Int {
        var score = 0
        if (name.contains(flavor)) score += 4
        if (name.contains(abi)) score += 3
        if (name.contains("universal")) score += 2
        if (name.contains("release")) score += 1
        return score
    }

    private fun getDeviceAbi(): String {
        val supportedSplits = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        return Build.SUPPORTED_ABIS.firstOrNull { it in supportedSplits } ?: "universal"
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
