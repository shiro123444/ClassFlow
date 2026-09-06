package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.ui.res.stringResource
import com.xingheyuzhuan.shiguangschedule.R
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.SliderCaptchaData
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.SliderCaptchaResult
import kotlin.math.max
import kotlin.math.roundToInt
private fun decodeBase64Image(base64: String): ImageBitmap? {
    return runCatching {
        val cleaned = base64.substringAfter(',') // 兼容 data:image/png;base64, 前缀
        val bytes = Base64.decode(cleaned, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}
/**
 * 统一身份认证滑块验证码弹窗。
 * 展示大图与小拼块，用户拖拽对齐缺口后自动提交位移距离。
 */
@Composable
fun SliderCaptchaDialog(
    captcha: SliderCaptchaData,
    onSubmit: (SliderCaptchaResult) -> Unit,
    onDismiss: () -> Unit
) {
    val bigImage = remember(captcha.bigImageBase64) { decodeBase64Image(captcha.bigImageBase64) }
    val smallImage = remember(captcha.smallImageBase64) { decodeBase64Image(captcha.smallImageBase64) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
        title = {
            Text(stringResource(R.string.title_security_verification), style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.desc_slider_captcha_guide),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (bigImage != null && smallImage != null) {
                    SliderCaptchaArea(
                        captcha = captcha,
                        bigImage = bigImage,
                        smallImage = smallImage,
                        onSubmit = { moveLength ->
                            onSubmit(SliderCaptchaResult.Move(moveLength))
                        }
                    )
                } else {
                    Text(
                        text = stringResource(R.string.err_captcha_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(SliderCaptchaResult.Refresh) }) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_refresh_captcha))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
/**
 * 滑块交互区。
 * 参考官方 longbow.slidercaptcha 实现：
 * - 大图按容器宽度铺满，小图是"全高度竖条"，拼块的纵向位置由图片自带，覆盖即可对齐缺口
 * - 底部轨道使用 Material3 Slider（样式对齐"个性化配置"中的拖动条），拖动 thumb 同步移动拼块
 * - moveLength 按 轨道显示宽度 与 canvasLength 比例换算
 */
@Composable
private fun SliderCaptchaArea(
    captcha: SliderCaptchaData,
    bigImage: ImageBitmap,
    smallImage: ImageBitmap,
    onSubmit: (Int) -> Unit
) {
    var offsetX by remember(captcha.smallImageBase64) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    Column(modifier = Modifier.fillMaxWidth()) {
        // 图片区：大图 + 拼块竖条
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.06f))
        ) {
            val containerWidthPx = constraints.maxWidth.toFloat()
            val containerHeightPx = if (bigImage.width > 0) {
                containerWidthPx * bigImage.height.toFloat() / bigImage.width.toFloat()
            } else {
                containerWidthPx
            }
            val pieceWidthPx = smallImage.width.toFloat() * (containerWidthPx / bigImage.width.toFloat())
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { containerHeightPx.toDp() })
            ) {
                Image(
                    bitmap = bigImage,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
                // 全高度竖条：纵向位置由图片自带，宽度按比例缩放，随 offsetX 同步移动
                Image(
                    bitmap = smallImage,
                    contentDescription = stringResource(R.string.a11y_captcha_slider_block),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(offsetX.roundToInt(), 0) }
                        .size(
                            width = with(density) { pieceWidthPx.toDp() },
                            height = with(density) { containerHeightPx.toDp() }
                        ),
                    contentScale = ContentScale.FillBounds
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        // 底部滑块轨道：Material3 Slider，样式对齐"个性化配置"中的拖动条（圆形 thumb + 圆形高条），整体加大。
        // 填充物自绘，右端与 thumb 左缘对齐（而非默认延伸到 thumb 中心），使 thumb 完全骑在填充物前方；
        // thumb 初始位置在填充物起点处（刚露出一小段填充），允许往回拖到最左。
        @OptIn(ExperimentalMaterial3Api::class)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val trackWidthPx = constraints.maxWidth.toFloat()
            val thumbWidthPx = with(density) { 24.dp.toPx() }
            val thumbRadiusPx = thumbWidthPx / 2f
            val initialGapPx = with(density) { 5.dp.toPx() }
            val pieceWidthPx = smallImage.width.toFloat() * (trackWidthPx / bigImage.width.toFloat())
            val maxDragPx = (trackWidthPx - max(pieceWidthPx, thumbWidthPx)).coerceAtLeast(0f)
            // 初始位置：thumb 左缘距 track 左端 3dp（fill 右端与之对齐）
            val initialOffsetPx = if (maxDragPx > 0f) {
                ((initialGapPx + thumbRadiusPx) / trackWidthPx * maxDragPx).coerceIn(0f, maxDragPx)
            } else 0f
            LaunchedEffect(captcha.smallImageBase64) {
                offsetX = initialOffsetPx
            }
            // 提示文字（置于轨道之下，thumb 可覆盖其上）
            Text(
                text = stringResource(R.string.label_slide_to_verify),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.Center)
            )
            // 轨道：高条圆形底 + Material3 Slider（样式对齐"个性化配置"拖动条，整体加大）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.CenterStart
            ) {
                Slider(
                    value = offsetX,
                    onValueChange = { offsetX = it },
                    onValueChangeFinished = {
                        val moveLength = ((offsetX / trackWidthPx) * captcha.canvasLength)
                            .toInt()
                            .coerceIn(0, captcha.canvasLength)
                        onSubmit(moveLength)
                    },
                    valueRange = 0f..maxDragPx,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    thumb = {
                        Surface(
                            modifier = Modifier.size(24.dp),
                            shape = CircleShape,
                            color = Color.White,
                            shadowElevation = 2.dp,
                            border = BorderStroke(0.5.dp, Color.LightGray.copy(alpha = 0.5f))
                        ) {}
                    },
                    track = { sliderState ->
                        // 自绘填充物：fill 右端略越过 thumb 右缘（"再往左来一点"），
                        // 使 thumb 更"嵌"在填充物里，视觉上往左偏移
                        val fraction = ((sliderState.value - sliderState.valueRange.start) /
                            (sliderState.valueRange.endInclusive - sliderState.valueRange.start))
                            .coerceIn(0f, 1f)
                        val overlapPx = with(density) { 3.dp.toPx() }
                        val fillWidthPx = ((trackWidthPx - 2f * thumbRadiusPx) * fraction +
                            thumbRadiusPx + overlapPx)
                            .coerceIn(0f, trackWidthPx - thumbRadiusPx)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(30.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(with(density) { fillWidthPx.toDp() })
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                            )
                        }
                    }
                )
            }
        }
    }
}
