package com.xingheyuzhuan.shiguangschedule.ui.settings.conversion

import androidx.hilt.navigation.compose.hiltViewModel
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.SendToMobile
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.tool.WakeupExportTool
import com.xingheyuzhuan.shiguangschedule.tool.shareFile
import com.xingheyuzhuan.shiguangschedule.ui.components.CourseTablePickerDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.DockSafeBottomPadding
import com.xingheyuzhuan.shiguangschedule.ui.settings.SettingsCard
import com.xingheyuzhuan.shiguangschedule.ui.settings.SettingTile
import com.xingheyuzhuan.shiguangschedule.ui.settings.SettingDivider
import com.xingheyuzhuan.shiguangschedule.ui.theme.ThemeGradients
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun CourseTableConversionScreen(
    navBridge: NavBridge,
    viewModel: CourseTableConversionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val snackbarCannotOpenFile = stringResource(R.string.snackbar_cannot_open_file)
    val snackbarFileSelectionCanceled = stringResource(R.string.snackbar_file_selection_canceled)
    val snackbarCannotSaveFile = stringResource(R.string.snackbar_cannot_save_file)
    val snackbarFileSaveCanceled = stringResource(R.string.snackbar_file_save_canceled)
    val snackbarFileCopyFailedForShare = stringResource(R.string.snackbar_file_copy_failed_for_share)
    val snackbarCalendarPermissionDenied = stringResource(R.string.error_sync_calendar_failed)

    val dialogTitleFileSaved = stringResource(R.string.dialog_title_file_saved)
    val dialogTextFileSavedSharePrompt = stringResource(R.string.dialog_text_file_saved_share_prompt)
    val actionShare = stringResource(R.string.action_share)
    val actionCancel = stringResource(R.string.action_cancel)

    var pendingImportTableId by remember { mutableStateOf<String?>(null) }
    var pendingExportJsonContent by remember { mutableStateOf<String?>(null) }
    var pendingExportIcsTableId by remember { mutableStateOf<String?>(null) }
    var pendingAlarmMinutes by remember { mutableStateOf<Int?>(null) }
    var pendingWakeupFileName by remember { mutableStateOf<String?>(null) }

    // 新增状态，用于显示分享弹窗。保存公共目录的Uri和原始文件名。
    var showShareDialog by remember { mutableStateOf<Triple<Uri, String, String>?>(null) }

    // Wakeup 导出后的动作选择弹窗状态（保存待处理的文件名）
    var showWakeupActionDialog by remember { mutableStateOf<String?>(null) }

    // 文件导入启动器
    val importLauncher = rememberLauncherForActivityResult(OpenJsonDocumentContract()) { uri: Uri? ->
        val tableId = pendingImportTableId
        if (uri != null && tableId != null) {
            val inputStream: InputStream? = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                null
            }
            if (inputStream != null) {
                viewModel.handleFileImport(tableId, inputStream)
            } else {
                coroutineScope.launch { snackbarHostState.showSnackbar(snackbarCannotOpenFile) }
            }
        } else if (uri == null) {
            coroutineScope.launch { snackbarHostState.showSnackbar(snackbarFileSelectionCanceled) }
        }
        pendingImportTableId = null
    }

    // 文件导出启动器
    val exportLauncher = rememberLauncherForActivityResult(CreateJsonDocumentContract()) { uri: Uri? ->
        val jsonContent = pendingExportJsonContent
        val filename = "classflow_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.json"
        if (uri != null && jsonContent != null) {
            val outputStream: OutputStream? = try {
                context.contentResolver.openOutputStream(uri)
            } catch (e: Exception) {
                null
            }
            if (outputStream != null) {
                // 将文件内容写入
                outputStream.bufferedWriter().use { writer ->
                    writer.write(jsonContent)
                }
                // 在文件保存成功后，设置状态以显示分享弹窗。我们保存公共Uri和我们想要的原始文件名。
                showShareDialog = Triple(uri, "application/json", filename)
            } else {
                coroutineScope.launch { snackbarHostState.showSnackbar(snackbarCannotSaveFile) }
            }
        } else if (uri == null) {
            coroutineScope.launch { snackbarHostState.showSnackbar(snackbarFileSaveCanceled) }
        }
        pendingExportJsonContent = null
    }

    // ICS 文件导出启动器
    val icsExportLauncher = rememberLauncherForActivityResult(CreateIcsDocumentContract()) { uri: Uri? ->
        val tableId = pendingExportIcsTableId
        val alarmMinutes = pendingAlarmMinutes
        val filename = "classflow_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.ics"
        if (uri != null && tableId != null) {
            val outputStream: OutputStream? = try {
                context.contentResolver.openOutputStream(uri)
            } catch (e: Exception) {
                null
            }
            if (outputStream != null) {
                viewModel.handleIcsExport(tableId, outputStream, alarmMinutes)
                // 在文件保存成功后，设置状态以显示分享弹窗。我们保存公共Uri和我们想要的原始文件名。
                showShareDialog = Triple(uri, "text/calendar", filename)
            } else {
                coroutineScope.launch { snackbarHostState.showSnackbar(snackbarCannotSaveFile) }
            }
        } else if (uri == null) {
            coroutineScope.launch { snackbarHostState.showSnackbar(snackbarFileSaveCanceled) }
        }
        pendingExportIcsTableId = null
        pendingAlarmMinutes = null
    }

    // WakeUp 文件导出另存为启动器
    val wakeupExportLauncher = rememberLauncherForActivityResult(CreateWakeupDocumentContract()) { uri: Uri? ->
        val filename = pendingWakeupFileName
        if (uri != null && filename != null) {
            val shareTempDir = File(context.cacheDir, "share_temp")
            val sourceFile = File(shareTempDir, filename)
            val success = try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    sourceFile.inputStream().use { input -> input.copyTo(output) }
                }
                true
            } catch (e: Exception) {
                false
            }
            if (success) {
                showShareDialog = Triple(uri, "*/*", filename)
            } else {
                coroutineScope.launch { snackbarHostState.showSnackbar(snackbarCannotSaveFile) }
            }
        } else if (uri == null) {
            coroutineScope.launch { snackbarHostState.showSnackbar(snackbarFileSaveCanceled) }
        }
        pendingWakeupFileName = null
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.onSyncToCalendarClick()
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(snackbarCalendarPermissionDenied)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ConversionEvent.LaunchImportFilePicker -> {
                    pendingImportTableId = event.tableId
                    importLauncher.launch(Unit)
                }
                is ConversionEvent.LaunchExportFileCreator -> {
                    pendingExportJsonContent = event.jsonContent
                    val now = LocalDateTime.now()
                    val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                    val timestamp = now.format(formatter)
                    val filename = "classflow_$timestamp.json"
                    exportLauncher.launch(filename)
                }
                is ConversionEvent.LaunchExportIcsFileCreator -> {
                    pendingExportIcsTableId = event.tableId
                    pendingAlarmMinutes = event.alarmMinutes
                    val now = LocalDateTime.now()
                    val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                    val timestamp = now.format(formatter)
                    val filename = "classflow_$timestamp.ics"
                    icsExportLauncher.launch(filename)
                }
                is ConversionEvent.PromptWakeupExportAction -> {
                    showWakeupActionDialog = event.fileName
                }
                is ConversionEvent.OpenWakeupFile -> {
                    val shareTempDir = File(context.cacheDir, "share_temp")
                    val file = File(shareTempDir, event.fileName)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    WakeupExportTool.openWithWakeup(context, uri, event.fileName)
                }
                is ConversionEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    val backgroundBrush = ThemeGradients.backgroundGradient()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Back button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navBridge.navigateUp() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.a11y_back)
                    )
                }
                Text(
                    text = stringResource(R.string.title_conversion),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            SettingsCard(title = stringResource(R.string.section_file_conversion)) {
                SettingTile(
                    icon = Icons.Rounded.FileDownload,
                    title = stringResource(R.string.item_import_course_file),
                    subtitle = stringResource(R.string.desc_import_json),
                    onClick = { viewModel.onImportClick() }
                )
                SettingDivider()
                SettingTile(
                    icon = Icons.Rounded.FileUpload,
                    title = stringResource(R.string.item_export_course_file),
                    subtitle = stringResource(R.string.desc_export_json_with_config),
                    onClick = { viewModel.onExportClick() }
                )
                SettingDivider()
                SettingTile(
                    icon = Icons.Rounded.CalendarMonth,
                    title = stringResource(R.string.item_export_ics_file),
                    subtitle = stringResource(R.string.desc_export_ics_with_alarm),
                    onClick = { if (!uiState.isLoading) viewModel.onExportIcsClick() },
                    trailingContent = if (uiState.isLoading) {
                        { CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp) }
                    } else null
                )
                SettingDivider()
                SettingTile(
                    icon = Icons.AutoMirrored.Rounded.SendToMobile,
                    title = stringResource(R.string.item_export_wakeup_file),
                    subtitle = stringResource(R.string.desc_export_wakeup_file),
                    onClick = { if (!uiState.isLoading) viewModel.onExportWakeupClick() },
                    trailingContent = if (uiState.isLoading) {
                        { CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp) }
                    } else null
                )
                SettingDivider()
                SettingTile(
                    icon = Icons.Rounded.Image,
                    title = stringResource(R.string.item_export_schedule_image),
                    subtitle = stringResource(R.string.desc_export_schedule_image),
                    onClick = { if (!uiState.isLoading) viewModel.onExportImageClick() },
                    trailingContent = if (uiState.isLoading) {
                        { CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp) }
                    } else null
                )
            }

            // 教务导入：仅保留 WBU 入口。
            // 多校 SchoolSelection / 适配仓库 UpdateRepo 代码与路由仍保留（无 UI 入口），降低下次上游 merge 冲突面。
            SettingsCard(title = stringResource(R.string.section_school_import)) {
                SettingTile(
                    icon = Icons.Rounded.School,
                    title = stringResource(R.string.title_wbu_sync_schedule),
                    subtitle = stringResource(R.string.desc_wbu_sync_schedule),
                    onClick = {
                        navBridge.navigate(Destination.WebView(initialUrl = "https://jwxt.wbu.edu.cn", assetJsPath = "WBU/wbu_chaoxing.js"))
                    }
                )
            }

            SettingsCard(title = stringResource(R.string.section_sync)) {
                SettingTile(
                    icon = Icons.Rounded.EventAvailable,
                    title = stringResource(R.string.item_sync_to_system_calendar),
                    subtitle = stringResource(R.string.desc_sync_to_system_calendar),
                    onClick = {
                        if (!uiState.isLoading) {
                            calendarPermissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.READ_CALENDAR,
                                    android.Manifest.permission.WRITE_CALENDAR
                                )
                            )
                        }
                    },
                    trailingContent = if (uiState.isLoading) {
                        { CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp) }
                    } else null
                )
            }

            Spacer(modifier = Modifier.height(DockSafeBottomPadding))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }

    if (uiState.showImportTableDialog) {
        CourseTablePickerDialog(
            title = stringResource(R.string.dialog_title_select_import_table),
            onDismissRequest = { viewModel.dismissDialog() },
            onTableSelected = { selectedTable ->
                viewModel.onImportTableSelected(selectedTable.id)
            }
        )
    }

    if (uiState.showExportTableDialog) {
        when (uiState.exportType) {
            ExportType.JSON -> {
                CourseTablePickerDialog(
                    title = stringResource(R.string.dialog_title_select_export_table),
                    onDismissRequest = { viewModel.dismissDialog() },
                    onTableSelected = { selectedTable ->
                        viewModel.onExportTableSelected(selectedTable.id, null)
                    }
                )
            }
            ExportType.ICS -> {
                IcsExportDialog(
                    onDismissRequest = { viewModel.dismissDialog() },
                    onConfirm = { tableId, alarmMinutes ->
                        viewModel.onExportTableSelected(tableId, alarmMinutes)
                    }
                )
            }
            ExportType.WAKEUP -> {
                CourseTablePickerDialog(
                    title = stringResource(R.string.dialog_title_select_export_table),
                    onDismissRequest = { viewModel.dismissDialog() },
                    onTableSelected = { selectedTable ->
                        viewModel.onExportTableSelected(selectedTable.id, null)
                    }
                )
            }
            ExportType.IMAGE -> {
                ImageExportDialog(
                    onDismissRequest = { viewModel.dismissDialog() },
                    onConfirm = { tableId, showDashedDivider, autoSplitConflict ->
                        viewModel.onExportTableSelected(
                            tableId = tableId,
                            alarmMinutes = null,
                            showDashedDivider = showDashedDivider,
                            autoSplitConflict = autoSplitConflict
                        )
                    }
                )
            }
            else -> {
            }
        }
    }

    if (showWakeupActionDialog != null) {
        val fileName = showWakeupActionDialog!!
        AlertDialog(
            onDismissRequest = { showWakeupActionDialog = null },
            title = { Text(stringResource(R.string.dialog_title_wakeup_export_action)) },
            text = { Text(stringResource(R.string.dialog_desc_wakeup_export_action)) },
            confirmButton = {
                TextButton(onClick = {
                    showWakeupActionDialog = null
                    val shareTempDir = File(context.cacheDir, "share_temp")
                    val file = File(shareTempDir, fileName)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    WakeupExportTool.openWithWakeup(context, uri, fileName)
                }) {
                    Text(stringResource(R.string.action_open_in_wakeup))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showWakeupActionDialog = null
                    pendingWakeupFileName = fileName
                    wakeupExportLauncher.launch(fileName)
                }) {
                    Text(stringResource(R.string.action_save_as_file))
                }
            }
        )
    }

    if (showShareDialog != null) {
        AlertDialog(
            onDismissRequest = { showShareDialog = null },
            title = { Text(dialogTitleFileSaved) },
            text = { Text(dialogTextFileSavedSharePrompt) },
            confirmButton = {
                TextButton(onClick = {
                    val (publicUri, mimeType, defaultFilename) = showShareDialog!!

                    val userDefinedFilename = context.contentResolver.query(
                        publicUri,
                        arrayOf(OpenableColumns.DISPLAY_NAME), // 明确指定要查询的列名
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            // 检查索引是否有效
                            if (nameIndex >= 0) {
                                cursor.getString(nameIndex)
                            } else null
                        } else null
                    } ?: defaultFilename // 如果查询失败，回退到代码中的默认时间戳文件名

                    // 确保 share_temp 目录存在
                    val shareTempDir = File(context.cacheDir, "share_temp")
                    if (!shareTempDir.exists()) {
                        shareTempDir.mkdirs()
                    }

                    val tempFile = File(shareTempDir, userDefinedFilename)

                    try {
                        context.contentResolver.openInputStream(publicUri)?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        coroutineScope.launch { snackbarHostState.showSnackbar(snackbarFileCopyFailedForShare) }
                        showShareDialog = null
                        return@TextButton
                    }

                    // FileProvider 将会根据 tempFile 的名称来设置分享的文件名
                    val shareUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        tempFile
                    )

                    // 使用 FileProvider 的 Uri 来分享
                    shareFile(context, shareUri, mimeType)

                    showShareDialog = null
                }) {
                    Text(actionShare)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showShareDialog = null
                }) {
                    Text(actionCancel)
                }
            }
        )
    }
}

