package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.R

/**
 * U净品牌加载首屏：logo 尺寸与位置固定，下方为固定高度的状态区（转圈 / 文案）。
 *
 * 原生取水页（正在连接 → 转圈）与 WebApp 启动洗衣机页共用同一套布局，
 * 保证阶段切换时 logo 不跳动；入场只做淡入，不做缩放，避免与后续阶段尺寸不一致。
 */
@Composable
fun UjingBrandLoading(
    message: String,
    showSpinner: Boolean,
    modifier: Modifier = Modifier,
    logoSize: Dp = 200.dp
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "ujingBrandAlpha"
    )

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ujing_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(logoSize)
                    .graphicsLayer { this.alpha = alpha }
            )
            Spacer(Modifier.height(20.dp))
            // 固定高度状态区：无论是否显示转圈，logo 的绝对位置都不变
            Box(modifier = Modifier.height(76.dp), contentAlignment = Alignment.TopCenter) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (showSpinner) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                        Spacer(Modifier.height(14.dp))
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
