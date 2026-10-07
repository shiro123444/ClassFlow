package com.xingheyuzhuan.shiguangschedule.ui.schedule
import com.xingheyuzhuan.shiguangschedule.ui.theme.LocalIsDarkTheme
import androidx.hilt.navigation.compose.hiltViewModel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import android.util.Log
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuAuthTipsScenario
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet
import com.xingheyuzhuan.shiguangschedule.ui.components.VpnSmsCodeDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.VpnPasswordPromptDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.DockSafeBottomPadding
import com.xingheyuzhuan.shiguangschedule.ui.components.NavigationRailWidth
import com.xingheyuzhuan.shiguangschedule.ui.components.isWideScreen
import com.xingheyuzhuan.shiguangschedule.ui.components.CourseTablePickerDialog
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.VpnFullLoginStatus
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthMode
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.needsRelogin
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.PortalCaptchaData
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.PortalCaptchaResult
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WebVpnClient
import com.xingheyuzhuan.shiguangschedule.ui.components.PortalCaptchaDialog
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuNetworkProbe
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.ScheduleModeProto
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTable
import java.util.Locale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xingheyuzhuan.shiguangschedule.NavBridge
import coil3.compose.AsyncImage
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.navigation.AddEditCourseChannel
import com.xingheyuzhuan.shiguangschedule.navigation.PresetCourseData
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.CourseDetailBottomSheet
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.FloatingCourseBar
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGrid
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGridActions
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGridViewState
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGridStyleComposed
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.WbuSyncActionButton
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.WeekSelectorBottomSheet
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.liquidGlassSurfaceModifier
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.rememberScheduleGridState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.xingheyuzhuan.shiguangschedule.ui.theme.ClassFlowTheme
import com.xingheyuzhuan.shiguangschedule.ui.theme.ThemeGradients
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web.WbuWebLoginAutofillStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import com.xingheyuzhuan.shiguangschedule.ui.components.accessFailureText
import com.xingheyuzhuan.shiguangschedule.ui.components.WbuSyncSession
import com.xingheyuzhuan.shiguangschedule.ui.components.needLoginHintText
import com.xingheyuzhuan.shiguangschedule.ui.components.prepareWbuImportWithSavedPassword
import com.xingheyuzhuan.shiguangschedule.ui.components.prepareWbuSyncSession
import com.xingheyuzhuan.shiguangschedule.ui.components.silentUnifiedAuthLogin

/**
 * 无限时间轴的中值锚点。
 */
private const val INFINITE_PAGER_CENTER = Int.MAX_VALUE / 2

