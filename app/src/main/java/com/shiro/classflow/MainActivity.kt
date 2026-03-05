package com.shiro.classflow

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.canopas.lib.showcase.IntroShowcase
import com.canopas.lib.showcase.IntroShowcaseScope
import com.canopas.lib.showcase.component.IntroShowcaseState
import com.canopas.lib.showcase.component.ShowcaseStyle
import com.canopas.lib.showcase.component.rememberIntroShowcaseState
import com.shiro.classflow.ui.components.BottomNavigationBar
import com.shiro.classflow.ui.components.isOnboardingCompleted
import com.shiro.classflow.ui.components.markOnboardingCompleted
import com.shiro.classflow.ui.schedule.WeeklyScheduleScreen
import com.shiro.classflow.ui.schoolselection.web.WebViewScreen
import com.shiro.classflow.ui.settings.SettingsScreen
import com.shiro.classflow.ui.settings.additional.MoreOptionsScreen
import com.shiro.classflow.ui.settings.additional.OpenSourceLicensesScreen
import com.shiro.classflow.ui.settings.contribution.ContributionScreen
import com.shiro.classflow.ui.settings.conversion.CourseTableConversionScreen
import com.shiro.classflow.ui.settings.course.AddEditCourseScreen
import com.shiro.classflow.ui.settings.coursemanagement.COURSE_NAME_ARG
import com.shiro.classflow.ui.settings.coursemanagement.CourseInstanceListScreen
import com.shiro.classflow.ui.settings.coursemanagement.CourseNameListScreen
import com.shiro.classflow.ui.settings.coursetables.ManageCourseTablesScreen
import com.shiro.classflow.ui.settings.quickactions.QuickActionsScreen
import com.shiro.classflow.ui.settings.quickactions.delete.QuickDeleteScreen
import com.shiro.classflow.ui.settings.quickactions.tweaks.TweakScheduleScreen
import com.shiro.classflow.ui.settings.style.StyleSettingsScreen
import com.shiro.classflow.ui.settings.style.WallpaperAdjustScreen
import com.shiro.classflow.ui.settings.time.TimeSlotManagementScreen
import com.shiro.classflow.ui.settings.update.UpdateRepoScreen
import com.shiro.classflow.ui.theme.ClassFlowTheme
import com.shiro.classflow.ui.today.TodayScheduleScreen

