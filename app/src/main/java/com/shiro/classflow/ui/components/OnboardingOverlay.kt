package com.shiro.classflow.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiro.classflow.R
import com.shiro.classflow.Screen

private const val ONBOARDING_PREFS = "classflow_onboarding"
private const val KEY_COMPLETED = "onboarding_completed"

// Visual constants for consistency
private object OnboardingAlpha {
    const val OVERLAY = 0.32f // MD3 standard scrim
    const val SPOTLIGHT_BORDER = 0.70f
    const val INDICATOR_LINE = 0.80f
    const val BUBBLE_BG = 0.45f
    const val BUBBLE_BORDER = 0.70f
    const val SECONDARY_TEXT = 0.85f
    const val INACTIVE_DOT = 0.55f
}

private object OnboardingDimens {
    val CORNER_RADIUS = 20.dp
    val BORDER_WIDTH = 2.dp
    val INDICATOR_STROKE_WIDTH = 2.5.dp
    val ARROW_SIZE = 12.dp
    val SPOTLIGHT_PADDING = 12.dp
    val BUBBLE_PADDING_HORIZONTAL = 24.dp
    val BUBBLE_PADDING_VERTICAL = 20.dp
    val DOT_SIZE_ACTIVE = 12.dp
    val DOT_SIZE_INACTIVE = 8.dp
}

object OnboardingTargets {
    var semesterStartDateBoundsInWindow by mutableStateOf<Rect?>(null)
}

fun isOnboardingCompleted(context: Context): Boolean {
    return context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_COMPLETED, false)
}

fun markOnboardingCompleted(context: Context) {
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_COMPLETED, true)
        .apply()
}

private data class StepSpec(
    val titleRes: Int,
    val bodyRes: Int,
    val spotFraction: Rect,
    val bubbleAboveSpot: Boolean,
    val cornerDp: Float
)

private val onboardingSteps = listOf(
    StepSpec(
        titleRes = R.string.onboarding_title_1,
        bodyRes = R.string.onboarding_body_1,
        spotFraction = Rect(0.04f, 0.16f, 0.96f, 0.82f),
        bubbleAboveSpot = false,
        cornerDp = 20f
    ),
    StepSpec(
        titleRes = R.string.onboarding_title_2,
        bodyRes = R.string.onboarding_body_2,
        spotFraction = Rect(0.02f, 0.22f, 0.98f, 0.86f),
        bubbleAboveSpot = false,
        cornerDp = 20f
    ),
    StepSpec(
        titleRes = R.string.onboarding_title_4,
        bodyRes = R.string.onboarding_body_4,
        spotFraction = Rect(0.08f, 0.24f, 0.92f, 0.36f),
        bubbleAboveSpot = false,
        cornerDp = 20f
    )
)

