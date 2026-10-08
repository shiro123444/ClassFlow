package com.xingheyuzhuan.shiguangschedule.ui.campus.shower

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** 出水孔 / 水柱的列数（同时决定水滴的错峰相位）。 */
private const val COLUMN_COUNT = 5

/**
 * 每个出水孔那颗下落水珠的「大小 / 相位 / 速度」。
 *
 * 三个数都故意不成比例，也故意不按列递增：一排整齐同步落下的水珠看着很假，
 * 错开之后才像散落的水。大小只有两档 —— 就是两根长短不同的**长方形**水条。
 */
private val DROP_SIZE = floatArrayOf(1.00f, 0.60f, 1.00f, 0.60f, 1.00f)
private val DROP_OFFSET = floatArrayOf(0.00f, 0.61f, 0.27f, 0.83f, 0.42f)

/**
 * 每个出水孔那颗水珠在一个周期里落几次。
 *
 * **必须是整数**：小数速度（比如 1.38）在动画循环回卷的那一刻相位对不上，
 * 水珠会从底部瞬间跳回顶部 —— 看起来就是「抽搐」。
 */
private val DROP_SPEED = floatArrayOf(2.00f, 1.00f, 1.00f, 2.00f, 1.00f)

/** 水柱长度呼吸的周期（比水珠下落慢得多：水柱一秒抖一次同样像抽搐）。 */
private const val FLOW_PERIOD_MS = 5_000

/** 一颗水珠从出水孔落到画面底部的周期（比水柱起伏快一点，看着才像在落）。 */
private const val FALL_PERIOD_MS = 1_150

/** 底光的呼吸周期。 */
private const val PULSE_PERIOD_MS = 2_400

/**
 * 淋浴插画（**纯矢量**：整幅图由 Canvas 的圆角矩形、圆、长方形水条与渐变画出来，不含任何位图）。
 *
 * 两个状态共用同一套几何：
 * - `active = false`：淋浴头静静挂着几颗水珠 + 一圈安静的底光，示意「随时可以开始」；
 * - `active = true`：五道水柱带着起伏、五根长短不一的水条错峰落下，底光随呼吸轻微明暗 —— 表示正在出水。
 *
 * 待机时**不创建**动画（不排帧，也就不耗电）；颜色全部取自主题色板，深浅色模式自动适配。
 */
@Composable
fun ShowerIllustration(
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val fixture = scheme.onSurfaceVariant
    val fixtureSoft = scheme.onSurfaceVariant.copy(alpha = 0.45f)
    val water = scheme.primary
    // 光晕用 primary 而不是 primaryContainer：浅色主题里 primaryContainer 几乎就是底色，
    // 铺上去看不出来，primary 低透明度在深浅两套主题里都是一圈看得见的柔光
    val halo = scheme.primary

    // 只在用水中创建无限动画；读值放在 draw 阶段（只重绘、不重组）
    val fallState: State<Float>?
    val flowState: State<Float>?
    val pulseState: State<Float>?
    if (active) {
        val transition = rememberInfiniteTransition(label = "showerWaterFlow")
        fallState = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(FALL_PERIOD_MS, easing = LinearEasing)),
            label = "showerWaterFall"
        )
        pulseState = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(PULSE_PERIOD_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "showerWaterPulse"
        )
        // 水柱起伏走的是一条 0..1..0（正弦整周期）的慢曲线：回卷处首尾相接，不会突然弹一下
        flowState = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(FLOW_PERIOD_MS, easing = LinearEasing)),
            label = "showerWaterFlow"
        )
    } else {
        fallState = null
        flowState = null
        pulseState = null
    }

    Canvas(modifier = modifier) {
        drawShower(
            progress = fallState?.value ?: 0f,
            flow = flowState?.value ?: 0f,
            pulse = pulseState?.value ?: 0f,
            active = active,
            water = water,
            halo = halo,
            fixture = fixture,
            fixtureSoft = fixtureSoft
        )
    }
}

/**
 * 画一整幅淋浴图：底光 → 立管 → 淋浴头 →（出水孔）→ 水柱 / 水滴。
 *
 * 所有尺度都按画布宽高取比例，所以同一套代码在方形（待机）和竖长（用水中）容器里都不会变形。
 */
