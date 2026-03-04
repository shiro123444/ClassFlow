package com.xingheyuzhuan.classflow.ui.components

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingheyuzhuan.classflow.R
import kotlinx.coroutines.flow.*
import kotlin.math.abs

@Composable
fun <T> NativeNumberPicker(
    values: List<T>,
    selectedValue: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 48.dp,
    visibleItemsCount: Int = 3,
    itemTextOffsetY: Dp = 0.dp,
    dividerColor: Color = MaterialTheme.colorScheme.primary,
    dividerSize: Dp = 1.dp,
) {
    // 校验可见项数量
    require(visibleItemsCount >= 3 && visibleItemsCount % 2 != 0) {
        "可见项数量必须是大于等于 3 的奇数"
    }

    val initialSelectedIndex = remember(values, selectedValue) {
        values.indexOf(selectedValue).coerceAtLeast(0)
    }

    val listState = rememberLazyListState(initialSelectedIndex)
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    var visuallyCenteredIndex by remember { mutableIntStateOf(initialSelectedIndex) }

    // 获取基础状态词
    val stateSelected = stringResource(R.string.a11y_state_selected)
    val stateNotSelected = stringResource(R.string.a11y_state_not_selected)

    // 逻辑部分：初始化滚动
    LaunchedEffect(initialSelectedIndex) {
        listState.scrollToItem(initialSelectedIndex)
    }

    // 逻辑部分：处理吸附同步
    LaunchedEffect(listState) {
        listState.interactionSource.interactions
            .filterIsInstance<DragInteraction.Stop>()
            .combine(snapshotFlow { listState.isScrollInProgress }) { _, inProgress -> !inProgress }
            .filter { it }
            .collectLatest {
                val (firstIndex, offset) = listState.run { firstVisibleItemIndex to firstVisibleItemScrollOffset }
                val targetIndex = if (offset > itemHeightPx / 2) (firstIndex + 1).coerceAtMost(values.lastIndex) else firstIndex
                visuallyCenteredIndex = targetIndex
                if (targetIndex in values.indices) onValueChange(values[targetIndex])
                listState.animateScrollToItem(targetIndex)
            }
    }

    // 逻辑部分：实时更新视觉索引
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            snapshotFlow {
                listState.run {
                    if (firstVisibleItemScrollOffset > itemHeightPx / 2) (firstVisibleItemIndex + 1).coerceAtMost(values.lastIndex) else firstVisibleItemIndex
                }
            }.distinctUntilChanged().collect { visuallyCenteredIndex = it }
        }
    }

    Box(
        modifier = modifier
            .height(itemHeight * visibleItemsCount)
            .clipToBounds()
    ) {
        // 分隔线
        listOf(-1, 1).forEach { direction ->
            HorizontalDivider(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = itemHeight / 2 * direction),
                color = dividerColor,
                thickness = dividerSize
            )
        }

        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight * visibleItemsCount),
            contentPadding = PaddingValues(vertical = itemHeight * (visibleItemsCount / 2))
        ) {
            itemsIndexed(values) { index, item ->
                val isSelected = index == visuallyCenteredIndex
                val distance = abs(index - visuallyCenteredIndex)

                // 样式计算
                val (fontSize, textColor) = when (distance) {
                    0 -> 30.sp to MaterialTheme.colorScheme.primary
                    1 -> 25.sp to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    else -> 20.sp to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                }

                Text(
                    text = item.toString(),
                    fontSize = fontSize,
                    color = textColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .height(itemHeight)
                        .fillMaxWidth()
                        .offset(y = itemTextOffsetY)
                        .semantics {
                            selected = isSelected
                            val stateText = if (isSelected) stateSelected else stateNotSelected
                            contentDescription = "$item $stateText"
                        }
                )
            }
        }
    }
}
