package com.xingheyuzhuan.shiguangschedule.tool

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 一次安装尝试的最终结果。
 */
sealed interface InstallResult {
    /** 安装成功（自更新场景下进程随后会被系统重启） */
    data object Installed : InstallResult

    /** 用户在系统确认框上取消，或系统中止了本次安装 */
    data object UserAborted : InstallResult

    /** 安装失败，[message] 为系统返回的错误信息 */
    data class Error(val message: String?) : InstallResult
}

/**
 * 基于 [PackageInstaller] 会话的应用安装器（对齐 F-Droid 的实现思路）。
 *
 * 与旧的 `Intent.ACTION_VIEW` 拉起安装器方案相比：
 * 1. 安装走 PackageInstaller 会话通道，由系统安装器统一托管，不再依赖 FileProvider 传递临时授权；
 * 2. 当 [PackageManager.canRequestPackageInstalls] 未被打开时，会话仍可提交，系统会接管并给出确认流程；
 * 3. 在 Android 12+（API 31）上，若被安装 APK 的 targetSdk 达标且本应用持有
 *    `android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION`，系统对**自身应用的更新**会走静默路径
 *    （AOSP `PackageInstallerSession#computeUserActionRequirement` 中的 `isSelfUpdate` 分支），
 *    连确认框都不会弹出。
 */
object SessionInstallManager {

    private const val TAG = "SessionInstallManager"

    /** 安装结果回执广播（仅本应用内动态注册接收） */
    private const val ACTION_INSTALL_RESULT = "com.shiro.classflow.action.INSTALL_RESULT"

    /**
     * 判断在给定被安装 APK 的 targetSdk 下，静默更新（无需用户操作）是否受系统支持。
     * 对应 AOSP `PackageInstallerSession.isTargetSdkConditionSatisfied` 的能力矩阵。
     */
    fun isSilentUpdateSupported(targetSdk: Int?): Boolean {
        if (targetSdk == null) return false
        val sdk = Build.VERSION.SDK_INT
        if (sdk < Build.VERSION_CODES.S) return false
        return when (sdk) {
            31, 32 -> targetSdk >= 29
            33 -> targetSdk >= 30
            34 -> targetSdk >= 31
            35 -> targetSdk >= 33
            else -> targetSdk >= 34
        }
    }

    /** 读取 APK 文件声明的 targetSdkVersion，解析失败返回 null */
    fun readTargetSdk(context: Context, apkFile: File): Int? =
        runCatching {
            context.packageManager
                .getPackageArchiveInfo(apkFile.absolutePath, 0)
                ?.applicationInfo
                ?.targetSdkVersion
        }.getOrNull()

    /**
     * 通过 PackageInstaller 会话安装 APK。
     *
     * 挂起直到拿到**最终**结果；若系统要求用户确认，会自动调起系统确认界面并继续等待。
     * 调用方需保证应用处于前台，否则系统确认界面无法被拉起。
     */
    suspend fun install(
        context: Context,
        apkFile: File,
        packageName: String = context.packageName,
    ): InstallResult = suspendCancellableCoroutine { cont ->
        val appContext = context.applicationContext
        val installer = appContext.packageManager.packageInstaller
        val size = apkFile.length()

        if (size <= 0L) {
            cont.resume(InstallResult.Error("APK file is empty"))
            return@suspendCancellableCoroutine
        }

        val targetSdk = readTargetSdk(appContext, apkFile)
        Log.i(
            TAG,
            "install ${apkFile.name} size=$size targetSdk=$targetSdk " +
                "silent=${isSilentUpdateSupported(targetSdk)}"
        )

        val sessionId = try {
            installer.createSession(buildSessionParams(packageName, size, targetSdk))
        } catch (e: Exception) {
            Log.e(TAG, "createSession failed", e)
            cont.resume(InstallResult.Error(e.message ?: e.javaClass.simpleName))
            return@suspendCancellableCoroutine
        }

        val settled = AtomicBoolean(false)
        /** 提交后会话已交给系统托管，此时不能再 abandon，否则会打断用户的确认操作 */
        var committed = false
        var confirmationSent = false
        lateinit var receiver: BroadcastReceiver

        fun unregister() {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // 已经注销或从未注册
            }
        }

        fun finish(result: InstallResult) {
            if (!settled.compareAndSet(false, true)) return
            unregister()
            if (cont.isActive) cont.resume(result)
        }

        fun sendUserConfirmation(intent: Intent?, message: String?) {
            if (confirmationSent) return
            confirmationSent = true
            val confirmation = intent?.confirmationIntent()
            if (confirmation == null) {
                finish(InstallResult.Error(message ?: "Missing user confirmation intent"))
                return
            }
            try {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                PendingIntent.getActivity(appContext, sessionId, confirmation, flags).send()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch user confirmation", e)
                finish(InstallResult.Error(e.message ?: e.javaClass.simpleName))
            }
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent == null) return
                if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) return
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE,
                )
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> finish(InstallResult.Installed)

                    PackageInstaller.STATUS_PENDING_USER_ACTION ->
                        // 系统要求用户确认：拉起系统安装确认界面，并继续等待后续回执
                        sendUserConfirmation(intent, message)

                    PackageInstaller.STATUS_FAILURE_ABORTED -> finish(InstallResult.UserAborted)

                    else -> finish(InstallResult.Error(message))
                }
            }
        }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(ACTION_INSTALL_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        cont.invokeOnCancellation {
            unregister()
            if (!committed) {
                try {
                    installer.abandonSession(sessionId)
                } catch (e: Exception) {
                    Log.w(TAG, "abandonSession failed", e)
                }
            }
        }

        try {
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite(packageName, 0, size).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                session.commit(buildResultSender(appContext, sessionId, packageName))
                committed = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "install session failed", e)
            finish(InstallResult.Error(e.message ?: e.javaClass.simpleName))
        }
    }

    /** 旧版兜底通道：交给系统安装器处理。仅在会话安装异常时使用。 */
    fun installLegacy(context: Context, apkFile: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Log.w(TAG, "Falling back to legacy install for ${apkFile.name}")
        context.startActivity(intent)
    }

    private fun buildSessionParams(
        packageName: String,
        size: Long,
        targetSdk: Int?,
    ): PackageInstaller.SessionParams {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        params.setSize(size)
        params.setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isSilentUpdateSupported(targetSdk)) {
            // 允许系统在能力允许时跳过用户确认（自更新场景）
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+：声明本次安装意图取得更新归属，避免被其他商店夺走更新权
            params.setRequestUpdateOwnership(true)
        }
        return params
    }

    private fun buildResultSender(
        context: Context,
        sessionId: Int,
        packageName: String,
    ): IntentSender {
        val broadcast = Intent(ACTION_INSTALL_RESULT).apply {
            setPackage(context.packageName)
            putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, packageName)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        // 必须是 mutable，否则系统无法回填会话状态等 extras
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, sessionId, broadcast, flags).intentSender
    }

    /** 从系统回执中取出安装确认 Intent（兼容 API 33 的 parcelable 读取方式） */
    private fun Intent.confirmationIntent(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_INTENT)
    }
}