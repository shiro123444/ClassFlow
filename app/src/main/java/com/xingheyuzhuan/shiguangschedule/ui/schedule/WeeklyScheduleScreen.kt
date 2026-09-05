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
import com.xingheyuzhuan.shiguangschedule.ui.components.WbuAuthBottomSheet
import com.xingheyuzhuan.shiguangschedule.ui.components.VpnSmsCodeDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.DockSafeBottomPadding
import com.xingheyuzhuan.shiguangschedule.ui.components.NavigationRailWidth
import com.xingheyuzhuan.shiguangschedule.ui.components.isWideScreen
import com.xingheyuzhuan.shiguangschedule.ui.components.CourseTablePickerDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.SliderCaptchaDialog
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.VpnFullLoginStatus
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthMode
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.SliderCaptchaData
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.SliderCaptchaResult
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.LocalLoginFailure
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WebVpnClient
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuNetworkProbe
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuLoginMethod
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.QrSession
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AuthForm
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.DynamicCodeSendResult
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.QrStatus
import com.xingheyuzhuan.shiguangschedule.ui.components.QrUiState
import com.xingheyuzhuan.shiguangschedule.ui.components.QrPhase
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
    var wbuInitialUseVpn by remember { mutableStateOf(WbuSyncEngine.getSavedUseVpn(appContext) ?: false) }
    var wbuLoginMethod by remember { mutableStateOf(WbuLoginMethod.PASSWORD) }
    var wbuQrState by remember { mutableStateOf<QrUiState?>(null) }
    var activeAuthEngine by remember { mutableStateOf<WbuSyncEngine?>(null) }
    var dynamicPrep by remember { mutableStateOf<AuthForm?>(null) }
    var qrJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var selectedBlockForDetail by remember { mutableStateOf<MergedCourseBlock?>(null) }
    var showTableSwitcher by remember { mutableStateOf(false) }
    var isGridHolding by remember { mutableStateOf(false) } // 拖拽编辑期间禁用 Pager 滑页（上游同步）
    val gridScrollState = rememberScrollState()

    // SMS 验证码对话框状态
    var smsDialogPhone by remember { mutableStateOf<String?>(null) }
    var smsDialogIsStillValid by remember { mutableStateOf(false) }
    var smsDialogSendInterval by remember { mutableIntStateOf(60) }
    var smsDialogPromptText by remember { mutableStateOf<String?>(null) }
    var smsDeferred by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }
    var smsVerifying by remember { mutableStateOf(false) }
    var smsError by remember { mutableStateOf<String?>(null) }
    // 保持 vpnEngine 引用以便 resend
    var activeVpnEngine by remember { mutableStateOf<WbuSyncEngine?>(null) }

    // WebVPN 证书校验异常对话框状态
    var sslIssueMessage by remember { mutableStateOf("") }
    var sslIssueDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    // 滑块验证码对话框状态（统一认证滑块）
    var captchaDialogData by remember { mutableStateOf<SliderCaptchaData?>(null) }
    var captchaDeferred by remember { mutableStateOf<CompletableDeferred<SliderCaptchaResult?>?>(null) }

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
                wbuError = "当前课表已归档锁定，无法直接同步覆盖。请新建课表或前往管理课表解除归档。"
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
                wbuSyncStatus = "正在获取可选学期..."
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
            wbuSyncStatus = "正在拉取课表数据..."
        }
        val coursesRaw = engine.fetchCourseData(currentTableId, targetXnxq)
        if (coursesRaw.isNullOrEmpty()) {
            withContext(Dispatchers.Main) {
                wbuError = "未获取到课表数据（可能该学期未排课）"
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
            wbuSyncStatus = "正在写入课表..."
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
            snackbarHostState.showSuccessSnackbar("课表导入成功！")
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

    fun vpnStatusText(authMode: WbuAuthMode): (VpnFullLoginStatus) -> Unit = { status ->
        wbuSyncStatus = when (status) {
            VpnFullLoginStatus.SMS_REQUIRED -> "需要短信验证码，请输入后继续~"
            VpnFullLoginStatus.SMS_VERIFIED -> "验证码通过，正在完成教务认证..."
            VpnFullLoginStatus.VPN_AUTHENTICATED -> "WebVPN 已进入，无需短信验证码"
            VpnFullLoginStatus.VPN_READY_SKIP_CAS -> "VPN 会话已生效，正在获取课表..."
            VpnFullLoginStatus.VPN_READY_NEED_CAS ->
                if (authMode == WbuAuthMode.JYXT_LEGACY) "VPN 已进入，正在使用教务系统密码登录..." else "VPN 已进入，正在完成统一认证..."
            VpnFullLoginStatus.CAS_COMPLETED ->
                if (authMode == WbuAuthMode.JYXT_LEGACY) "教务密码验证完成，正在抓取课表..." else "统一认证完成，正在抓取课表..."
            VpnFullLoginStatus.CAS_FAILED -> "认证未完成，可能需要额外验证码"
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
                                onClick = {
                            if (onSyncButtonClickIntercept?.invoke() == true) return@WbuSyncActionButton
                            if (isWbuSyncing) return@WbuSyncActionButton
                            coroutineScope.launch {
                                val activeTableId = viewModel.uiState.value.tableId
                                if (activeTableId == null) {
                                    snackbarHostState.showSnackbar("当前没有可同步的课表")
                                    return@launch
                                }

                                val savedUseVpn = WbuSyncEngine.getSavedUseVpn(appContext)

                                wbuInitialUseVpn = savedUseVpn ?: false
                                wbuInitialStudentId = WbuSyncEngine.getSavedStudentId(appContext)
                                wbuSyncStatus = ""
                                wbuError = ""
                                wbuLoginMethod = WbuLoginMethod.PASSWORD
                                wbuQrState = null
                                dynamicPrep = null
                                qrJob?.cancel()
                                showWbuAuthDialog = true
                            }
                        },
                            // 长按：忽略已保存登录态，清除会话并强制走重新登录
                            onLongClick = {
                                if (isWbuSyncing) return@WbuSyncActionButton
                                coroutineScope.launch {
                                    val activeTableId = viewModel.uiState.value.tableId
                                    if (activeTableId == null) {
                                        snackbarHostState.showSnackbar("当前没有可同步的课表")
                                        return@launch
                                    }

                                    val savedUseVpn = WbuSyncEngine.getSavedUseVpn(appContext) ?: false
                                    WbuSyncEngine(context = appContext, useVpn = savedUseVpn)
                                        .clearPersistedSession()
                                    snackbarHostState.showSnackbar("已忽略已保存登录态，请重新登录")

                                    wbuInitialUseVpn = savedUseVpn
                                    wbuInitialStudentId = WbuSyncEngine.getSavedStudentId(appContext)
                                    wbuSyncStatus = ""
                                    wbuError = ""
                                    wbuLoginMethod = WbuLoginMethod.PASSWORD
                                    wbuQrState = null
                                    dynamicPrep = null
                                    qrJob?.cancel()
                                    showWbuAuthDialog = true
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
                    val weekStr = pageWeekNumber?.let { "第${it}周" }

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

        val startQrFlow: (Boolean) -> Unit = { useVpn ->
            wbuError = ""
            qrJob?.cancel()
            val engine = WbuSyncEngine(context = appContext, useVpn = useVpn)
            activeAuthEngine = engine
            activeVpnEngine = engine
            wbuQrState = QrUiState(qrContent = null, phase = QrPhase.GENERATING, statusText = "正在获取二维码...")
            coroutineScope.launch {
                val session = engine.startQrLogin("QR")
                if (session == null) {
                    wbuQrState = QrUiState(qrContent = null, phase = QrPhase.ERROR, statusText = "获取二维码失败，点二维码重试")
                    return@launch
                }
                wbuQrState = QrUiState(qrContent = session.content, phase = QrPhase.WAIT, statusText = "请扫码登录")
                qrJob = coroutineScope.launch {
                    while (true) {
                        delay(2000)
                        when (val st = engine.pollQrStatus(session)) {
                            QrStatus.WAIT -> wbuQrState = QrUiState(qrContent = session.content, phase = QrPhase.WAIT, statusText = "请扫码登录")
                            QrStatus.CONFIRM -> wbuQrState = QrUiState(qrContent = session.content, phase = QrPhase.SCANNED, statusText = "已扫码，请在手机上确认")
                            QrStatus.SUCCESS -> {
                                wbuQrState = QrUiState(qrContent = session.content, phase = QrPhase.CONFIRMING, statusText = "确认成功，正在登录...")
                                isWbuSyncing = true
                                try {
                                    val activeTableId = viewModel.uiState.value.tableId
                                    wbuSyncStatus = "登录成功，正在获取课表..."
                                    val qrOk = engine.completeQrLogin(
                                        session = session,
                                        flowTag = "QR",
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
                                    if (qrOk && activeTableId != null) {
                                        val sid = WbuSyncEngine.getSavedStudentId(appContext)
                                        performCourseImportPipeline(engine, sid)
                                    } else {
                                        wbuError = engine.lastLocalLoginError?.takeIf { it.isNotBlank() } ?: "扫码登录失败，请重试"
                                        wbuQrState = QrUiState(qrContent = null, phase = QrPhase.ERROR, statusText = "登录失败，点二维码重试")
                                    }
                                } finally {
                                    isWbuSyncing = false
                                }
                                return@launch
                            }
                            QrStatus.EXPIRED -> {
                                wbuQrState = QrUiState(qrContent = session.content, phase = QrPhase.EXPIRED, statusText = "二维码已过期，点二维码刷新")
                                return@launch
                            }
                            QrStatus.ERROR -> {
                                wbuQrState = QrUiState(qrContent = session.content, phase = QrPhase.ERROR, statusText = "查询状态失败，点二维码重试")
                            }
                        }
                    }
                }
            }
        }

        WbuAuthBottomSheet(
            onDismissRequest = {
                if (!isWbuSyncing) {
                    qrJob?.cancel()
                    wbuQrState = null
                    showWbuAuthDialog = false
                }
            },
            isLoading = isWbuSyncing,
            statusMessage = wbuSyncStatus,
            errorMessage = wbuError,
            initialStudentId = wbuInitialStudentId,
            initialUseVpn = wbuInitialUseVpn,
            method = wbuLoginMethod,
            onMethodChange = { m ->
                wbuLoginMethod = m
                if (m != WbuLoginMethod.QR) {
                    qrJob?.cancel()
                    wbuQrState = null
                } else if (wbuQrState == null) {
                    // 选中二维码即自动尝试生成
                    startQrFlow(wbuInitialUseVpn)
                }
            },
            onUseVpnChange = {
                wbuInitialUseVpn = it
                WbuSyncEngine.setSavedUseVpn(appContext, it)
            },
            qrState = wbuQrState,
            onPasswordLogin = { studentId, password, useVpn, authMode ->
                isWbuSyncing = true
                wbuError = ""
                coroutineScope.launch {
                    try {
                        val activeTableId = viewModel.uiState.value.tableId ?: return@launch

                        // VPN 模式
                        if (useVpn) {
                            val vpnEngine = WbuSyncEngine(context = appContext, useVpn = true)
                            activeVpnEngine = vpnEngine
                            vpnEngine.sslIssueHandler = { msg ->
                                val d = CompletableDeferred<Boolean>()
                                withContext(Dispatchers.Main) {
                                    sslIssueMessage = msg
                                    sslIssueDeferred = d
                                }
                                d.await()
                            }

                            // 直接完整登录：先建立/校验 WebVPN 鉴权（TWFID/门户登录），
                            // 完成后再访问教务；不再在鉴权前做 hasActiveSession 探测。
                            // 如果是教务系统密码模式且无 TWFID，先弹窗询问 WebVPN/统一认证密码
                            var customVpnPassword: String? = null
                            if (authMode == WbuAuthMode.JYXT_LEGACY && WebVpnClient.getTwfid(appContext).isBlank()) {
                                val d = CompletableDeferred<String?>()
                                withContext(Dispatchers.Main) {
                                    vpnPasswordError = null
                                    vpnPasswordDeferred = d
                                }
                                customVpnPassword = d.await()
                                if (customVpnPassword == null) {
                                    // 用户取消输入 WebVPN 密码
                                    isWbuSyncing = false
                                    wbuSyncStatus = ""
                                    return@launch
                                }
                            }

                            wbuSyncStatus = "正在登录 WebVPN..."
                            val fullLoginOk = vpnEngine.loginVpnFull(
                                studentId, password,
                                authMode = authMode,
                                vpnPassword = customVpnPassword,
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
                                },
                                captchaProvider = { captcha ->
                                    val deferred = CompletableDeferred<SliderCaptchaResult?>()
                                    withContext(Dispatchers.Main) {
                                        captchaDeferred = deferred
                                        captchaDialogData = captcha
                                    }
                                    deferred.await() ?: SliderCaptchaResult.Cancel
                                },
                                statusCallback = vpnStatusText(authMode)
                            )

                            if (fullLoginOk) {
                                performCourseImportPipeline(vpnEngine, studentId)
                                return@launch
                            } else {
                                isWbuSyncing = false
                                wbuSyncStatus = ""
                                val realErr = vpnEngine.lastLocalLoginError?.takeIf { it.isNotBlank() }
                                when {
                                    vpnEngine.lastLocalLoginFailure == LocalLoginFailure.CAPTCHA -> {
                                        showWbuAuthDialog = false
                                        WbuWebLoginAutofillStore.put(studentId = studentId, password = password)
                                        manualLoginUseVpn = true
                                        showManualLoginPrompt = true
                                    }
                                    vpnEngine.lastLocalLoginNetworkError -> {
                                        wbuError = "WebVPN 连接失败，请检查网络后重试"
                                    }
                                    realErr != null -> {
                                        wbuError = realErr
                                    }
                                    else -> {
                                        wbuError = "自动登录失败，请检查账号或密码"
                                    }
                                }
                            }
                            return@launch
                        }

                        // 校园网直连
                        if (!confirmCampusIfDirect(false)) return@launch
                        wbuSyncStatus = "正在连接校园网..."
                        val engine = WbuSyncEngine(context = appContext, useVpn = false)
                        val loginSuccess = engine.login(
                            studentId, password,
                            authMode = authMode,
                            captchaProvider = { captcha ->
                                val deferred = CompletableDeferred<SliderCaptchaResult?>()
                                withContext(Dispatchers.Main) {
                                    captchaDeferred = deferred
                                    captchaDialogData = captcha
                                }
                                deferred.await() ?: SliderCaptchaResult.Cancel
                            }
                        )
                        if (!loginSuccess) {
                            isWbuSyncing = false
                            wbuSyncStatus = ""
                            val realErr = engine.lastLocalLoginError?.takeIf { it.isNotBlank() }
                            when {
                                engine.lastLocalLoginFailure == LocalLoginFailure.CAPTCHA -> {
                                    showWbuAuthDialog = false
                                    WbuWebLoginAutofillStore.put(studentId = studentId, password = password)
                                    manualLoginUseVpn = false
                                    showManualLoginPrompt = true
                                }
                                engine.lastLocalLoginNetworkError -> {
                                    wbuError = "校园网直连失败，请确认已连接校园网"
                                }
                                realErr != null -> {
                                    wbuError = realErr
                                }
                                else -> {
                                    wbuError = "登录失败，请检查学号或密码后重试"
                                }
                            }
                            return@launch
                        }

                        performCourseImportPipeline(engine, studentId)
                    } catch (e: Exception) {
                        Log.e("WbuSync", "同步发生错误", e)
                        wbuError = "同步发生错误: ${e.message}"
                    } finally {
                        isWbuSyncing = false
                    }
                }
            },
            onSendDynamicCode = { sid, useVpn ->
                val engine = WbuSyncEngine(context = appContext, useVpn = useVpn)
                activeAuthEngine = engine
                activeVpnEngine = engine
                val result = engine.sendDynamicCode(
                    sid.trim(), "DYNAMIC",
                    captchaProvider = { captcha ->
                        val deferred = CompletableDeferred<SliderCaptchaResult?>()
                        withContext(Dispatchers.Main) {
                            captchaDeferred = deferred
                            captchaDialogData = captcha
                        }
                        deferred.await() ?: SliderCaptchaResult.Cancel
                    }
                )
                dynamicPrep = (result as? DynamicCodeSendResult.Success)?.prep
                result
            },
            onDynamicCodeLogin = { sid, code, useVpn ->
                isWbuSyncing = true
                wbuError = ""
                coroutineScope.launch {
                    try {
                        val activeTableId = viewModel.uiState.value.tableId ?: return@launch
                        if (!confirmCampusIfDirect(useVpn)) return@launch
                        val engine = if (activeAuthEngine?.useVpn == useVpn) {
                            activeAuthEngine!!
                        } else {
                            val newEngine = WbuSyncEngine(context = appContext, useVpn = useVpn)
                            activeAuthEngine = newEngine
                            newEngine
                        }
                        activeVpnEngine = engine
                        // 没有现成 prep 时（用户直接填已有验证码）临时取表单参数
                        val prep = dynamicPrep ?: engine.obtainDynamicCodeForm("DYNAMIC")
                        if (prep == null) {
                            wbuError = "无法获取登录参数，请重试"
                            return@launch
                        }
                        wbuSyncStatus = "正在登录..."
                        val result = engine.dynamicCodeLogin(
                            sid.trim(), code, prep, "DYNAMIC",
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
                        if (result.success) {
                            performCourseImportPipeline(engine, sid)
                        } else {
                            wbuError = result.message.ifBlank { "动态码登录失败，请检查验证码" }
                        }
                    } catch (e: Exception) {
                        Log.e("WbuSync", "动态码同步错误", e)
                        wbuError = "同步发生错误: ${e.message}"
                    } finally {
                        isWbuSyncing = false
                    }
                }
            },
            onStartQr = { useVpn -> startQrFlow(useVpn) },
            onRefreshQr = { useVpn -> startQrFlow(useVpn) }
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
                    snackbarHostState.showSnackbar("验证码已提交，后台脚本正在继续同步，请稍候~")
                }
                smsDeferred?.complete(code)
                // 对话框保持打开直到验证完成；验证结果由协程流程控制关闭
                smsDialogPhone = null
                smsDeferred = null
                smsVerifying = false
            },
            onResend = {
                coroutineScope.launch {
                    val result = activeVpnEngine?.resendVpnSmsCode()
                    if (result?.success == true) {
                        smsDialogSendInterval = result.cooldownSeconds
                        snackbarHostState.showSnackbar("验证码已重新发送")
                    } else {
                        snackbarHostState.showSnackbar("重新发送失败，请稍后重试")
                    }
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

    // 统一认证滑块验证码对话框
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

    // 校园网不可达「是否继续」确认对话框
    campusConfirmDeferred?.let { deferred ->
        AlertDialog(
            onDismissRequest = {
                deferred.complete(false)
                campusConfirmDeferred = null
            },
            title = { Text("未检测到校园网") },
            text = { Text("当前好像不在校园网环境，校园网直连可能无法成功。是否仍要继续尝试？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deferred.complete(true)
                        campusConfirmDeferred = null
                    }
                ) { Text("继续") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deferred.complete(false)
                        campusConfirmDeferred = null
                    }
                ) { Text("取消") }
            }
        )
    }

    // 教务系统疑似触发超星验证码 → 询问是否跳转 WebView 手动登录
    if (showManualLoginPrompt) {
        AlertDialog(
            onDismissRequest = { showManualLoginPrompt = false },
            title = { Text("自动登录失败") },
            text = { Text("教务系统可能要求人工验证，是否跳转浏览器页面手动登录？") },
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
                ) { Text("去登录") }
            },
            dismissButton = {
                TextButton(onClick = { showManualLoginPrompt = false }) { Text("取消") }
            }
        )
    }

    // WebVPN 统一认证密码询问弹窗（教务密码模式且未配置 TWFID 时触发）
    vpnPasswordDeferred?.let { deferred ->
        var inputPassword by remember { mutableStateOf("") }
        var passwordVisible by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                deferred.complete(null)
                vpnPasswordDeferred = null
            },
            title = { Text("连接 WebVPN") },
            text = {
                Column {
                    Text(
                        text = "校外访问教务系统需先通过 WebVPN 门禁。请输入您的【统一认证/WebVPN 密码】以连接网络：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inputPassword,
                        onValueChange = { inputPassword = it },
                        label = { Text("统一认证 (WebVPN) 密码") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deferred.complete(inputPassword)
                        vpnPasswordDeferred = null
                    },
                    enabled = inputPassword.isNotBlank()
                ) {
                    Text("继续连接")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deferred.complete(null)
                        vpnPasswordDeferred = null
                    }
                ) {
                    Text("取消")
                }
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
            title = { Text("证书校验异常") },
            text = { Text("检测到 WebVPN 证书校验失败（$sslIssueMessage）。是否继续信任并重试？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deferred.complete(true)
                        sslIssueDeferred = null
                    }
                ) { Text("继续信任") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deferred.complete(false)
                        sslIssueDeferred = null
                    }
                ) { Text("取消") }
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
            title = { Text("学期不一致提醒") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "您选择导入的学期是【${selectedSemester}】，而当前课表绑定的学期是【${currentTable.semesterCode}】。\n\n如选择覆盖，当前课表内的课程将被清空重写。"
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
                            text = "自动修改该课表名称",
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
                    Text("新建课表")
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
                        Text("取消")
                    }
                    TextButton(
                        onClick = {
                            val act = if (conflictAutoRenameChecked) 3 else 4
                            conflictDeferred?.complete(act)
                            conflictDialogData = null
                            conflictDeferred = null
                        }
                    ) {
                        Text("覆盖课表")
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
                "重复课程处理（${dupInfo.groupCount} 组 / ${dupInfo.totalConflictCourses} 门）"
            dupInfo.hasMultiTeacher ->
                "多教师重复课程处理（${dupInfo.groupCount} 组 / ${dupInfo.totalConflictCourses} 门）"
            else ->
                "完全相同的重复课程处理（${dupInfo.groupCount} 组 / ${dupInfo.totalConflictCourses} 门）"
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
                        duplicateCoursesDeferred?.complete(selectedStrategy)
                        duplicateCoursesDialogData = null
                        duplicateCoursesDeferred = null
                    }
                ) {
                    Text("确定")
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
                    Text("取消")
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
            title = { Text("发现新学期课表") },
            text = {
                Text("教务系统当前已有新学期【$newXnxq】的课表。是否立即导入并新建该学期课表？")
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
                                        snackbarHostState.showSuccessSnackbar("新学期课表已导入！")
                                    } else {
                                        snackbarHostState.showSnackbar("新学期暂无课程数据")
                                    }
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("新学期导入失败: ${e.message}")
                                }
                            }
                        }
                    }
                ) {
                    Text("立即导入新建")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        newSemesterPromptXnxq = null
                        newSemesterPromptEngine = null
                    }
                ) {
                    Text("稍后再说")
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

