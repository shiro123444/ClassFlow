package com.xingheyuzhuan.shiguangschedule.ui.settings.style

import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.ui.schedule.MergedCourseBlock
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGrid
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGridActions
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGridViewState
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.liquidGlassSurfaceModifier
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.rememberScheduleGridState
import com.xingheyuzhuan.shiguangschedule.ui.theme.ThemeGradients
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperAdjustScreen(
    onBack: () -> Unit,
    viewModel: StyleSettingsViewModel = hiltViewModel()
) {
    val styleState by viewModel.styleState.collectAsStateWithLifecycle()
    val demoUiState by viewModel.demoUiState.collectAsStateWithLifecycle()

    var phoneSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val persistTransform = {
        viewModel.updateWallpaperTransform(scale, offsetX, offsetY)
    }

    BackHandler {
        persistTransform()
        onBack()
    }

    LaunchedEffect(styleState?.backgroundImagePath) {
        styleState?.let { style ->
            scale = style.backgroundScale
            offsetX = style.backgroundOffsetX
            offsetY = style.backgroundOffsetY
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            persistTransform()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("壁纸微调") },
                navigationIcon = {
                    IconButton(onClick = {
                        persistTransform()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                            viewModel.resetWallpaperTransform()
                        }
                    ) {
                        Text(stringResource(R.string.action_reset_wallpaper_position))
                    }
                }
            )
        }
    ) { innerPadding ->
        val style = styleState

        if (style == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (style.backgroundImagePath.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "请先在个性化配置中选择壁纸",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        // 课表模拟数据与状态
        val today = remember { LocalDate.now() }
        val localDates = remember(demoUiState.firstDayOfWeek) {
            val dayOfWeekStart = DayOfWeek.of(demoUiState.firstDayOfWeek)
            val startOfWeek = today.with(TemporalAdjusters.previousOrSame(dayOfWeekStart))
            (0..6).map { startOfWeek.plusDays(it.toLong()) }
        }
        val dummyDates = remember(localDates) {
            val formatter = DateTimeFormatter.ofPattern("MM/dd")
            localDates.map { it.format(formatter) }
        }
        val dynamicTodayIndex = remember(localDates) { localDates.indexOf(today) }
        val previewWeekStr = stringResource(id = R.string.format_week_display, 1)
        val gridScrollState = rememberScrollState()
        val gridState = rememberScheduleGridState(gridScrollState = gridScrollState)

        // 针对缩略手机模型优化课程块文字与排版比例，避免大字占满格子造成视觉不协调
        val previewStyle = remember(style) {
            style.copy(
                fontScale = (style.fontScale * 0.55f).coerceAtLeast(0.35f),
                courseBlockInnerPadding = (style.courseBlockInnerPadding * 0.5f).coerceAtLeast(1.dp),
                courseBlockOuterPadding = (style.courseBlockOuterPadding * 0.5f).coerceAtLeast(0.5.dp),
                courseBlockCornerRadius = (style.courseBlockCornerRadius * 0.6f).coerceAtLeast(2.dp),
                timeColumnWidth = (style.timeColumnWidth * 0.7f).coerceAtLeast(16.dp),
                sectionHeight = (style.sectionHeight * 0.7f).coerceAtLeast(32.dp),
                dayHeaderHeight = (style.dayHeaderHeight * 0.7f).coerceAtLeast(22.dp)
            )
        }

        val previewDemoCourses = remember(demoUiState.currentMergedCourses) {
            demoUiState.currentMergedCourses.map { block ->
                block.copy(
                    courses = block.courses.map { cww ->
                        val refinedName = when (cww.course.name) {
                            "普通课程展示" -> "高等数学"
                            "精准渲染演示" -> "大学英语"
                            "冲突课程 A" -> "算法设计"
                            "冲突课程 B" -> "计算机网络"
                            else -> cww.course.name
                        }
                        cww.copy(course = cww.course.copy(name = refinedName))
                    }
                )
            }
        }

        val gridViewState = remember(dummyDates, demoUiState, dynamicTodayIndex, previewWeekStr, previewDemoCourses) {
            ScheduleGridViewState(
                dates = dummyDates,
                currentYear = today.year.toString(),
                currentWeek = previewWeekStr,
                timeSlots = demoUiState.timeSlots,
                mergedCourses = previewDemoCourses,
                showWeekends = demoUiState.showWeekends,
                todayIndex = dynamicTodayIndex,
                firstDayOfWeek = demoUiState.firstDayOfWeek,
                currentSectionIndex = -1
            )
        }
        val gridActions = remember {
            object : ScheduleGridActions {
                override fun onCourseBlockClicked(block: MergedCourseBlock) {}
                override fun onGridCellClicked(day: Int, section: Int) {}
                override fun onTimeSlotClicked() {}
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.label_wallpaper_gesture_adjust),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(vertical = 12.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    PhonePreviewShell(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(9f / 19.5f)
                    ) {
                        val hazeState = remember { HazeState() }
                        val textColor = style.pageTextColor ?: MaterialTheme.colorScheme.onSurface

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { phoneSize = it }
                        ) {
                            val widthPx = phoneSize.width.toFloat().coerceAtLeast(1f)
                            val heightPx = phoneSize.height.toFloat().coerceAtLeast(1f)

                            // 1. 底层沉浸式渐变与壁纸（Haze 源，覆盖全屏）
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(ThemeGradients.weeklyScheduleGradient())
                                    .hazeSource(hazeState)
                            ) {
                                AsyncImage(
                                    model = style.backgroundImagePath,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                            translationX = widthPx * offsetX
                                            translationY = heightPx * offsetY
                                        }
                                        .blur(style.backgroundBlurRadius),
                                    contentScale = ContentScale.Crop,
                                    alignment = Alignment.TopCenter
                                )
                            }

                            // 2. 主界面布局层：状态栏 -> TopAppBar -> 课表网格 -> 底部手势条
                            Column(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                // 状态栏区域：保留高度，避免与课表重叠
                                PhoneStatusBar(textColor = textColor)

                                // TopAppBar 顶栏：同步主界面“第 1 周”毛玻璃胶囊与操作按钮
                                PhoneTopBar(
                                    hazeState = hazeState,
                                    textColor = textColor
                                )

                                // 课表网格：位于顶栏下方，包含星期栏、时间列及课程卡片
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                ) {
                                    ScheduleGrid(
                                        state = gridState,
                                        viewState = gridViewState,
                                        actions = gridActions,
                                        style = previewStyle,
                                        hazeState = hazeState
                                    )
                                }

                                // 底部安全区手势条
                                PhoneBottomBar(barColor = textColor)
                            }

                            // 3. 全局透明手势控制层（同时支持单指拖拽与双指缩放）
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(phoneSize) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            val transformWidthPx = phoneSize.width.toFloat().coerceAtLeast(1f)
                                            val transformHeightPx = phoneSize.height.toFloat().coerceAtLeast(1f)
                                            scale = (scale * zoom).coerceIn(0.8f, 5f)
                                            offsetX = (offsetX + pan.x / transformWidthPx).coerceIn(-0.5f, 0.5f)
                                            offsetY = (offsetY + pan.y / transformHeightPx).coerceIn(-0.5f, 0.5f)
                                        }
                                    }
                            )
                        }
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 78.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_wallpaper_transform_values, scale, offsetX, offsetY),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "支持双指缩放与拖动；退出页面时自动保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start
                    )
                }
            }
        }
    }
}