/**
 * 周课表主屏幕组件。
 * 持三周滑动窗口预加载，消除滑动残留与加载闪烁。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WeeklyScheduleScreen(
    navBridge: NavBridge,
    viewModel: WeeklyScheduleViewModel = hiltViewModel(),
    weekTitleModifier: Modifier = Modifier,
    syncButtonModifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onWeekTitleClickIntercept: (() -> Boolean)? = null,
    onSyncButtonClickIntercept: (() -> Boolean)? = null,
    onFloatingModeChange: (Boolean) -> Unit = {} // 悬浮课程模式状态通知（上游：挂起时隐藏底部导航栏）
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val snackbarMsg = stringResource(id = R.string.snackbar_add_course_within_semester)
    val appContext = remember { context.applicationContext }

    LaunchedEffect(Unit) {
        viewModel.setStringProvider { id, args ->
            appContext.resources.getString(id, *args)
        }
    }

    val pagerState = rememberPagerState(
        initialPage = INFINITE_PAGER_CENTER,
        pageCount = { Int.MAX_VALUE }
    )

    // 同步 Pager 状态到 ViewModel (用于标题和当前周逻辑更新)
    LaunchedEffect(pagerState.currentPage, uiState.firstDayOfWeek) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { pageIndex ->
                val offsetWeeks = (pageIndex - INFINITE_PAGER_CENTER).toLong()
                val firstDay = DayOfWeek.of(uiState.firstDayOfWeek)
                val thisMonday = today.with(TemporalAdjusters.previousOrSame(firstDay))
                val targetMonday = thisMonday.plusWeeks(offsetWeeks)
                viewModel.updatePagerDate(targetMonday)
            }
    }

    // UI 交互控制
    var showWeekSelector by remember { mutableStateOf(false) }
    var showWbuAuthDialog by remember { mutableStateOf(false) }
    var isWbuSyncing by remember { mutableStateOf(false) }
    var wbuSyncStatus by remember { mutableStateOf("") }
    var wbuError by remember { mutableStateOf("") }
    var wbuInitialStudentId by remember { mutableStateOf(WbuSyncEngine.getSavedStudentId(appContext)) }
    var selectedBlockForDetail by remember { mutableStateOf<MergedCourseBlock?>(null) }
    var showTableSwitcher by remember { mutableStateOf(false) }
    var isGridHolding by remember { mutableStateOf(false) } // 拖拽编辑期间禁用 Pager 滑页（上游同步）
    val gridScrollState = rememberScrollState()

    // SMS 验证码对话框状态
    var smsDialogPhone by remember { mutableStateOf<String?>(null) }
    var smsDialogIsStillValid by remember { mutableStateOf(false) }
    var smsDialogSendInterval by remember { mutableIntStateOf(0) }
    var smsDialogPromptText by remember { mutableStateOf<String?>(null) }
    var smsDeferred by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }
    var smsVerifying by remember { mutableStateOf(false) }
    var smsError by remember { mutableStateOf<String?>(null) }
    var portalCaptchaData by remember { mutableStateOf<PortalCaptchaData?>(null) }
    var portalCaptchaDeferred by remember { mutableStateOf<CompletableDeferred<PortalCaptchaResult?>?>(null) }
    // 保持 vpnEngine 引用以便 resend
    var activeVpnEngine by remember { mutableStateOf<WbuSyncEngine?>(null) }

    // WebVPN 证书校验异常对话框状态
    var sslIssueMessage by remember { mutableStateOf("") }
    var sslIssueDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    // 教务系统疑似触发超星验证码 → 询问是否跳转 WebView 手动登录
    var showManualLoginPrompt by remember { mutableStateOf(false) }
    var manualLoginUseVpn by remember { mutableStateOf(false) }

    // 校园网不可达时「是否继续」确认对话框状态
    var campusConfirmDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    // WebVPN 统一认证密码询问对话框状态（教务密码模式且无 TWFID 时使用）
    var vpnPasswordDeferred by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }
    var vpnPasswordError by remember { mutableStateOf<String?>(null) }

    // 学期选择弹窗状态
    var semesterSelectOptions by remember { mutableStateOf<List<WbuSyncEngine.WbuSemesterOption>>(emptyList()) }
    var semesterSelectCurrentXnxq by remember { mutableStateOf<String?>(null) }
    var semesterSelectDeferred by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }

    // 学期冲突询问对话框状态
    var conflictDialogData by remember { mutableStateOf<Pair<String, CourseTable>?>(null) }
    var conflictDeferred by remember { mutableStateOf<CompletableDeferred<Int>?>(null) }
    var conflictAutoRenameChecked by remember { mutableStateOf(true) }

    // 重复课程冲突处理状态 (multiTeacher / identical)
    var duplicateCoursesDialogData by remember { mutableStateOf<WbuSyncEngine.DuplicateGroupInfo?>(null) }
    var duplicateCoursesDeferred by remember { mutableStateOf<CompletableDeferred<WbuSyncEngine.DuplicateResolveStrategy?>?>(null) }

    // 学号冲突询问对话框状态 (currentStudentId, loginSid) -> (1: Cancel, 2: Create New, 3: Overwrite)
    var studentIdConflictData by remember { mutableStateOf<Pair<String, String>?>(null) }
    var studentIdConflictDeferred by remember { mutableStateOf<CompletableDeferred<Int>?>(null) }

    // 同步完成后检测到教务有更新学期时的提示对话框
    var newSemesterPromptXnxq by remember { mutableStateOf<String?>(null) }
    var newSemesterPromptEngine by remember { mutableStateOf<WbuSyncEngine?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val composedStyle by remember(uiState.style) {
        derivedStateOf { with(ScheduleGridStyleComposed) { uiState.style.toComposedStyle() } }
    }

    // 悬浮课程模式时通知宿主隐藏底部导航栏（上游同步）
    LaunchedEffect(uiState.floatingCourse != null) {
        onFloatingModeChange(uiState.floatingCourse != null)
    }

    // 悬浮课程（跨周挂起）状态与时长（上游同步）
    val floatingCourse = uiState.floatingCourse
    val floatingDuration by remember(floatingCourse, composedStyle.scheduleMode) {
        derivedStateOf {
            if (floatingCourse != null) {
                val start = floatingCourse.course.startSection?.toFloat() ?: 1f
                val end = floatingCourse.course.endSection?.toFloat() ?: 1f

                if (composedStyle.scheduleMode == ScheduleModeProto.TIME_24H_MODE) {
                    (end - start).coerceAtLeast(1.0f)
                } else {
                    (end - start + 1f).coerceAtLeast(1.0f)
                }
            } else {
                1.0f
            }
        }
    }

    fun parseXnxqScore(xnxq: String?): Long {
        val match = Regex("""(\d{4})-\d{4}-(\d+)""").find(xnxq.orEmpty()) ?: return 0L
        return (match.groupValues[1].toLongOrNull() ?: 0L) * 10 + (match.groupValues[2].toLongOrNull() ?: 0L)
    }

    suspend fun performCourseImportPipeline(
        engine: WbuSyncEngine,
        loginSid: String
    ): Boolean {
        val currentTableId = viewModel.uiState.value.tableId ?: return false
        val currentTable = viewModel.getTableById(currentTableId) ?: return false

        if (currentTable.isArchived) {
            withContext(Dispatchers.Main) {
                wbuError = appContext.getString(R.string.err_table_archived_locked)
            }
            return false
        }

        val effectiveSid = engine.lastResolvedStudentId ?: loginSid.ifBlank { WbuSyncEngine.getSavedStudentId(appContext) }

        // 检查学号一致性（账号冲突拦截）
        var forceCreateNewBySidConflict = false
        if (!currentTable.studentId.isNullOrBlank() && effectiveSid.isNotBlank() && currentTable.studentId != effectiveSid) {
            val sidConflictDef = CompletableDeferred<Int>()
            withContext(Dispatchers.Main) {
                studentIdConflictData = Pair(currentTable.studentId, effectiveSid)
                studentIdConflictDeferred = sidConflictDef
            }
            val sidAction = sidConflictDef.await()
            when (sidAction) {
                1 -> { // 取消
                    withContext(Dispatchers.Main) {
                        wbuSyncStatus = ""
                        isWbuSyncing = false
                    }
                    return false
                }
                2 -> { // 新建独立课表（推荐）
                    forceCreateNewBySidConflict = true
                }
                3 -> { // 执意覆盖当前课表
                    forceCreateNewBySidConflict = false
                }
            }
        }

        val selectSemester = WbuSyncEngine.getSelectSemesterOnImport(appContext)
        var targetXnxq: String? = null

        if (selectSemester) {
            withContext(Dispatchers.Main) {
                wbuSyncStatus = appContext.getString(R.string.status_fetching_semesters)
            }
            val options = engine.fetchSemesterOptions()
            if (options.isNotEmpty()) {
                val deferred = CompletableDeferred<String?>()
                withContext(Dispatchers.Main) {
                    semesterSelectOptions = options
                    semesterSelectCurrentXnxq = currentTable.semesterCode
                    semesterSelectDeferred = deferred
                }
                val chosen = deferred.await()
                if (chosen == null) {
                    withContext(Dispatchers.Main) {
                        wbuSyncStatus = ""
                        isWbuSyncing = false
                    }
                    return false
                }
                targetXnxq = chosen
            }
        }

        // 如果未开启手动选择学期，并且当前课表已经绑定了学期，则严格使用课表绑定的学期
        if (targetXnxq == null && !currentTable.semesterCode.isNullOrBlank()) {
            targetXnxq = currentTable.semesterCode
        }

        withContext(Dispatchers.Main) {
            wbuSyncStatus = appContext.getString(R.string.status_pulling_course_data)
        }
        val coursesRaw = engine.fetchCourseData(currentTableId, targetXnxq)
        if (coursesRaw.isNullOrEmpty()) {
            withContext(Dispatchers.Main) {
                wbuError = appContext.getString(R.string.err_no_course_data_semester)
            }
            return false
        }

        // 检查是否存在同时间同地点的多教师/重复课程（移植自 school.js）
        val dupInfo = WbuSyncEngine.analyzeDuplicateCourses(coursesRaw)
        val courses = if (dupInfo != null) {
            val def = CompletableDeferred<WbuSyncEngine.DuplicateResolveStrategy?>()
            withContext(Dispatchers.Main) {
                duplicateCoursesDialogData = dupInfo
                duplicateCoursesDeferred = def
            }
            val strategy = def.await()
            if (strategy == null) {
                withContext(Dispatchers.Main) {
                    wbuSyncStatus = ""
                    isWbuSyncing = false
                }
                return false
            }
            WbuSyncEngine.resolveDuplicateCourses(coursesRaw, strategy)
        } else {
            coursesRaw
        }

        val effectiveXnxq = engine.lastResolvedXnxq ?: targetXnxq.orEmpty()
        val allTablesBeforeSave = viewModel.getAllCourseTables()

        // 判断冲突与目标写入课表
        var destTableId = currentTableId
        var shouldRenameDest = false

        if (forceCreateNewBySidConflict) {
            // 因学号冲突，用户选择为新学号新建课表（应用方案 B 命名）
            val newName = WbuSyncEngine.computeNonConflictingTableName(effectiveXnxq, effectiveSid, allTablesBeforeSave)
            val newTable = viewModel.createAndSwitchTable(
                name = newName,
                studentId = effectiveSid,
                semesterCode = effectiveXnxq
            )
            destTableId = newTable.id
            shouldRenameDest = false
        } else if (selectSemester && !currentTable.semesterCode.isNullOrBlank() && currentTable.semesterCode != effectiveXnxq) {
            // 属性不一致，弹窗询问覆盖还是新建 (1: Cancel, 2: Create New, 3: Overwrite with rename, 4: Overwrite keep name)
            val conflictDef = CompletableDeferred<Int>()
            withContext(Dispatchers.Main) {
                conflictDialogData = Pair(effectiveXnxq, currentTable)
                conflictDeferred = conflictDef
                conflictAutoRenameChecked = true
            }
            val action = conflictDef.await()
            when (action) {
                1 -> {
                    withContext(Dispatchers.Main) {
                        wbuSyncStatus = ""
                        isWbuSyncing = false
                    }
                    return false
                }
                2 -> {
                    val newName = WbuSyncEngine.computeNonConflictingTableName(effectiveXnxq, effectiveSid, allTablesBeforeSave)
                    val newTable = viewModel.createAndSwitchTable(
                        name = newName,
                        studentId = effectiveSid,
                        semesterCode = effectiveXnxq
                    )
                    destTableId = newTable.id
                }
                3 -> {
                    destTableId = currentTableId
                    shouldRenameDest = true
                }
                4 -> {
                    destTableId = currentTableId
                    shouldRenameDest = false
                }
            }
        } else {
            // 没有冲突（学期一致，或者当前课表原本没有绑定学期）
            if (currentTable.name == "我的课表") {
                shouldRenameDest = true
            }
        }

        withContext(Dispatchers.Main) {
            wbuSyncStatus = appContext.getString(R.string.status_writing_schedule)
        }

        viewModel.importCourses(courses, targetTableId = destTableId)
        val semConfig = engine.fetchSemesterConfig(
            xnxq = effectiveXnxq,
            xqdm = engine.lastResolvedXqdm
        )
        viewModel.applySemesterConfig(semConfig, targetTableId = destTableId)

        // 更新目标课表的学期与学号元数据
        val finalName = if (shouldRenameDest) WbuSyncEngine.computeNonConflictingTableName(effectiveXnxq, effectiveSid, allTablesBeforeSave) else null
        viewModel.updateTableMeta(
            tableId = destTableId,
            name = finalName,
            studentId = effectiveSid,
            semesterCode = effectiveXnxq
        )

        withContext(Dispatchers.Main) {
            wbuSyncStatus = ""
            showWbuAuthDialog = false
            // 成功提示独立成协程：showSnackbar 会挂起到提示消失（Short ≈ 4s）。
            // 若在这里 await，调用方的 finally 也要等提示消失才能把 isWbuSyncing 置回 false，
            // 那段时间点右上角同步按钮会被 `if (isWbuSyncing) return` 直接吞掉（表现为「点了没反应」）。
            coroutineScope.launch {
                snackbarHostState.showSuccessSnackbar(appContext.getString(R.string.toast_schedule_imported_success))
            }
        }

        // 检查教务系统是否有新于本地全部课表的新学期
        val serverNewestXnxq = engine.systemCurrentXnxq
            ?: semesterSelectOptions.maxByOrNull { parseXnxqScore(it.value) }?.value
            ?: effectiveXnxq
        val allTables = viewModel.getAllCourseTables()
        val maxScore = allTables.maxOfOrNull { parseXnxqScore(it.semesterCode) } ?: 0L
        val serverNewestScore = parseXnxqScore(serverNewestXnxq)
        if (serverNewestScore > maxScore && allTables.none { it.semesterCode == serverNewestXnxq }) {
            withContext(Dispatchers.Main) {
                newSemesterPromptXnxq = serverNewestXnxq
                newSemesterPromptEngine = engine
            }
        }

        return true
    }

    /** 同步 / 导入的提示：先顶掉上一条，免得一秒钟弹出三条排队等着播的提示。null / 空 = 不提示。 */
    suspend fun showWbuSnackbar(message: String?) {
        if (message.isNullOrBlank()) return
        withContext(Dispatchers.Main) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    /**
     * 单击同步：**全程不打开登录面板**。
     *
     * 1. 用保存的密码 / 现有会话把登录态静默准备好（缺门禁密码、短信验证码、滑块校验就地弹小窗）；
     * 2. 需要校园网：开了「自动校园网探测」且人就在校园网内时直接连，不绕 WebVPN；
     *    判据过期导致直连白跑一趟时（刚走出 WiFi 范围最常见）重算一次通道再试，不把这次失败甩给用户；
     * 3. 直接跑导入管线；真需要用户重新登录时只提示一句 —— 登录面板的入口是**长按**按钮。
     */
    suspend fun performDirectSync() {
        if (isWbuSyncing) return
        // 立刻进入加载态：探测 / 静默登录 / 换票这几秒里，按钮得看得出「正在干活」，
        // 而不是像点了没反应（校外探测要等超时，用户最先抱怨的就是这个「没反应」）。
        isWbuSyncing = true
        wbuError = ""
        wbuSyncStatus = appContext.getString(R.string.status_fetching_schedule)

        fun stopLoading() {
            wbuSyncStatus = ""
            isWbuSyncing = false
        }

        try {
            // 通道 + 教务会话一次办齐：这一步内部会按需静默登录、必要时重算一次通道
            val session = prepareWbuSyncSession(appContext, "SCHEDULE") {
                coroutineScope.launch {
                    showWbuSnackbar(appContext.getString(R.string.status_login_saved_credentials))
                }
            }
            when (session) {
                is WbuSyncSession.Failed -> {
                    stopLoading()
                    showWbuSnackbar(needLoginHintText(appContext, session.failure))
                }

                is WbuSyncSession.Ready -> performCourseImportPipeline(
                    session.engine,
                    WbuSyncEngine.getSavedStudentId(appContext)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("WbuSync", "单击同步发生错误", e)
            showWbuSnackbar(
                accessFailureText(appContext, e)
                    ?: appContext.getString(R.string.format_err_sync_error, e.message ?: "")
            )
        } finally {
            stopLoading()
            // 导入管线把「为什么没导成」写在 wbuError 里（那是登录面板在显示的状态），
            // 单击同步没有那个面板，所以在这里补一条提示，别让失败静默掉。
            val pipelineError = wbuError
            if (pipelineError.isNotBlank()) {
                wbuError = ""
                showWbuSnackbar(pipelineError)
            }
        }
    }

    fun vpnStatusText(authMode: WbuAuthMode): (VpnFullLoginStatus) -> Unit = { status ->
        wbuSyncStatus = when (status) {
            VpnFullLoginStatus.SMS_REQUIRED -> appContext.getString(R.string.status_vpn_sms_required)
            VpnFullLoginStatus.SMS_VERIFIED -> appContext.getString(R.string.status_vpn_sms_verified)
            VpnFullLoginStatus.VPN_AUTHENTICATED -> appContext.getString(R.string.status_vpn_entered_no_sms)
            VpnFullLoginStatus.VPN_READY_SKIP_CAS -> appContext.getString(R.string.status_vpn_ready_fetching)
            VpnFullLoginStatus.VPN_READY_NEED_CAS ->
                if (authMode == WbuAuthMode.JYXT_LEGACY) appContext.getString(R.string.status_vpn_logging_jwxt) else appContext.getString(R.string.status_vpn_logging_cas)
            VpnFullLoginStatus.CAS_COMPLETED ->
                if (authMode == WbuAuthMode.JYXT_LEGACY) appContext.getString(R.string.status_vpn_jwxt_verified_fetching) else appContext.getString(R.string.status_vpn_cas_verified_fetching)
            VpnFullLoginStatus.CAS_FAILED -> appContext.getString(R.string.status_vpn_cas_failed)
        }
    }

    val weeklyBgBrush = ThemeGradients.weeklyScheduleGradient()
    var bgContainerSize by remember { mutableStateOf(IntSize.Zero) }
    // 课程块毛玻璃的 Haze 源：仅采集壁纸层（课程块是源外部的兄弟节点，Haze 不允许 effect 节点在 source 内部）
    val gridHazeState = remember { HazeState() }

    Box(modifier = Modifier
        .fillMaxSize()
        .onSizeChanged { bgContainerSize = it }
    ) {
        // 背景层（Haze 源）：主题渐变 + 壁纸。课程块是它的兄弟层（绘制在网格之上），Haze 不允许 effect 节点在 source 内部。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(weeklyBgBrush)
                .hazeSource(gridHazeState)
        ) {
            // Full-screen wallpaper (unchanged)
            if (composedStyle.backgroundImagePath.isNotEmpty()) {
                AsyncImage(
                    model = composedStyle.backgroundImagePath,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val widthPx = bgContainerSize.width.toFloat().coerceAtLeast(1f)
                            val heightPx = bgContainerSize.height.toFloat().coerceAtLeast(1f)
                            scaleX = composedStyle.backgroundScale
                            scaleY = composedStyle.backgroundScale
                            translationX = widthPx * composedStyle.backgroundOffsetX
                            translationY = heightPx * composedStyle.backgroundOffsetY
                        }
                        .blur(composedStyle.backgroundBlurRadius),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize()
                .padding(start = if (isWideScreen) NavigationRailWidth else 0.dp)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                // 左对齐：「第n周」放在左侧；右侧切换课表/同步按钮采用导航栏 Liquid Glass 毛玻璃样式
                TopAppBar(
                    title = {
                        // ──【备份·角标原设计 无玻璃（WeekSelector 点击区）】如需回退，把下面这段还原为 title 内容即可 ──
                        // Box(
                        //     modifier = weekTitleModifier.clickable(
                        //         interactionSource = remember { MutableInteractionSource() },
                        //         indication = ripple(bounded = false),
                        //         onClick = {
                        //             if (onWeekTitleClickIntercept?.invoke() == true) {
                        //                 return@clickable
                        //             }
                        //             showWeekSelector = true
                        //         }
                        //     )
                        // ) {
                        //     Text(
                        //         text = uiState.weekTitle,
                        //         fontSize = 18.sp,
                        //         fontWeight = FontWeight.ExtraBold,
                        //         color = composedStyle.pageTextColor ?: MaterialTheme.colorScheme.onSurface
                        //     )
                        //     val titleTint = (composedStyle.pageTextColor
                        //         ?: MaterialTheme.colorScheme.onSurface).copy(alpha = 0.7f)
                        //     Canvas(
                        //         modifier = Modifier
                        //             .size(8.dp)
                        //             .align(Alignment.BottomEnd)
                        //             .offset(x = 4.dp, y = (-2).dp)
                        //     ) {
                        //         val tri = Path().apply {
                        //             moveTo(0f, size.height)
                        //             lineTo(size.width, size.height)
                        //             lineTo(size.width, 0f)
                        //             close()
                        //         }
                        //         drawPath(tri, color = titleTint)
                        //     }
                        // }
                        Box(
                            modifier = weekTitleModifier
                                .then(liquidGlassSurfaceModifier(hazeState, RoundedCornerShape(16.dp)))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = false),
                                    onClick = {
                                        if (onWeekTitleClickIntercept?.invoke() == true) {
                                            return@clickable
                                        }
                                        // Keep course tab behavior stable: title click only opens week selector,
                                        // never redirects to Settings implicitly.
                                        showWeekSelector = true
                                    }
                                )
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = uiState.weekTitle,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Normal,
                                color = composedStyle.pageTextColor ?: MaterialTheme.colorScheme.onSurface
                            )
                            // 角标 ◢ 已隐藏（备份在标题区上方的注释里）
                        }
                    },
                    actions = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 课表/学期切换（上游同步，Liquid Glass 毛玻璃样式）
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .then(liquidGlassSurfaceModifier(hazeState, RoundedCornerShape(16.dp)))
                                    .clickable { showTableSwitcher = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapHoriz,
                                    contentDescription = stringResource(R.string.action_select_table),
                                    tint = composedStyle.pageTextColor ?: MaterialTheme.colorScheme.onSurface
                                )
                            }
                            WbuSyncActionButton(
                                modifier = syncButtonModifier,
                                hazeState = hazeState,
                                contentColor = composedStyle.pageTextColor ?: MaterialTheme.colorScheme.onSurface,
                                loading = isWbuSyncing,
                                onLongClickLabel = stringResource(R.string.a11y_long_press_login),
                                // 长按 = 打开登录面板：需要重新登录 / 想换账号 / 想改导入偏好时走这里
                                onLongClick = syncLongPress@{
                            if (onSyncButtonClickIntercept?.invoke() == true) return@syncLongPress
                            if (isWbuSyncing) return@syncLongPress
                            coroutineScope.launch {
                                val activeTableId = viewModel.uiState.value.tableId
                                if (activeTableId == null) {
                                    snackbarHostState.showSnackbar(appContext.getString(R.string.snackbar_no_syncable_table))
                                    return@launch
                                }

                                wbuInitialStudentId = WbuSyncEngine.getSavedStudentId(appContext)
                                wbuSyncStatus = ""
                                wbuError = ""

                                // 「自动使用保存的密码登录」：本地登录态缺失时先用保存的密码静默建好，
                                // 打开的面板就是一份「已经登录好、只差点确认」的状态 —— 用户不必再输一次密码。
                                // 静默过程中需要门禁密码 / 短信 / 图形校验会就地弹小窗。
                                val prepareFailure = prepareWbuImportWithSavedPassword(appContext, "SCHEDULE") {
                                    snackbarHostState.showSnackbar(appContext.getString(R.string.status_login_saved_credentials))
                                }
                                if (prepareFailure != null && !prepareFailure.needsRelogin) {
                                    // 网络类 / 协议类失败：不是「重新登录」能解决的，别把用户推到登录面板前
                                    snackbarHostState.showSnackbar(
                                        accessFailureText(appContext, prepareFailure)
                                            ?: appContext.getString(R.string.err_need_unified_auth_session)
                                    )
                                    return@launch
                                }
                                // 会话失效 / 凭据被拒：照常打开面板（面板本身就是登录入口），并把原因写进去
                                wbuError = prepareFailure?.let { accessFailureText(appContext, it).orEmpty() }.orEmpty()
                                showWbuAuthDialog = true
                            }
                        },
                                // 单击 = 直接同步：不打开登录面板（真要登录时提示一句「长按按钮」）
                                onClick = syncTap@{
                            if (onSyncButtonClickIntercept?.invoke() == true) return@syncTap
                            if (isWbuSyncing) return@syncTap
                            coroutineScope.launch {
                                if (viewModel.uiState.value.tableId == null) {
                                    snackbarHostState.showSnackbar(appContext.getString(R.string.snackbar_no_syncable_table))
                                    return@launch
                                }
                                wbuInitialStudentId = WbuSyncEngine.getSavedStudentId(appContext)
                                wbuSyncStatus = ""
                                wbuError = ""
                                performDirectSync()
                            }
                        })
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        // Keep top bar color consistent with schedule background in all states.
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    ),
                    scrollBehavior = scrollBehavior
                )
            },
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.padding(bottom = DockSafeBottomPadding)
                ) { snackbarData ->
                    val visuals = snackbarData.visuals as? AppSnackbarVisuals
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentWidth(Alignment.CenterHorizontally)
                    ) {
                        Snackbar(
                            modifier = Modifier.widthIn(max = 280.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (visuals?.leadingIcon == AppSnackbarLeadingIcon.Success) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(snackbarData.visuals.message)
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                beyondViewportPageCount = 1,
                // 拖拽编辑期间禁用滑页防止手势冲突（上游同步）
                userScrollEnabled = !isGridHolding
            ) { pageIndex ->

                    val pageMondayDate = remember(pageIndex, uiState.firstDayOfWeek) {
                        val offsetWeeks = (pageIndex - INFINITE_PAGER_CENTER).toLong()
                        val firstDay = DayOfWeek.of(uiState.firstDayOfWeek)
                        today.with(TemporalAdjusters.previousOrSame(firstDay)).plusWeeks(offsetWeeks)
                    }

                    val pageDateStrings = remember(pageMondayDate) {
                        val formatter = DateTimeFormatter.ofPattern("MM-dd")
                        (0..6).map { pageMondayDate.plusDays(it.toLong()).format(formatter) }
                    }

                    val pageTodayIndex = remember(pageMondayDate) {
                        val weekDates = (0..6).map { pageMondayDate.plusDays(it.toLong()) }
                        weekDates.indexOf(today)
                    }

                    val pageCourses = uiState.courseCache[pageMondayDate.toString()] ?: emptyList()

                    val pageYearString = remember(pageMondayDate) {
                        pageMondayDate.year.toString()
                    }

                    val pageWeekNumber = remember(pageIndex) {
                        val offsetWeeks = (pageIndex - INFINITE_PAGER_CENTER).toInt()
                        uiState.currentWeekNumber?.plus(offsetWeeks)
                    }
                    val weekStr = pageWeekNumber?.let { stringResource(R.string.status_current_week_format, it) }

                    val gridState = rememberScheduleGridState(gridScrollState = gridScrollState)

                    val gridViewState = remember(pageDateStrings, pageYearString, uiState, pageCourses, pageTodayIndex, weekStr) {
                        ScheduleGridViewState(
                            dates = pageDateStrings,
                            currentYear = pageYearString,
                            currentWeek = weekStr,
                            timeSlots = uiState.timeSlots,
                            mergedCourses = pageCourses,
                            showWeekends = uiState.showWeekends,
                            todayIndex = pageTodayIndex,
                            firstDayOfWeek = uiState.firstDayOfWeek,
                            currentSectionIndex = if (pageTodayIndex >= 0) uiState.currentSectionIndex else -1
                        )
                    }

                    val gridActions = remember(uiState, floatingDuration, snackbarMsg) {
                        object : ScheduleGridActions {
                            override fun onCourseBlockClicked(block: MergedCourseBlock) {
                                // 与上游一致：统一走详情卡片（含冲突块），不再弹出 ConflictCourseBottomSheet
                                selectedBlockForDetail = block
                            }

                            override fun onGridCellClicked(day: Int, section: Int) {
                                // 悬浮课程放置（上游同步）
                                if (floatingCourse != null) {
                                    val targetWeek = uiState.weekIndexInPager ?: uiState.currentWeekNumber ?: return
                                    val startSec = section.toFloat()
                                    val endSec = if (composedStyle.scheduleMode == ScheduleModeProto.TIME_24H_MODE) {
                                        startSec + floatingDuration
                                    } else {
                                        startSec + floatingDuration - 1f
                                    }

                                    coroutineScope.launch {
                                        viewModel.updateCourseTimeByFloatingGesture(
                                            targetWeek = targetWeek,
                                            targetDay = day,
                                            startSection = startSec,
                                            endSection = endSec
                                        )
                                    }
                                } else {
                                    // 上游同步：仅在教学周内允许添加，并预设当前周次；24h 模式预设 1 小时自定义时间段
                                    val currentWeek = uiState.weekIndexInPager ?: 0
                                    val isCurrentPageValid = currentWeek in 1..uiState.totalWeeks

                                    if (isCurrentPageValid) {
                                        coroutineScope.launch {
                                            val currentWeekSet = setOf(currentWeek)

                                            val presetData = if (composedStyle.scheduleMode == ScheduleModeProto.TIME_24H_MODE) {
                                                val startHour = section.coerceIn(0, 23)
                                                val endHour = (startHour + 1) % 24

                                                val startTimeStr = String.format(Locale.US, "%02d:00", startHour)
                                                val endTimeStr = String.format(Locale.US, "%02d:00", endHour)

                                                PresetCourseData(
                                                    day = day,
                                                    isCustomTime = true,
                                                    customStartTime = startTimeStr,
                                                    customEndTime = endTimeStr,
                                                    presetWeeks = currentWeekSet
                                                )
                                            } else {
                                                PresetCourseData(
                                                    day = day,
                                                    startSection = section,
                                                    endSection = section,
                                                    isCustomTime = false,
                                                    presetWeeks = currentWeekSet
                                                )
                                            }

                                            AddEditCourseChannel.sendEvent(presetData)
                                            navBridge.navigate(Destination.AddEditCourse())
                                        }
                                    } else {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(snackbarMsg)
                                        }
                                    }
                                }
                            }

                            override fun onTimeSlotClicked() {
                                navBridge.navigate(Destination.TimeSlotSettings)
                            }

                            override fun onHoldStateChanged(isHolding: Boolean) {
                                isGridHolding = isHolding
                            }

                            override fun onCourseMovedWithinGrid(
                                block: MergedCourseBlock,
                                newDay: Int,
                                newStartSection: Float,
                                newEndSection: Float
                            ) {
                                val currentWeek = uiState.weekIndexInPager ?: 0
                                val isCurrentPageValid = currentWeek in 1..uiState.totalWeeks

                                if (isCurrentPageValid) {
                                    val courseId = block.courses.firstOrNull()?.course?.id
                                    if (courseId != null) {
                                        coroutineScope.launch {
                                            viewModel.updateCourseTimeByGesture(
                                                courseId = courseId,
                                                targetDay = newDay,
                                                startSection = newStartSection,
                                                endSection = newEndSection
                                            )
                                        }
                                    }
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(snackbarMsg)
                                    }
                                }
                            }

                            override fun onCourseTimeAdjusted(
                                block: MergedCourseBlock,
                                newStart: Float,
                                newEnd: Float
                            ) {
                                val currentWeek = uiState.weekIndexInPager ?: 0
                                val isCurrentPageValid = currentWeek in 1..uiState.totalWeeks

                                if (isCurrentPageValid) {
                                    val courseId = block.courses.firstOrNull()?.course?.id
                                    if (courseId != null) {
                                        coroutineScope.launch {
                                            viewModel.updateCourseTimeByGesture(
                                                courseId = courseId,
                                                targetDay = block.day,
                                                startSection = newStart,
                                                endSection = newEnd
                                            )
                                        }
                                    }
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(snackbarMsg)
                                    }
                                }
                            }

                            override fun onInitiateFloatingMode(block: MergedCourseBlock) {
                                val targetCourseWrapper = block.courses.firstOrNull()
                                val currentWeek = uiState.weekIndexInPager ?: uiState.currentWeekNumber
                                if (targetCourseWrapper != null && currentWeek != null) {
                                    viewModel.enterFloatingMode(
                                        course = targetCourseWrapper,
                                        sourceWeek = currentWeek
                                    )
                                }
                            }
                        }
                    }

                    ScheduleGrid(
                        state = gridState,
                        viewState = gridViewState,
                        actions = gridActions,
                        style = composedStyle,
                        hazeState = gridHazeState
                    )
            }
        }

        // 悬浮课程胶囊条（上游同步；导航栏此时已由宿主隐藏，固定底部位置）
        FloatingCourseBar(
            floatingCourse = floatingCourse,
            onCancelClick = { viewModel.exitFloatingMode() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }

    // 课表切换弹窗（上游同步）
    if (showTableSwitcher) {
        CourseTablePickerDialog(
            title = stringResource(R.string.action_select_table),
            onDismissRequest = { showTableSwitcher = false },
            onTableSelected = { table: CourseTable ->
                viewModel.switchCourseTable(table.id)
                showTableSwitcher = false
            }
        )
    }

    // 周次选择弹窗
    if (showWeekSelector) {
        WeekSelectorBottomSheet(
            totalWeeks = uiState.totalWeeks,
            currentWeek = uiState.currentWeekNumber ?: 1,
            selectedWeek = uiState.weekIndexInPager ?: (uiState.currentWeekNumber ?: 1),
            onWeekSelected = { week ->
                val currentWeekAtPage = uiState.weekIndexInPager ?: 1
                val offset = week - currentWeekAtPage
                coroutineScope.launch {
                    pagerState.animateScrollToPage(pagerState.currentPage + offset)
                }
                showWeekSelector = false
            },
            onDismissRequest = { showWeekSelector = false }
        )
    }

    // 课程详情卡片（上游同步：点击课程先弹卡片，再从卡片进入编辑）
    selectedBlockForDetail?.let { block ->
        CourseDetailBottomSheet(
            block = block,
            onDismissRequest = { selectedBlockForDetail = null },
            onEditClick = { courseId ->
                selectedBlockForDetail = null
                navBridge.navigate(Destination.AddEditCourse(courseId = courseId))
            }
        )
    }

    if (showWbuAuthDialog) {
        // 校园网直连（非 VPN）时先探测并询问是否继续；VPN 直接返回 true
        val confirmCampusIfDirect: suspend (Boolean) -> Boolean = { useVpn ->
            if (useVpn) {
                true
            } else if (WbuSyncEngine.getSkipCampusCheck(appContext)) {
                // 「不检测校园网环境」开启：跳过探测与确认
                true
            } else {
                val onCampus = WbuNetworkProbe.refresh()
                if (onCampus) {
                    true
                } else {
                    val proceed = CompletableDeferred<Boolean>()
                    withContext(Dispatchers.Main) { campusConfirmDeferred = proceed }
                    proceed.await()
                }
            }
        }

        WbuCampusAuthSheet(
            onDismiss = { if (!isWbuSyncing) showWbuAuthDialog = false },
            onLoginSuccess = {
                coroutineScope.launch {
                    try {
                        isWbuSyncing = true
                        wbuError = ""
                        val activeTableId = viewModel.uiState.value.tableId
                        if (activeTableId == null) {
                            isWbuSyncing = false
                            return@launch
                        }
                        val useVpn = WbuSyncEngine.getSavedUseVpn(appContext)
                        val sid = WbuSyncEngine.getSavedStudentId(appContext)
                        val engine = WbuSyncEngine(context = appContext, useVpn = useVpn)
                        performCourseImportPipeline(engine, sid)
                    } catch (e: Exception) {
                        Log.e("WbuSync", "同步发生错误", e)
                        wbuError = accessFailureText(appContext, e)
                            ?: appContext.getString(R.string.format_err_sync_error, e.message ?: "")
                    } finally {
                        isWbuSyncing = false
                    }
                }
            },
            dismissOnSuccess = false,
            hideImportPreferences = false,
            tipsScenario = WbuAuthTipsScenario.IMPORT,
            onNavigateToAccount = { navBridge.navigate(Destination.CredentialManagement) },
            primaryButtonText = stringResource(R.string.action_one_tap_sync),
            loadingButtonText = stringResource(R.string.status_fetching_schedule),
            externalLoading = isWbuSyncing,
            externalStatusMessage = wbuSyncStatus,
            externalErrorMessage = wbuError,
            flowTagPrefix = "SCHEDULE",
            confirmCampusNetwork = confirmCampusIfDirect,
            onVpnStatus = { status, authMode -> vpnStatusText(authMode).invoke(status) },
            onCaptchaFallback = { sid, pwd, useVpn ->
                showWbuAuthDialog = false
                WbuWebLoginAutofillStore.put(studentId = sid, password = pwd)
                manualLoginUseVpn = useVpn
                showManualLoginPrompt = true
            },
            onSyncWithCredentials = {
                if (!isWbuSyncing) {
                    isWbuSyncing = true
                    wbuError = ""
                    coroutineScope.launch {
                        try {
                            val useVpn = WbuSyncEngine.getSavedUseVpn(appContext)
                            val engine = WbuSyncEngine(context = appContext, useVpn = useVpn)
                            activeVpnEngine = engine
                            engine.sslIssueHandler = { msg ->
                                val d = CompletableDeferred<Boolean>()
                                withContext(Dispatchers.Main) {
                                    sslIssueMessage = msg
                                    sslIssueDeferred = d
                                }
                                d.await()
                            }
                            engine.portalCaptchaProvider = { captcha ->
                                val d = CompletableDeferred<PortalCaptchaResult?>()
                                withContext(Dispatchers.Main) {
                                    portalCaptchaData = captcha
                                    portalCaptchaDeferred = d
                                }
                                d.await() ?: PortalCaptchaResult.Cancel
                            }
                            // WebVPN 模式下先打通门禁：TWFID 缺失/失效时弹窗索取密码 + 短信二次验证
                            if (useVpn) {
                                wbuSyncStatus = appContext.getString(R.string.status_logging_in_webvpn)
                                val tunnelOk = engine.ensureVpnTunnelReady(
                                    studentId = WbuSyncEngine.getSavedStudentId(appContext),
                                    vpnPasswordProvider = {
                                        val d = CompletableDeferred<String?>()
                                        withContext(Dispatchers.Main) {
                                            vpnPasswordError = null
                                            vpnPasswordDeferred = d
                                        }
                                        d.await()
                                    },
                                    smsCodeProvider = { maskedPhone, isStillValid, sendInterval, promptText ->
                                        val deferred = CompletableDeferred<String?>()
                                        withContext(Dispatchers.Main) {
                                            smsError = null
                                            smsVerifying = false
                                            smsDeferred = deferred
                                            smsDialogPhone = maskedPhone
                                            smsDialogIsStillValid = isStillValid
                                            smsDialogSendInterval = sendInterval
                                            smsDialogPromptText = promptText
                                        }
                                        deferred.await()
                                    }
                                )
                                if (!tunnelOk) {
                                    isWbuSyncing = false
                                    wbuSyncStatus = ""
                                    // 结构化原因优先：用户取消输入 → 空文案（不报错），
                                    // 其余情况同理给出「连不上 WebVPN / WebVPN 登录状态已过期」这类可执行提示
                                    val tunnelFailure = engine.lastFailure
                                    wbuError = if (tunnelFailure == null) {
                                        appContext.getString(R.string.err_webvpn_connect_failed)
                                    } else {
                                        accessFailureText(appContext, tunnelFailure).orEmpty()
                                    }
                                    return@launch
                                }
                            }
                            if (engine.ensureJwxtSessionWithExistingCredentials()) {
                                performCourseImportPipeline(engine, WbuSyncEngine.getSavedStudentId(appContext))
                            } else {
                                wbuError = appContext.getString(R.string.err_no_valid_credentials)
                            }
                        } catch (e: Exception) {
                            Log.e("WbuSync", "已有凭据同步错误", e)
                            wbuError = accessFailureText(appContext, e)
                            ?: appContext.getString(R.string.format_err_sync_error, e.message ?: "")
                        } finally {
                            isWbuSyncing = false
                        }
                    }
                }
            }
        )
    }

    // WebVPN 短信验证码对话框
    if (smsDialogPhone != null) {
        VpnSmsCodeDialog(
            maskedPhone = smsDialogPhone!!,
            isStillValid = smsDialogIsStillValid,
            sendInterval = smsDialogSendInterval,
            promptText = smsDialogPromptText,
            isVerifying = smsVerifying,
            errorMessage = smsError,
            onSubmit = { code ->
                smsError = null
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(appContext.getString(R.string.snackbar_otp_submitted_syncing))
                }
                smsDeferred?.complete(code)
                // 对话框保持打开直到验证完成；验证结果由协程流程控制关闭
                smsDialogPhone = null
                smsDeferred = null
                smsVerifying = false
            },
            onResend = {
                val result = activeVpnEngine?.resendVpnSmsCode()
                if (result?.success == true) {
                    smsDialogSendInterval = result.cooldownSeconds
                    snackbarHostState.showSnackbar(appContext.getString(R.string.snackbar_otp_resent))
                    result.cooldownSeconds
                } else {
                    snackbarHostState.showSnackbar(appContext.getString(R.string.snackbar_otp_resend_failed))
                    null
                }
            },
            onDismiss = {
                smsDeferred?.complete(null)
                smsDialogPhone = null
                smsDeferred = null
                smsVerifying = false
                smsError = null
            }
        )
    }

    portalCaptchaData?.let { captcha ->
        PortalCaptchaDialog(
            captcha = captcha,
            onSubmit = { code ->
                portalCaptchaDeferred?.complete(PortalCaptchaResult.Submit(code))
                portalCaptchaDeferred = null
                portalCaptchaData = null
            },
            onRefresh = {
                portalCaptchaDeferred?.complete(PortalCaptchaResult.Refresh)
                portalCaptchaDeferred = null
                portalCaptchaData = null
            },
            onDismiss = {
                portalCaptchaDeferred?.complete(PortalCaptchaResult.Cancel)
                portalCaptchaDeferred = null
                portalCaptchaData = null
            }
        )
    }

    // 校园网不可达「是否继续」确认对话框
    campusConfirmDeferred?.let { deferred ->
        AlertDialog(
            onDismissRequest = {
                deferred.complete(false)
                campusConfirmDeferred = null
            },
            title = { Text(stringResource(R.string.title_no_campus_network)) },
            text = { Text(stringResource(R.string.msg_no_campus_network)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deferred.complete(true)
                        campusConfirmDeferred = null
                    }
                ) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deferred.complete(false)
                        campusConfirmDeferred = null
                    }
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // 教务系统疑似触发超星验证码 → 询问是否跳转 WebView 手动登录
    if (showManualLoginPrompt) {
        AlertDialog(
            onDismissRequest = { showManualLoginPrompt = false },
            title = { Text(stringResource(R.string.title_auto_login_failed)) },
            text = { Text(stringResource(R.string.msg_auto_login_failed)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showManualLoginPrompt = false
                        val target = if (manualLoginUseVpn) {
                            Destination.WebView(initialUrl = "https://webvpn.wbu.edu.cn/portal/?redirect_uri=http%3A%2F%2Fjwxt-wbu-edu-cn-s.webvpn.wbu.edu.cn%3A8118%2F#!/login", assetJsPath = "WBU/wbu_chaoxing.js")
                        } else {
                            Destination.WebView(initialUrl = "https://jwxt.wbu.edu.cn", assetJsPath = "WBU/wbu_chaoxing.js")
                        }
                        navBridge.navigate(target)
                    }
                ) { Text(stringResource(R.string.action_go_to_login)) }
            },
            dismissButton = {
                TextButton(onClick = { showManualLoginPrompt = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // WebVPN 统一认证密码询问弹窗（教务密码模式且未配置 TWFID 时触发）
        // WebVPN 统一认证密码询问弹窗（教务密码模式且未配置 TWFID 时触发）
    vpnPasswordDeferred?.let { deferred ->
        VpnPasswordPromptDialog(
            onSubmit = { value ->
                deferred.complete(value)
                vpnPasswordDeferred = null
            }
        )
    }

    // WebVPN TLS 证书校验异常 → 询问是否继续信任（仅其它/未知证书时触发）
    sslIssueDeferred?.let { deferred ->
        AlertDialog(
            onDismissRequest = {
                deferred.complete(false)
                sslIssueDeferred = null
            },
            title = { Text(stringResource(R.string.title_ssl_exception)) },
            text = { Text(stringResource(R.string.msg_ssl_exception, sslIssueMessage)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deferred.complete(true)
                        sslIssueDeferred = null
                    }
                ) { Text(stringResource(R.string.action_trust_and_continue)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deferred.complete(false)
                        sslIssueDeferred = null
                    }
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // 1. 学期选择对话框
    semesterSelectDeferred?.let { deferred ->
        com.xingheyuzhuan.shiguangschedule.ui.components.SemesterPickerDialog(
            options = semesterSelectOptions,
            currentXnxq = semesterSelectCurrentXnxq,
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

    // 2. 学期冲突确认对话框
    conflictDialogData?.let { (selectedSemester, currentTable) ->
        AlertDialog(
            onDismissRequest = {
                conflictDeferred?.complete(1)
                conflictDialogData = null
                conflictDeferred = null
            },
            title = { Text(stringResource(R.string.title_semester_mismatch)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.msg_semester_mismatch, selectedSemester, currentTable.semesterCode ?: "")
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { conflictAutoRenameChecked = !conflictAutoRenameChecked }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = conflictAutoRenameChecked,
                            onCheckedChange = { conflictAutoRenameChecked = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.label_auto_rename_table),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        conflictDeferred?.complete(2) // Create new
                        conflictDialogData = null
                        conflictDeferred = null
                    }
                ) {
                    Text(stringResource(R.string.action_create_new_table))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            conflictDeferred?.complete(1) // Cancel
                            conflictDialogData = null
                            conflictDeferred = null
                        }
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            val act = if (conflictAutoRenameChecked) 3 else 4
                            conflictDeferred?.complete(act)
                            conflictDialogData = null
                            conflictDeferred = null
                        }
                    ) {
                        Text(stringResource(R.string.action_overwrite_table))
                    }
                }
            }
        )
    }

    // 2.5 重复课程冲突处理弹窗（多教师/相同课程，移植自 school.js）
    duplicateCoursesDialogData?.let { dupInfo ->
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
                duplicateCoursesDeferred?.complete(null)
                duplicateCoursesDialogData = null
                duplicateCoursesDeferred = null
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
                        duplicateCoursesDeferred?.complete(selectedStrategy)
                        duplicateCoursesDialogData = null
                        duplicateCoursesDeferred = null
                    }
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        duplicateCoursesDeferred?.complete(null)
                        duplicateCoursesDialogData = null
                        duplicateCoursesDeferred = null
                    }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // 2.7 学号冲突确认对话框
    studentIdConflictData?.let { (currSid, newSid) ->
        AlertDialog(
            onDismissRequest = {
                studentIdConflictDeferred?.complete(1)
                studentIdConflictData = null
                studentIdConflictDeferred = null
            },
            title = { Text(stringResource(R.string.title_student_id_conflict)) },
            text = {
                Text(
                    text = stringResource(R.string.msg_student_id_conflict, currSid, newSid),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        studentIdConflictDeferred?.complete(2) // 新建独立课表
                        studentIdConflictData = null
                        studentIdConflictDeferred = null
                    }
                ) {
                    Text(stringResource(R.string.action_create_new_table_recommend))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            studentIdConflictDeferred?.complete(1) // 取消
                            studentIdConflictData = null
                            studentIdConflictDeferred = null
                        }
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            studentIdConflictDeferred?.complete(3) // 执意覆盖
                            studentIdConflictData = null
                            studentIdConflictDeferred = null
                        }
                    ) {
                        Text(stringResource(R.string.action_overwrite_anyway))
                    }
                }
            }
        )
    }

    // 3. 教务系统发布新学期提示对话框
    newSemesterPromptXnxq?.let { newXnxq ->
        AlertDialog(
            onDismissRequest = {
                newSemesterPromptXnxq = null
                newSemesterPromptEngine = null
            },
            title = { Text(stringResource(R.string.title_new_semester_found)) },
            text = {
                Text(stringResource(R.string.msg_new_semester_found, newXnxq))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val engine = newSemesterPromptEngine
                        val xnxqToImport = newXnxq
                        newSemesterPromptXnxq = null
                        newSemesterPromptEngine = null
                        if (engine != null) {
                            coroutineScope.launch {
                                try {
                                    val sid = engine.lastResolvedStudentId ?: WbuSyncEngine.getSavedStudentId(appContext)
                                    val currentAllTables = viewModel.getAllCourseTables()
                                    val newName = WbuSyncEngine.computeNonConflictingTableName(xnxqToImport, sid, currentAllTables)
                                    val newTable = viewModel.createAndSwitchTable(
                                        name = newName,
                                        studentId = sid,
                                        semesterCode = xnxqToImport
                                    )
                                    val courses = engine.fetchCourseData(newTable.id, xnxqToImport)
                                    if (!courses.isNullOrEmpty()) {
                                        viewModel.importCourses(courses, targetTableId = newTable.id)
                                        val cfg = engine.fetchSemesterConfig(xnxq = xnxqToImport, xqdm = engine.lastResolvedXqdm)
                                        viewModel.applySemesterConfig(cfg, targetTableId = newTable.id)
                                        snackbarHostState.showSuccessSnackbar(appContext.getString(R.string.toast_new_semester_imported_success))
                                    } else {
                                        snackbarHostState.showSnackbar(appContext.getString(R.string.snackbar_new_semester_no_courses))
                                    }
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar(appContext.getString(R.string.format_snackbar_new_semester_failed, e.message ?: ""))
                                }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_import_and_create_now))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        newSemesterPromptXnxq = null
                        newSemesterPromptEngine = null
                    }
                ) {
                    Text(stringResource(R.string.action_later))
                }
            }
        )
    }
}

private enum class AppSnackbarLeadingIcon {
    None,
    Success
}

private data class AppSnackbarVisuals(
    override val message: String,
    val leadingIcon: AppSnackbarLeadingIcon = AppSnackbarLeadingIcon.None,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short
) : SnackbarVisuals

private suspend fun SnackbarHostState.showSuccessSnackbar(message: String) {
    showSnackbar(
        AppSnackbarVisuals(
            message = message,
            leadingIcon = AppSnackbarLeadingIcon.Success
        )
    )
}