private fun DrawScope.drawShower(
    progress: Float,
    flow: Float,
    pulse: Float,
    active: Boolean,
    water: Color,
    halo: Color,
    fixture: Color,
    fixtureSoft: Color
) {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) return

    val centerX = width / 2f
    val unit = min(width, height)

    val headTop = height * 0.08f
    val headHeight = (unit * 0.14f).coerceAtLeast(6.dp.toPx())
    val headHalf = min(width * 0.34f, unit * 0.36f)
    val headBottom = headTop + headHeight
    val armWidth = (unit * 0.05f).coerceAtLeast(3.dp.toPx())

    val nozzleRadius = (unit * 0.013f).coerceAtLeast(1.2.dp.toPx())
    val streamWidth = (unit * 0.022f).coerceAtLeast(1.8.dp.toPx())
    val barWidth = (unit * 0.026f).coerceAtLeast(2.4.dp.toPx())
    val hangBarWidth = barWidth * 0.55f
    val fallSpan = (height - headBottom).coerceAtLeast(1f)
    val step = headHalf * 1.30f / (COLUMN_COUNT - 1)

    // ── 底光：用水中随呼吸变大变亮，待机时是一圈安静的浅色
    val haloCenter = Offset(centerX, headBottom + height * 0.16f)
    val haloRadius = unit * (0.46f + if (active) 0.05f * pulse else 0f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                halo.copy(alpha = if (active) 0.22f + 0.12f * pulse else 0.18f),
                Color.Transparent
            ),
            center = haloCenter,
            radius = haloRadius
        ),
        radius = haloRadius,
        center = haloCenter
    )

    // ── 立管 + 淋浴头（圆角横板，上浅下深做出厚度） + 下沿出水面板
    drawRoundRect(
        color = fixtureSoft,
        topLeft = Offset(centerX - armWidth / 2f, headTop * 0.2f),
        size = Size(armWidth, headTop * 0.8f + armWidth),
        cornerRadius = CornerRadius(armWidth / 2f)
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(fixture, fixture.copy(alpha = 0.72f)),
            startY = headTop,
            endY = headBottom
        ),
        topLeft = Offset(centerX - headHalf, headTop),
        size = Size(headHalf * 2f, headHeight),
        cornerRadius = CornerRadius(headHeight / 2f)
    )
    // 顶面高光：浅色主题的淋浴头是深色（onSurfaceVariant），这道白边能看出金属反光
    drawRoundRect(
        color = Color.White.copy(alpha = 0.22f),
        topLeft = Offset(centerX - headHalf * 0.72f, headTop + headHeight * 0.24f),
        size = Size(headHalf * 1.44f, headHeight * 0.14f),
        cornerRadius = CornerRadius(headHeight * 0.07f)
    )
    drawRoundRect(
        color = fixtureSoft,
        topLeft = Offset(centerX - headHalf * 0.82f, headTop + headHeight * 0.58f),
        size = Size(headHalf * 1.64f, headHeight * 0.30f),
        cornerRadius = CornerRadius(headHeight * 0.15f)
    )

    // ── 出水孔 + 水
    for (index in 0 until COLUMN_COUNT) {
        val columnX = centerX - headHalf * 0.65f + step * index
        drawCircle(
            color = fixtureSoft,
            radius = nozzleRadius * 1.5f,
            center = Offset(columnX, headBottom + nozzleRadius * 0.6f)
        )

        if (!active) {
            // 待机：孔口挂着几滴水就够，别画成在出水（和下落的水珠同一个形状，只是短一点）
            if (index % 2 == 0) {
                drawDropBar(
                    center = Offset(columnX, headBottom + hangBarWidth * 2.8f),
                    width = hangBarWidth,
                    height = hangBarWidth * 2.2f,
                    color = water.copy(alpha = 0.65f)
                )
            }
            continue
        }

        // 水柱：长度只轻轻起伏（慢曲线驱动，5 秒一个来回），末端用渐变淡出（不是一根生硬的棒子）
        val wobble = 0.5f + 0.5f * sin(2f * PI.toFloat() * (flow + index * 0.19f))
        val streamLength = fallSpan * (0.34f + 0.06f * wobble)
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(water.copy(alpha = 0.90f), water.copy(alpha = 0.08f)),
                startY = headBottom,
                endY = headBottom + streamLength
            ),
            start = Offset(columnX, headBottom),
            end = Offset(columnX, headBottom + streamLength),
            strokeWidth = streamWidth,
            cap = StrokeCap.Round
        )

        // 下落的水珠：一根长方形水条（一长一短两档），各列的相位与速度都不同。
        // 透明度两端都收到 0：从水柱里「钻出来」、落到底之前淡掉，回卷那一刻它是看不见的，
        // 所以看不出循环在哪 —— 这也是不再抽搐的原因之一
        val dropPhase = fract(progress * DROP_SPEED[index] + DROP_OFFSET[index])
        val dropWidth = barWidth * DROP_SIZE[index]
        drawDropBar(
            center = Offset(columnX, headBottom + fallSpan * (0.18f + 0.82f * dropPhase)),
            width = dropWidth,
            height = dropWidth * 2.8f,
            color = water.copy(alpha = 0.88f * sin(PI.toFloat() * dropPhase))
        )
    }
}

/**
 * 一颗下落的水珠：**就是一根长方形水条**，只把四个角收圆一点点（不收圆的话小尺寸下
 * 看着像掉像素）。长短两档、错峰落下，比拼出来的水滴形更像正在流的水。
 */
private fun DrawScope.drawDropBar(center: Offset, width: Float, height: Float, color: Color) {
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
        size = Size(width, height),
        cornerRadius = CornerRadius(width * 0.35f)
    )
}

/** 取小数部分，用来把一条 0..1 的相位摊成多列错峰的水滴。 */
private fun fract(value: Float): Float = value - kotlin.math.floor(value)
