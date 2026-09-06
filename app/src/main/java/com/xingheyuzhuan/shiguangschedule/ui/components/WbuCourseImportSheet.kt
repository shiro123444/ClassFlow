package com.xingheyuzhuan.shiguangschedule.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AuthForm
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.DynamicCodeSendResult
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.QrStatus
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.SliderCaptchaData
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.SliderCaptchaResult
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuLoginMethod
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.ui.settings.coursetables.ManageCourseTablesViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 通用 WBU 教务直连一键导入课表流程组件。
 * 包含身份认证弹窗 (WbuAuthBottomSheet)、扫码轮询、滑块验证码、短信验证码、
 * 学期选择器 (SemesterPickerDialog)、重复/多教师冲突课程处理，并在完成后自动新建与切换课表。
 */
@Composable
fun WbuCourseImportSheet(
    onDismissRequest: () -> Unit,
    onImportSuccess: ((tableName: String) -> Unit)? = null,
    viewModel: ManageCourseTablesViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()

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
    var semesterCurrentXnxq by remember { mutableStateOf<String?>(null) }
    var semesterSelectDeferred by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }

    var duplicateDialogData by remember { mutableStateOf<WbuSyncEngine.DuplicateGroupInfo?>(null) }
    var duplicateDeferred by remember { mutableStateOf<CompletableDeferred<WbuSyncEngine.DuplicateResolveStrategy?>?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            qrJob?.cancel()
            captchaDeferred?.complete(SliderCaptchaResult.Cancel)
            smsDeferred?.complete(null)
            semesterSelectDeferred?.complete(null)
            duplicateDeferred?.complete(null)
        }
    }

    suspend fun runDirectImportPipeline(engine: WbuSyncEngine, inputSid: String): Boolean {
        isImporting = true
        importStatusMessage = "正在获取可选学期..."
        val options = engine.fetchSemesterOptions()
        val chosenSemester = if (options.isNotEmpty()) {
            val deferred = CompletableDeferred<String?>()
            semesterOptions = options
            semesterCurrentXnxq = engine.systemCurrentXnxq ?: engine.lastResolvedXnxq
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
        Toast.makeText(context, "课表【$newTableName】导入成功！", Toast.LENGTH_LONG).show()
        onImportSuccess?.invoke(newTableName)
        onDismissRequest()
        return true
    }

    val startQrFlow: (Boolean) -> Unit = { useVpn ->
        qrJob?.cancel()
        val engine = WbuSyncEngine(context = context, useVpn = useVpn)
        qrImportState = QrUiState(qrContent = null, phase = QrPhase.GENERATING, statusText = context.getString(R.string.status_qr_fetching))
        coroutineScope.launch {
            val session = engine.startQrLogin("IMPORT_QR")
            if (session == null) {
                qrImportState = QrUiState(qrContent = null, phase = QrPhase.ERROR, statusText = context.getString(R.string.status_qr_fetch_failed))
                return@launch
            }
            qrImportState = QrUiState(qrContent = session.content, phase = QrPhase.WAIT, statusText = context.getString(R.string.status_scan_qr_to_login))
            qrJob = coroutineScope.launch {
                while (true) {
                    delay(2000)
                    when (engine.pollQrStatus(session)) {
                        QrStatus.WAIT -> qrImportState = QrUiState(qrContent = session.content, phase = QrPhase.WAIT, statusText = context.getString(R.string.status_scan_qr_to_login))
                        QrStatus.CONFIRM -> qrImportState = QrUiState(qrContent = session.content, phase = QrPhase.SCANNED, statusText = context.getString(R.string.status_qr_scanned))
                        QrStatus.SUCCESS -> {
                            qrImportState = QrUiState(qrContent = session.content, phase = QrPhase.CONFIRMING, statusText = context.getString(R.string.status_qr_confirming))
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
                            qrImportState = QrUiState(qrContent = session.content, phase = QrPhase.EXPIRED, statusText = context.getString(R.string.status_qr_expired))
                            return@launch
                        }
                        QrStatus.ERROR -> {
                            qrImportState = QrUiState(qrContent = session.content, phase = QrPhase.ERROR, statusText = context.getString(R.string.status_qr_query_failed))
                        }
                    }
                }
            }
        }
    }

    // 主登录 Sheet
    WbuAuthBottomSheet(
        onDismissRequest = {
            if (!isImporting) {
                qrJob?.cancel()
                qrImportState = null
                onDismissRequest()
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

    // 学期选择弹窗
    semesterSelectDeferred?.let { deferred ->
        SemesterPickerDialog(
            options = semesterOptions,
            currentXnxq = semesterCurrentXnxq,
            confirmButtonText = stringResource(R.string.action_create_and_import),
            onConfirm = { chosen ->
                deferred.complete(chosen)
                semesterSelectDeferred = null
            },
            onDismissRequest = {
                deferred.complete(null)
                semesterSelectDeferred = null
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
                stringResource(R.string.format_dup_dialog_title, dupInfo.groupCount, dupInfo.totalConflictCourses)
            dupInfo.hasMultiTeacher ->
                stringResource(R.string.format_dup_dialog_title_teacher, dupInfo.groupCount, dupInfo.totalConflictCourses)
            else ->
                stringResource(R.string.format_dup_dialog_title_identical, dupInfo.groupCount, dupInfo.totalConflictCourses)
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
                        text = stringResource(R.string.desc_dup_course_dialog),
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
                                    text = stringResource(R.string.action_merge_teachers_rec),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = stringResource(R.string.format_dup_sample_teacher, dupInfo.sampleCourseName, dupInfo.sampleTeacherSummary),
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
                                    text = stringResource(R.string.action_keep_one_course),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = stringResource(R.string.format_dup_sample_keep_one, dupInfo.sampleCourseName),
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
                            text = stringResource(R.string.format_keep_all_courses, dupInfo.totalConflictCourses),
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
