package com.xingheyuzhuan.shiguangschedule.ui.settings.style

import android.content.res.Configuration
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xingheyuzhuan.shiguangschedule.NavBridge
import coil3.compose.AsyncImage
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.BorderTypeProto
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.ScheduleModeProto
import com.xingheyuzhuan.shiguangschedule.ui.components.AdvancedColorPicker
import com.xingheyuzhuan.shiguangschedule.ui.components.ColorPickerConfig
import com.xingheyuzhuan.shiguangschedule.ui.schedule.MergedCourseBlock
import com.xingheyuzhuan.shiguangschedule.ui.schedule.WeeklyScheduleUiState
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGrid
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGridActions
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGridViewState
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGridStyleComposed
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.rememberScheduleGridState
import com.xingheyuzhuan.shiguangschedule.ui.theme.ThemeGradients
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleSettingsScreen(
    navBridge: NavBridge,
    viewModel: StyleSettingsViewModel = hiltViewModel()
) {
    val styleState by viewModel.styleState.collectAsStateWithLifecycle()
    val demoUiState by viewModel.demoUiState.collectAsStateWithLifecycle()

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var showColorPicker by remember { mutableStateOf(false) }
    var isDarkTarget by remember { mutableStateOf(false) }
    var selectedColorIndex by remember { mutableIntStateOf(0) }

    val sheetState = rememberModalBottomSheetState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.item_personalization)) },
                navigationIcon = {
                    IconButton(onClick = { navBridge.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.a11y_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        styleState?.let { currentStyle ->
            val contentModifier = Modifier.padding(paddingValues).fillMaxSize()

            val previewContent = @Composable { modifier: Modifier ->
                val density = LocalDensity.current
                val containerSize = LocalWindowInfo.current.containerSize
                val windowWidthDp = with(density) { containerSize.width.toDp() }
                Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)).horizontalScroll(rememberScrollState()).pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent() } } }) {
                    Box(modifier = Modifier.requiredWidth(windowWidthDp)) {
                        ScheduleGridContent(currentStyle, demoUiState)
                    }
                }
            }

            if (isLandscape) {
                Row(modifier = contentModifier) {
                    previewContent(Modifier.fillMaxHeight().weight(0.4f))
                    // 去掉外侧卡片圆角，消除"卡片套卡片"的视觉（上游同步）
                    Card(modifier = Modifier.fillMaxHeight().weight(0.6f), shape = RoundedCornerShape(0.dp)) {
                        SettingsListContent(currentStyle, viewModel, onNavigateToAdjust = {
                            navBridge.navigate(Destination.WallpaperAdjust)
                        }) { isDark, idx ->
                            isDarkTarget = isDark; selectedColorIndex = idx
                            showColorPicker = true
                        }
                    }
                }
            } else {
                Column(modifier = contentModifier) {
                    previewContent(Modifier.fillMaxWidth().weight(0.38f))
                    // 去掉外侧卡片圆角，消除"卡片套卡片"的视觉（上游同步）
                    Card(modifier = Modifier.fillMaxWidth().weight(0.62f), shape = RoundedCornerShape(0.dp)) {
                        SettingsListContent(currentStyle, viewModel, onNavigateToAdjust = {
                            navBridge.navigate(Destination.WallpaperAdjust)
                        }) { isDark, idx ->
                            isDarkTarget = isDark; selectedColorIndex = idx
                            showColorPicker = true
                        }
                    }
                }
            }

            if (showColorPicker) {
                ModalBottomSheet(
                    onDismissRequest = { showColorPicker = false },
                    sheetState = sheetState,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 2.dp,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                ) {
                    val initialColor = run {
                        val pair = currentStyle.courseColorMaps.getOrNull(selectedColorIndex)
                        if (isDarkTarget) pair?.dark ?: Color.Gray else pair?.light ?: Color.Gray
                    }

                    var currentColorInPicker by remember { mutableStateOf(initialColor) }

                    AdvancedColorPicker(
                        initialColor = initialColor,
                        config = ColorPickerConfig(showAlpha = false),
                        onColorChanged = { newColor ->
                            currentColorInPicker = newColor
                            viewModel.updatePrimaryColor(selectedColorIndex, newColor, isDarkTarget)
                        },
                        previewContent = {
                            ColorPreviewBox(currentColorInPicker, !isDarkTarget)
                        }
                    )
                    Spacer(modifier = Modifier.navigationBarsPadding())
                }
            }
        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

