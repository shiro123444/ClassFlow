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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.Destination
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.abs

/** Extra bottom content-padding for top-level screens overlaid by the floating dock. */
val DockSafeBottomPadding = 88.dp

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
                                    Color.White.copy(alpha = if (isDark) 0.22f else 0.88f),
                                    Color(if (isDark) 0xFFDCEBFF else 0xFFF5F8FF)
                                        .copy(alpha = if (isDark) 0.18f else 0.65f)
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

