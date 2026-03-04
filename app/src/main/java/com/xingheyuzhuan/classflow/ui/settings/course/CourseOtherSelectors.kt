package com.xingheyuzhuan.classflow.ui.settings.course

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.classflow.R
import com.xingheyuzhuan.classflow.data.model.DualColor
import kotlinx.coroutines.launch

@Composable
fun WeekSelector(
    selectedWeeks: Set<Int>,
    onWeekClick: () -> Unit
) {
    val labelCourseWeeks = stringResource(R.string.label_course_weeks)
    val buttonSelectWeeks = stringResource(R.string.button_select_weeks)
    val textWeeksSelected = stringResource(R.string.text_weeks_selected)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = labelCourseWeeks, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onWeekClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            val weeksText = if (selectedWeeks.isEmpty()) {
                buttonSelectWeeks
            } else {
                String.format(textWeeksSelected, selectedWeeks.sorted().joinToString(", "))
            }
            Text(text = weeksText, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WeekSelectorBottomSheet(
    totalWeeks: Int,
    selectedWeeks: Set<Int>,
    onDismissRequest: () -> Unit,
    onConfirm: (Set<Int>) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val modalBottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tempSelectedWeeks by remember { mutableStateOf(selectedWeeks) }

    val titleSelectWeeks = stringResource(R.string.title_select_weeks)
    val actionSelectAll = stringResource(R.string.action_select_all)
    val actionSingleWeek = stringResource(R.string.action_single_week)
    val actionDoubleWeek = stringResource(R.string.action_double_week)
    val actionCancel = stringResource(R.string.action_cancel)
    val actionConfirm = stringResource(R.string.action_confirm)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = modalBottomSheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = titleSelectWeeks,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 48.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(totalWeeks) { week ->
                    val weekNumber = week + 1
                    val isSelected = tempSelectedWeeks.contains(weekNumber)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                tempSelectedWeeks = if (isSelected) {
                                    tempSelectedWeeks - weekNumber
                                } else {
                                    tempSelectedWeeks + weekNumber
                                }
                            }
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = weekNumber.toString(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = tempSelectedWeeks.size == totalWeeks && totalWeeks > 0,
                    onClick = {
                        tempSelectedWeeks = if (tempSelectedWeeks.size == totalWeeks) emptySet()
                        else (1..totalWeeks).toSet()
                    },
                    label = { Text(actionSelectAll) }
                )
                FilterChip(
                    selected = tempSelectedWeeks.isNotEmpty() && tempSelectedWeeks.all { it % 2 != 0 },
                    onClick = {
                        tempSelectedWeeks = (1..totalWeeks).filter { it % 2 != 0 }.toSet()
                    },
                    label = { Text(actionSingleWeek) }
                )
                FilterChip(
                    selected = tempSelectedWeeks.isNotEmpty() && tempSelectedWeeks.all { it % 2 == 0 },
                    onClick = {
                        tempSelectedWeeks = (1..totalWeeks).filter { it % 2 == 0 }.toSet()
                    },
                    label = { Text(actionDoubleWeek) }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(actionCancel)
                }
                Button(
                    onClick = {
                        onConfirm(tempSelectedWeeks)
                        coroutineScope.launch { modalBottomSheetState.hide() }.invokeOnCompletion {
                            if (!modalBottomSheetState.isVisible) onDismissRequest()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(actionConfirm)
                }
            }
        }
    }
}

@Composable
fun ColorPicker(
    selectedColor: Color,
    onColorClick: () -> Unit
) {
    val labelCourseColor = stringResource(R.string.label_course_color)
    val buttonSelectColor = stringResource(R.string.button_select_color)

    // 自动计算对比度高的文字颜色
    val textColor = if (selectedColor.luminance() > 0.5f) Color.Black else Color.White

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = labelCourseColor, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onColorClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = selectedColor)
        ) {
            Text(buttonSelectColor, color = textColor)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerBottomSheet(
    colorMaps: List<DualColor>,
    selectedIndex: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val modalBottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 内部临时保存选中的索引，直到点击“确认”
    var tempSelectedIndex by remember(selectedIndex) { mutableStateOf(selectedIndex) }

    val titleSelectColor = stringResource(R.string.title_select_color)
    val actionCancel = stringResource(R.string.action_cancel)
    val actionConfirm = stringResource(R.string.action_confirm)

    val isDarkTheme = isSystemInDarkTheme()

    // 动态映射颜色列表，根据当前主题显示对应的 light 或 dark 颜色
    val displayColors = remember(isDarkTheme, colorMaps) {
        colorMaps.map { if (isDarkTheme) it.dark else it.light }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = modalBottomSheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = titleSelectColor,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            if (displayColors.isEmpty()) {
                // 防御性处理：如果没有颜色数据时的占位
                Text(text = "No colors available", modifier = Modifier.padding(32.dp))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(displayColors) { index, color ->
                        val isSelected = tempSelectedIndex == index

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f) // 确保是正圆
                                .clip(CircleShape)
                                .clickable { tempSelectedIndex = index }
                                .then(
                                    if (isSelected) Modifier.border(
                                        width = 3.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape
                                    ) else Modifier
                                )
                                .padding(4.dp) // 描边和内部圆圈的间距
                                .clip(CircleShape)
                                .background(color),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Done,
                                    contentDescription = null,
                                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(actionCancel)
                }
                Button(
                    onClick = {
                        onConfirm(tempSelectedIndex)
                        coroutineScope.launch { modalBottomSheetState.hide() }.invokeOnCompletion {
                            if (!modalBottomSheetState.isVisible) onDismissRequest()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(actionConfirm)
                }
            }
        }
    }
}
