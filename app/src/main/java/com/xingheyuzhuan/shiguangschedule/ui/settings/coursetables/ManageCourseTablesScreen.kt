package com.xingheyuzhuan.shiguangschedule.ui.settings.coursetables

import android.app.Application
import androidx.hilt.navigation.compose.hiltViewModel
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuLoginMethod
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthMode
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.SliderCaptchaData
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.SliderCaptchaResult
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AuthForm
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.DynamicCodeSendResult
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.QrSession
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.QrStatus
import com.xingheyuzhuan.shiguangschedule.ui.components.WbuAuthBottomSheet
import com.xingheyuzhuan.shiguangschedule.ui.components.SliderCaptchaDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.VpnSmsCodeDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.QrUiState
import com.xingheyuzhuan.shiguangschedule.ui.components.QrPhase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageCourseTablesScreen(
    navBridge: NavBridge,
    viewModel: ManageCourseTablesViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    val uiState by viewModel.uiState.collectAsState()

    var showAddTableDialog by remember { mutableStateOf(false) }
    var newTableName by remember { mutableStateOf("") }

    var showEditTableDialog by remember { mutableStateOf(false) }
    var editingTableInfo by remember { mutableStateOf<CourseTable?>(null) }
    var editedTableName by remember { mutableStateOf("") }
    var editedSemesterCode by remember { mutableStateOf("") }
    var editedStudentId by remember { mutableStateOf("") }
    var editedIsArchived by remember { mutableStateOf(false) }

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var tableToDelete by remember { mutableStateOf<CourseTable?>(null) }

    // 教务导入相关状态
    val coroutineScope = rememberCoroutineScope()
    var showImportSheet by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var importStatusMessage by remember { mutableStateOf("") }
    var importErrorMessage by remember { mutableStateOf("") }
    var importMethod by remember { mutableStateOf(WbuLoginMethod.PASSWORD) }
    var qrImportState by remember { mutableStateOf<QrUiState?>(null) }
    var qrJob by remember { mutableStateOf<Job?>(null) }
    var captchaDialogData by remember { mutableStateOf<SliderCaptchaData?>(null) }
    var captchaDeferred by remember { mutableStateOf<CompletableDeferred<SliderCaptchaResult?>?>(null) }
    var smsDialogPhone by remember { mutableStateOf<String?>(null) }
    var smsDeferred by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }
    var smsError by remember { mutableStateOf<String?>(null) }
    var smsVerifying by remember { mutableStateOf(false) }
    var smsDialogIsStillValid by remember { mutableStateOf(false) }
    var smsDialogSendInterval by remember { mutableStateOf(60) }
    var smsDialogPromptText by remember { mutableStateOf("") }
    var dynamicPrep by remember { mutableStateOf<AuthForm?>(null) }

    var semesterOptions by remember { mutableStateOf<List<WbuSyncEngine.WbuSemesterOption>>(emptyList()) }
    var semesterSelectDeferred by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }

    var duplicateDialogData by remember { mutableStateOf<WbuSyncEngine.DuplicateGroupInfo?>(null) }
    var duplicateDeferred by remember { mutableStateOf<CompletableDeferred<WbuSyncEngine.DuplicateResolveStrategy?>?>(null) }

    val titleManageTables = stringResource(R.string.title_manage_course_tables)
    val a11yBack = stringResource(R.string.a11y_back)
    val a11yAddNewTable = stringResource(R.string.a11y_add_new_table)
    val textNoTablesHint = stringResource(R.string.text_no_tables_hint)
    val dialogTitleAddTable = stringResource(R.string.dialog_title_add_table)
    val labelTableName = stringResource(R.string.label_table_name)
    val actionAdd = stringResource(R.string.action_add)
    val actionCancel = stringResource(R.string.action_cancel)
    val toastNameEmpty = stringResource(R.string.toast_name_empty)
    val toastSwitchSuccess = stringResource(R.string.toast_switch_table_success)
    val toastAddSuccess = stringResource(R.string.toast_add_table_success)
    val dialogTitleEditTable = stringResource(R.string.dialog_title_edit_table)
    val a11ySave = stringResource(R.string.a11y_save)
    val toastEditSuccess = stringResource(R.string.toast_edit_table_success)
    val dialogTitleConfirmDelete = stringResource(R.string.confirm_delete)
    val dialogTextConfirmDelete = stringResource(R.string.dialog_text_confirm_delete)
    val actionDelete = stringResource(R.string.a11y_delete) // 复用 a11y_delete 作为按钮文本
    val toastDeleteSuccess = stringResource(R.string.toast_delete_table_success)
    val toastDeleteLastFailed = stringResource(R.string.toast_delete_last_table_failed)
    val a11yImportFromJwxt = stringResource(R.string.a11y_import_from_jwxt)

    suspend fun runDirectImportPipeline(engine: WbuSyncEngine, inputSid: String): Boolean {
        isImporting = true
        importStatusMessage = "正在获取可选学期..."
        val options = engine.fetchSemesterOptions()
        val chosenSemester = if (options.isNotEmpty()) {
            val deferred = CompletableDeferred<String?>()
            semesterOptions = options
            semesterSelectDeferred = deferred
            val chosen = deferred.await()
            if (chosen == null) {
                isImporting = false
                importStatusMessage = ""
                return false
            }
            chosen
        } else {
            engine.lastResolvedXnxq ?: ""
        }

        if (chosenSemester.isBlank()) {
            importErrorMessage = "未能识别学期"
            isImporting = false
            importStatusMessage = ""
            return false
        }

        importStatusMessage = "正在获取【$chosenSemester】课程数据..."
        val sid = engine.lastResolvedStudentId ?: inputSid.ifBlank { WbuSyncEngine.getSavedStudentId(context) }
        val candidateName = chosenSemester.ifBlank { "未命名课表" }
        val hasConflictWithOtherSid = uiState.courseTables.any {
            it.name == candidateName && it.studentId != null && it.studentId != sid
        }
        val newTableName = if (hasConflictWithOtherSid && sid.isNotBlank()) {
            "$candidateName ($sid)"
        } else {
            candidateName
        }
        val newTable = viewModel.createAndSwitchTable(
            name = newTableName,
            studentId = sid,
            semesterCode = chosenSemester
        )

        val coursesRaw = engine.fetchCourseData(newTable.id, chosenSemester)
        if (coursesRaw.isNullOrEmpty()) {
            importErrorMessage = "该学期未获取到课表数据（可能尚未排课）"
            isImporting = false
            importStatusMessage = ""
            return false
        }

        val dupInfo = WbuSyncEngine.analyzeDuplicateCourses(coursesRaw)
        val courses = if (dupInfo != null) {
            val def = CompletableDeferred<WbuSyncEngine.DuplicateResolveStrategy?>()
            duplicateDialogData = dupInfo
            duplicateDeferred = def
            val strategy = def.await()
            if (strategy == null) {
                isImporting = false
                importStatusMessage = ""
                return false
            }
            WbuSyncEngine.resolveDuplicateCourses(coursesRaw, strategy)
        } else {
            coursesRaw
        }

        importStatusMessage = "正在写入课表..."
        viewModel.importCourses(courses, newTable.id)
        val cfg = engine.fetchSemesterConfig(xnxq = chosenSemester, xqdm = engine.lastResolvedXqdm)
        viewModel.applySemesterConfig(cfg, newTable.id)

        isImporting = false
        importStatusMessage = ""
        showImportSheet = false
        Toast.makeText(context, "课表【$newTableName】导入成功！", Toast.LENGTH_LONG).show()
        return true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleManageTables) },
                navigationIcon = {
                    IconButton(onClick = { navBridge.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = a11yBack)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FloatingActionButton(
                    onClick = {
                        importErrorMessage = ""
                        importStatusMessage = ""
                        showImportSheet = true
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = a11yImportFromJwxt)
                }
                FloatingActionButton(onClick = { showAddTableDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = a11yAddNewTable)
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (uiState.courseTables.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = textNoTablesHint, style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.courseTables) { tableInfo ->
                        val isSelected = tableInfo.id == uiState.currentActiveTableId
                        CourseTableCard(
                            tableInfo = tableInfo,
                            isSelected = isSelected,
                            onDeleteClick = {
                                tableToDelete = it
                                showDeleteConfirmDialog = true
                            },
                            onEditClick = {
                                editingTableInfo = it
                                editedTableName = it.name
                                editedSemesterCode = it.semesterCode.orEmpty()
                                editedStudentId = it.studentId.orEmpty()
                                editedIsArchived = it.isArchived
                                showEditTableDialog = true
                            },
                            onCardClick = {
                                viewModel.switchCourseTable(it.id)
                                Toast.makeText(context, toastSwitchSuccess.format(it.name), Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }

        if (showAddTableDialog) {
            AlertDialog(
                onDismissRequest = { showAddTableDialog = false; newTableName = "" },
                title = { Text(dialogTitleAddTable) },
                text = {
                    OutlinedTextField(
                        value = newTableName,
                        onValueChange = { newTableName = it },
                        label = { Text(labelTableName) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newTableName.isNotBlank()) {
                                // 直接将字符串名称传递给 ViewModel
                                viewModel.createNewCourseTable(newTableName)
                                Toast.makeText(context, toastAddSuccess.format(newTableName), Toast.LENGTH_SHORT).show()
                                showAddTableDialog = false
                                newTableName = ""
                            } else {
                                Toast.makeText(context, toastNameEmpty, Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text(actionAdd)
                    }
                },
                dismissButton = {
                    Button(onClick = { showAddTableDialog = false; newTableName = "" }) {
                        Text(actionCancel)
                    }
                }
            )
        }

        if (showEditTableDialog && editingTableInfo != null) {
            AlertDialog(
                onDismissRequest = {
                    showEditTableDialog = false
                    editingTableInfo = null
                    editedTableName = ""
                    editedSemesterCode = ""
                    editedStudentId = ""
                    editedIsArchived = false
                },
                title = { Text(dialogTitleEditTable) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = editedTableName,
                            onValueChange = { editedTableName = it },
                            label = { Text(labelTableName) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editedSemesterCode,
                            onValueChange = { editedSemesterCode = it },
                            label = { Text(stringResource(R.string.label_semester_code)) },
                            placeholder = { Text("例如 2026-2027-1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editedStudentId,
                            onValueChange = { editedStudentId = it },
                            label = { Text(stringResource(R.string.label_student_id)) },
                            placeholder = { Text("例如 260593099") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editedIsArchived = !editedIsArchived }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = editedIsArchived,
                                onCheckedChange = { editedIsArchived = it }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.label_archive_table),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(R.string.desc_archive_table),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (editedTableName.isNotBlank()) {
                                editingTableInfo?.let { tableToEdit ->
                                    val updatedTable = tableToEdit.copy(
                                        name = editedTableName.trim(),
                                        semesterCode = editedSemesterCode.trim().ifEmpty { null },
                                        studentId = editedStudentId.trim().ifEmpty { null },
                                        isArchived = editedIsArchived
                                    )
                                    viewModel.updateCourseTable(updatedTable)
                                    Toast.makeText(context, toastEditSuccess, Toast.LENGTH_SHORT).show()
                                    showEditTableDialog = false
                                    editingTableInfo = null
                                    editedTableName = ""
                                    editedSemesterCode = ""
                                    editedStudentId = ""
                                    editedIsArchived = false
                                }
                            } else {
                                Toast.makeText(context, toastNameEmpty, Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text(a11ySave)
                    }
                },
                dismissButton = {
                    Button(onClick = {
                        showEditTableDialog = false
                        editingTableInfo = null
                        editedTableName = ""
                        editedSemesterCode = ""
                        editedStudentId = ""
                        editedIsArchived = false
                    }) {
                        Text(actionCancel)
                    }
                }
            )
        }

        if (showDeleteConfirmDialog && tableToDelete != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false; tableToDelete = null },
                title = { Text(dialogTitleConfirmDelete) },
                text = { Text(dialogTextConfirmDelete.format(tableToDelete?.name ?: "")) },
                confirmButton = {
                    Button(
                        onClick = {
                            if (uiState.courseTables.size > 1) { // 使用 ViewModel 的数据进行检查
                                tableToDelete?.let {
                                    viewModel.deleteCourseTable(it)
                                    Toast.makeText(context, toastDeleteSuccess.format(it.name), Toast.LENGTH_SHORT).show()
                                }
                                showDeleteConfirmDialog = false
                                tableToDelete = null
                            } else {
                                Toast.makeText(context, toastDeleteLastFailed, Toast.LENGTH_SHORT).show()
                                showDeleteConfirmDialog = false
                                tableToDelete = null
                            }
                        }
                    ) {
                        Text(actionDelete)
                    }
                },
                dismissButton = {
                    Button(onClick = { showDeleteConfirmDialog = false; tableToDelete = null }) {
                        Text(actionCancel)
                    }
                }
            )
        }

        // 教务导入弹窗与认证
        if (showImportSheet) {
            val startQrFlow: (Boolean) -> Unit = { useVpn ->
                qrJob?.cancel()
                val engine = WbuSyncEngine(context = context, useVpn = useVpn)
                qrImportState = QrUiState(qrContent = null, phase = QrPhase.GENERATING, statusText = "正在获取二维码...")
                coroutineScope.launch {
                    val session = engine.startQrLogin("IMPORT_QR")
                    if (session == null) {
                        qrImportState = QrUiState(qrContent = null, phase = QrPhase.ERROR, statusText = "获取二维码失败，点二维码重试")
                        return@launch
                    }
                    qrImportState = QrUiState(qrContent = session.content, phase = QrPhase.WAIT, statusText = "请扫码登录")
                    qrJob = coroutineScope.launch {
                        while (true) {
                            delay(2000)
                            when (val st = engine.pollQrStatus(session)) {
                                QrStatus.WAIT -> qrImportState = QrUiState(qrContent = session.content, phase = QrPhase.WAIT, statusText = "请扫码登录")
                                QrStatus.CONFIRM -> qrImportState = QrUiState(qrContent = session.content, phase = QrPhase.SCANNED, statusText = "已扫码，请在手机上确认")
                                QrStatus.SUCCESS -> {
                                    qrImportState = QrUiState(qrContent = session.content, phase = QrPhase.CONFIRMING, statusText = "确认成功，正在登录...")
                                    try {
                                        val qrOk = engine.completeQrLogin(
                                            session = session,
                                            flowTag = "IMPORT_QR",
                                            vpnPasswordProvider = { null },
                                            smsCodeProvider = { maskedPhone, isStillValid, sendInterval, promptText ->
                                                val deferred = CompletableDeferred<String?>()
                                                smsError = null
                                                smsVerifying = false
                                                smsDeferred = deferred
                                                smsDialogPhone = maskedPhone
                                                smsDialogIsStillValid = isStillValid
                                                smsDialogSendInterval = sendInterval
                                                smsDialogPromptText = promptText
                                                deferred.await()
                                            }
                                        )
                                        if (qrOk) {
                                            runDirectImportPipeline(engine, WbuSyncEngine.getSavedStudentId(context))
                                        } else {
                                            importErrorMessage = "扫码登录失败"
                                        }
                                    } finally {
                                        isImporting = false
                                    }
                                    return@launch
                                }
                                QrStatus.EXPIRED -> {
                                    qrImportState = QrUiState(qrContent = session.content, phase = QrPhase.EXPIRED, statusText = "二维码已过期，点二维码刷新")
                                    return@launch
                                }
                                QrStatus.ERROR -> {
                                    qrImportState = QrUiState(qrContent = session.content, phase = QrPhase.ERROR, statusText = "查询状态失败，点二维码重试")
                                }
                            }
                        }
                    }
                }
            }

            WbuAuthBottomSheet(
                onDismissRequest = {
                    if (!isImporting) {
                        qrJob?.cancel()
                        qrImportState = null
                        showImportSheet = false
                    }
                },
                isLoading = isImporting,
                statusMessage = importStatusMessage,
                errorMessage = importErrorMessage,
                initialStudentId = WbuSyncEngine.getSavedStudentId(context),
                initialUseVpn = WbuSyncEngine.getSavedUseVpn(context) ?: false,
                hideSelectSemesterSwitch = true,
                method = importMethod,
                onMethodChange = { importMethod = it },
                qrState = qrImportState,
                onStartQr = { useVpn -> startQrFlow(useVpn) },
                onRefreshQr = { useVpn -> startQrFlow(useVpn) },
                onPasswordLogin = { sid, pwd, useVpn, authMode ->
                    coroutineScope.launch {
                        try {
                            isImporting = true
                            importStatusMessage = "正在登录..."
                            importErrorMessage = ""
                            val engine = WbuSyncEngine(context = context, useVpn = useVpn)
                            val ok = engine.login(
                                sid, pwd, authMode = authMode,
                                captchaProvider = { captcha ->
                                    val def = CompletableDeferred<SliderCaptchaResult?>()
                                    captchaDeferred = def
                                    captchaDialogData = captcha
                                    def.await() ?: SliderCaptchaResult.Cancel
                                }
                            )
                            if (ok) {
                                runDirectImportPipeline(engine, sid)
                            } else {
                                isImporting = false
                                importErrorMessage = engine.lastLocalLoginError?.takeIf { it.isNotBlank() } ?: "登录失败，请检查账号密码"
                            }
                        } catch (e: Exception) {
                            isImporting = false
                            importErrorMessage = "登录异常: ${e.message}"
                        }
                    }
                },
                onDynamicCodeLogin = { sid, code, useVpn ->
                    coroutineScope.launch {
                        try {
                            isImporting = true
                            importStatusMessage = "正在登录..."
                            importErrorMessage = ""
                            val engine = WbuSyncEngine(context = context, useVpn = useVpn)
                            val prep = dynamicPrep ?: engine.obtainDynamicCodeForm("IMPORT_DYNAMIC")
                            if (prep == null) {
                                isImporting = false
                                importErrorMessage = "无法获取登录参数，请重试"
                                return@launch
                            }
                            val res = engine.dynamicCodeLogin(
                                sid.trim(), code, prep, "IMPORT_DYNAMIC",
                                vpnPasswordProvider = { null },
                                smsCodeProvider = { maskedPhone, isStillValid, sendInterval, promptText ->
                                    val def = CompletableDeferred<String?>()
                                    smsError = null
                                    smsVerifying = false
                                    smsDeferred = def
                                    smsDialogPhone = maskedPhone
                                    smsDialogIsStillValid = isStillValid
                                    smsDialogSendInterval = sendInterval
                                    smsDialogPromptText = promptText
                                    def.await()
                                }
                            )
                            if (res.success) {
                                runDirectImportPipeline(engine, sid)
                            } else {
                                isImporting = false
                                importErrorMessage = res.message.ifBlank { "验证码登录失败" }
                            }
                        } catch (e: Exception) {
                            isImporting = false
                            importErrorMessage = "动态码登录失败: ${e.message}"
                        }
                    }
                },
                onSendDynamicCode = { sid, useVpn ->
                    val engine = WbuSyncEngine(context = context, useVpn = useVpn)
                    val result = engine.sendDynamicCode(
                        sid.trim(), "IMPORT_DYNAMIC",
                        captchaProvider = { captcha ->
                            val def = CompletableDeferred<SliderCaptchaResult?>()
                            captchaDeferred = def
                            captchaDialogData = captcha
                            def.await() ?: SliderCaptchaResult.Cancel
                        }
                    )
                    dynamicPrep = (result as? DynamicCodeSendResult.Success)?.prep
                    result
                }
            )
        }

        // 学期选择弹窗（管理界面导入流程）
        semesterSelectDeferred?.let { deferred ->
            var selectedValue by remember(semesterOptions) {
                mutableStateOf(semesterOptions.firstOrNull()?.value.orEmpty())
            }
            AlertDialog(
                onDismissRequest = {
                    deferred.complete(null)
                    semesterSelectDeferred = null
                },
                title = { Text("选择导入的学年学期") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        semesterOptions.forEach { opt ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedValue = opt.value }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedValue == opt.value,
                                    onClick = { selectedValue = opt.value }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = opt.text,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deferred.complete(selectedValue)
                            semesterSelectDeferred = null
                        },
                        enabled = selectedValue.isNotBlank()
                    ) {
                        Text("确定新建导入")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            deferred.complete(null)
                            semesterSelectDeferred = null
                        }
                    ) {
                        Text("取消")
                    }
                }
            )
        }

        // 统一认证滑块验证码弹窗
        captchaDialogData?.let { captchaData ->
            SliderCaptchaDialog(
                captcha = captchaData,
                onSubmit = { result ->
                    captchaDeferred?.complete(result)
                    captchaDialogData = null
                    captchaDeferred = null
                },
                onDismiss = {
                    captchaDeferred?.complete(SliderCaptchaResult.Cancel)
                    captchaDialogData = null
                    captchaDeferred = null
                }
            )
        }

        // 短信验证码弹窗
        if (smsDialogPhone != null) {
            VpnSmsCodeDialog(
                maskedPhone = smsDialogPhone!!,
                promptText = smsDialogPromptText,
                isStillValid = smsDialogIsStillValid,
                sendInterval = smsDialogSendInterval,
                errorMessage = smsError,
                isVerifying = smsVerifying,
                onSubmit = { code: String ->
                    smsVerifying = true
                    smsDeferred?.complete(code)
                },
                onResend = {},
                onDismiss = {
                    smsDeferred?.complete(null)
                    smsDialogPhone = null
                    smsDeferred = null
                    smsVerifying = false
                    smsError = null
                }
            )
        }

        // 重复课程冲突处理弹窗（多教师/相同课程）
        duplicateDialogData?.let { dupInfo ->
            var selectedStrategy by remember(dupInfo) {
                mutableStateOf(
                    if (dupInfo.hasMultiTeacher) WbuSyncEngine.DuplicateResolveStrategy.MERGE_TEACHERS
                    else WbuSyncEngine.DuplicateResolveStrategy.KEEP_ONE
                )
            }
            val titleText = when {
                dupInfo.hasIdentical && dupInfo.hasMultiTeacher ->
                    "重复课程处理（${dupInfo.groupCount} 组 / ${dupInfo.totalConflictCourses} 门）"
                dupInfo.hasMultiTeacher ->
                    "多教师重复课程处理（${dupInfo.groupCount} 组 / ${dupInfo.totalConflictCourses} 门）"
                else ->
                    "完全相同的重复课程处理（${dupInfo.groupCount} 组 / ${dupInfo.totalConflictCourses} 门）"
            }

            AlertDialog(
                onDismissRequest = {
                    duplicateDeferred?.complete(null)
                    duplicateDialogData = null
                    duplicateDeferred = null
                },
                title = { Text(titleText) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "检测到部分课程在同一时间、地点被分为多条记录。请选择处理方式：",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        if (dupInfo.hasMultiTeacher) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedStrategy = WbuSyncEngine.DuplicateResolveStrategy.MERGE_TEACHERS }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedStrategy == WbuSyncEngine.DuplicateResolveStrategy.MERGE_TEACHERS,
                                    onClick = { selectedStrategy = WbuSyncEngine.DuplicateResolveStrategy.MERGE_TEACHERS }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "合并教师（推荐）",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = "如：${dupInfo.sampleCourseName} -> ${dupInfo.sampleTeacherSummary}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (dupInfo.hasIdentical || !dupInfo.hasMultiTeacher) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedStrategy = WbuSyncEngine.DuplicateResolveStrategy.KEEP_ONE }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedStrategy == WbuSyncEngine.DuplicateResolveStrategy.KEEP_ONE,
                                    onClick = { selectedStrategy = WbuSyncEngine.DuplicateResolveStrategy.KEEP_ONE }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "只保留一门（去重）",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = "如：${dupInfo.sampleCourseName} 仅保留一条",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedStrategy = WbuSyncEngine.DuplicateResolveStrategy.KEEP_ALL }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedStrategy == WbuSyncEngine.DuplicateResolveStrategy.KEEP_ALL,
                                onClick = { selectedStrategy = WbuSyncEngine.DuplicateResolveStrategy.KEEP_ALL }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "全部保留（${dupInfo.totalConflictCourses} 门）",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            duplicateDeferred?.complete(selectedStrategy)
                            duplicateDialogData = null
                            duplicateDeferred = null
                        }
                    ) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            duplicateDeferred?.complete(null)
                            duplicateDialogData = null
                            duplicateDeferred = null
                        }
                    ) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@Composable
fun CourseTableCard(
    tableInfo: CourseTable,
    isSelected: Boolean,
    onDeleteClick: (CourseTable) -> Unit,
    onEditClick: (CourseTable) -> Unit,
    onCardClick: (CourseTable) -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    val a11yCurrentTable = stringResource(R.string.a11y_current_table)
    val a11yEdit = stringResource(R.string.a11y_edit)
    val a11yDelete = stringResource(R.string.a11y_delete)
    val idPrefix = stringResource(R.string.course_table_id_prefix)
    val createdAtPrefix = stringResource(R.string.course_table_created_at_prefix)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCardClick(tableInfo) },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = tableInfo.name, style = MaterialTheme.typography.titleMedium)
                    if (tableInfo.isArchived) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = stringResource(R.string.tag_archived),
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // 属性标签展示：学期（第一行）与学号（第二行）
                if (!tableInfo.semesterCode.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.DateRange,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = tableInfo.semesterCode,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (!tableInfo.studentId.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.School,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = tableInfo.studentId,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Text(
                    text = createdAtPrefix.format(dateFormatter.format(Date(tableInfo.createdAt))),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = a11yCurrentTable,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                IconButton(onClick = { onEditClick(tableInfo) }) {
                    Icon(Icons.Default.Edit, contentDescription = a11yEdit)
                }
                IconButton(onClick = { onDeleteClick(tableInfo) }) {
                    Icon(Icons.Default.Delete, contentDescription = a11yDelete)
                }
            }
        }
    }
}
