package com.xingheyuzhuan.shiguangschedule

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.canopas.lib.showcase.IntroShowcase
import com.canopas.lib.showcase.IntroShowcaseScope
import com.canopas.lib.showcase.component.IntroShowcaseState
import com.canopas.lib.showcase.component.ShowcaseStyle
import com.canopas.lib.showcase.component.rememberIntroShowcaseState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import com.xingheyuzhuan.shiguangschedule.tool.ReleaseUpdateInfo
import com.xingheyuzhuan.shiguangschedule.tool.UpdateChecker
import com.xingheyuzhuan.shiguangschedule.tool.UpdateStatus
import com.xingheyuzhuan.shiguangschedule.ui.components.AppDownloadProgressDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.AppUpdateFoundDialog
import com.xingheyuzhuan.shiguangschedule.ui.components.InstallPermissionPromptDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import com.xingheyuzhuan.shiguangschedule.data.model.AppSettingsModel
import com.xingheyuzhuan.shiguangschedule.data.model.AppThemeMode
import com.xingheyuzhuan.shiguangschedule.data.model.StartScreen
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseConversionRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseTableRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.TimeSlotRepository
import com.xingheyuzhuan.shiguangschedule.ui.components.BottomNavigationBar
import com.xingheyuzhuan.shiguangschedule.ui.components.LeftNavigationRail
import com.xingheyuzhuan.shiguangschedule.ui.components.isWideScreen
import com.xingheyuzhuan.shiguangschedule.ui.components.isOnboardingCompleted
import com.xingheyuzhuan.shiguangschedule.ui.components.markOnboardingCompleted
import com.xingheyuzhuan.shiguangschedule.ui.schedule.WeeklyScheduleScreen
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.list.AdapterSelectionScreen
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.list.SchoolSelectionListScreen
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web.WebViewScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.SettingsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.additional.MoreOptionsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.additional.OpenSourceLicensesScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.backup.BackupScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.contribution.ContributionScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.conversion.CourseTableConversionScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.course.AddEditCourseScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.coursemanagement.CourseInstanceListScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.coursemanagement.CourseNameListScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.coursetables.ManageCourseTablesScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.notification.NotificationSettingsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.quickactions.QuickActionsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.quickactions.delete.QuickDeleteScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.quickactions.tweaks.TweakScheduleScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.style.StyleSettingsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.style.WallpaperAdjustScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.themesettings.ThemeSettingsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.time.TimeSlotManagementScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.update.UpdateRepoScreen
import com.xingheyuzhuan.shiguangschedule.ui.theme.ClassFlowTheme
import com.xingheyuzhuan.shiguangschedule.ui.today.TodayScheduleScreen
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    lateinit var courseConversionRepository: CourseConversionRepository

    @Inject
    lateinit var courseTableRepository: CourseTableRepository

    @Inject
    lateinit var timeSlotRepository: TimeSlotRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)
        enableHighRefreshRate()
        setContent {
            val settings by appSettingsRepository.getAppSettings()
                .collectAsState(initial = null)

            // 等待设置就绪后再进入导航，保证启动页面设置生效（上游同步）
            val readySettings = settings ?: return@setContent

            val darkTheme = when (readySettings.themeMode) {
                AppThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            ClassFlowTheme(
                darkTheme = darkTheme,
                dynamicColor = readySettings.useDynamicColor,
                timeBasedTheme = readySettings.useSakuraTimeTheme,
                customLightPrimary = Color(readySettings.customLightPrimary),
                customDarkPrimary = Color(readySettings.customDarkPrimary)
            ) {
                AppNavigation(
                    startDestination = when (readySettings.startScreen) {
                        StartScreen.COURSE_SCHEDULE -> Destination.CourseSchedule
                        StartScreen.TODAY_SCHEDULE -> Destination.TodaySchedule
                    },
                    courseConversionRepository = courseConversionRepository,
                    courseTableRepository = courseTableRepository,
                    timeSlotRepository = timeSlotRepository,
                    appSettingsRepository = appSettingsRepository
                )
            }
        }
    }

    private fun enableHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }
            display?.supportedModes?.maxByOrNull { it.refreshRate }?.let { mode ->
                val params = window.attributes
                params.preferredDisplayModeId = mode.modeId
                window.attributes = params
            }
        }
    }
}

