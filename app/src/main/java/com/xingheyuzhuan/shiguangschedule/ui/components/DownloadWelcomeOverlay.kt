package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingheyuzhuan.shiguangschedule.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 品牌入场动画：光晕 + 扩散环 + 粒子 + logo 弹入 + 流光扫过 + 渐变标题，
 * 最后整体缩放淡出，露出后面的真实界面。
 *
 * 本组件只做"遮罩"，不阻塞底层页面：底层 ViewModel / 相机 / WebView 在动画期间照常启动，
 * 保证业务以最快速度开始。[accelerate] 置 true（底层已有可展示内容，如 U净 转圈、
 * 相机就绪、网页就绪）时会从当前位置快速收尾，进一步压缩等待。
 *
 * 只在「没有 U净 品牌首屏」的入口使用：官网下载页、吹风机（直接调起支付宝）。
 * U净 饮水机 / 洗衣机页面自带 U净 品牌首屏，不再叠加本动画，避免两段品牌动画排队出现。
 *
 * 使用场景：官网下载页入口、吹风机 / 扫一扫。
 */
@Composable
fun BrandEntryOverlay(
    durationMillis: Int = 2700,
    accelerate: Boolean = false,
    onFinished: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.3f) }

    val finished = remember { mutableStateOf(false) }
    fun finishOnce() {
        if (!finished.value) {
            finished.value = true
            onFinished()
        }
    }
    val currentAccelerate by rememberUpdatedState(accelerate)

    LaunchedEffect(Unit) {
        val base = launch {
            progress.animateTo(1f, tween(durationMillis = durationMillis, easing = FastOutSlowInEasing))
        }
        // 等待自然播完，或底层页面就绪的加速信号
        while (base.isActive && !currentAccelerate) {
            delay(24)
        }
        if (base.isActive) {
            // 加速收尾：打断当前动画，从当前位置快速淡出
            progress.animateTo(1f, tween(durationMillis = 220, easing = LinearEasing))
            base.cancel()
        }
        finishOnce()
    }
    // logo 稍晚一点弹入（带过冲）
    LaunchedEffect(Unit) {
        delay(180)
        logoScale.animateTo(
            1f,
            spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow)
        )
    }

    val p = progress.value
    val infinite = rememberInfiniteTransition(label = "download-welcome")
    val ringPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "ring-phase"
    )

    val bgAlpha = when {
        p < 0.07f -> p / 0.07f
        p > 0.9f -> ((1f - p) / 0.1f).coerceIn(0f, 1f)
        else -> 1f
    }
    val exitScale = 1f + ramp(p, 0.88f, 1f) * 0.16f
    val titleAlpha = smooth(ramp(p, 0.30f, 0.50f))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // 点任意位置可跳过
                detectTapGestures {
                    scope.launch {
                        progress.stop()
                        finishOnce()
                    }
                }
            }
    ) {
        // 深色品牌渐变背景
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = bgAlpha }
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF2A0D28), Color(0xFF140616), Color(0xFF0A040B))
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = bgAlpha
                    scaleX = exitScale
                    scaleY = exitScale
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(240.dp),
                contentAlignment = Alignment.Center
            ) {
                // 光晕 / 扩散环 / 粒子
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxR = size.minDimension / 2f

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x59FF6BB5), Color(0x00FF6BB5)),
                            center = center,
                            radius = maxR
                        ),
                        radius = maxR,
                        center = center
                    )

                    repeat(3) { i ->
                        val t = (ringPhase + i / 3f) % 1f
                        drawCircle(
                            color = Color(0xFFFF9EC4).copy(alpha = (1f - t) * 0.5f),
                            radius = maxR * (0.26f + 0.74f * t),
                            center = center,
                            style = Stroke(width = 1.4.dp.toPx())
                        )
                    }

                    val life = ramp(p, 0.10f, 0.78f)
                    if (life > 0f && life < 1f) {
                        val sparkAlpha = (sin(life * PI).toFloat() * 0.95f).coerceIn(0f, 1f)
                        repeat(18) { i ->
                            val angle = i * (2f * PI.toFloat() / 18f) + p * 0.9f
                            val dist = maxR * (0.30f + 0.78f * life) * (0.72f + (i % 4) * 0.11f)
                            drawCircle(
                                color = Color(0xFFFFD6E7).copy(alpha = sparkAlpha),
                                radius = (1.1f + (i % 3) * 0.55f).dp.toPx(),
                                center = Offset(
                                    center.x + cos(angle) * dist,
                                    center.y + sin(angle) * dist
                                )
                            )
                        }
                    }
                }

                // logo（启动图标同款：浅粉底 + 前景图）
                Box(
                    modifier = Modifier
                        .size(108.dp)
                        .graphicsLayer {
                            scaleX = logoScale.value
                            scaleY = logoScale.value
                        }
                        .clip(RoundedCornerShape(30.dp))
                        .background(colorResource(R.color.ic_launcher_background))
                        .drawWithContent {
                            drawContent()
                            // 流光扫过
                            val sweep = ramp(p, 0.26f, 0.56f)
                            if (sweep > 0f && sweep < 1f) {
                                val w = size.width
                                val x = -w + (3f * w) * sweep
                                drawRect(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.White.copy(alpha = 0.75f),
                                            Color.Transparent
                                        ),
                                        start = Offset(x - w * 0.35f, 0f),
                                        end = Offset(x + w * 0.35f, size.height)
                                    )
                                )
                            }
                        }
                ) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp)
                    )
                }
            }

            Spacer(Modifier.height(26.dp))

            Text(
                text = "ClassFlow",
                style = TextStyle(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFFFFFFF), Color(0xFFFFB3D1))
                    )
                ),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.5.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = titleAlpha
                    translationY = (1f - titleAlpha) * 26f
                }
            )
        }
    }
}

/** 把 p 从 [from, to] 线性映射到 0..1。 */
private fun ramp(p: Float, from: Float, to: Float): Float =
    ((p - from) / (to - from)).coerceIn(0f, 1f)

/** smoothstep 缓动。 */
private fun smooth(t: Float): Float = t * t * (3f - 2f * t)
