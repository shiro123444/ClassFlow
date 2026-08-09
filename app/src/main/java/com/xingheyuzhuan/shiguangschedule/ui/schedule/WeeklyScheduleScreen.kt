package com.xingheyuzhuan.shiguangschedule.ui.schedule
import com.xingheyuzhuan.shiguangschedule.ui.theme.LocalIsDarkTheme
import androidx.hilt.navigation.compose.hiltViewModel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import android.util.Log
import com.xingheyuzhuan.shiguangschedule.ui.components.WbuAuthBottomSheet
import com.xingheyuzhuan.shiguangschedule.ui.components.VpnSmsCodeDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.DockSafeBottomPadding
import com.xingheyuzhuan.shiguangschedule.ui.components.CourseTablePickerDialog
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.VpnFullLoginStatus
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
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
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.rememberScheduleGridState
import com.xingheyuzhuan.shiguangschedule.ui.theme.ClassFlowTheme
import com.xingheyuzhuan.shiguangschedule.ui.theme.ThemeGradients
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web.WbuWebLoginAutofillStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
    var wbuInitialStudentId by remember { mutableStateOf(WbuSyncEngine.getSavedStudentId(appContext)) }
    var wbuInitialUseVpn by remember { mutableStateOf(WbuSyncEngine.getSavedUseVpn(appContext) ?: false) }
    var selectedBlockForDetail by remember { mutableStateOf<MergedCourseBlock?>(null) }
    var showTableSwitcher by remember { mutableStateOf(false) }
    var isGridHolding by remember { mutableStateOf(false) } // 拖拽编辑期间禁用 Pager 滑页（上游同步）
    val gridScrollState = rememberScrollState()

    // SMS 验证码对话框状态
    var smsDialogPhone by remember { mutableStateOf<String?>(null) }
    var smsDeferred by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }
    var smsVerifying by remember { mutableStateOf(false) }
    var smsError by remember { mutableStateOf<String?>(null) }
    // 保持 vpnEngine 引用以便 resend
    var activeVpnEngine by remember { mutableStateOf<WbuSyncEngine?>(null) }

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

    val onVpnStatus: (VpnFullLoginStatus) -> Unit = { status ->
        wbuSyncStatus = when (status) {
            VpnFullLoginStatus.SMS_REQUIRED -> "需要短信验证码，请输入后继续~"
            VpnFullLoginStatus.SMS_VERIFIED -> "验证码通过，正在完成教务认证..."
            VpnFullLoginStatus.VPN_AUTHENTICATED -> "WebVPN 已进入，无需短信验证码"
            VpnFullLoginStatus.VPN_READY_SKIP_CAS -> "VPN 会话已生效，正在获取课表..."
            VpnFullLoginStatus.VPN_READY_NEED_CAS -> "VPN 已进入，正在完成统一认证..."
            VpnFullLoginStatus.CAS_COMPLETED -> "统一认证完成，正在抓取课表..."
            VpnFullLoginStatus.CAS_FAILED -> "认证未完成，可能需要额外验证码"
        }
    }

    val weeklyBgBrush = ThemeGradients.weeklyScheduleGradient()
    var bgContainerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(weeklyBgBrush)
        .onSizeChanged { bgContainerSize = it }
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

        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = weekTitleModifier.clickable {
                                if (onWeekTitleClickIntercept?.invoke() == true) {
                                    return@clickable
                                }
                                // Keep course tab behavior stable: title click only opens week selector,
                                // never redirects to Settings implicitly.
                                showWeekSelector = true
                            }
                        ) {
                            Text(
                                text = uiState.weekTitle,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = composedStyle.pageTextColor ?: MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp).offset(y = (-4).dp),
                                tint = (composedStyle.pageTextColor ?: MaterialTheme.colorScheme.onSurface).copy(alpha = 0.7f)
                            )
                        }
                    },
                    actions = {
                        // 课表切换（上游同步）
                        IconButton(onClick = { showTableSwitcher = true }) {
                            Icon(
                                imageVector = Icons.Default.SwapHoriz,
                                contentDescription = stringResource(R.string.action_select_table),
                                tint = composedStyle.pageTextColor ?: MaterialTheme.colorScheme.onSurface
                            )
                        }
                        WbuSyncActionButton(
                            modifier = syncButtonModifier,
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
                                val hasPersistedSession = WbuSyncEngine.hasPersistedSession(appContext)

                                if (savedUseVpn != null && hasPersistedSession) {
                                    isWbuSyncing = true
                                    val engine = WbuSyncEngine(context = appContext, useVpn = savedUseVpn)
                                    try {
                                        snackbarHostState.showSnackbar("检测到已保存登录态，正在尝试无感同步...")
                                        val sessionValid = engine.hasActiveSession()
                                        if (sessionValid) {
                                            val courses = engine.fetchCourseData(activeTableId)
                                            if (!courses.isNullOrEmpty()) {
                                                viewModel.importCourses(courses)
                                                snackbarHostState.showSuccessSnackbar("已复用登录态，同步成功！")
                                                return@launch
                                            }
                                        }

                                        engine.clearPersistedSession()
                                        snackbarHostState.showSnackbar("登录态已失效，请重新登录")
                                    } finally {
                                        isWbuSyncing = false
                                    }
                                }

                                wbuInitialUseVpn = savedUseVpn ?: false
                                wbuInitialStudentId = WbuSyncEngine.getSavedStudentId(appContext)
                                wbuSyncStatus = ""
                                showWbuAuthDialog = true
                            }
                        })
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
                    Snackbar {
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
                        showGlassBorder = uiState.useSakuraTimeTheme
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
        WbuAuthBottomSheet(
            onDismissRequest = { if (!isWbuSyncing) showWbuAuthDialog = false },
            isLoading = isWbuSyncing,
            statusMessage = wbuSyncStatus,
            initialStudentId = wbuInitialStudentId,
            initialUseVpn = wbuInitialUseVpn,
            onLoginClick = { studentId, password, useVpn ->
                isWbuSyncing = true
                coroutineScope.launch {
                    try {
                        val activeTableId = viewModel.uiState.value.tableId ?: return@launch

                        // VPN 模式
                        if (useVpn) {
                            val vpnEngine = WbuSyncEngine(context = appContext, useVpn = true)
                            activeVpnEngine = vpnEngine

                            // 1. 优先尝试持久化 session
                            if (vpnEngine.hasActiveSession()) {
                                wbuSyncStatus = "使用已保存的 VPN 会话，正在获取课表..."
                                val courses = vpnEngine.fetchCourseData(activeTableId)
                                if (courses != null && courses.isNotEmpty()) {
                                    viewModel.importCourses(courses)
                                    wbuSyncStatus = ""
                                    showWbuAuthDialog = false
                                    snackbarHostState.showSuccessSnackbar("课表导入成功！")
                                    return@launch
                                }
                                wbuSyncStatus = "已保存的会话无法获取课表，尝试重新登录..."
                            }

                            // 2. 完整 WebVPN 登录（密码 + 短信验证码 + 校内认证）
                            wbuSyncStatus = "正在登录 WebVPN，可能需要短信验证..."
                            val fullLoginOk = vpnEngine.loginVpnFull(
                                studentId, password,
                                smsCodeProvider = { maskedPhone ->
                                    // 切到主线程显示对话框，通过 CompletableDeferred 挂起等待用户输入
                                    val deferred = CompletableDeferred<String?>()
                                    withContext(Dispatchers.Main) {
                                        smsError = null
                                        smsVerifying = false
                                        smsDeferred = deferred
                                        smsDialogPhone = maskedPhone
                                    }
                                    deferred.await()
                                },
                                statusCallback = onVpnStatus
                            )

                            if (fullLoginOk) {
                                wbuSyncStatus = "登录成功，正在获取课表..."
                                val courses = vpnEngine.fetchCourseData(activeTableId)
                                if (courses != null && courses.isNotEmpty()) {
                                    viewModel.importCourses(courses)
                                    wbuSyncStatus = ""
                                    showWbuAuthDialog = false
                                    snackbarHostState.showSuccessSnackbar("课表导入成功！")
                                    return@launch
                                }
                                wbuSyncStatus = "登录成功但未获取到课表数据"
                            } else {
                                // 全部失败 → 兜底到 WebView
                                isWbuSyncing = false
                                wbuSyncStatus = ""
                                showWbuAuthDialog = false
                                snackbarHostState.showSnackbar("自动登录失败，请通过 WebView 手动登录")
                                WbuWebLoginAutofillStore.put(studentId = studentId, password = password)
                                navBridge.navigate(Destination.WebView(initialUrl = "https://webvpn.wbu.edu.cn/portal/#!/login", assetJsPath = "WBU/wbu_chaoxing.js"))
                            }
                            return@launch
                        }

                        // 非 VPN（校园网直连）
                        wbuSyncStatus = "正在连接校园网..."
                        val engine = WbuSyncEngine(context = appContext, useVpn = false)
                        val loginSuccess = engine.login(studentId, password)
                        if (!loginSuccess) {
                            wbuSyncStatus = "校园网直连失败，请确认已连接校内网络"
                            return@launch
                        }

                        wbuSyncStatus = "登录成功，正在获取课表..."
                        val courses = engine.fetchCourseData(activeTableId)
                        if (courses != null && courses.isNotEmpty()) {
                            viewModel.importCourses(courses)
                            wbuSyncStatus = ""
                            showWbuAuthDialog = false
                            snackbarHostState.showSuccessSnackbar("课表导入成功！")
                        } else {
                            wbuSyncStatus = "未获取到课表数据"
                        }
                    } catch (e: Exception) {
                        Log.e("WbuSync", "同步发生错误", e)
                        wbuSyncStatus = "同步发生错误: ${e.message}"
                    } finally {
                        isWbuSyncing = false
                    }
                }
            }
        )
    }

    // WebVPN 短信验证码对话框
    if (smsDialogPhone != null) {
        VpnSmsCodeDialog(
            maskedPhone = smsDialogPhone!!,
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
                    val ok = activeVpnEngine?.resendVpnSmsCode() ?: false
                    if (ok) {
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

