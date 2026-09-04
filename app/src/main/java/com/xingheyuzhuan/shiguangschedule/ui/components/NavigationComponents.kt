package com.xingheyuzhuan.shiguangschedule.ui.components
import com.xingheyuzhuan.shiguangschedule.ui.theme.LocalIsDarkTheme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.Destination
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.abs

/** Extra bottom content-padding for top-level screens overlaid by the floating dock. */
val DockSafeBottomPadding = 88.dp

/** 平板/宽屏左侧导航栏的宽度 (dp)。宽屏下各主屏「内容层」按此值让位（背景保持全屏铺满）。 */
val NavigationRailWidth = 112.dp

/**
 * 平板/宽屏判定：仅看屏幕宽度 ≥ 600dp。
 * 说明：按需求「目前允许手机横屏触发」，因此用宽度而非最短边——手机横屏（宽常 ≥ 600dp）也会进入左侧导航栏模式。
 * 各主屏用它给内容层加左内边距（背景不动）。
 */
val isWideScreen: Boolean
    @Composable get() = LocalConfiguration.current.screenWidthDp >= 600

/**
 * Liquid Glass bottom navigation bar.
 *
                                        popUpTo(navController.graph.startDestinationId)
 * - Tinted glass surface (not pure white) for contrast
                                        // Use deterministic tab navigation to avoid restoring stale
                                        // Settings stack after onboarding/semester-date operations.
                                        restoreState = false
 * - Specular highlight and glass sheen on the indicator
 * - Compact water-drop indicator around icon (not long bar)
 */
