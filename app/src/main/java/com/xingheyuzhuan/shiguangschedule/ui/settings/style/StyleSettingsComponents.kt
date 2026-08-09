package com.xingheyuzhuan.shiguangschedule.ui.settings.style

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.ui.components.AdvancedColorPicker
import com.xingheyuzhuan.shiguangschedule.ui.components.ColorPickerConfig
import com.xingheyuzhuan.shiguangschedule.ui.schedule.MergedCourseBlock
import com.xingheyuzhuan.shiguangschedule.ui.schedule.WeeklyScheduleUiState
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGrid
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGridActions
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGridViewState
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.ScheduleGridStyleComposed
import com.xingheyuzhuan.shiguangschedule.ui.schedule.components.rememberScheduleGridState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

@Composable
fun ColorPreviewBox(color: Color, isLightModeUI: Boolean) {
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
fun ScheduleGridContent(
    style: ScheduleGridStyleComposed,
    demoUiState: WeeklyScheduleUiState
) {
    val today = remember { LocalDate.now() }
    val localDates = remember(demoUiState.firstDayOfWeek) {
        val dayOfWeekStart = DayOfWeek.of(demoUiState.firstDayOfWeek)
        val startOfWeek = today.with(TemporalAdjusters.previousOrSame(dayOfWeekStart))
        (0..6).map { startOfWeek.plusDays(it.toLong()) }
    }
    val currentYearString = remember(today) { today.year.toString() }
    val dummyDates = remember(localDates) {
        val formatter = DateTimeFormatter.ofPattern("MM/dd")
        localDates.map { it.format(formatter) }
    }
    val dynamicTodayIndex = remember(localDates) { localDates.indexOf(today) }
    val previewWeekStr = stringResource(id = R.string.format_week_display, 1)
    val previewScrollState = rememberScrollState()
    val gridState = rememberScheduleGridState(gridScrollState = previewScrollState)
    val gridViewState = remember(dummyDates, currentYearString, demoUiState, dynamicTodayIndex, previewWeekStr) {
        ScheduleGridViewState(
            dates = dummyDates,
            currentYear = currentYearString,
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

    Box(modifier = Modifier.fillMaxSize()) {
        if (style.backgroundImagePath.isNotEmpty()) {
            AsyncImage(
                model = style.backgroundImagePath,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(style.backgroundBlurRadius),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter
            )
        }

        ScheduleGrid(
            state = gridState,
            viewState = gridViewState,
            actions = gridActions,
            style = style
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .scrollable(
                    orientation = Orientation.Vertical,
                    state = ScrollableState { delta ->
                        previewScrollState.dispatchRawDelta(-delta)
                        delta
                    },
                    flingBehavior = ScrollableDefaults.flingBehavior()
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {},
                        onTap = {}
                    )
                }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleSliderItem(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    stepValue: Float = 1f,
    onValueChange: (Float) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    val isIntegerStep = stepValue >= 1f

    fun formatValue(v: Float): String {
        if (isIntegerStep) return "${v.toInt()}"
        return if (stepValue < 0.1f) "%.2f".format(v) else "%.1f".format(v)
    }

    val steps = remember(range, stepValue) {
        if (stepValue > 0f) {
            ((range.endInclusive - range.start) / stepValue).toInt() - 1
        } else 0
    }

    if (showDialog) {
        var textFieldValue by remember { mutableStateOf(formatValue(value)) }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label) },
            text = {
                Column {
                    Text(
                        text = "${stringResource(R.string.label_range)}: ${formatValue(range.start)} - ${formatValue(range.endInclusive)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = textFieldValue,
                        onValueChange = { input ->
                            if (isIntegerStep) {
                                if (input.all { it.isDigit() }) textFieldValue = input
                            } else {
                                if (input.all { it.isDigit() || it == '.' }) textFieldValue = input
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (isIntegerStep) KeyboardType.Number
                            else KeyboardType.Decimal
                        ),
                        placeholder = { Text(stringResource(R.string.placeholder_input_value)) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newValue = textFieldValue.toFloatOrNull()
                    if (newValue != null) {
                        val clampedValue = newValue.coerceIn(range.start, range.endInclusive)
                        val steppedValue = if (stepValue > 0f) {
                            val count = ((clampedValue - range.start) / stepValue).roundToInt()
                            range.start + count * stepValue
                        } else clampedValue

                        onValueChange(steppedValue)
                        showDialog = false
                    }
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { showDialog = true }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = formatValue(value),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = if (steps > 0) steps else 0,
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
fun StyleSwitchItem(
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
fun WallpaperItem(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerItem(
    label: String,
    currentColor: Color?,
    onColorChanged: (Color) -> Unit,
    onReset: () -> Unit
) {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { showSheet = true }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)

        if (currentColor != null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(currentColor)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), CircleShape)
            )
        } else {
            Text(
                text = stringResource(R.string.status_not_set),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 40.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                    TextButton(onClick = {
                        onReset()
                        showSheet = false
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_reset))
                    }
                }
                val pickerInitialColor = currentColor ?: MaterialTheme.colorScheme.primary

                AdvancedColorPicker(
                    initialColor = pickerInitialColor,
                    onColorChanged = onColorChanged,
                    config = ColorPickerConfig(
                        showAlpha = false,
                        showInputMode = true
                    )
                )
            }
        }
    }
}