@Composable
fun AppNavigation(
    startDestination: Destination,
    courseConversionRepository: CourseConversionRepository,
    courseTableRepository: CourseTableRepository,
    timeSlotRepository: TimeSlotRepository,
    appSettingsRepository: AppSettingsRepository
) {
    val backStack = rememberNavBackStack(startDestination)
    val currentDestination = backStack.lastOrNull() as? Destination
    val context = LocalContext.current
    // 悬浮课程模式时隐藏底部导航栏（上游同步）
    var isFloatingCourseMode by remember { mutableStateOf(false) }
    val showBottomDock = currentDestination?.isMainScreen == true && !isFloatingCourseMode

    val coroutineScope = rememberCoroutineScope()
    val updateChecker = remember(context) { UpdateChecker(context.applicationContext) }
    var autoUpdateFoundInfo by remember { mutableStateOf<ReleaseUpdateInfo?>(null) }
    var autoUpdateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Idle) }
    var showAutoInstallPermissionDialog by remember { mutableStateOf(false) }
    var autoPendingApkFile by remember { mutableStateOf<File?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val apk = autoPendingApkFile
                if (apk != null && apk.exists() && updateChecker.canRequestPackageInstalls()) {
                    updateChecker.installApk(apk)
                    autoPendingApkFile = null
                    showAutoInstallPermissionDialog = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        delay(1500)
        val settings = appSettingsRepository.getAppSettingsOnce()
        if (!settings.autoCheckUpdate) return@LaunchedEffect
        val effectiveApiUrl = settings.customUpdateApiUrl.ifBlank { BuildConfig.UPDATE_API_URL }.trim()
        if (effectiveApiUrl.isBlank()) return@LaunchedEffect

        val now = System.currentTimeMillis()
        val checkIntervalMs = 24 * 60 * 60 * 1000L
        if (now - settings.lastCheckUpdateTime < checkIntervalMs) return@LaunchedEffect

        val status = updateChecker.checkUpdate(
            customApiUrl = effectiveApiUrl,
            channel = settings.updateChannel
        )

        if (status is UpdateStatus.Found) {
            // 发现新版本：若未被用户明确标记跳过，则弹出更新提示
            if (status.info.latestVersionName != settings.ignoredUpdateVersion) {
                autoUpdateFoundInfo = status.info
            }
        } else if (status is UpdateStatus.Latest) {
            // 只有在已是最新版本时，才记录24小时冷却，避免频繁请求
            appSettingsRepository.updateLastCheckUpdateTime(now)
        }
    }

    // NavBridge 实现（navigation3 后向兼容层，供各 Screen 使用）
    val navBridge: NavBridge = remember(backStack, context) {
        object : NavBridge {
            override val context = context.applicationContext
            override fun navigate(destination: Destination) {
                if (backStack.lastOrNull() != destination) {
                    backStack.add(destination)
                }
            }

            override fun navigateToMain(destination: Destination) {
                backStack.clear()
                backStack.add(destination)
            }

            override fun popBackStack() {
                if (backStack.size > 1) {
                    backStack.removeAt(backStack.lastIndex)
                }
            }

            override fun navigateUp() = popBackStack()
        }
    }

    // 课表页下滑隐藏底部导航（上游同步）：滚动方向驱动显示/隐藏
    var dockVisible by remember { mutableStateOf(true) }
    val dockCollapseFraction by animateFloatAsState(
        targetValue = if (dockVisible) 0f else 1f,
        animationSpec = tween(220),
        label = "dockCollapse"
    )
    val dockNestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    // 手指下滑（查看上方内容，available.y < 0）隐藏；手指上滑（available.y > 0）显示
                    if (available.y < 0 && dockVisible) dockVisible = false
                    else if (available.y > 0 && !dockVisible) dockVisible = true
                }
                return Offset.Zero
            }
        }
    }

    var showOnboarding by remember { mutableStateOf(!isOnboardingCompleted(context)) }
    val introShowcaseState = rememberIntroShowcaseState()
    val completeOnboarding = {
        markOnboardingCompleted(context)
        showOnboarding = false
    }

    BackHandler(enabled = showOnboarding) {
        // Lock onboarding flow: do not allow mid-way back exit.
    }

    // Keep onboarding on the expected route for each step.
    LaunchedEffect(showOnboarding, introShowcaseState.currentTargetIndex, currentDestination) {
        if (!showOnboarding) return@LaunchedEffect
        val currentIndex = introShowcaseState.currentTargetIndex
        when {
            // Steps 0..3: stay on CourseSchedule
            currentIndex in 0..3 && currentDestination !is Destination.CourseSchedule -> {
                navBridge.navigateToMain(Destination.CourseSchedule)
            }
            // Step 4: manage tables guide on Settings screen
            currentIndex == 4 && currentDestination !is Destination.Settings -> {
                navBridge.navigateToMain(Destination.Settings)
            }
        }
    }

    val showcaseStyle = ShowcaseStyle.Default.copy(
        backgroundColor = Color(0xFF0F1A2C),
        backgroundAlpha = 0.94f,
        targetCircleColor = Color.White
    )

    // navigation3 转场动画（主页面间无过渡，其余页面滑动+渐变）
    val animSpec = tween<IntOffset>(300)

    // Backdrop blur source for the floating dock (glass nav bar samples content behind it).
    val dockHazeState = remember { HazeState() }

    Box(modifier = Modifier.fillMaxSize()) {
        IntroShowcase(
            showIntroShowCase = showOnboarding,
            state = introShowcaseState,
            dismissOnClickOutside = introShowcaseState.currentTargetIndex != 3,
            onShowCaseCompleted = {
                // Canopas callback fires both on true finish and on temporary missing-target transitions.
                // Only complete when the index has actually moved beyond the last onboarding step.
                if (showOnboarding && introShowcaseState.currentTargetIndex > LAST_ONBOARDING_TARGET_INDEX) {
                    completeOnboarding()
                }
            }
        ) {
            // ── Step 0: Welcome  +  Step 1: Swipe  +  Step 2: Course Drag & Resize ── target weekTitle
            val weekTitleTargetModifier =
                if (showOnboarding && currentDestination is Destination.CourseSchedule) {
                    Modifier
                        .introShowCaseTarget(
                            index = 0,
                            style = showcaseStyle,
                            content = {
                                OnboardingCard(
                                    title = stringResource(R.string.onboarding_title_1),
                                    body = stringResource(R.string.onboarding_body_1),
                                    isLastStep = false,
                                    icon = { Icon(Icons.Rounded.Celebration, null, tint = Color.White, modifier = Modifier.size(32.dp)) },
                                    showcaseState = introShowcaseState,
                                    onComplete = completeOnboarding
                                )
                            }
                        )
                        .introShowCaseTarget(
                            index = 1,
                            style = showcaseStyle,
                            content = {
                                OnboardingCard(
                                    title = stringResource(R.string.onboarding_title_2),
                                    body = stringResource(R.string.onboarding_body_2),
                                    isLastStep = false,
                                    icon = { SwipeGestureAnimation() },
                                    showcaseState = introShowcaseState,
                                    onComplete = completeOnboarding
                                )
                            }
                        )
                        .introShowCaseTarget(
                            index = 2,
                            style = showcaseStyle,
                            content = {
                                OnboardingCard(
                                    title = stringResource(R.string.onboarding_title_course_drag),
                                    body = stringResource(R.string.onboarding_body_course_drag),
                                    isLastStep = false,
                                    icon = { CourseDragGestureAnimation() },
                                    showcaseState = introShowcaseState,
                                    onComplete = completeOnboarding
                                )
                            }
                        )
                } else {
                    Modifier
                }

            // ── Step 3: Sync button ──
            val syncButtonTargetModifier =
                if (showOnboarding && currentDestination is Destination.CourseSchedule) {
                    Modifier.introShowCaseTarget(
                        index = 3,
                        style = showcaseStyle,
                        content = {
                            OnboardingCard(
                                title = stringResource(R.string.onboarding_title_3),
                                body = stringResource(R.string.onboarding_body_3),
                                isLastStep = false,
                                advanceByTapAnywhere = false,
                                icon = { TapGestureAnimation() },
                                showcaseState = introShowcaseState,
                                onComplete = completeOnboarding
                            )
                        }
                    )
                } else {
                    Modifier
                }

            // ── Step 4: Manage Course Tables (Settings page item) ── (新手引导最后一步)
            val manageCourseTablesTargetModifier =
                if (showOnboarding && currentDestination is Destination.Settings) {
                    Modifier.introShowCaseTarget(
                        index = 4,
                        style = showcaseStyle,
                        content = {
                            OnboardingCard(
                                title = stringResource(R.string.onboarding_title_manage_tables),
                                body = stringResource(R.string.onboarding_body_manage_tables),
                                isLastStep = true,
                                advanceByTapAnywhere = true,
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Book,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )
                                },
                                showcaseState = introShowcaseState,
                                onComplete = completeOnboarding
                            )
                        }
                    )
                } else {
                    Modifier
                }

            // ── Dock step disabled (library popup placement is unstable on extra-wide dock target) ──
            val bottomNavTargetModifier =
                Modifier

            NavDisplay(
                backStack = backStack,
                onBack = navBridge::popBackStack,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(dockNestedScrollConnection)
                    .hazeSource(dockHazeState),
                transitionSpec = {
                    val fromMain = initialState.metadata[ShiguangNavMetadata.IsMainScreenKey] ?: false
                    val toMain = targetState.metadata[ShiguangNavMetadata.IsMainScreenKey] ?: false

                    if (fromMain && toMain) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        slideInHorizontally(initialOffsetX = { it }, animationSpec = animSpec) togetherWith
                                slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = animSpec) + fadeOut()
                    }
                },
                popTransitionSpec = {
                    val fromMain = initialState.metadata[ShiguangNavMetadata.IsMainScreenKey] ?: false
                    val toMain = targetState.metadata[ShiguangNavMetadata.IsMainScreenKey] ?: false

                    if (fromMain && toMain) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = animSpec) + fadeIn() togetherWith
                                slideOutHorizontally(targetOffsetX = { it }, animationSpec = animSpec)
                    }
                },
                predictivePopTransitionSpec = {
                    slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = animSpec) + fadeIn() togetherWith
                            slideOutHorizontally(targetOffsetX = { it }, animationSpec = animSpec)
                },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator()
                )
            ) { key ->
                val destination = key as Destination

                NavEntry(
                    key = key,
                    metadata = metadata {
                        put(ShiguangNavMetadata.IsMainScreenKey, destination.isMainScreen)
                    }
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        when (destination) {
                            Destination.CourseSchedule -> WeeklyScheduleScreen(
                                navBridge = navBridge,
                                weekTitleModifier = weekTitleTargetModifier,
                                syncButtonModifier = syncButtonTargetModifier,
                                hazeState = dockHazeState,
                                onFloatingModeChange = { isFloatingCourseMode = it },
                                onWeekTitleClickIntercept = {
                                    if (showOnboarding && introShowcaseState.currentTargetIndex in 0..2) {
                                        introShowcaseState.goToNext(
                                            onComplete = completeOnboarding,
                                            allowCompleteOnMissingTarget = false
                                        )
                                        true
                                    } else {
                                        false
                                    }
                                },
                                onSyncButtonClickIntercept = {
                                    if (!showOnboarding) {
                                        false
                                    } else {
                                        if (introShowcaseState.currentTargetIndex == 3) {
                                            navBridge.navigateToMain(Destination.Settings)
                                            introShowcaseState.goToNext(
                                                onComplete = completeOnboarding,
                                                allowCompleteOnMissingTarget = false
                                            )
                                            true
                                        } else {
                                            true
                                        }
                                    }
                                }
                            )

                            Destination.Settings -> SettingsScreen(
                                navBridge = navBridge,
                                manageCourseTablesModifier = manageCourseTablesTargetModifier,
                                forceScrollToManageTables = showOnboarding && introShowcaseState.currentTargetIndex == 4
                            )

                            Destination.TodaySchedule -> TodayScheduleScreen(navBridge = navBridge)
                            Destination.TimeSlotSettings -> TimeSlotManagementScreen(onBackClick = navBridge::popBackStack)
                            Destination.ManageCourseTables -> ManageCourseTablesScreen(navBridge = navBridge)
                            Destination.SchoolSelectionListScreen -> SchoolSelectionListScreen(navBridge = navBridge)
                            Destination.CourseTableConversion -> CourseTableConversionScreen(navBridge = navBridge)
                            Destination.MoreOptions -> MoreOptionsScreen(navBridge = navBridge)
                            Destination.OpenSourceLicenses -> OpenSourceLicensesScreen(navBridge = navBridge)
                            Destination.QuickActions -> QuickActionsScreen(navBridge = navBridge)
                            Destination.TweakSchedule -> TweakScheduleScreen(navBridge = navBridge)
                            Destination.ContributionList -> ContributionScreen(navBridge = navBridge)
                            Destination.CourseManagementList -> CourseNameListScreen(navBridge = navBridge)
                            Destination.StyleSettings -> StyleSettingsScreen(navBridge = navBridge)
                            Destination.WallpaperAdjust -> WallpaperAdjustScreen(onBack = navBridge::popBackStack)
                            Destination.QuickDelete -> QuickDeleteScreen(navBridge = navBridge)
                            Destination.UpdateRepo -> UpdateRepoScreen(navBridge = navBridge)
                            Destination.NotificationSettings -> NotificationSettingsScreen(onBack = navBridge::popBackStack)
                            Destination.ThemeSettings -> ThemeSettingsScreen(onBack = navBridge::popBackStack)
                            Destination.BackupAndRestore -> BackupScreen(onBack = navBridge::popBackStack)

                            // 动态传参页面
                            is Destination.AdapterSelection -> AdapterSelectionScreen(
                                navBridge = navBridge,
                                schoolId = destination.schoolId,
                                schoolName = destination.schoolName,
                                categoryNumber = destination.categoryNumber,
                                resourceFolder = destination.resourceFolder
                            )

                            is Destination.WebView -> WebViewScreen(
                                navBridge = navBridge,
                                initialUrl = destination.initialUrl,
                                assetJsPath = destination.assetJsPath,
                                courseConversionRepository = courseConversionRepository,
                                courseTableRepository = courseTableRepository,
                                timeSlotRepository = timeSlotRepository,
                                appSettingsRepository = appSettingsRepository
                            )

                            is Destination.AddEditCourse -> AddEditCourseScreen(
                                onBack = navBridge::popBackStack,
                                courseId = destination.courseId
                            )

                            is Destination.CourseManagementDetail -> CourseInstanceListScreen(
                                courseName = destination.courseName,
                                onNavigateBack = navBridge::popBackStack,
                                navBridge = navBridge
                            )
                        }
                    }
                }
            }

            // 宽屏（平板）→ 左侧导航栏（ClassFlow 定制）；手机 → 底部悬浮 dock
            if (showBottomDock && isWideScreen) {
                LeftNavigationRail(
                    navBridge = navBridge,
                    currentDestination = currentDestination,
                    hazeState = dockHazeState,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                )
            }

            if (showBottomDock && !isWideScreen) {
                Box(
                    modifier = bottomNavTargetModifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer {
                            translationY = size.height * dockCollapseFraction
                            alpha = 1f - dockCollapseFraction
                        }
                ) {
                    BottomNavigationBar(
                        navBridge = navBridge,
                        currentDestination = currentDestination,
                        isTransparent = true,
                        hazeState = dockHazeState,
                        onTabClickIntercept = {
                            if (showOnboarding && introShowcaseState.currentTargetIndex == LAST_ONBOARDING_TARGET_INDEX) {
                                completeOnboarding()
                                false
                            } else {
                                showOnboarding
                            }
                        }
                    )
                }
            }
        }

        if (autoUpdateFoundInfo != null && autoUpdateStatus !is UpdateStatus.Downloading) {
            val info = autoUpdateFoundInfo!!
            AppUpdateFoundDialog(
                info = info,
                currentVersionName = BuildConfig.VERSION_NAME,
                onDismiss = {
                    autoUpdateFoundInfo = null
                },
                onSkipVersion = {
                    coroutineScope.launch {
                        appSettingsRepository.updateIgnoredUpdateVersion(info.latestVersionName)
                    }
                    autoUpdateFoundInfo = null
                },
                onUpdateConfirm = {
                        coroutineScope.launch {
                            autoUpdateStatus = UpdateStatus.Downloading()
                            val result = updateChecker.downloadAndInstallUpdate(
                                downloadUrl = info.downloadUrl,
                                versionName = info.latestVersionName,
                                expectedSize = info.expectedSize,
                                expectedMd5 = info.expectedMd5,
                                onProgress = { progress, currentBytes, totalBytes ->
                                    autoUpdateStatus = UpdateStatus.Downloading(progress, currentBytes, totalBytes)
                                }
                            )
                        if (result.isSuccess) {
                            val apk = result.getOrNull()
                            autoPendingApkFile = apk
                            autoUpdateStatus = UpdateStatus.Idle
                            autoUpdateFoundInfo = null
                            appSettingsRepository.updateIgnoredUpdateVersion("")
                            if (!updateChecker.canRequestPackageInstalls()) {
                                showAutoInstallPermissionDialog = true
                            }
                        } else {
                            autoUpdateStatus = UpdateStatus.Idle
                        }
                    }
                }
            )
        }

        if (autoUpdateStatus is UpdateStatus.Downloading) {
            AppDownloadProgressDialog(
                downloading = autoUpdateStatus as UpdateStatus.Downloading
            )
        }

        if (showAutoInstallPermissionDialog) {
            InstallPermissionPromptDialog(
                onConfirm = {
                    showAutoInstallPermissionDialog = false
                    updateChecker.openInstallPermissionSettings()
                },
                onDismiss = {
                    showAutoInstallPermissionDialog = false
                }
            )
        }
    }
}

