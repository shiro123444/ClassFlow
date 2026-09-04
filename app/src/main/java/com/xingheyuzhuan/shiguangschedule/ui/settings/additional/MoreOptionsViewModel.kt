package com.xingheyuzhuan.shiguangschedule.ui.settings.additional

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.model.AppSettingsModel
import com.xingheyuzhuan.shiguangschedule.data.model.StartScreen
import com.xingheyuzhuan.shiguangschedule.data.model.UpdateChannelType
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 更多选项页 ViewModel（启动页面设置等 DataStore 偏好）
 */
@HiltViewModel
class MoreOptionsViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository
) : ViewModel() {

    /** 当前启动页面 */
    val startScreen: StateFlow<StartScreen> = appSettingsRepository.getAppSettings()
        .map { it.startScreen }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettingsModel().startScreen
        )

    /** 自动检查更新开关 */
    val autoCheckUpdate: StateFlow<Boolean> = appSettingsRepository.getAppSettings()
        .map { it.autoCheckUpdate }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    /** 自定义更新服务器地址 */
    val customUpdateApiUrl: StateFlow<String> = appSettingsRepository.getAppSettings()
        .map { it.customUpdateApiUrl }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    /** 用户选择跳过的版本号 */
    val ignoredUpdateVersion: StateFlow<String> = appSettingsRepository.getAppSettings()
        .map { it.ignoredUpdateVersion }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    /** 当前更新渠道 */
    val updateChannel: StateFlow<String> = appSettingsRepository.getAppSettings()
        .map { it.updateChannel }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UpdateChannelType.STABLE.channelId
        )

    /** 更新更新渠道 */
    fun onUpdateChannelChanged(channel: String) {
        viewModelScope.launch {
            appSettingsRepository.updateUpdateChannel(channel)
            // 切换渠道后清空跳过标记并重置冷却，立即允许检查新渠道
            appSettingsRepository.updateIgnoredUpdateVersion("")
            appSettingsRepository.updateLastCheckUpdateTime(0L)
        }
    }

    /** 更新自定义更新服务器地址 */
    fun onCustomUpdateApiUrlChanged(url: String) {
        viewModelScope.launch {
            appSettingsRepository.updateCustomUpdateApiUrl(url.trim())
            // 更改更新地址后重置检查时间戳，以便下次冷启动能立即检测
            appSettingsRepository.updateLastCheckUpdateTime(0L)
        }
    }

    /** 恢复已跳过的版本提醒 */
    fun clearIgnoredUpdateVersion() {
        viewModelScope.launch {
            appSettingsRepository.updateIgnoredUpdateVersion("")
            appSettingsRepository.updateLastCheckUpdateTime(0L)
        }
    }

    /** 更新自动检查更新设置 */
    fun onAutoCheckUpdateChanged(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.updateAutoCheckUpdate(enabled)
        }
    }

    /** 跳过此版本 */
    fun ignoreUpdateVersion(versionName: String) {
        viewModelScope.launch {
            appSettingsRepository.updateIgnoredUpdateVersion(versionName)
        }
    }

    /** 更新应用启动时的默认主页 */
    fun onStartScreenChanged(newScreen: StartScreen) {
        viewModelScope.launch {
            val currentSettings = appSettingsRepository.getAppSettingsOnce()
            appSettingsRepository.insertOrUpdateAppSettings(
                currentSettings.copy(startScreen = newScreen)
            )
        }
    }
}
