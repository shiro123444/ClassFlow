package com.xingheyuzhuan.shiguangschedule.ui.schedule.components

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

/**
 * WBU 教务一键同步按钮。
 * ClassFlow 定制：独立文件承载，以缩小 WeeklyScheduleScreen 与上游的差异面。
 *
 * @param onClick 单击：优先尝试使用已保存的登录态无感同步
 * @param onLongClick 长按：忽略已保存登录态，强制走重新登录流程
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WbuSyncActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val isDark = LocalIsDarkTheme.current
    Box(
        modifier = modifier
            .padding(end = 8.dp)
            .size(48.dp)
            .border(
                width = 0.8.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (isDark) 0.18f else 0.55f),
                        Color.White.copy(alpha = if (isDark) 0.05f else 0.12f)
                    )
                ),
                shape = RoundedCornerShape(14.dp)
            )
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.55f else 0.72f),
                shape = RoundedCornerShape(14.dp)
            )
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "忽略已保存登录态，强制重新登录"
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Sync,
            contentDescription = "一键同步武商院课表",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.85f else 0.75f)
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
