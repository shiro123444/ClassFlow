package com.xingheyuzhuan.shiguangschedule.ui.settings
import com.xingheyuzhuan.shiguangschedule.ui.theme.LocalIsDarkTheme
import androidx.hilt.navigation.compose.hiltViewModel

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.ui.components.DatePickerModal
import com.xingheyuzhuan.shiguangschedule.ui.components.DockSafeBottomPadding
import com.xingheyuzhuan.shiguangschedule.ui.components.NavigationRailWidth
import com.xingheyuzhuan.shiguangschedule.ui.components.isWideScreen
import com.xingheyuzhuan.shiguangschedule.ui.components.NativeNumberPicker
import com.xingheyuzhuan.shiguangschedule.ui.components.OnboardingTargets
import com.xingheyuzhuan.shiguangschedule.ui.theme.ThemeGradients
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navBridge: NavBridge,
    semesterStartDateItemModifier: Modifier = Modifier,
    manageCourseTablesModifier: Modifier = Modifier,
    forceShowSemesterStartDateCard: Boolean = false,
    forceScrollToManageTables: Boolean = false,
    onSemesterStartDateSet: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val courseTableConfig by viewModel.courseTableConfigState.collectAsState()

    val showNonCurrentWeek = viewModel.appSettingsState.collectAsState().value.showNonCurrentWeekCourses
    val showWeekends = courseTableConfig?.showWeekends ?: false
    val semesterStartDateString = courseTableConfig?.semesterStartDate
    val semesterTotalWeeks = courseTableConfig?.semesterTotalWeeks ?: 20
    val firstDayOfWeekInt = courseTableConfig?.firstDayOfWeek ?: DayOfWeek.MONDAY.value
    val displayCurrentWeek by viewModel.currentWeekState.collectAsState()

    val semesterStartDate: LocalDate? = remember(semesterStartDateString) {
        semesterStartDateString?.let {
            try {
                LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (e: DateTimeParseException) {
                Log.e("SettingsScreen", "Failed to parse date string: $it", e)
                null
            }
        }
    }

    var showTotalWeeksDialog by remember { mutableStateOf(false) }
    var showManualWeekDialog by remember { mutableStateOf(false) }
    var showDatePickerModal by remember { mutableStateOf(false) }
    var showFirstDayOfWeekDialog by remember { mutableStateOf(false) }
    val settingsListState = rememberLazyListState()

    val backgroundBrush = ThemeGradients.backgroundGradient()

    DisposableEffect(Unit) {
        onDispose {
            OnboardingTargets.semesterStartDateBoundsInWindow = null
        }
    }

    LaunchedEffect(forceShowSemesterStartDateCard) {
        if (forceShowSemesterStartDateCard) {
            settingsListState.animateScrollToItem(index = 1)
        }
    }

    LaunchedEffect(forceScrollToManageTables) {
        if (forceScrollToManageTables) {
            // LazyColumn 中 index=0 是 Header，index=1 是通用设置，index=2 是高级功能。
            // 直接将高级功能卡片顶部平滑推到屏幕顶部，管理课表就会居中在屏幕中上方偏下位置
            kotlinx.coroutines.delay(100)
            settingsListState.animateScrollToItem(index = 2, scrollOffset = 0)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (isWideScreen) NavigationRailWidth else 0.dp)
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            state = settingsListState,
            contentPadding = PaddingValues(top = 24.dp, bottom = DockSafeBottomPadding),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                ProfileHeader()
            }

            item {
                SettingsCard(title = stringResource(R.string.section_title_general_settings)) {
                    SettingTile(
                        icon = Icons.Rounded.ViewWeek,
                        title = stringResource(R.string.item_show_non_current_week),
                        subtitle = stringResource(R.string.desc_show_non_current_week),
                        trailingContent = {
                            Switch(
                                checked = showNonCurrentWeek,
                                onCheckedChange = { viewModel.onShowNonCurrentWeekChanged(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                    uncheckedThumbColor = Color.White.copy(alpha = 0.9f),
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                                )
                            )
                        }
                    )
                    SettingDivider()
                    SettingTile(
                        icon = Icons.Rounded.EventAvailable,
                        title = stringResource(R.string.item_show_weekends),
                        subtitle = stringResource(R.string.desc_show_weekends),
                        trailingContent = { 
                            Switch(
                                checked = showWeekends, 
                                onCheckedChange = { viewModel.onShowWeekendsChanged(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                    uncheckedThumbColor = Color.White.copy(alpha = 0.9f),
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                                )
                            ) 
                        }
                    )
                    SettingDivider()
                    SettingTile(
                        icon = Icons.Rounded.DateRange,
                        title = stringResource(R.string.item_set_start_date),
                        subtitle = semesterStartDate?.format(DateTimeFormatter.ofPattern(stringResource(R.string.date_format_year_month_day))) ?: stringResource(R.string.status_not_set_semester_start),
                        contentHighlightModifier = semesterStartDateItemModifier
                            .onGloballyPositioned {
                                OnboardingTargets.semesterStartDateBoundsInWindow = it.boundsInWindow()
                            },
                        onClick = {
                            // Finish onboarding before opening DatePicker, otherwise the
                            // showcase overlay can block dialog interactions on this step.
                            if (forceShowSemesterStartDateCard) {
                                onSemesterStartDateSet?.invoke()
                            }
                            showDatePickerModal = true
                        }
                    )
                    SettingDivider()
                    SettingTile(
                        icon = Icons.Rounded.LinearScale,
                        title = stringResource(R.string.item_total_weeks),
                        subtitle = stringResource(R.string.status_total_weeks_format, semesterTotalWeeks),
                        onClick = { showTotalWeeksDialog = true }
                    )
                    SettingDivider()
                    val currentWeekVal = displayCurrentWeek
                    val weekStatusText = when {
                        semesterStartDate == null -> stringResource(R.string.status_set_start_date_first)
                        currentWeekVal == null -> stringResource(R.string.dialog_option_on_vacation)
                        else -> stringResource(R.string.status_current_week_format, currentWeekVal)
                    }
                    SettingTile(
                        icon = Icons.Rounded.CalendarToday,
                        title = stringResource(R.string.item_current_week),
                        subtitle = weekStatusText,
                        onClick = { showManualWeekDialog = true }
                    )
                    SettingDivider()
                    val dayText = if (firstDayOfWeekInt == DayOfWeek.SUNDAY.value) stringResource(R.string.day_of_week_sunday) else stringResource(R.string.day_of_week_monday)
                    SettingTile(
                        icon = Icons.Rounded.ViewWeek,
                        title = stringResource(R.string.item_first_day_of_week),
                        subtitle = stringResource(R.string.desc_first_day_of_week_format, dayText),
                        onClick = { showFirstDayOfWeekDialog = true }
                    )
                    SettingDivider()
                    SettingTile(
                        icon = Icons.Rounded.SwapHoriz,
                        title = stringResource(R.string.item_quick_actions),
                        subtitle = stringResource(R.string.desc_quick_actions),
                        onClick = { navBridge.navigate(Destination.QuickActions) }
                    )
                }
            }

            item {
                SettingsCard(title = stringResource(R.string.section_title_advanced_features)) {
                    // 课表导入/导出设置项
                    SettingTile(
                        icon = Icons.Rounded.FolderZip,
                        title = stringResource(R.string.item_course_conversion),
                        subtitle = stringResource(R.string.desc_course_conversion),
                        onClick = { navBridge.navigate(Destination.CourseTableConversion) }
                    )
                    SettingDivider()
                    // 课程提醒设置项
                    SettingTile(
                        icon = Icons.Rounded.Notifications,
                        title = stringResource(R.string.title_course_notification_settings),
                        subtitle = stringResource(R.string.desc_notification_settings),
                        onClick = { navBridge.navigate(Destination.NotificationSettings) }
                    )
                    SettingDivider()
                    // 管理课表设置项
                    SettingTile(
                        contentHighlightModifier = manageCourseTablesModifier,
                        icon = Icons.Rounded.Book,
                        title = stringResource(R.string.title_manage_course_tables),
                        subtitle = stringResource(R.string.desc_manage_course_tables),
                        onClick = { navBridge.navigate(Destination.ManageCourseTables) }
                    )
                    SettingDivider()
                    // 课程管理设置项
                    SettingTile(
                        icon = Icons.Rounded.School,
                        title = stringResource(R.string.item_course_management),
                        subtitle = stringResource(R.string.desc_course_management),
                        onClick = { navBridge.navigate(Destination.CourseManagementList) }
                    )
                    SettingDivider()
                    // 自定义时间段设置项
                    SettingTile(
                        icon = Icons.Rounded.Schedule,
                        title = stringResource(R.string.item_time_slot_customization),
                        subtitle = stringResource(R.string.desc_time_slot_customization),
                        onClick = { navBridge.navigate(Destination.TimeSlotSettings) }
                    )
                    SettingDivider()
                    // 个性化配置
                    SettingTile(
                        icon = Icons.Rounded.Palette,
                        title = stringResource(R.string.item_personalization),
                        subtitle = stringResource(R.string.desc_personalization),
                        onClick = { navBridge.navigate(Destination.StyleSettings) }
                    )
                    SettingDivider()
                    // 更多选项设置项
                    SettingTile(
                        icon = Icons.Rounded.Info,
                        title = stringResource(R.string.item_more_options),
                        subtitle = stringResource(R.string.desc_more_options),
                        onClick = { navBridge.navigate(Destination.MoreOptions) }
                    )
                }
            }
        }
    }

    // --- Dialogs below ---
    if (showDatePickerModal) {
        DatePickerModal(
            onDateSelected = { selectedDateMillis ->
                viewModel.onSemesterStartDateSelected(selectedDateMillis)
                if (selectedDateMillis != null) {
                    onSemesterStartDateSet?.invoke()
                }
            },
            onDismiss = { showDatePickerModal = false }
        )
    }

    if (showTotalWeeksDialog) {
        NumberPickerDialog(
            title = stringResource(R.string.dialog_title_select_total_weeks),
            range = 1..30,
            initialValue = semesterTotalWeeks,
            onDismiss = { showTotalWeeksDialog = false },
            onConfirm = { selectedWeeks ->
                viewModel.onSemesterTotalWeeksSelected(selectedWeeks)
                showTotalWeeksDialog = false
            }
        )
    }

    if (showManualWeekDialog) {
        ManualWeekPickerDialog(
            totalWeeks = semesterTotalWeeks,
            currentWeek = displayCurrentWeek,
            onDismiss = { showManualWeekDialog = false },
            onConfirm = { weekNumber ->
                viewModel.onCurrentWeekManuallySet(weekNumber)
                showManualWeekDialog = false
            }
        )
    }

    if (showFirstDayOfWeekDialog) {
        DayOfWeekPickerDialog(
            initialDayOfWeekInt = firstDayOfWeekInt,
            onDismiss = { showFirstDayOfWeekDialog = false },
            onConfirm = { selectedDayInt ->
                viewModel.onFirstDayOfWeekSelected(selectedDayInt)
                showFirstDayOfWeekDialog = false
            }
        )
    }
}

