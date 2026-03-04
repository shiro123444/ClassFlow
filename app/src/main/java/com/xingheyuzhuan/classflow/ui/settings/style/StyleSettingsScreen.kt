package com.xingheyuzhuan.classflow.ui.settings.style

import android.content.res.Configuration
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.xingheyuzhuan.classflow.R
import com.xingheyuzhuan.classflow.Screen
import com.xingheyuzhuan.classflow.ui.components.AdvancedColorPicker
import com.xingheyuzhuan.classflow.ui.components.ColorPickerConfig
import com.xingheyuzhuan.classflow.ui.schedule.WeeklyScheduleUiState
import com.xingheyuzhuan.classflow.ui.schedule.components.ScheduleGrid
import com.xingheyuzhuan.classflow.ui.schedule.components.ScheduleGridStyleComposed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleSettingsScreen(
    navController: NavController,
    viewModel: StyleSettingsViewModel = viewModel(factory = StyleSettingsViewModelFactory)
) {
    val styleState by viewModel.styleState.collectAsStateWithLifecycle()
    val demoUiState by viewModel.demoUiState.collectAsStateWithLifecycle()

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var showColorPicker by remember { mutableStateOf(false) }
    var pickingCategory by remember { mutableIntStateOf(0) }
    var isDarkTarget by remember { mutableStateOf(false) }
    var selectedColorIndex by remember { mutableIntStateOf(0) }

    val sheetState = rememberModalBottomSheetState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.item_personalization)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
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
                    Card(modifier = Modifier.fillMaxHeight().weight(0.6f), shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)) {
                        SettingsListContent(currentStyle, viewModel, onNavigateToAdjust = {
                            navController.navigate(Screen.WallpaperAdjust.route)
                        }) { cat, isDark, idx ->
                            pickingCategory = cat; isDarkTarget = isDark; selectedColorIndex = idx
                            showColorPicker = true
                        }
                    }
                }
            } else {
                Column(modifier = contentModifier) {
                    previewContent(Modifier.fillMaxWidth().weight(0.38f))
                    Card(modifier = Modifier.fillMaxWidth().weight(0.62f), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                        SettingsListContent(currentStyle, viewModel, onNavigateToAdjust = {
                            navController.navigate(Screen.WallpaperAdjust.route)
                        }) { cat, isDark, idx ->
                            pickingCategory = cat; isDarkTarget = isDark; selectedColorIndex = idx
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
                    val initialColor = if (pickingCategory == 1) {
                        if (isDarkTarget) currentStyle.conflictCourseColorDark else currentStyle.conflictCourseColor
                    } else {
                        val pair = currentStyle.courseColorMaps.getOrNull(selectedColorIndex)
                        if (isDarkTarget) pair?.dark ?: Color.Gray else pair?.light ?: Color.Gray
                    }

                    var currentColorInPicker by remember { mutableStateOf(initialColor) }

                    AdvancedColorPicker(
                        initialColor = initialColor,
                        config = ColorPickerConfig(showAlpha = false),
                        onColorChanged = { newColor ->
                            currentColorInPicker = newColor
                            if (pickingCategory == 1) {
                                viewModel.updateConflictColor(newColor, isDarkTarget)
                            } else {
                                viewModel.updatePrimaryColor(selectedColorIndex, newColor, isDarkTarget)
                            }
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
    onPick: (category: Int, isDark: Boolean, index: Int) -> Unit
) {
    var showResetDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let {
            viewModel.updateWallpaper(context, it)
            onNavigateToAdjust()
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
            WallpaperItem(
                path = currentStyle.backgroundImagePath,
                onClick = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onLongClick = { viewModel.removeWallpaper(context) },
                onAdjust = if (currentStyle.backgroundImagePath.isNotEmpty()) onNavigateToAdjust else null
            )
            StyleSwitchItem(stringResource(R.string.label_hide_section_time), currentStyle.hideSectionTime) { viewModel.updateHideSectionTime(it) }
            StyleSwitchItem(stringResource(R.string.label_hide_date_under_day), currentStyle.hideDateUnderDay) { viewModel.updateHideDateUnderDay(it) }
            StyleSwitchItem(stringResource(R.string.label_hide_grid_lines), currentStyle.hideGridLines) { viewModel.updateHideGridLines(it) }
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
                    2 to stringResource(R.string.glass_style_frost)
                ).forEachIndexed { index, (preset, label) ->
                    SegmentedButton(
                        selected = currentStyle.glassPreset == preset,
                        onClick = { viewModel.applyGlassPreset(preset) },
                        shape = SegmentedButtonDefaults.itemShape(index, 3)
                    ) { Text(label) }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            StyleSwitchItem(stringResource(R.string.label_show_start_time), currentStyle.showStartTime) { viewModel.updateShowStartTime(it) }
            StyleSwitchItem(stringResource(R.string.label_hide_location), currentStyle.hideLocation) { viewModel.updateHideLocation(it) }
            StyleSwitchItem(stringResource(R.string.label_hide_teacher), currentStyle.hideTeacher) { viewModel.updateHideTeacher(it) }
            StyleSwitchItem(stringResource(R.string.label_remove_location_at), currentStyle.removeLocationAt) { viewModel.updateRemoveLocationAt(it) }
        }

        // ── Fine-tuning ──
        SettingsGroup(title = stringResource(R.string.label_font_style)) {
            StyleSliderItem(stringResource(R.string.label_font_scale), currentStyle.fontScale, 0.5f..2.0f) { viewModel.updateCourseBlockFontScale(it) }
            StyleSliderItem(stringResource(R.string.label_corner_radius), currentStyle.courseBlockCornerRadius.value, 0f..24f) { viewModel.updateCornerRadius(it) }
            StyleSliderItem(stringResource(R.string.label_inner_padding), currentStyle.courseBlockInnerPadding.value, 0f..12f) { viewModel.updateInnerPadding(it) }
            StyleSliderItem(stringResource(R.string.label_outer_padding), currentStyle.courseBlockOuterPadding.value, 0f..8f) { viewModel.updateOuterPadding(it) }
            StyleSliderItem(stringResource(R.string.label_opacity), currentStyle.courseBlockAlpha, 0.1f..1f) { viewModel.updateAlpha(it) }
            StyleSliderItem(stringResource(R.string.label_background_dim), currentStyle.backgroundDimAlpha, 0f..0.7f) { viewModel.updateBackgroundDimAlpha(it) }
        }

        // ── Color scheme ──
        SettingsGroup(title = stringResource(R.string.style_category_color_scheme)) {
            ColorSchemeSection(
                title = stringResource(R.string.title_light_color_pool),
                bgColor = lightColorScheme().surfaceContainerLow,
                isDarkSection = false,
                colors = currentStyle.courseColorMaps.map { it.light },
                conflictColor = currentStyle.conflictCourseColor,
                onEditColor = { onPick(0, false, it) },
                onEditConflict = { onPick(1, false, 0) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            ColorSchemeSection(
                title = stringResource(R.string.title_dark_color_pool),
                bgColor = darkColorScheme().surfaceContainerLow,
                isDarkSection = true,
                colors = currentStyle.courseColorMaps.map { it.dark },
                conflictColor = currentStyle.conflictCourseColorDark,
                onEditColor = { onPick(0, true, it) },
                onEditConflict = { onPick(1, true, 0) }
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
    conflictColor: Color,
    onEditColor: (Int) -> Unit,
    onEditConflict: () -> Unit
) {
    val contentColor = if (isDarkSection) Color.White else Color.Black

    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bgColor).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = contentColor)
        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(conflictColor).border(2.dp, contentColor.copy(0.3f), CircleShape).clickable { onEditConflict() })
                Text(stringResource(R.string.label_color_conflict), style = MaterialTheme.typography.labelSmall, color = contentColor.copy(0.6f), modifier = Modifier.padding(top = 4.dp))
            }

            VerticalDivider(modifier = Modifier.height(40.dp).padding(horizontal = 12.dp), color = contentColor.copy(0.1f))

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
private fun ColorPreviewBox(color: Color, isLightModeUI: Boolean) {
    Box(modifier = Modifier.fillMaxWidth().height(100.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(color), contentAlignment = Alignment.Center) {
        Text(
            text = if (isLightModeUI) stringResource(R.string.preview_light_mode) else stringResource(R.string.preview_dark_mode),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = if (isLightModeUI) lightColorScheme().onSurface else Color.White
        )
    }
}

@Composable
internal fun ScheduleGridContent(
    style: ScheduleGridStyleComposed,
    demoUiState: WeeklyScheduleUiState
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

    var bgContainerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { bgContainerSize = it }
    ) {
        if (style.backgroundImagePath.isNotEmpty()) {
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
                    },
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = style.backgroundDimAlpha))
            )
        }
        ScheduleGrid(
            style = style,
            dates = dummyDates,
            timeSlots = demoUiState.timeSlots,
            mergedCourses = demoUiState.currentMergedCourses,
            showWeekends = demoUiState.showWeekends,
            todayIndex = dynamicTodayIndex,
            firstDayOfWeek = demoUiState.firstDayOfWeek,
            onCourseBlockClicked = { },
            onGridCellClicked = { _, _ -> },
            onTimeSlotClicked = { }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StyleSliderItem(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (value < 100f && value > 0.01f) "%.2f".format(value) else "${value.toInt()}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.height(32.dp),
            thumb = {
                Surface(
                    modifier = Modifier.size(16.dp),
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 1.dp,
                    border = BorderStroke(0.5.dp, Color.LightGray.copy(alpha = 0.5f))
                ) {}
            },
            track = { sliderState ->
                Box(
                    modifier = Modifier.fillMaxWidth().height(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.fillMaxWidth().height(22.dp),
                        colors = SliderDefaults.colors(
                            activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            inactiveTrackColor = Color.Transparent
                        ),
                        thumbTrackGapSize = 0.dp,
                        trackInsideCornerSize = 0.dp
                    )
                }
            }
        )
    }
}

@Composable
private fun StyleSwitchItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            thumbContent = if (checked) {
                { Icon(modifier = Modifier.size(SwitchDefaults.IconSize), imageVector = Icons.Filled.Check, contentDescription = null) }
            } else null
        )
    }
}

@Composable
private fun WallpaperItem(
    path: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAdjust: (() -> Unit)? = null
) {
    val hasWallpaper = path.isNotEmpty()

    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.label_wallpaper), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (hasWallpaper) stringResource(R.string.desc_wallpaper_set)
                else stringResource(R.string.desc_wallpaper_unset),
                style = MaterialTheme.typography.labelSmall,
                color = if (hasWallpaper) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onAdjust != null) {
            IconButton(onClick = onAdjust) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = stringResource(R.string.action_adjust_wallpaper),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Icon(
            imageVector = Icons.Default.Image,
            contentDescription = null,
            tint = if (hasWallpaper) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
    }
}
