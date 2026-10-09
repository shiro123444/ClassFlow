package com.xingheyuzhuan.shiguangschedule.ui.campus.hairdryer

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerDetectionMode
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerDeviceType
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerHistoryEntry
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerNfcMode
import com.xingheyuzhuan.shiguangschedule.data.repository.HairdryerHubRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 吹风机页的逻辑：判型设置、使用记录、桌面快捷方式。
 *
 * 页面本身不碰任何接口 —— 记录里的设备名由 [HairdryerHubRepository] 在后台补全
 * （扫码那一刻没探测过的记录，列表默认只有一串设备码）。
 */
@HiltViewModel
class HairdryerHubViewModel @Inject constructor(
    private val repository: HairdryerHubRepository,
) : ViewModel() {

    data class UiState(
        val settings: HairdryerHubRepository.Settings = HairdryerHubRepository.Settings(),
        val entries: List<HairdryerHistoryEntry> = emptyList(),
        /** 正在补全设备名（列表顶部转圈的那个状态）。 */
        val refreshing: Boolean = false,
    )

    private val refreshing = MutableStateFlow(false)

    val uiState: StateFlow<UiState> = combine(
        repository.settings,
        repository.history,
        refreshing
    ) { settings, entries, isRefreshing ->
        UiState(settings = settings, entries = entries, refreshing = isRefreshing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    // ── 判型设置 ──

    fun setMode(mode: HairdryerDetectionMode) {
        viewModelScope.launch { repository.setDetectionMode(mode) }
    }

    fun setNfcMode(mode: HairdryerNfcMode) {
        viewModelScope.launch { repository.setNfcMode(mode) }
    }

    fun resetFormatVerdicts() {
        viewModelScope.launch { repository.resetFormatVerdicts() }
    }

    // ── 记录 ──

    fun rename(cd: String, alias: String) {
        viewModelScope.launch { repository.rename(cd, alias) }
    }

    fun setType(cd: String, type: HairdryerDeviceType) {
        viewModelScope.launch {
            repository.setType(cd, type)
            _messages.emit(R.string.hairdryer_message_type_changed)
        }
    }

    fun setIcon(cd: String, iconId: String) {
        viewModelScope.launch { repository.setIcon(cd, iconId) }
    }

    fun delete(cd: String) {
        viewModelScope.launch { repository.delete(cd) }
    }

    /** 补全记录里的设备名（店铺 / 服务主体）：只对还没名字、且留着扫码原文的记录发探测。 */
    fun refreshNames() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                val targets = uiState.value.entries.filter { it.storeName == null && it.raw.isNotBlank() }
                targets.forEach { repository.enrich(it.cd, it.raw) }
                if (targets.isNotEmpty()) _messages.emit(R.string.hairdryer_message_names_updated)
            } finally {
                refreshing.value = false
            }
        }
    }

    fun notify(@StringRes messageRes: Int) {
        _messages.tryEmit(messageRes)
    }
}
