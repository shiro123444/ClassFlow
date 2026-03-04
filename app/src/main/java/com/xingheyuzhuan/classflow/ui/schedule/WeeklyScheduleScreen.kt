package com.xingheyuzhuan.classflow.ui.schedule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import android.util.Log
import com.xingheyuzhuan.classflow.ui.components.WbuAuthBottomSheet
import com.xingheyuzhuan.classflow.ui.components.VpnSmsCodeDialog
import com.xingheyuzhuan.classflow.ui.components.DockSafeBottomPadding
import com.xingheyuzhuan.classflow.data.network.wbu.VpnFullLoginStatus
import com.xingheyuzhuan.classflow.data.network.wbu.WbuSyncEngine
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.xingheyuzhuan.classflow.R
import com.xingheyuzhuan.classflow.Screen
import com.xingheyuzhuan.classflow.data.db.main.CourseWithWeeks
import com.xingheyuzhuan.classflow.navigation.AddEditCourseChannel
import com.xingheyuzhuan.classflow.navigation.PresetCourseData
import com.xingheyuzhuan.classflow.ui.components.DockSafeBottomPadding
import com.xingheyuzhuan.classflow.ui.schedule.components.ConflictCourseBottomSheet
import com.xingheyuzhuan.classflow.ui.schedule.components.ScheduleGrid
import com.xingheyuzhuan.classflow.ui.schedule.components.ScheduleGridStyleComposed
import com.xingheyuzhuan.classflow.ui.schedule.components.WeekSelectorBottomSheet
import com.xingheyuzhuan.classflow.ui.theme.ClassFlowTheme
import com.xingheyuzhuan.classflow.ui.theme.ThemeGradients
import com.xingheyuzhuan.classflow.ui.schoolselection.web.WbuWebLoginAutofillStore
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
    navController: NavHostController,
    viewModel: WeeklyScheduleViewModel = viewModel(factory = WeeklyScheduleViewModelFactory),
    weekTitleModifier: Modifier = Modifier,
    syncButtonModifier: Modifier = Modifier,
    onWeekTitleClickIntercept: (() -> Boolean)? = null,
    onSyncButtonClickIntercept: (() -> Boolean)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val snackbarMsg = stringResource(id = R.string.snackbar_add_course_after_start)
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
    var showConflictBottomSheet by remember { mutableStateOf(false) }
    var showWbuAuthDialog by remember { mutableStateOf(false) }
    var isWbuSyncing by remember { mutableStateOf(false) }
    var wbuSyncStatus by remember { mutableStateOf("") }
    var wbuInitialStudentId by remember { mutableStateOf(WbuSyncEngine.getSavedStudentId(appContext)) }
    var wbuInitialUseVpn by remember { mutableStateOf(WbuSyncEngine.getSavedUseVpn(appContext) ?: false) }
    var conflictCoursesToShow by remember { mutableStateOf(emptyList<CourseWithWeeks>()) }

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
                    },
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = composedStyle.backgroundDimAlpha))
            )
        }

        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = uiState.weekTitle,
                            modifier = weekTitleModifier.clickable {
                                if (onWeekTitleClickIntercept?.invoke() == true) {
                                    return@clickable
                                }
                                // Keep course tab behavior stable: title click only opens week selector,
                                // never redirects to Settings implicitly.
                                showWeekSelector = true
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    },
                    actions = {
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
                                                snackbarHostState.showSnackbar("🎉 已复用登录态，同步成功！")
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
                )
            }
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                beyondViewportPageCount = 1
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

                    ScheduleGrid(
                        style = composedStyle,
                        dates = pageDateStrings,
                        timeSlots = uiState.timeSlots,
                        mergedCourses = pageCourses,
                        showWeekends = uiState.showWeekends,
                        todayIndex = pageTodayIndex,
                        firstDayOfWeek = uiState.firstDayOfWeek,
                        onCourseBlockClicked = { mergedBlock ->
                            if (mergedBlock.isConflict) {
                                conflictCoursesToShow = mergedBlock.courses
                                showConflictBottomSheet = true
                            } else {
                                mergedBlock.courses.firstOrNull()?.course?.id?.let {
                                    navController.navigate(Screen.AddEditCourse.createRouteWithCourseId(it))
                                }
                            }
                        },
                        onGridCellClicked = { day, section ->
                            if (uiState.semesterStartDate != null && !today.isBefore(uiState.semesterStartDate)) {
                                coroutineScope.launch {
                                    AddEditCourseChannel.sendEvent(PresetCourseData(day, section, section))
                                    navController.navigate(Screen.AddEditCourse.createRouteForNewCourse())
                                }
                            } else {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(snackbarMsg)
                                }
                            }
                        },
                        onTimeSlotClicked = {
                            navController.navigate(Screen.TimeSlotSettings.route)
                        }
                    )
            }
        }
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

    // 冲突处理弹窗
    if (showConflictBottomSheet) {
        ConflictCourseBottomSheet(
            style = composedStyle,
            courses = conflictCoursesToShow,
            timeSlots = uiState.timeSlots,
            onCourseClicked = { course ->
                showConflictBottomSheet = false
                navController.navigate(Screen.AddEditCourse.createRouteWithCourseId(course.course.id))
            },
            onDismissRequest = { showConflictBottomSheet = false }
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
                                    snackbarHostState.showSnackbar("🎉 课表导入成功！")
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
                                    snackbarHostState.showSnackbar("🎉 课表导入成功！")
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
                                navController.navigate(
                                    Screen.WebView.createRoute(
                                        initialUrl = "https://webvpn.wbu.edu.cn/portal/#!/login",
                                        assetJsPath = "WBU/wbu_chaoxing.js"
                                    )
                                )
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
                            snackbarHostState.showSnackbar("🎉 课表导入成功！")
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

@Composable
private fun WbuSyncActionButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    IconButton(
        onClick = onClick,
        modifier = modifier
            .padding(end = 8.dp)
            .border(
                width = 0.8.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (isDark) 0.18f else 0.55f),
                        Color.White.copy(alpha = if (isDark) 0.05f else 0.12f)
                    )
                ),
                shape = RoundedCornerShape(14.dp)
            )
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.55f else 0.72f),
                shape = RoundedCornerShape(14.dp)
            )
    ) {
        Icon(
            imageVector = Icons.Filled.Sync,
            contentDescription = "一键同步武商院课表"
        )
    }
}


@Preview(showBackground = true)
@Composable
private fun WbuSyncActionButtonPreview() {
    ClassFlowTheme {
        WbuSyncActionButton(onClick = {})
    }
}


