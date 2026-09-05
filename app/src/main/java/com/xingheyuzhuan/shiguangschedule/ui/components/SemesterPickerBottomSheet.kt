package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine

/**
 * 学期选择对话框：
 * 采用标准 AlertDialog + LazyColumn，从根本上杜绝手势滑动误关闭，
 * 同时支持长列表流畅滚动、当前学期徽章置顶高亮、最近几年直选与更早历史学期折叠。
 */
@Composable
fun SemesterPickerDialog(
    options: List<WbuSyncEngine.WbuSemesterOption>,
    currentXnxq: String? = null,
    title: String = "选择导入的学年学期",
    confirmButtonText: String = "确定",
    onConfirm: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    // 数据清洗与过滤：去除空值或空白项
    val cleanOptions = remember(options) {
        options.filter { it.value.isNotBlank() && it.text.isNotBlank() }
    }

    // 默认高亮并选中的学期：优先匹配传入的当前学期，否则选第一项（通常是最新学期）
    val defaultSelection = remember(cleanOptions, currentXnxq) {
        cleanOptions.find { it.value == currentXnxq }?.value
            ?: cleanOptions.firstOrNull()?.value.orEmpty()
    }
    var selectedValue by remember(defaultSelection) { mutableStateOf(defaultSelection) }
    var isHistoryExpanded by remember { mutableStateOf(false) }

    // 划分近期学期与历史学期（默认展示前 6 个近期学期，约为最近 3 年）
    val recentOptions = remember(cleanOptions) {
        cleanOptions.take(6)
    }
    val historyOptions = remember(cleanOptions) {
        if (cleanOptions.size > 6) cleanOptions.drop(6) else emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false // 防止用户翻滚长列表时手滑触碰外部蒙层导致意外关闭
        ),
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(vertical = 24.dp),
        icon = {
            Icon(
                imageVector = Icons.Default.School,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "请选择要导入的学期（将同步课表及周历配置）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 学期选择列表，限制最大高度，支持内部平滑滚动，完全无外部手势冲突
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    // 近期学期
                    items(recentOptions, key = { it.value }) { opt ->
                        val isCurrent = opt.value == currentXnxq || (currentXnxq.isNullOrBlank() && opt == cleanOptions.firstOrNull())
                        SemesterOptionItem(
                            option = opt,
                            isSelected = selectedValue == opt.value,
                            isCurrentTerm = isCurrent,
                            onClick = { selectedValue = opt.value }
                        )
                    }

                    // 历史学期折叠入口
                    if (historyOptions.isNotEmpty()) {
                        item {
                            Surface(
                                onClick = { isHistoryExpanded = !isHistoryExpanded },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (isHistoryExpanded) "收起较早历史学期" else "展开更早学期 (${historyOptions.size} 个)",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Icon(
                                        imageVector = if (isHistoryExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        if (isHistoryExpanded) {
                            items(historyOptions, key = { it.value }) { opt ->
                                SemesterOptionItem(
                                    option = opt,
                                    isSelected = selectedValue == opt.value,
                                    isCurrentTerm = false,
                                    onClick = { selectedValue = opt.value }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedValue.isNotBlank()) {
                        onConfirm(selectedValue)
                    }
                },
                enabled = selectedValue.isNotBlank()
            ) {
                Text(confirmButtonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("取消")
            }
        }
    )
}

/**
 * 兼容旧名称的别名函数
 */
@Composable
fun SemesterPickerBottomSheet(
    options: List<WbuSyncEngine.WbuSemesterOption>,
    currentXnxq: String? = null,
    title: String = "选择导入的学年学期",
    confirmButtonText: String = "确定",
    onConfirm: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    SemesterPickerDialog(
        options = options,
        currentXnxq = currentXnxq,
        title = title,
        confirmButtonText = confirmButtonText,
        onConfirm = onConfirm,
        onDismissRequest = onDismissRequest
    )
}

@Composable
private fun SemesterOptionItem(
    option: WbuSyncEngine.WbuSemesterOption,
    isSelected: Boolean,
    isCurrentTerm: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        },
        tonalElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = option.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (isCurrentTerm) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    modifier = Modifier.padding(start = 6.dp)
                ) {
                    Text(
                        text = "当前学期",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
