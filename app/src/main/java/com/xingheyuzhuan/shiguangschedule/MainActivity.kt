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
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.xingheyuzhuan.shiguangschedule.data.model.AppSettingsModel
import com.xingheyuzhuan.shiguangschedule.data.model.AppThemeMode
import com.xingheyuzhuan.shiguangschedule.data.model.StartScreen
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseConversionRepository
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
import com.xingheyuzhuan.shiguangschedule.ui.settings.coursemanagement.COURSE_NAME_ARG
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
    timeSlotRepository: TimeSlotRepository,
    appSettingsRepository: AppSettingsRepository
) {
    val backStack = rememberNavBackStack(startDestination)
    val currentDestination = backStack.lastOrNull() as? Destination
    val context = LocalContext.current
    // 悬浮课程模式时隐藏底部导航栏（上游同步）
    var isFloatingCourseMode by remember { mutableStateOf(false) }
    val showBottomDock = currentDestination?.isMainScreen == true && !isFloatingCourseMode

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
    var pendingSyncStepAdvance by remember { mutableStateOf(false) }
    val completeOnboarding = {
        markOnboardingCompleted(context)
        showOnboarding = false
        pendingSyncStepAdvance = false
    }

    BackHandler(enabled = showOnboarding) {
        // Lock onboarding flow: do not allow mid-way back exit.
    }

    // Keep onboarding on the expected route for each step.
    LaunchedEffect(showOnboarding, introShowcaseState.currentTargetIndex, currentDestination, pendingSyncStepAdvance) {
        if (!showOnboarding) return@LaunchedEffect
        val currentIndex = introShowcaseState.currentTargetIndex
        if (pendingSyncStepAdvance && currentIndex != 2) {
            pendingSyncStepAdvance = false
        }
        when {
            pendingSyncStepAdvance && currentDestination !is Destination.Settings -> {
                navBridge.navigateToMain(Destination.Settings)
            }

            pendingSyncStepAdvance && currentDestination is Destination.Settings && currentIndex == 2 -> {
                introShowcaseState.goToNext(
                    onComplete = completeOnboarding,
                    allowCompleteOnMissingTarget = false
                )
                pendingSyncStepAdvance = false
            }

            currentIndex < LAST_ONBOARDING_TARGET_INDEX &&
                currentDestination !is Destination.CourseSchedule -> {
                navBridge.navigateToMain(Destination.CourseSchedule)
            }

            currentIndex == LAST_ONBOARDING_TARGET_INDEX &&
                currentDestination !is Destination.Settings -> {
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
            dismissOnClickOutside = false,
            onShowCaseCompleted = {
                // Canopas callback fires both on true finish and on temporary missing-target transitions.
                // Only complete when the index has actually moved beyond the last onboarding step.
                if (showOnboarding && introShowcaseState.currentTargetIndex > LAST_ONBOARDING_TARGET_INDEX) {
                    completeOnboarding()
                }
            }
        ) {
            // ── Step 0: Welcome  +  Step 1: Swipe ── both target weekTitle
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
                } else {
                    Modifier
                }

            // ── Step 2: Sync button ──
            val syncButtonTargetModifier =
                if (showOnboarding && currentDestination is Destination.CourseSchedule) {
                    Modifier.introShowCaseTarget(
                        index = 2,
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

            // Transition anchor: keep step-2 target available while moving to Settings,
            // so the showcase does not complete early on a missing target.
            val syncStepTransitionModifier =
                if (
                    showOnboarding &&
                    pendingSyncStepAdvance &&
                    currentDestination is Destination.Settings &&
                    introShowcaseState.currentTargetIndex == 2
                ) {
                    Modifier.introShowCaseTarget(
                        index = 2,
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

            // ── Dock step disabled (library popup placement is unstable on extra-wide dock target) ──
            val bottomNavTargetModifier =
                Modifier

            // ── Step 3: Semester start date (Settings page) ──
            val semesterSettingTargetModifier =
                if (showOnboarding && currentDestination is Destination.Settings) {
                    Modifier.introShowCaseTarget(
                        index = 3,
                        style = showcaseStyle.copy(backgroundColor = Color(0xFF12222E)),
                        content = {
                            OnboardingCard(
                                title = stringResource(R.string.onboarding_title_5),
                                body = stringResource(R.string.onboarding_body_5),
                                isLastStep = true,
                                icon = { TapGestureAnimation() },
                                showcaseState = introShowcaseState,
                                onComplete = completeOnboarding
                            )
                        }
                    )
                } else {
                    Modifier
                }

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
                                    if (showOnboarding && introShowcaseState.currentTargetIndex in 0..1) {
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
                                        if (introShowcaseState.currentTargetIndex == 2) {
                                            pendingSyncStepAdvance = true
                                            if (currentDestination !is Destination.Settings) {
                                                navBridge.navigateToMain(Destination.Settings)
                                            }
                                        }
                                        true
                                    }
                                }
                            )

                            Destination.Settings -> Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(syncStepTransitionModifier)
                            ) {
                                SettingsScreen(
                                    navBridge = navBridge,
                                    semesterStartDateItemModifier = semesterSettingTargetModifier,
                                    forceShowSemesterStartDateCard = showOnboarding &&
                                        introShowcaseState.currentTargetIndex == LAST_ONBOARDING_TARGET_INDEX,
                                    onSemesterStartDateSet = {
                                        if (
                                            showOnboarding &&
                                            introShowcaseState.currentTargetIndex == LAST_ONBOARDING_TARGET_INDEX
                                        ) {
                                            completeOnboarding()
                                        }
                                    }
                                )
                            }

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
        isLastStep -> "? 点击任意处完成"
        advanceByTapAnywhere -> "点击任意处继续 →"
        else -> "请点击右上角同步按钮继续 →"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
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
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        if (icon != null) {
            icon()
            Spacer(modifier = Modifier.height(12.dp))
        }
        Text(
            text = title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = body,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = continueHint,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        }
    }
}

private const val LAST_ONBOARDING_TARGET_INDEX = 3

// ── Gesture hint animations ──

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