@Composable
fun BottomNavigationBar(
    navBridge: NavBridge,
    currentDestination: Destination?,
    isTransparent: Boolean = false,
    hazeState: HazeState? = null,
    onTabClickIntercept: ((Destination) -> Boolean)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val density = LocalDensity.current
    val navItems = listOf(
        Triple(stringResource(R.string.nav_today_schedule), Destination.TodaySchedule, 0),
        Triple(stringResource(R.string.nav_course_schedule), Destination.CourseSchedule, 1),
        Triple(stringResource(R.string.nav_settings), Destination.Settings, 2)
    )

    val effectiveDestination = currentDestination ?: Destination.CourseSchedule
    val selectedIndex = navItems.indexOfFirst { it.second == effectiveDestination }.coerceAtLeast(0)
    val glassShape = RoundedCornerShape(22.dp)

    // ── Glass surface tint (Apple Liquid Glass: warm-white light / cool-gray dark) ──
    val glassTint = if (isDark) {
        Color(0xFF1A2332).copy(alpha = if (isTransparent) 0.18f else 0.22f)
    } else {
        Color(0xFFFFFBF8).copy(alpha = if (isTransparent) 0.68f else 0.75f)
    }

    // ── Glass border (top-lit gradient for 3D refraction) ──
    val borderTop = if (isDark) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.90f)
    val borderBottom = if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.25f)

    // ── Drop shadow color ──
    val shadowColor = if (isDark) Color.Black.copy(alpha = 0.35f) else Color(0xFF93AAC8).copy(alpha = 0.12f)

    var rowWidthPx by remember { mutableIntStateOf(0) }

    // ── Liquid-flow position animation ──
    // Use Animatable to snap on first frame (no animation from wrong position)
    val targetFraction = (selectedIndex + 0.5f) / navItems.size
    val animatable = remember { Animatable(targetFraction) }
    var previousIndex by remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(targetFraction) {
        if (abs(animatable.value - targetFraction) < 0.01f && previousIndex == selectedIndex) {
            // First composition or same tab — snap immediately
            animatable.snapTo(targetFraction)
        } else {
            // Tab switch — directional two-stage liquid motion (overshoot then settle)
            val hop = abs(selectedIndex - previousIndex).coerceAtLeast(1)
            val direction = if (selectedIndex >= previousIndex) 1f else -1f
            val overshoot = 0.032f / hop

            animatable.animateTo(
                targetFraction + direction * overshoot,
                animationSpec = tween(durationMillis = 120)
            )

            animatable.animateTo(
                targetFraction,
                animationSpec = spring(
                    dampingRatio = if (hop == 1) 0.58f else 0.68f,
                    stiffness = if (hop == 1) 210f else 260f
                )
            )
        }

        previousIndex = selectedIndex
    }
    val animatedFraction = animatable.value

    // ── Liquid stretch: indicator widens while moving ──
    val distanceFromTarget = abs(animatedFraction - targetFraction)
    val stretchMultiplier = 1f + distanceFromTarget * 1.45f

    // Compact droplet around icon only (avoids long strip look).
    val indicatorWidthFraction = 0.25f
    val indicatorHeightDp = 44.dp
    val indicatorCornerDp = 22.dp

    // Pre-compute pixel values
    val indicatorHeightPx = with(density) { indicatorHeightDp.toPx() }
    val indicatorCornerPx = with(density) { indicatorCornerDp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Swallow taps on the nav bar's padding/gap regions so they don't
            // pass through to the screen content underneath. Child tab
            // clickables still take priority for taps on the icons.
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { }
            .navigationBarsPadding()
            .padding(horizontal = 14.dp)
            .padding(bottom = 4.dp)
    ) {
        // ── Glass container with drop shadow for 3D depth ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(glassShape)
                .shadow(
                    elevation = if (isDark) 12.dp else 8.dp,
                    shape = glassShape,
                    ambientColor = shadowColor,
                    spotColor = shadowColor
                )
                .then(
                    if (hazeState != null) {
                        Modifier.hazeEffect(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = glassTint.copy(
                                    alpha = if (isDark) 0.28f else 0.42f
                                ),
                                tint = null,
                                blurRadius = 20.dp
                            )
                        )
                    } else {
                        Modifier.background(glassTint, glassShape)
                    }
                )
                .border(
                    width = 0.75.dp,
                    brush = Brush.verticalGradient(listOf(borderTop, borderBottom)),
                    shape = glassShape
                )
                .height(60.dp),
            contentAlignment = Alignment.Center
        ) {
            // ── Inner content row with animated indicator ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .onSizeChanged { rowWidthPx = it.width }
                    .drawBehind {
                        if (rowWidthPx <= 0) return@drawBehind

                        val baseWidth = size.width * indicatorWidthFraction
                        val iw = baseWidth * stretchMultiplier  // Liquid stretch
                        val ih = indicatorHeightPx
                        val cr = indicatorCornerPx
                        val cx = animatedFraction * size.width
                        val left = cx - iw / 2
                        val top = (size.height - ih) / 2

                        // Capsule indicator with soft glass gradient
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.22f),
                                    Color(if (isDark) 0xFFDCEBFF else 0xFFF5F8FF).copy(alpha = 0.18f)
                                ),
                                startY = top,
                                endY = top + ih
                            ),
                            topLeft = Offset(left, top),
                            size = Size(iw, ih),
                            cornerRadius = CornerRadius(cr, cr)
                        )
                    },
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                navItems.forEach { (label, destination, index) ->
                    val isSelected = selectedIndex == index

                    val scale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1f,
                        animationSpec = spring(
                            dampingRatio = 0.45f,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "scale$index"
                    )

                    val iconAlpha by animateFloatAsState(
                        targetValue = if (isSelected) 1f else 0.50f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "alpha$index"
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                if (onTabClickIntercept?.invoke(destination) == true) {
                                    return@clickable
                                }
                                // Use the actual destination for click-guard.
                                // If currentDestination is temporarily null, tabs must still be clickable.
                                if (currentDestination != destination) {
                                    navBridge.navigateToMain(destination)
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val (selIcon, unselIcon) = when (destination) {
                            Destination.TodaySchedule -> Icons.Rounded.Today to Icons.Outlined.Today
                            Destination.CourseSchedule -> Icons.Rounded.CalendarMonth to Icons.Outlined.CalendarMonth
                            else -> Icons.Rounded.Person to Icons.Outlined.Person
                        }

                        Icon(
                            imageVector = if (isSelected) selIcon else unselIcon,
                            contentDescription = label,
                            modifier = Modifier
                                .size(21.dp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = iconAlpha
                                },
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )

                    }
                }
            }
        }
    }
}

/**
 * 平板/宽屏左侧导航栏（NavigationRail，ClassFlow 定制）。
 *
 * 仅在宽屏（宿主判定：屏幕宽度 ≥ 600dp）时由 MainActivity 显示；
 * 采用竖向玻璃胶囊，按钮逻辑与 [BottomNavigationBar] 一致（图标/选中高亮/点击切换）。
 *
 * 注意：按需求「目前允许手机横屏触发」，宿主用「宽度 ≥ 600dp」判定而非最短边——
 * 因此手机横屏（宽 ≥ 600dp）也会进入本左侧导航栏模式。
 *
 * @param hazeState 玻璃模糊源（复用底部 dock 的 dockHazeState）；为 null 时回退纯半透明表面。
 */