@Composable
fun ProfileHeader() {
    val isDark = LocalIsDarkTheme.current
    val glassBg = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.55f)
    val borderTop = if (isDark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.6f)
    val borderBottom = if (isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.15f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(glassBg)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(borderTop, borderBottom)),
                shape = RoundedCornerShape(28.dp)
            )
            .padding(24.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Face,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "ClassFlow",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(R.string.brand_welcome_wbuer),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val glassBg = if (isDark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.50f)
    val borderTop = if (isDark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.55f)
    val borderBottom = if (isDark) Color.White.copy(alpha = 0.03f) else Color.White.copy(alpha = 0.12f)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(glassBg)
                .border(
                    width = 0.8.dp,
                    brush = Brush.verticalGradient(listOf(borderTop, borderBottom)),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
fun SettingTile(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    contentHighlightModifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = {
        val dark = LocalIsDarkTheme.current
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.18f else 0.4f))
                .border(
                    width = 0.6.dp,
                    color = Color.White.copy(alpha = if (dark) 0.18f else 0.45f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
) {
    val isDark = LocalIsDarkTheme.current
    val tileBg = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.34f)
    val tileBorderTop = if (isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.48f)
    val tileBorderBottom = if (isDark) Color.White.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.12f)

    Row(
        modifier = Modifier
            .then(modifier)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tileBg)
            .border(
                width = 0.75.dp,
                brush = Brush.verticalGradient(listOf(tileBorderTop, tileBorderBottom)),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.30f else 0.45f))
                .border(
                    width = 0.75.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (isDark) 0.22f else 0.55f),
                            Color.White.copy(alpha = if (isDark) 0.06f else 0.18f)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            Column(modifier = contentHighlightModifier) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailingContent()
        }
    }
}

