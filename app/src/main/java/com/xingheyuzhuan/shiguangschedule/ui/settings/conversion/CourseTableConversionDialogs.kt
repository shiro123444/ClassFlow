package com.xingheyuzhuan.shiguangschedule.ui.settings.conversion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.ui.components.CourseTablePickerDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.NativeNumberPicker


class OpenJsonDocumentContract : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

class CreateJsonDocumentContract : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, input)
        }
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

class CreateIcsDocumentContract : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/calendar"
            putExtra(Intent.EXTRA_TITLE, input)
        }
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

class CreateWakeupDocumentContract : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, input)
        }
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

// --- 内部数据模型 ---
private data class LocalizedAlarmOption(val value: Int?, private val displayString: String) {
    override fun toString(): String = displayString
}

@Composable
fun AlarmMinutesPicker(
    modifier: Modifier = Modifier,
    initialValue: Int? = 15,
    onValueSelected: (Int?) -> Unit,
    itemHeight: Dp
) {
    val alarmOptionNone = stringResource(R.string.alarm_option_none)
    val alarmOptionOnTime = stringResource(R.string.alarm_option_on_time)

    val localizedOptions = remember(alarmOptionNone, alarmOptionOnTime) {
        buildList {
            add(LocalizedAlarmOption(null, alarmOptionNone))
            add(LocalizedAlarmOption(0, alarmOptionOnTime))
            for (i in 1..60) {
                add(LocalizedAlarmOption(i, i.toString()))
            }
        }
    }
    val initialOption = remember(initialValue, localizedOptions) {
        localizedOptions.find { it.value == initialValue } ?: localizedOptions.find { it.value == 15 }!!
    }

    NativeNumberPicker(
        values = localizedOptions,
        selectedValue = initialOption,
        onValueChange = { onValueSelected(it.value) },
        modifier = modifier,
        itemHeight = itemHeight
    )
}

/**
 * ICS 导出对话框（ClassFlow 定制版：Dialog+Card 样式，替代上游 AlertDialog 样式）
 */
@Composable
fun IcsExportDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (String, Int?) -> Unit
) {
    var alarmMinutes by remember { mutableStateOf<Int?>(15) }
    var showTablePicker by remember { mutableStateOf(false) }

    val dialogTitleIcsExport = stringResource(R.string.dialog_title_ics_export_settings)
    val labelSelectAlarm = stringResource(R.string.label_select_alarm_time)
    val actionCancel = stringResource(R.string.action_cancel)
    val actionNextStep = stringResource(R.string.action_next_step)
    val dialogTitleSelectExportTable = stringResource(R.string.dialog_title_select_export_table)

    // 当 showTablePicker 为 false 时，显示第一个对话框（提醒时间选择）
    if (!showTablePicker) {
        Dialog(onDismissRequest = onDismissRequest) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = dialogTitleIcsExport,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(labelSelectAlarm, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    AlarmMinutesPicker(
                        modifier = Modifier.width(150.dp),
                        onValueSelected = { minutes -> alarmMinutes = minutes },
                        itemHeight = 48.dp
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismissRequest) {
                            Text(actionCancel)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { showTablePicker = true }) {
                            Text(actionNextStep)
                        }
                    }
                }
            }
        }
    }

    // 当 showTablePicker 为 true 时，显示第二个对话框（课表选择）
    if (showTablePicker) {
        CourseTablePickerDialog(
            title = dialogTitleSelectExportTable,
            // 这里我们希望关闭课表选择器时，整个导出流程都结束
            onDismissRequest = onDismissRequest,
            onTableSelected = { selectedTable ->
                // 在回调中，同时传递课表ID和之前选择的提醒时间
                onConfirm(selectedTable.id, alarmMinutes)
            }
        )
    }
}

/**
 * 课表图片导出配置对话框：支持复选框选择是否显示分割虚线、是否自动截断冲突课程（默认开启）
 */
@Composable
fun ImageExportDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (String, Boolean, Boolean) -> Unit
) {
    var showDashedDivider by remember { mutableStateOf(false) }
    var autoSplitConflict by remember { mutableStateOf(true) } // 默认勾选截断
    var showTablePicker by remember { mutableStateOf(false) }

    val dialogTitle = stringResource(R.string.dialog_title_image_export_settings)
    val optionDashed = stringResource(R.string.option_show_dashed_divider)
    val optionAutoSplit = stringResource(R.string.option_auto_split_conflict)
    val descAutoSplit = stringResource(R.string.desc_auto_split_conflict)
    val actionCancel = stringResource(R.string.action_cancel)
    val actionNextStep = stringResource(R.string.action_next_step)
    val dialogTitleSelectExportTable = stringResource(R.string.dialog_title_select_export_table)

    if (!showTablePicker) {
        Dialog(onDismissRequest = onDismissRequest) {
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = dialogTitle,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // 选项 1：虚线分割（默认关闭）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = showDashedDivider,
                            onCheckedChange = { showDashedDivider = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = optionDashed,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    // 选项 2：自动截断冲突课程（默认开启）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = autoSplitConflict,
                            onCheckedChange = { autoSplitConflict = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = optionAutoSplit,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = descAutoSplit,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismissRequest) {
                            Text(actionCancel)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { showTablePicker = true }) {
                            Text(actionNextStep)
                        }
                    }
                }
            }
        }
    }

    if (showTablePicker) {
        CourseTablePickerDialog(
            title = dialogTitleSelectExportTable,
            onDismissRequest = onDismissRequest,
            onTableSelected = { selectedTable ->
                onConfirm(selectedTable.id, showDashedDivider, autoSplitConflict)
            }
        )
    }
}

/**
 * 统一弹窗管理器管理中心
 */
@Composable
fun ConversionDialogOverlay(
    uiState: ConversionUiState,
    onDismiss: () -> Unit,
    onConfirmImport: (String) -> Unit,
    onConfirmExport: (String, Int?) -> Unit
) {
    if (uiState.showImportTableDialog) {
        CourseTablePickerDialog(
            title = stringResource(R.string.dialog_title_select_import_table),
            onDismissRequest = onDismiss,
            onTableSelected = { onConfirmImport(it.id) }
        )
    }

    if (uiState.showExportTableDialog) {
        when (uiState.exportType) {
            ExportType.JSON -> {
                CourseTablePickerDialog(
                    title = stringResource(R.string.dialog_title_select_export_table),
                    onDismissRequest = onDismiss,
                    onTableSelected = { onConfirmExport(it.id, null) }
                )
            }
            ExportType.ICS -> {
                IcsExportDialog(
                    onDismissRequest = onDismiss,
                    onConfirm = { tableId, alarmMinutes ->
                        onConfirmExport(tableId, alarmMinutes)
                    }
                )
            }
            else -> {}
        }
    }
}
