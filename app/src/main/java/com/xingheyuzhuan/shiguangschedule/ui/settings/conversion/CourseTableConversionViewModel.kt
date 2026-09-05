package com.xingheyuzhuan.shiguangschedule.ui.settings.conversion

import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseConversionRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseImportExport
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseTableRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * 课表导入/导出界面的 ViewModel。
 * 处理所有业务逻辑和状态，并通过事件通道与 UI 沟通。
 */
@HiltViewModel
class CourseTableConversionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val courseConversionRepository: CourseConversionRepository,
    private val courseTableRepository: CourseTableRepository,
    private val appSettingsRepository: AppSettingsRepository
) : ViewModel() {

    // UI 状态流，仅包含 UI 显示相关的状态（如对话框可见性、加载状态）
    private val _uiState = MutableStateFlow(ConversionUiState())
    val uiState = _uiState.asStateFlow()

    // UI 事件通道，用于发送一次性副作用（如启动文件选择器，显示 Snackbar）
    private val _events = Channel<ConversionEvent>()
    val events = _events.receiveAsFlow()

    // context 已由构造注入

    fun onImportClick() {
        _uiState.value = _uiState.value.copy(showImportTableDialog = true)
    }

    fun onExportClick() {
        _uiState.value = _uiState.value.copy(
            showExportTableDialog = true,
            exportType = ExportType.JSON
        )
    }

    fun onExportIcsClick() {
        _uiState.value = _uiState.value.copy(
            showExportTableDialog = true,
            exportType = ExportType.ICS
        )
    }

    fun onExportWakeupClick() {
        _uiState.value = _uiState.value.copy(
            showExportTableDialog = true,
            exportType = ExportType.WAKEUP
        )
    }

    fun onExportImageClick() {
        _uiState.value = _uiState.value.copy(
            showExportTableDialog = true,
            exportType = ExportType.IMAGE
        )
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(
            showImportTableDialog = false,
            showExportTableDialog = false
        )
    }

    fun onImportTableSelected(tableId: String) {
        viewModelScope.launch {
            _events.send(ConversionEvent.LaunchImportFilePicker(tableId))
            dismissDialog()
        }
    }

    fun onExportTableSelected(
        tableId: String,
        alarmMinutes: Int?,
        showDashedDivider: Boolean = false,
        autoSplitConflict: Boolean = true
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                if (_uiState.value.exportType == ExportType.JSON) {
                    val jsonModel = courseConversionRepository.exportCourseTableToJson(tableId)
                    if (jsonModel != null) {
                        val jsonString = Json.encodeToString(jsonModel)
                        _events.send(ConversionEvent.LaunchExportFileCreator(jsonString))
                    } else {
                        val message = context.getString(R.string.error_export_table_not_found)
                        _events.send(ConversionEvent.ShowMessage(message))
                    }
                } else if (_uiState.value.exportType == ExportType.ICS) {
                    _events.send(ConversionEvent.LaunchExportIcsFileCreator(tableId, alarmMinutes))
                } else if (_uiState.value.exportType == ExportType.WAKEUP) {
                    val file = courseConversionRepository.exportCourseTableToWakeupFile(tableId, context)
                    if (file != null) {
                        _events.send(ConversionEvent.PromptWakeupExportAction(file.name))
                    } else {
                        val message = context.getString(R.string.error_export_table_not_found)
                        _events.send(ConversionEvent.ShowMessage(message))
                    }
                } else if (_uiState.value.exportType == ExportType.IMAGE) {
                    val success = courseConversionRepository.exportCourseTableToImageFile(
                        tableId = tableId,
                        context = context,
                        showDashedDivider = showDashedDivider,
                        autoSplitConflict = autoSplitConflict
                    )
                    if (success) {
                        val message = context.getString(R.string.toast_image_export_success)
                        _events.send(ConversionEvent.ShowMessage(message))
                    } else {
                        val message = context.getString(R.string.error_image_export_failed)
                        _events.send(ConversionEvent.ShowMessage(message))
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseTableConversionViewModel", "导出失败：${e.message}", e)
                val message = context.getString(R.string.error_export_failed, e.message)
                _events.send(ConversionEvent.ShowMessage(message))
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
                dismissDialog()
            }
        }
    }

    fun handleFileImport(tableId: String, inputStream: InputStream) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val jsonString = inputStream.bufferedReader(Charset.forName("UTF-8")).use { it.readText() }
                val importModel = Json.decodeFromString<CourseImportExport.CourseTableImportModel>(jsonString)
                courseConversionRepository.importCourseTableFromJson(tableId, importModel)

                val message = context.getString(R.string.toast_import_success)
                _events.send(ConversionEvent.ShowMessage(message))
            } catch (e: Exception) {
                Log.e("CourseTableConversionViewModel", "导入失败：${e.message}", e)
                val message = context.getString(R.string.error_import_failed, e.message)
                _events.send(ConversionEvent.ShowMessage(message))
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun handleIcsExport(tableId: String, outputStream: OutputStream, alarmMinutes: Int?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val icsContent = courseConversionRepository.exportToIcsString(tableId, alarmMinutes)
                if (icsContent != null) {
                    outputStream.bufferedWriter(Charset.forName("UTF-8")).use { writer ->
                        writer.write(icsContent)
                    }
                    val message = context.getString(R.string.toast_ics_export_success)
                    _events.send(ConversionEvent.ShowMessage(message))
                } else {
                    val message = context.getString(R.string.error_ics_export_data_failed)
                    _events.send(ConversionEvent.ShowMessage(message))
                }
            } catch (e: Exception) {
                Log.e("CourseTableConversionViewModel", "日历文件导出失败：${e.message}", e)
                val message = context.getString(R.string.error_ics_export_failed, e.message)
                _events.send(ConversionEvent.ShowMessage(message))
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun onSyncToCalendarClick() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val success = courseConversionRepository.syncCurrentTableToSystemCalendar(context)
                val message = if (success) {
                    context.getString(R.string.toast_sync_calendar_success)
                } else {
                    context.getString(R.string.error_sync_calendar_failed)
                }
                _events.send(ConversionEvent.ShowMessage(message))
            } catch (e: Exception) {
                Log.e("CourseTableConversionViewModel", "同步系统日历失败：${e.message}", e)
                _events.send(ConversionEvent.ShowMessage(context.getString(R.string.error_sync_calendar_failed)))
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }
}

data class ConversionUiState(
    val isLoading: Boolean = false,
    val showImportTableDialog: Boolean = false,
    val showExportTableDialog: Boolean = false,
    val exportType: ExportType = ExportType.NONE
)

enum class ExportType {
    NONE,
    JSON,
    ICS,
    WAKEUP,
    IMAGE
}

sealed class ConversionEvent {
    data class LaunchImportFilePicker(val tableId: String) : ConversionEvent()
    data class LaunchExportFileCreator(val jsonContent: String) : ConversionEvent()
    data class LaunchExportIcsFileCreator(val tableId: String, val alarmMinutes: Int?) : ConversionEvent()
    data class PromptWakeupExportAction(val fileName: String) : ConversionEvent()
    data class OpenWakeupFile(val fileName: String) : ConversionEvent()
    data class ShowMessage(val message: String) : ConversionEvent()
}