@Composable
fun SettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 64.dp, vertical = 4.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)
    )
}

@Composable
fun ManualWeekPickerDialog(
    totalWeeks: Int,
    currentWeek: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int?) -> Unit
) {
    val optionOnVacationText = stringResource(R.string.dialog_option_on_vacation)
    val weekOptions = listOf(optionOnVacationText) + (1..totalWeeks).map { stringResource(R.string.status_current_week_format, it) }
    val initialSelectedValue = when (currentWeek) {
        null -> optionOnVacationText
        else -> stringResource(R.string.status_current_week_format, currentWeek)
    }

    var dialogSelectedValue by remember { mutableStateOf(initialSelectedValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_title_manual_set_week)) },
        text = {
            NativeNumberPicker(
                values = weekOptions,
                selectedValue = dialogSelectedValue,
                onValueChange = { dialogSelectedValue = it },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = {
                val weekNumber = if (dialogSelectedValue == optionOnVacationText) null else dialogSelectedValue.filter { it.isDigit() }.toIntOrNull()
                onConfirm(weekNumber)
            }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
fun DayOfWeekPickerDialog(
    initialDayOfWeekInt: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val mondayText = stringResource(R.string.day_of_week_monday)
    val sundayText = stringResource(R.string.day_of_week_sunday)
    val dayOptionsMap = mapOf(
        mondayText to DayOfWeek.MONDAY.value,
        sundayText to DayOfWeek.SUNDAY.value
    )
    val initialSelectedDayText = dayOptionsMap.entries.firstOrNull { it.value == initialDayOfWeekInt }?.key ?: mondayText
    var dialogSelectedText by remember { mutableStateOf(initialSelectedDayText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_title_set_first_day_of_week)) },
        text = {
            NativeNumberPicker(
                values = dayOptionsMap.keys.toList(),
                selectedValue = dialogSelectedText,
                onValueChange = { dialogSelectedText = it },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(dayOptionsMap[dialogSelectedText] ?: DayOfWeek.MONDAY.value)
            }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun NumberPickerDialog(
    title: String,
    range: IntRange,
    initialValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var dialogSelectedValue by remember { mutableStateOf(initialValue.coerceIn(range)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            NativeNumberPicker(
                values = range.toList(),
                selectedValue = initialValue.coerceIn(range),
                onValueChange = { dialogSelectedValue = it },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(dialogSelectedValue) }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