@Composable
fun OnboardingOverlay(
    currentRoute: String?,
    onFinish: () -> Unit,
    onGoToSettings: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    var currentStep by remember { mutableIntStateOf(0) }
    val step = onboardingSteps[currentStep]
    val isLastStep = currentStep == onboardingSteps.lastIndex

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { }
    ) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }

        val defaultSpot = Rect(
            left = screenWidthPx * step.spotFraction.left,
            top = screenHeightPx * step.spotFraction.top,
            right = screenWidthPx * step.spotFraction.right,
            bottom = screenHeightPx * step.spotFraction.bottom
        )

        val rowSpot = OnboardingTargets.semesterStartDateBoundsInWindow?.let { rowRect ->
            val padding = with(density) { OnboardingDimens.SPOTLIGHT_PADDING.toPx() }
            Rect(
                left = (rowRect.left - padding).coerceAtLeast(0f),
                top = (rowRect.top - padding).coerceAtLeast(0f),
                right = (rowRect.right + padding).coerceAtMost(screenWidthPx),
                bottom = (rowRect.bottom + padding).coerceAtMost(screenHeightPx)
            )
        }

        val shouldUsePreciseSettingsTarget =
            isLastStep && currentRoute == Screen.Settings.route && rowSpot != null

        val spotlightRect = if (shouldUsePreciseSettingsTarget) rowSpot else defaultSpot

        val bubbleYFraction = when {
            step.bubbleAboveSpot -> {
                (spotlightRect.top / screenHeightPx - 0.28f).coerceIn(0.08f, 0.7f)
            }

            else -> {
                (spotlightRect.bottom / screenHeightPx + 0.03f).coerceIn(0.1f, 0.72f)
            }
        }

        val bubbleTopPx = screenHeightPx * bubbleYFraction
        val bubbleCenterX = screenWidthPx / 2f
        val bubbleAnchorY = if (step.bubbleAboveSpot) bubbleTopPx + 180f else bubbleTopPx
        val spotCenter = Offset(
            x = (spotlightRect.left + spotlightRect.right) / 2f,
            y = (spotlightRect.top + spotlightRect.bottom) / 2f
        )

        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer(alpha = 0.99f)) { // Force layer for BlendMode.Clear
            drawRect(Color.Black.copy(alpha = OnboardingAlpha.OVERLAY))
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(spotlightRect.left, spotlightRect.top),
                size = Size(
                    width = spotlightRect.width,
                    height = spotlightRect.height
                ),
                cornerRadius = CornerRadius(
                    with(density) { step.cornerDp.dp.toPx() },
                    with(density) { step.cornerDp.dp.toPx() }
                ),
                blendMode = BlendMode.Clear
            )
            drawRoundRect(
                color = Color.White.copy(alpha = OnboardingAlpha.SPOTLIGHT_BORDER),
                topLeft = Offset(spotlightRect.left, spotlightRect.top),
                size = Size(spotlightRect.width, spotlightRect.height),
                cornerRadius = CornerRadius(
                    with(density) { step.cornerDp.dp.toPx() },
                    with(density) { step.cornerDp.dp.toPx() }
                ),
                style = Stroke(width = with(density) { OnboardingDimens.BORDER_WIDTH.toPx() })
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val start = Offset(bubbleCenterX, bubbleAnchorY)
            val end = spotCenter

            // Calculate distance for dynamic curvature
            val distance = kotlin.math.sqrt(
                (end.x - start.x) * (end.x - start.x) +
                (end.y - start.y) * (end.y - start.y)
            )
            val curvature = distance * 0.25f

            val control = Offset(
                x = start.x * 0.4f + end.x * 0.6f, // Asymmetric bias toward target
                y = if (step.bubbleAboveSpot) start.y + curvature else start.y - curvature
            )

            val path = Path().apply {
                moveTo(start.x, start.y)
                quadraticTo(control.x, control.y, end.x, end.y)
            }

            val indicatorStrokeWidth = with(density) { OnboardingDimens.INDICATOR_STROKE_WIDTH.toPx() }
            drawPath(
                path = path,
                color = Color.White.copy(alpha = OnboardingAlpha.INDICATOR_LINE),
                style = Stroke(width = indicatorStrokeWidth, cap = StrokeCap.Round)
            )

            val arrowSize = with(density) { OnboardingDimens.ARROW_SIZE.toPx() }
            val direction = end - control
            val len = kotlin.math.sqrt(direction.x * direction.x + direction.y * direction.y).coerceAtLeast(1f)
            val ux = direction.x / len
            val uy = direction.y / len
            val perpX = -uy
            val perpY = ux

            val tip = end
            val arrowWidth = arrowSize * 0.65f // Wider angle
            val left = Offset(
                x = tip.x - ux * arrowSize + perpX * arrowWidth,
                y = tip.y - uy * arrowSize + perpY * arrowWidth
            )
            val right = Offset(
                x = tip.x - ux * arrowSize - perpX * arrowWidth,
                y = tip.y - uy * arrowSize - perpY * arrowWidth
            )

            drawLine(
                color = Color.White.copy(alpha = OnboardingAlpha.INDICATOR_LINE),
                start = left,
                end = tip,
                strokeWidth = indicatorStrokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White.copy(alpha = OnboardingAlpha.INDICATOR_LINE),
                start = right,
                end = tip,
                strokeWidth = indicatorStrokeWidth,
                cap = StrokeCap.Round
            )
        }

        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                (slideInHorizontally { it / 4 } + fadeIn())
                    .togetherWith(slideOutHorizontally { -it / 4 } + fadeOut())
            },
            label = "onboarding_content",
            modifier = Modifier.fillMaxSize()
        ) { stepIndex ->
            val s = onboardingSteps[stepIndex]

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OnboardingDimens.BUBBLE_PADDING_HORIZONTAL)
                    .padding(top = maxHeight * bubbleYFraction),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BubbleCard(
                    title = stringResource(s.titleRes),
                    body = stringResource(s.bodyRes),
                    primary = primary,
                    isLastStep = isLastStep,
                    lastStepReady = shouldUsePreciseSettingsTarget,
                    onLastStepClick = {
                        if (currentRoute != Screen.Settings.route) {
                            onGoToSettings()
                            return@BubbleCard
                        }

                        if (shouldUsePreciseSettingsTarget) {
                            markOnboardingCompleted(context)
                            onFinish()
                        }
                    }
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                onboardingSteps.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(
                                if (index == currentStep) OnboardingDimens.DOT_SIZE_ACTIVE
                                else OnboardingDimens.DOT_SIZE_INACTIVE
                            )
                            .clip(CircleShape)
                            .background(
                                if (index == currentStep) primary
                                else Color.White.copy(alpha = OnboardingAlpha.INACTIVE_DOT)
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        markOnboardingCompleted(context)
                        onFinish()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_skip),
                        color = Color.White.copy(alpha = OnboardingAlpha.SECONDARY_TEXT)
                    )
                }

                if (!isLastStep) {
                    Button(
                        onClick = { currentStep += 1 },
                        shape = RoundedCornerShape(OnboardingDimens.CORNER_RADIUS),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primary,
                            contentColor = Color.White
                        )
                    ) {
                        Text(text = stringResource(R.string.onboarding_next))
                    }
                }
            }
        }
    }
}

@Composable
private fun BubbleCard(
    title: String,
    body: String,
    primary: Color,
    isLastStep: Boolean,
    lastStepReady: Boolean,
    onLastStepClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OnboardingDimens.CORNER_RADIUS))
            .background(Color.Black.copy(alpha = OnboardingAlpha.BUBBLE_BG))
            .border(
                width = OnboardingDimens.BORDER_WIDTH,
                color = primary.copy(alpha = OnboardingAlpha.BUBBLE_BORDER),
                shape = RoundedCornerShape(OnboardingDimens.CORNER_RADIUS)
            )
            .padding(
                horizontal = OnboardingDimens.BUBBLE_PADDING_HORIZONTAL,
                vertical = OnboardingDimens.BUBBLE_PADDING_VERTICAL
            )
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium.copy(
                lineHeight = 21.sp
            ),
            color = Color.White.copy(alpha = 0.9f),
            textAlign = TextAlign.Center
        )

        if (isLastStep) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onLastStepClick,
                shape = RoundedCornerShape(OnboardingDimens.CORNER_RADIUS),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primary,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = if (lastStepReady) {
                        stringResource(R.string.action_confirm)
                    } else {
                        stringResource(R.string.onboarding_next)
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

