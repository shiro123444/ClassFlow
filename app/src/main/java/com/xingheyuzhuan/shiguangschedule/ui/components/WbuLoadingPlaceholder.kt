package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.R

/**
 * WBU 品牌强调色：浅色主题用深蓝，深色主题用浅蓝。
 *
 * 供品牌占位首屏与链接过渡动画等「单色纹样」场景共用，保证两处取色一致。
 */
@Composable
fun wbuBrandAccent(): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) Color(0xFF8FC3EC) else Color(0xFF005FAD)
}

/**
 * 通用品牌占位首屏（预留）：品牌色铺满全屏 + WBU 编钟纹样居中。
 *
 * 编钟纹样中间为镂空（透明），会直接透出底部容器的颜色，形成「底色即纹样内胆」的效果。
 * 配色跟随应用主题：浅色用淡蓝底 + 深蓝纹样，深色用深蓝黑底 + 浅蓝纹样，不强行套白底。
 */
@Composable
fun WbuLoadingPlaceholder(
    message: String? = null,
    modifier: Modifier = Modifier
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val backgroundColor = if (dark) Color(0xFF0F1A24) else Color(0xFFDCEAF8)
    val accentColor = wbuBrandAccent()
    val textColor = if (dark) Color(0xFFA9C4DA) else Color(0xFF3D6E99)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.wbu_bell_emblem),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(accentColor),
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .aspectRatio(1f)
            )
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(28.dp))
                CircularProgressIndicator(
                    color = accentColor,
                    strokeWidth = 3.dp
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )
            }
        }
    }
}