@Composable
private fun SettingsListContent(
    currentStyle: ScheduleGridStyleComposed,
    viewModel: StyleSettingsViewModel,
    onNavigateToAdjust: () -> Unit,
    onPick: (isDark: Boolean, index: Int) -> Unit
) {
    var showResetDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let {
            viewModel.updateWallpaper(context, it) {
                onNavigateToAdjust()
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.dialog_reset_title)) },
            text = { Text(stringResource(R.string.dialog_reset_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetStyleSettings()
                    showResetDialog = false
                }) { Text(stringResource(R.string.action_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Interface ──
        SettingsGroup(title = stringResource(R.string.style_category_interface)) {
            // 24小时时间轴模式（上游同步）
            StyleSwitchItem(stringResource(R.string.label_schedule_mode_24h), currentStyle.scheduleMode == ScheduleModeProto.TIME_24H_MODE) {
                viewModel.updateScheduleMode(if (it) ScheduleModeProto.TIME_24H_MODE else ScheduleModeProto.SECTION_MODE)
            }
            WallpaperItem(
                path = currentStyle.backgroundImagePath,
                onClick = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onLongClick = { viewModel.removeWallpaper(context) },
                onAdjust = if (currentStyle.backgroundImagePath.isNotEmpty()) onNavigateToAdjust else null
            )
            if (currentStyle.backgroundImagePath.isNotEmpty()) {
                StyleSliderItem(stringResource(R.string.label_background_dim), currentStyle.backgroundDimAlpha, 0f..0.7f, 0.01f) { viewModel.updateBackgroundDimAlpha(it) }
            }
            StyleSwitchItem(stringResource(R.string.label_hide_section_time), currentStyle.hideSectionTime) { viewModel.updateHideSectionTime(it) }
            StyleSwitchItem(stringResource(R.string.label_hide_date_under_day), currentStyle.hideDateUnderDay) { viewModel.updateHideDateUnderDay(it) }
            StyleSwitchItem(stringResource(R.string.label_hide_grid_lines), currentStyle.hideGridLines) { viewModel.updateHideGridLines(it) }
            // 页面文字颜色（上游同步）
            ColorPickerItem(
                label = stringResource(R.string.label_page_text_color),
                currentColor = currentStyle.pageTextColor,
                onColorChanged = { viewModel.updatePageTextColor(it) },
                onReset = { viewModel.updatePageTextColor(null) }
            )
        }

        // ── Grid size ──
        SettingsGroup(title = stringResource(R.string.style_category_grid_size)) {
            StyleSliderItem(stringResource(R.string.label_section_height), currentStyle.sectionHeight.value, 40f..120f) { viewModel.updateSectionHeight(it) }
            StyleSliderItem(stringResource(R.string.label_time_column_width), currentStyle.timeColumnWidth.value, 20f..80f) { viewModel.updateTimeColumnWidth(it) }
            StyleSliderItem(stringResource(R.string.label_day_header_height), currentStyle.dayHeaderHeight.value, 30f..80f) { viewModel.updateDayHeaderHeight(it) }
        }

        // ── Course block appearance ──
        SettingsGroup(title = stringResource(R.string.style_category_course_block)) {
            // Glass preset as segmented row
            Text(stringResource(R.string.label_glass_style), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(
                    0 to stringResource(R.string.glass_style_clear),
                    1 to stringResource(R.string.glass_style_liquid),
                    2 to stringResource(R.string.glass_style_frost),
                    3 to stringResource(R.string.glass_style_angular)
                ).forEachIndexed { index, (preset, label) ->
                    SegmentedButton(
                        selected = currentStyle.glassPreset == preset,
                        onClick = { viewModel.applyGlassPreset(preset) },
                        shape = SegmentedButtonDefaults.itemShape(index, 4)
                    ) {
                        Text(
                            text = label,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 课程块文字颜色（上游同步）
            ColorPickerItem(
                label = stringResource(R.string.label_course_text_color),
                currentColor = currentStyle.courseTextColor,
                onColorChanged = { viewModel.updateCourseTextColor(it) },
                onReset = { viewModel.updateCourseTextColor(null) }
            )
            StyleSwitchItem(stringResource(R.string.label_show_start_time), currentStyle.showStartTime) { viewModel.updateShowStartTime(it) }
            StyleSwitchItem(stringResource(R.string.label_hide_location), currentStyle.hideLocation) { viewModel.updateHideLocation(it) }
            StyleSwitchItem(stringResource(R.string.label_hide_teacher), currentStyle.hideTeacher) { viewModel.updateHideTeacher(it) }
            StyleSwitchItem(stringResource(R.string.label_remove_location_at), currentStyle.removeLocationAt) { viewModel.updateRemoveLocationAt(it) }
            // 文字对齐（上游同步）
            StyleSwitchItem(stringResource(R.string.label_text_align_center_h), currentStyle.textAlignCenterHorizontal) { viewModel.updateTextAlignCenterHorizontal(it) }
            StyleSwitchItem(stringResource(R.string.label_text_align_center_v), currentStyle.textAlignCenterVertical) { viewModel.updateTextAlignCenterVertical(it) }
            // 边框样式（上游同步）
            BorderTypeSelector(currentStyle.borderType) { viewModel.updateBorderType(it) }

            // 文字与几何微调（跟随上游：与课程块外观同组）
            StyleSliderItem(stringResource(R.string.label_font_scale), currentStyle.fontScale, 0.5f..2.0f, 0.1f) { viewModel.updateCourseBlockFontScale(it) }
            StyleSliderItem(stringResource(R.string.label_corner_radius), currentStyle.courseBlockCornerRadius.value, 0f..24f) { viewModel.updateCornerRadius(it) }
            StyleSliderItem(stringResource(R.string.label_inner_padding), currentStyle.courseBlockInnerPadding.value, 0f..12f) { viewModel.updateInnerPadding(it) }
            StyleSliderItem(stringResource(R.string.label_outer_padding), currentStyle.courseBlockOuterPadding.value, 0f..8f) { viewModel.updateOuterPadding(it) }
            StyleSliderItem(stringResource(R.string.label_opacity), currentStyle.courseBlockAlpha, 0.1f..1f, 0.1f) { viewModel.updateAlpha(it) }
            StyleSliderItem(stringResource(R.string.label_course_block_blur), currentStyle.courseBlockBlurRadius.value, 0f..20f, 1f) { viewModel.updateCourseBlockBlurRadius(it) }
            StyleSwitchItem(stringResource(R.string.label_course_block_colorless), currentStyle.courseBlockColorless) { viewModel.updateCourseBlockColorless(it) }
        }

        // ── Color scheme ──
        SettingsGroup(title = stringResource(R.string.style_category_color_scheme)) {
            ColorSchemeSection(
                title = stringResource(R.string.title_light_color_pool),
                bgColor = lightColorScheme().surfaceContainerLow,
                isDarkSection = false,
                colors = currentStyle.courseColorMaps.map { it.light },
                onEditColor = { onPick(false, it) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            ColorSchemeSection(
                title = stringResource(R.string.title_dark_color_pool),
                bgColor = darkColorScheme().surfaceContainerLow,
                isDarkSection = true,
                colors = currentStyle.courseColorMaps.map { it.dark },
                onEditColor = { onPick(true, it) }
            )
        }

        // ── Reset (bottom) ──
        OutlinedButton(
            onClick = { showResetDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
        ) {
            Text(stringResource(R.string.action_reset_style))
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun BorderTypeSelector(
    currentType: BorderTypeProto,
    onTypeChange: (BorderTypeProto) -> Unit
) {
    val types = listOf(
        BorderTypeProto.BORDER_TYPE_NONE to stringResource(R.string.label_none),
        BorderTypeProto.BORDER_TYPE_SOLID to stringResource(R.string.border_type_solid),
        BorderTypeProto.BORDER_TYPE_DASHED to stringResource(R.string.border_type_dashed),
        BorderTypeProto.BORDER_TYPE_GLASS to stringResource(R.string.border_type_glass)
    )
    Text(stringResource(R.string.label_border_type), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        types.forEachIndexed { index, (type, label) ->
            SegmentedButton(
                selected = currentType == type,
                onClick = { onTypeChange(type) },
                shape = SegmentedButtonDefaults.itemShape(index, types.size)
            ) {
                Text(
                    text = label,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ColorSchemeSection(
    title: String,
    bgColor: Color,
    isDarkSection: Boolean,
    colors: List<Color>,
    onEditColor: (Int) -> Unit
) {
    val contentColor = if (isDarkSection) Color.White else Color.Black

    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bgColor).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = contentColor)
        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                colors.forEachIndexed { index, color ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(color).clickable { onEditColor(index) })
                        Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = contentColor.copy(0.6f), modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScheduleGridContent(
    style: ScheduleGridStyleComposed,
    demoUiState: WeeklyScheduleUiState,
    drawBackground: Boolean = true
) {
    val today = remember { java.time.LocalDate.now() }
    val localDates = remember(demoUiState.firstDayOfWeek) {
        val dayOfWeekStart = java.time.DayOfWeek.of(demoUiState.firstDayOfWeek)
        val startOfWeek = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(dayOfWeekStart))
        (0..6).map { startOfWeek.plusDays(it.toLong()) }
    }
    val dummyDates = remember(localDates) {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MM/dd")
        localDates.map { it.format(formatter) }
    }
    val dynamicTodayIndex = remember(localDates) { localDates.indexOf(today) }
    val previewWeekStr = stringResource(id = R.string.format_week_display, 1)
    val gridScrollState = rememberScrollState()
    val gridState = rememberScheduleGridState(gridScrollState = gridScrollState)
    val gridViewState = remember(dummyDates, demoUiState, dynamicTodayIndex, previewWeekStr) {
        ScheduleGridViewState(
            dates = dummyDates,
            currentYear = today.year.toString(),
            currentWeek = previewWeekStr,
            timeSlots = demoUiState.timeSlots,
            mergedCourses = demoUiState.currentMergedCourses,
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

    var bgContainerSize by remember { mutableStateOf(IntSize.Zero) }
    val previewHaze = remember { HazeState() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { bgContainerSize = it }
    ) {
        // 背景层（Haze 源）：demo 渐变 + 壁纸，保证课程块着色/模糊在预览中可见
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (drawBackground) {
                        Modifier.background(ThemeGradients.weeklyScheduleGradient())
                    } else {
                        Modifier
                    }
                )
                .hazeSource(previewHaze)
        ) {
            if (drawBackground && style.backgroundImagePath.isNotEmpty()) {
                AsyncImage(
                    model = style.backgroundImagePath,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val widthPx = bgContainerSize.width.toFloat().coerceAtLeast(1f)
                            val heightPx = bgContainerSize.height.toFloat().coerceAtLeast(1f)
                            scaleX = style.backgroundScale
                            scaleY = style.backgroundScale
                            translationX = widthPx * style.backgroundOffsetX
                            translationY = heightPx * style.backgroundOffsetY
                        }
                        .blur(style.backgroundBlurRadius),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter
                )
            }
        }
        ScheduleGrid(
            state = gridState,
            viewState = gridViewState,
            actions = gridActions,
            style = style,
            hazeState = previewHaze
        )
    }
}

// StyleSliderItem, StyleSwitchItem, WallpaperItem 已移至 StyleSettingsComponents.kt（共用）