@Composable
fun LeftNavigationRail(
    navBridge: NavBridge,
    currentDestination: Destination?,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val navItems = listOf(
        Triple(stringResource(R.string.nav_today_schedule), Destination.TodaySchedule, 0),
        Triple(stringResource(R.string.nav_course_schedule), Destination.CourseSchedule, 1),
        Triple(stringResource(R.string.nav_settings), Destination.Settings, 2)
    )
    val effectiveDestination = currentDestination ?: Destination.CourseSchedule
    val selectedIndex = navItems.indexOfFirst { it.second == effectiveDestination }.coerceAtLeast(0)

    val glassShape = RoundedCornerShape(22.dp)
    // 与底部导航栏一致的液态玻璃：暖白亮 / 冷灰暗
    val glassTint = if (isDark) Color(0xFF1A2332).copy(alpha = 0.22f) else Color(0xFFFFFBF8).copy(alpha = 0.75f)
    val hazeTint = glassTint.copy(alpha = if (isDark) 0.28f else 0.42f)
    val borderTop = if (isDark) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.90f)
    val borderBottom = if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.25f)

    val density = LocalDensity.current

    // ── 竖向水滴滑动动画（对齐底部导航栏动画逻辑）──
    val animatable = remember { Animatable(selectedIndex.toFloat()) }
    var previousIndex by remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex) {
        if (abs(animatable.value - selectedIndex.toFloat()) < 0.01f && previousIndex == selectedIndex) {
            animatable.snapTo(selectedIndex.toFloat())
        } else {
            val hop = abs(selectedIndex - previousIndex).coerceAtLeast(1)
            val direction = if (selectedIndex >= previousIndex) 1f else -1f
            val overshoot = 0.08f / hop

            animatable.animateTo(
                selectedIndex.toFloat() + direction * overshoot,
                animationSpec = tween(durationMillis = 120)
            )

            animatable.animateTo(
                selectedIndex.toFloat(),
                animationSpec = spring(
                    dampingRatio = if (hop == 1) 0.58f else 0.68f,
                    stiffness = if (hop == 1) 210f else 260f
                )
            )
        }
        previousIndex = selectedIndex
    }
    val animatedIndex = animatable.value

    // 水滴纵向拉伸：移动时胶囊变长
    val distanceFromTarget = abs(animatedIndex - selectedIndex.toFloat())
    val stretchMultiplier = 1f + distanceFromTarget * 0.45f

    val bubbleWidthDp = 46.dp
    val bubbleBaseHeightDp = 72.dp
    val itemSpacingDp = 12.dp
    val itemStrideDp = bubbleBaseHeightDp + itemSpacingDp

    val bubbleWidthPx = with(density) { bubbleWidthDp.toPx() }
    val bubbleBaseHeightPx = with(density) { bubbleBaseHeightDp.toPx() }
    val itemStridePx = with(density) { itemStrideDp.toPx() }

    Box(
        modifier = modifier
            .width(NavigationRailWidth)
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(glassShape)
                .then(
                    if (hazeState != null) {
                        Modifier.hazeEffect(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = hazeTint,
                                tint = null,
                                blurRadius = 20.dp
                            )
                        )
                    } else {
                        Modifier.background(glassTint, glassShape)
                    }
                )
                .border(0.75.dp, Brush.verticalGradient(listOf(borderTop, borderBottom)), glassShape)
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 3 个 tab 的容器，承载平滑滑动的水滴泡泡 drawBehind
            Column(
                modifier = Modifier
                    .drawBehind {
                        val animatedCenterY = animatedIndex * itemStridePx + bubbleBaseHeightPx / 2
                        val currentHeight = bubbleBaseHeightPx * stretchMultiplier
                        val topY = animatedCenterY - currentHeight / 2
                        val leftX = (size.width - bubbleWidthPx) / 2
                        val cornerRadius = bubbleWidthPx / 2

                        // 胶囊水滴：玻璃微光渐变
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.22f),
                                    Color(if (isDark) 0xFFDCEBFF else 0xFFF5F8FF).copy(alpha = 0.18f)
                                ),
                                startY = topY,
                                endY = topY + currentHeight
                            ),
                            topLeft = Offset(leftX, topY),
                            size = Size(bubbleWidthPx, currentHeight),
                            cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                navItems.forEachIndexed { index, (label, destination, _) ->
                    val isSelected = selectedIndex == index

                    val scale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1f,
                        animationSpec = spring(
                            dampingRatio = 0.45f,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "railScale$index"
                    )
                    val iconAlpha by animateFloatAsState(
                        targetValue = if (isSelected) 1f else 0.50f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "railAlpha$index"
                    )

                    val (selIcon, unselIcon) = when (destination) {
                        Destination.TodaySchedule -> Icons.Rounded.Today to Icons.Outlined.Today
                        Destination.CourseSchedule -> Icons.Rounded.CalendarMonth to Icons.Outlined.CalendarMonth
                        else -> Icons.Rounded.Person to Icons.Outlined.Person
                    }

                    // 仅图标，不显示文本（ClassFlow 定制）
                    Box(
                        modifier = Modifier
                            .size(width = bubbleWidthDp, height = bubbleBaseHeightDp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                if (currentDestination != destination) {
                                    navBridge.navigateToMain(destination)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isSelected) selIcon else unselIcon,
                            contentDescription = label,
                            modifier = Modifier
                                .size(26.dp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = iconAlpha
                                },
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (index < navItems.lastIndex) {
                        Spacer(modifier = Modifier.height(itemSpacingDp))
                    }
                }
            }
        }
    }
}