@Composable
private fun PhonePreviewShell(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .shadow(16.dp, RoundedCornerShape(32.dp), clip = false)
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xFF161A22))
            .border(1.5.dp, Color(0xFF2E3440), RoundedCornerShape(32.dp))
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface),
            content = content
        )

        // 现代前摄微孔
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 9.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.85f))
        )
    }
}

@Composable
private fun PhoneStatusBar(
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val timeText = remember {
        try {
            LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (_: Exception) {
            "10:08"
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
            color = textColor.copy(alpha = 0.85f),
            fontWeight = FontWeight.SemiBold
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 信号强度图标
            Row(
                horizontalArrangement = Arrangement.spacedBy(1.2.dp),
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.height(7.dp)
            ) {
                Box(Modifier.width(1.8.dp).height(2.dp).background(textColor.copy(alpha = 0.85f), RoundedCornerShape(0.5.dp)))
                Box(Modifier.width(1.8.dp).height(3.5.dp).background(textColor.copy(alpha = 0.85f), RoundedCornerShape(0.5.dp)))
                Box(Modifier.width(1.8.dp).height(5.dp).background(textColor.copy(alpha = 0.85f), RoundedCornerShape(0.5.dp)))
                Box(Modifier.width(1.8.dp).height(7.dp).background(textColor.copy(alpha = 0.85f), RoundedCornerShape(0.5.dp)))
            }
            Spacer(Modifier.width(1.5.dp))
            // 电池图标
            Box(
                modifier = Modifier
                    .width(15.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .border(0.9.dp, textColor.copy(alpha = 0.85f), RoundedCornerShape(2.dp))
                    .padding(0.8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.82f)
                        .clip(RoundedCornerShape(1.dp))
                        .background(textColor.copy(alpha = 0.85f))
                )
            }
        }
    }
}

@Composable
private fun PhoneTopBar(
    hazeState: HazeState,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：第 1 周胶囊
        Box(
            modifier = Modifier
                .then(liquidGlassSurfaceModifier(hazeState, RoundedCornerShape(12.dp)))
                .padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            Text(
                text = "第 1 周",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
        }

        // 右侧：课表切换与同步按钮
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .then(liquidGlassSurfaceModifier(hazeState, RoundedCornerShape(8.dp))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(14.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .then(liquidGlassSurfaceModifier(hazeState, RoundedCornerShape(8.dp))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun PhoneBottomBar(
    barColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(3.5.dp)
                .clip(CircleShape)
                .background(barColor.copy(alpha = 0.35f))
        )
    }
}
