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
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuAuthTipsScenario
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet
import com.xingheyuzhuan.shiguangschedule.ui.settings.coursetables.ManageCourseTablesViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * 通用 WBU 教务课表导入流程组件。
 *
 * 登录部分复用 [WbuCampusAuthSheet]（内建 WebVPN 门户登录、短信二次验证、滑块验证码与二维码），
 * 本组件只负责导入管线、学期选择器与重复/多教师冲突课程处理。
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

    var semesterOptions by remember { mutableStateOf<List<WbuSyncEngine.WbuSemesterOption>>(emptyList()) }
    var semesterCurrentXnxq by remember { mutableStateOf<String?>(null) }
    var semesterSelectDeferred by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }

    var duplicateDialogData by remember { mutableStateOf<WbuSyncEngine.DuplicateGroupInfo?>(null) }
    var duplicateDeferred by remember { mutableStateOf<CompletableDeferred<WbuSyncEngine.DuplicateResolveStrategy?>?>(null) }

    DisposableEffect(Unit) {
        onDispose {
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

        val effectiveSid = engine.lastResolvedStudentId?.takeIf { it.isNotBlank() } ?: sid
        val finalTableName = if (newTableName == "我的课表" && chosenSemester.isNotBlank() && effectiveSid.isNotBlank()) {
            WbuSyncEngine.computeNonConflictingTableName(chosenSemester, effectiveSid, uiState.courseTables)
        } else {
            newTableName
        }
        viewModel.updateTableMeta(
            tableId = newTable.id,
            name = finalTableName,
            studentId = effectiveSid.takeIf { it.isNotBlank() },
            semesterCode = chosenSemester.takeIf { it.isNotBlank() }
        )
        if (effectiveSid.isNotBlank()) {
            WbuSyncEngine.setSavedStudentId(context, effectiveSid)
        }

        isImporting = false
        importStatusMessage = ""
        Toast.makeText(context, "课表【$finalTableName】导入成功！", Toast.LENGTH_LONG).show()
        onImportSuccess?.invoke(finalTableName)
        onDismissRequest()
        return true
    }

    /** 登录成功后：用刚建立的会话来跑导入管线。 */
    val startImportAfterLogin: () -> Unit = {
        coroutineScope.launch {
            try {
                isImporting = true
                importStatusMessage = context.getString(R.string.status_fetching_schedule)
                importErrorMessage = ""
                val useVpn = WbuSyncEngine.getSavedUseVpn(context) ?: false
                val engine = WbuSyncEngine(context = context, useVpn = useVpn)
                runDirectImportPipeline(engine, WbuSyncEngine.getSavedStudentId(context))
            } catch (e: Exception) {
                isImporting = false
                importStatusMessage = ""
                importErrorMessage = "导入异常: ${e.message}"
            }
        }
    }

    WbuCampusAuthSheet(
        onDismiss = { if (!isImporting) onDismissRequest() },
        onLoginSuccess = { startImportAfterLogin() },
        dismissOnSuccess = false,
        hideImportPreferences = false,
        tipsScenario = WbuAuthTipsScenario.IMPORT,
        primaryButtonText = stringResource(R.string.action_one_tap_sync),
        loadingButtonText = stringResource(R.string.status_fetching_schedule),
        externalLoading = isImporting,
        externalStatusMessage = importStatusMessage,
        externalErrorMessage = importErrorMessage
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