// ── Onboarding card with title, body, optional icon, and action buttons ──

/**
 * Advance the IntroShowcase to the next target via reflection.
 * The library keeps `setCurrentTargetIndex` internal — this mirrors
 * the exact same logic found in `ShowcasePopup`'s click handler.
 */
private fun IntroShowcaseState.goToNext(
    onComplete: () -> Unit,
    allowCompleteOnMissingTarget: Boolean = true
) {
    if (currentTargetIndex >= LAST_ONBOARDING_TARGET_INDEX) {
        if (allowCompleteOnMissingTarget) onComplete()
        return
    }

    try {
        val nextIndex = currentTargetIndex + 1
        if (nextIndex > LAST_ONBOARDING_TARGET_INDEX) {
            if (allowCompleteOnMissingTarget) onComplete()
            return
        }

        val method = javaClass.getMethod(
            "setCurrentTargetIndex\$showcase_release",
            Int::class.javaPrimitiveType
        )
        method.invoke(this, nextIndex)
        // For cross-route steps, currentTarget can be temporarily null before navigation completes.
        if (allowCompleteOnMissingTarget && currentTarget == null) onComplete()
    } catch (_: Exception) { }
}

@Composable
private fun IntroShowcaseScope.OnboardingCard(
    title: String,
    body: String,
    isLastStep: Boolean,
    advanceByTapAnywhere: Boolean = true,
    icon: (@Composable () -> Unit)?,
    showcaseState: IntroShowcaseState,
    onComplete: () -> Unit
) {
    val continueHint = when {
        isLastStep -> stringResource(R.string.onboarding_tap_anywhere_finish)
        advanceByTapAnywhere -> stringResource(R.string.onboarding_tap_anywhere_continue)
        else -> stringResource(R.string.onboarding_tap_sync_continue)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (advanceByTapAnywhere || isLastStep) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showcaseState.goToNext(
                            onComplete = onComplete,
                            allowCompleteOnMissingTarget = isLastStep
                        )
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = if (isLastStep) 4.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        if (icon != null) {
            icon()
            Spacer(modifier = Modifier.height(10.dp))
        }
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = continueHint,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        }
    }
}