class MainActivity : ComponentActivity() {
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
            ClassFlowTheme {
                AppNavigation()
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
fun AppNavigation() {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val context = LocalContext.current
    val topLevelRoutes = setOf(
        Screen.TodaySchedule.route,
        Screen.CourseSchedule.route,
        Screen.Settings.route
    )
    val showBottomDock = currentRoute in topLevelRoutes

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

    val smoothEnter = slideInHorizontally(tween(260)) { it / 4 } + fadeIn(tween(200))
    val smoothExit = slideOutHorizontally(tween(260)) { -it / 6 } + fadeOut(tween(160))
    val smoothPopEnter = slideInHorizontally(tween(260)) { -it / 6 } + fadeIn(tween(200))
    val smoothPopExit = slideOutHorizontally(tween(260)) { it / 4 } + fadeOut(tween(160))

    // Keep onboarding on the expected route for each step.
    LaunchedEffect(showOnboarding, introShowcaseState.currentTargetIndex, currentRoute, pendingSyncStepAdvance) {
        if (!showOnboarding) return@LaunchedEffect
        val currentIndex = introShowcaseState.currentTargetIndex
        if (pendingSyncStepAdvance && currentIndex != 2) {
            pendingSyncStepAdvance = false
        }
        when {
            pendingSyncStepAdvance && currentRoute != Screen.Settings.route -> {
                navController.navigate(Screen.Settings.route) {
                    popUpTo(Screen.CourseSchedule.route) { inclusive = false }
                    launchSingleTop = true
                }
            }

            pendingSyncStepAdvance && currentRoute == Screen.Settings.route && currentIndex == 2 -> {
                introShowcaseState.goToNext(
                    onComplete = completeOnboarding,
                    allowCompleteOnMissingTarget = false
                )
                pendingSyncStepAdvance = false
            }

            currentIndex < LAST_ONBOARDING_TARGET_INDEX &&
                currentRoute != Screen.CourseSchedule.route -> {
                navController.navigate(Screen.CourseSchedule.route) {
                    popUpTo(Screen.CourseSchedule.route) { inclusive = false }
                    launchSingleTop = true
                }
            }

            currentIndex == LAST_ONBOARDING_TARGET_INDEX &&
                currentRoute != Screen.Settings.route -> {
            navController.navigate(Screen.Settings.route) {
                popUpTo(Screen.CourseSchedule.route) { inclusive = false }
                launchSingleTop = true
            }
            }
        }
    }

    val showcaseStyle = ShowcaseStyle.Default.copy(
        backgroundColor = Color(0xFF0F1A2C),
        backgroundAlpha = 0.94f,
        targetCircleColor = Color.White
    )

    Box(modifier = Modifier.fillMaxSize()) {
        IntroShowcase(
            showIntroShowCase = showOnboarding,
            state = introShowcaseState,
            dismissOnClickOutside = false,
            // Completion is controlled by explicit step actions to avoid race conditions
            // while transitioning from the sync step to Settings.
            onShowCaseCompleted = {}
        ) {
            // ── Step 0: Welcome  +  Step 1: Swipe ── both target weekTitle
            val weekTitleTargetModifier =
                if (showOnboarding && currentRoute == Screen.CourseSchedule.route) {
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
                if (showOnboarding && currentRoute == Screen.CourseSchedule.route) {
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
                    currentRoute == Screen.Settings.route &&
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
                if (showOnboarding && currentRoute == Screen.Settings.route) {
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

            NavHost(
                navController = navController,
                startDestination = Screen.CourseSchedule.route,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { smoothEnter },
                exitTransition = { smoothExit },
                popEnterTransition = { smoothPopEnter },
                popExitTransition = { smoothPopExit }
            ) {
        // 底部导航栏顶级页面：丝滑渐变+缩放 — 类似 Liquid Glass 过渡
        composable(
            Screen.CourseSchedule.route,
            enterTransition = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.94f) },
            exitTransition = { fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.94f) },
            popEnterTransition = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.94f) },
            popExitTransition = { fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.94f) }
        ) {
            WeeklyScheduleScreen(
                navController = navController,
                weekTitleModifier = weekTitleTargetModifier,
                syncButtonModifier = syncButtonTargetModifier,
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
                            if (currentRoute != Screen.Settings.route) {
                                navController.navigate(Screen.Settings.route) {
                                    popUpTo(Screen.CourseSchedule.route) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        }
                        true
                    }
                }
            )
        }

        composable(
            Screen.Settings.route,
            enterTransition = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.94f) },
            exitTransition = { fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.94f) },
            popEnterTransition = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.94f) },
            popExitTransition = { fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.94f) }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(syncStepTransitionModifier)
            ) {
                SettingsScreen(
                    navController = navController,
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
        }

        composable(
            Screen.TodaySchedule.route,
            enterTransition = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.94f) },
            exitTransition = { fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.94f) },
            popEnterTransition = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.94f) },
            popExitTransition = { fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.94f) }
        ) {
            TodayScheduleScreen(navController = navController)
        }

        // 子页面：继承 NavHost 级别的丝滑滑动+渐变动画
        composable(Screen.TimeSlotSettings.route) {
            TimeSlotManagementScreen(onBackClick = { navController.popBackStack() })
        }

        composable(Screen.ManageCourseTables.route) {
            ManageCourseTablesScreen(navController = navController)
        }

        composable(
            route = Screen.WebView.route,
            arguments = listOf(
                navArgument("initialUrl") { type = NavType.StringType },
                navArgument("assetJsPath") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val initialUrl = backStackEntry.arguments?.getString("initialUrl")
            val assetJsPath = backStackEntry.arguments?.getString("assetJsPath")

            val context = LocalContext.current
            val app = context.applicationContext as MyApplication

            WebViewScreen(
                navController = navController,
                initialUrl = initialUrl,
                assetJsPath = assetJsPath,
                courseConversionRepository = app.courseConversionRepository,
                timeSlotRepository = app.timeSlotRepository,
                appSettingsRepository = app.appSettingsRepository,
                courseScheduleRoute = Screen.CourseSchedule.route,
            )
        }

        composable(
            route = Screen.AddEditCourse.route,
            arguments = listOf(
                navArgument("courseId") {
                    type = NavType.StringType
                    nullable = true
                }
            )
        ) { backStackEntry ->
            val courseId = backStackEntry.arguments?.getString("courseId")
            AddEditCourseScreen(
                courseId = courseId,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.CourseTableConversion.route) {
            CourseTableConversionScreen(navController = navController)
        }

        composable(Screen.MoreOptions.route) {
            MoreOptionsScreen(navController = navController)
        }

        composable(Screen.OpenSourceLicenses.route) {
            OpenSourceLicensesScreen(navController = navController)
        }

        composable(Screen.QuickActions.route) {
            QuickActionsScreen(navController = navController)
        }

        composable(Screen.TweakSchedule.route) {
            TweakScheduleScreen(navController = navController)
        }

        composable(Screen.ContributionList.route) {
            ContributionScreen(navController = navController)
        }

        composable(Screen.CourseManagementList.route) {
            CourseNameListScreen(navController = navController)
        }

        composable(
            route = Screen.CourseManagementDetail.route,
            arguments = listOf(navArgument(COURSE_NAME_ARG) { type = NavType.StringType })
        ) { backStackEntry ->
            val courseName = Uri.decode(backStackEntry.arguments?.getString(COURSE_NAME_ARG) ?: "")
            CourseInstanceListScreen(
                courseName = courseName,
                onNavigateBack = { navController.popBackStack() },
                navController = navController
            )
        }

            composable(Screen.StyleSettings.route) {
                StyleSettingsScreen(navController = navController)
            }

            composable(Screen.WallpaperAdjust.route) {
                WallpaperAdjustScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.QuickDelete.route) {
                QuickDeleteScreen(navController = navController)
            }

            composable(Screen.UpdateRepo.route) {
                UpdateRepoScreen(navController = navController)
            }
            }

            if (showBottomDock) {
                Box(modifier = bottomNavTargetModifier.align(Alignment.BottomCenter)) {
                    BottomNavigationBar(
                        navController = navController,
                        currentRoute = currentRoute,
                        isTransparent = true,
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


