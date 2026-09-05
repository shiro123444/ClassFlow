package com.xingheyuzhuan.shiguangschedule.ui.schedule.components

import androidx.compose.ui.res.stringResource
import com.xingheyuzhuan.shiguangschedule.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.ui.theme.ClassFlowTheme
import com.xingheyuzhuan.shiguangschedule.ui.theme.LocalIsDarkTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
/**
 * Liquid Glass 毛玻璃表面容器修饰器。
 * 与底部导航栏 [BottomNavigationBar] 同源样式：圆角裁剪 + 半透明表面 + 顶部打光渐变描边，
 * 传入 [hazeState] 时启用真实背景模糊，否则回退为纯半透明表面。
 *
 * @param hazeState Haze 模糊源（底部导航栏传入的 dockHazeState）；为 null 时不启用模糊。
 * @param shape 容器形状（圆角矩形）。
 */
@Composable
internal fun liquidGlassSurfaceModifier(
    hazeState: HazeState?,
    shape: RoundedCornerShape
): Modifier {
    val isDark = LocalIsDarkTheme.current
    val glassTint = if (isDark) Color(0xFF1A2332) else Color(0xFFFFFBF8)
    val surfaceTint = glassTint.copy(alpha = if (isDark) 0.22f else 0.75f)
    val hazeTint = glassTint.copy(alpha = if (isDark) 0.28f else 0.42f)
    val borderTop = if (isDark) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.90f)
    val borderBottom = if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.25f)
    return Modifier
        .clip(shape)
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
                Modifier.background(surfaceTint, shape)
            }
        )
        .border(
            width = 0.75.dp,
            brush = Brush.verticalGradient(listOf(borderTop, borderBottom)),
            shape = shape
        )
}
/**
 * WBU 教务一键同步按钮。
 * ClassFlow 定制：独立文件承载，以缩小 WeeklyScheduleScreen 与上游的差异面。
 *
 * @param onClick 单击：优先尝试使用已保存的登录态无感同步
 * @param onLongClick 长按：忽略已保存登录态，强制走重新登录流程
 * @param hazeState 背景模糊源；传入时按钮采用与导航栏一致的 Liquid Glass 毛玻璃样式
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WbuSyncActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    contentColor: Color? = null,
    onLongClick: (() -> Unit)? = null
) {
    val isDark = LocalIsDarkTheme.current
    val shape = RoundedCornerShape(16.dp)
    val iconTint = contentColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.85f else 0.75f)
    Box(
        modifier = modifier
            .padding(end = 8.dp)
            .size(48.dp)
            .then(liquidGlassSurfaceModifier(hazeState, shape))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.desc_ignore_saved_session)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Sync,
            contentDescription = stringResource(R.string.a11y_sync_wbu_schedule),
            tint = iconTint
        )
    }
}
@Preview(showBackground = true)
@Composable
private fun WbuSyncActionButtonPreview() {
    ClassFlowTheme {
        WbuSyncActionButton(onClick = {}, onLongClick = {})
    }
}