private const val LAST_ONBOARDING_TARGET_INDEX = 4

// ── Gesture hint animations ──

@Composable
private fun CourseDragGestureAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "course_drag_anim")

    // phase: 0f..4200f (循环演示完整手势)
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 4200f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // 计算各阶段状态:
    // 0..800ms: 长按激活 -> 触点波纹渐显，端点出现
    // 800..1900ms: 拖动底部端点向下延长节数
    // 1900..3300ms: 拖动卡片整体向右移至屏幕边缘并悬浮挂起
    // 3300..4200ms: 边缘释放悬停，淡出还原
    val (blockHeightDp, blockOffsetXDp, handlesAlpha, touchIndicatorOffset, isNearEdge) = when {
        progress < 800f -> {
            val p = progress / 800f
            Quint(66f, -14f, p, Offset(-14f, 0f), false)
        }
        progress < 1900f -> {
            val p = ((progress - 800f) / 1100f).coerceIn(0f, 1f)
            val stretch = kotlin.math.sin(p * Math.PI.toFloat()) * 28f
            Quint(66f + stretch, -14f, 1f, Offset(10f, 33f + stretch), false)
        }
        progress < 3300f -> {
            val p = ((progress - 1900f) / 1400f).coerceIn(0f, 1f)
            val moveX = -14f + p * 62f // 从 -14f 移动到 +48f，直接跨上右侧屏幕边缘虚线
            val nearEdge = p > 0.65f
            Quint(66f, moveX, 1f, Offset(moveX, 0f), nearEdge)
        }
        else -> {
            val p = ((progress - 3300f) / 900f).coerceIn(0f, 1f)
            Quint(66f, 48f, 1f - p, Offset(48f, 0f), true)
        }
    }

    Box(
        modifier = Modifier
            .size(width = 176.dp, height = 112.dp)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        // 模拟右侧屏幕边缘基准线与目标周次提示
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(28.dp)
                .height(96.dp)
                .background(
                    color = if (isNearEdge) Color(0xFF60A5FA).copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                )
                .border(
                    width = 1.2.dp,
                    color = if (isNearEdge) Color(0xFF93C5FD).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isNearEdge) stringResource(R.string.onboarding_cross_week_release) else stringResource(R.string.onboarding_screen_edge),
                color = if (isNearEdge) Color.White else Color.White.copy(alpha = 0.5f),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = if (isNearEdge) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center
            )
        }

        // 模拟课程卡片
        Box(
            modifier = Modifier
                .offset(x = blockOffsetXDp.dp)
                .width(92.dp)
                .height(blockHeightDp.dp)
                .background(
                    color = if (isNearEdge) Color(0xFF2563EB).copy(alpha = 0.95f) else Color(0xFF3B82F6).copy(alpha = 0.88f),
                    shape = RoundedCornerShape(12.dp)
                )
                .border(
                    width = if (isNearEdge) 1.5.dp else 1.dp,
                    color = if (isNearEdge) Color(0xFFBAE6FD) else Color.White.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isNearEdge) stringResource(R.string.onboarding_cross_week_suspended) else stringResource(R.string.onboarding_demo_course),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenWith,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = if (isNearEdge) stringResource(R.string.onboarding_move_to_next_week) else stringResource(R.string.onboarding_long_press_drag),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 9.sp
                    )
                }
            }

            // 上端点
            if (handlesAlpha > 0.01f && !isNearEdge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (-5).dp, y = (-5).dp)
                        .size(11.dp)
                        .background(Color.White.copy(alpha = handlesAlpha), CircleShape)
                        .border(1.5.dp, Color(0xFF1D4ED8).copy(alpha = handlesAlpha), CircleShape)
                )
                // 下端点
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 5.dp, y = 5.dp)
                        .size(11.dp)
                        .background(Color.White.copy(alpha = handlesAlpha), CircleShape)
                        .border(1.5.dp, Color(0xFF1D4ED8).copy(alpha = handlesAlpha), CircleShape)
                )
            }
        }

        // 手指触控指示光标
        if (handlesAlpha > 0.05f) {
            Box(
                modifier = Modifier
                    .offset(x = touchIndicatorOffset.x.dp, y = touchIndicatorOffset.y.dp)
                    .size(24.dp)
                    .background(Color.White.copy(alpha = 0.28f * handlesAlpha), CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.9f * handlesAlpha), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color.White.copy(alpha = 0.95f * handlesAlpha), CircleShape)
                )
            }
        }
    }
}

private data class Quint<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

@Composable
private fun SwipeGestureAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "swipe")
    val offsetX by infiniteTransition.animateFloat(
        initialValue = -40f,
        targetValue = 40f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "swipeX"
    )
    Icon(
        imageVector = Icons.Rounded.Swipe,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
            .size(36.dp)
            .offset(x = offsetX.dp)
    )
}

@Composable
private fun TapGestureAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "tap")
    val offsetY by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tapY"
    )
    Icon(
        imageVector = Icons.Rounded.TouchApp,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
            .size(32.dp)
            .offset(y = offsetY.dp)
    )
}

