package com.xingheyuzhuan.classflow.ui.settings.course

import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xingheyuzhuan.classflow.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditCourseScreen(
    courseId: String?,
    onNavigateBack: () -> Unit,
) {
    val viewModel: AddEditCourseViewModel = viewModel(
        factory = AddEditCourseViewModel.Factory(
            courseId = courseId,
        )
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showWeekSelectorDialog by remember { mutableStateOf(false) }
    var showColorSelectorDialog by remember { mutableStateOf(false) }
    var showSectionTimeDialog by remember { mutableStateOf(false) }
    var showDayDialog by remember { mutableStateOf(false) }
    var showCustomTimeDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val saveSuccessText = stringResource(R.string.toast_save_success)
    val deleteSuccessText = stringResource(R.string.toast_delete_success)
    val nameEmptyText = stringResource(R.string.toast_name_empty)
    val toastCustomTimeEmpty = stringResource(R.string.toast_custom_time_empty)
    val toastTimeInvalid = stringResource(R.string.toast_time_invalid)

    val isDarkTheme = isSystemInDarkTheme()

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                UiEvent.SaveSuccess -> {
                    Toast.makeText(context, saveSuccessText, Toast.LENGTH_SHORT).show()
                    onNavigateBack()
                }
                UiEvent.DeleteSuccess -> {
                    Toast.makeText(context, deleteSuccessText, Toast.LENGTH_SHORT).show()
                    onNavigateBack()
                }
                UiEvent.Cancel -> onNavigateBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.isEditing) {
                            stringResource(R.string.title_edit_course)
                        } else {
                            stringResource(R.string.title_add_course)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = viewModel::onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.a11y_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (uiState.name.isBlank()) {
                                Toast.makeText(context, nameEmptyText, Toast.LENGTH_SHORT).show()
                            } else {
                                var isValid = true
                                if (uiState.isCustomTime) {
                                    if (uiState.customStartTime.isBlank() || uiState.customEndTime.isBlank()) {
                                        Toast.makeText(context, toastCustomTimeEmpty, Toast.LENGTH_SHORT).show()
                                        isValid = false
                                    }
                                    else if (uiState.customStartTime >= uiState.customEndTime) {
                                        Toast.makeText(context, toastTimeInvalid, Toast.LENGTH_SHORT).show()
                                        isValid = false
                                    }
                                } else if (uiState.startSection > uiState.endSection) {
                                    Toast.makeText(context, toastTimeInvalid, Toast.LENGTH_SHORT).show()
                                    isValid = false
                                }

                                if (isValid) {
                                    viewModel.onSave()
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Done, contentDescription = stringResource(R.string.a11y_save))
                    }
                    if (uiState.isEditing) {
                        IconButton(onClick = viewModel::onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.a11y_delete))
                        }
                    }
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = uiState.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.label_course_name)) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = uiState.teacher,
                onValueChange = viewModel::onTeacherChange,
                label = { Text(stringResource(R.string.label_teacher)) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = uiState.position,
                onValueChange = viewModel::onPositionChange,
                label = { Text(stringResource(R.string.label_position)) },
                modifier = Modifier.fillMaxWidth()
            )

            TimeAreaSelector(
                day = uiState.day,
                startSection = uiState.startSection,
                endSection = uiState.endSection,
                timeSlots = uiState.timeSlots,
                isCustomTime = uiState.isCustomTime,
                customStartTime = uiState.customStartTime,
                customEndTime = uiState.customEndTime,
                onIsCustomTimeChange = viewModel::onIsCustomTimeChange,
                onDayClick = { showDayDialog = true },
                onTimeRangeClick = { showCustomTimeDialog = true },
                onSectionButtonClick = { showSectionTimeDialog = true }
            )

            WeekSelector(
                selectedWeeks = uiState.weeks,
                onWeekClick = { showWeekSelectorDialog = true }
            )

            ColorPicker(
                selectedColor = uiState.courseColorMaps.getOrNull(uiState.colorIndex)?.let { dualColor ->
                    if (isDarkTheme) dualColor.dark else dualColor.light
                } ?: Color.Unspecified,
                onColorClick = { showColorSelectorDialog = true }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }


    if (showWeekSelectorDialog) {
        WeekSelectorBottomSheet(
            totalWeeks = uiState.semesterTotalWeeks,
            selectedWeeks = uiState.weeks,
            onDismissRequest = { showWeekSelectorDialog = false },
            onConfirm = { newWeeks ->
                viewModel.onWeeksChange(newWeeks)
                showWeekSelectorDialog = false
            }
        )
    }

    if (showColorSelectorDialog) {
        ColorPickerBottomSheet(
            colorMaps = uiState.courseColorMaps,
            selectedIndex = uiState.colorIndex,
            onDismissRequest = { showColorSelectorDialog = false },
            onConfirm = { newIndex ->
                viewModel.onColorChange(newIndex)
                showColorSelectorDialog = false
            }
        )
    }

    if (showSectionTimeDialog && !uiState.isCustomTime) {
        CourseTimePickerBottomSheet(
            selectedDay = uiState.day,
            onDaySelected = viewModel::onDayChange,
            startSection = uiState.startSection,
            onStartSectionChange = viewModel::onStartSectionChange,
            endSection = uiState.endSection,
            onEndSectionChange = viewModel::onEndSectionChange,
            timeSlots = uiState.timeSlots,
            onDismissRequest = { showSectionTimeDialog = false }
        )
    }

    if (showDayDialog) {
        DayPickerDialog(
            selectedDay = uiState.day,
            onDismissRequest = { showDayDialog = false },
            onDaySelected = { newDay ->
                viewModel.onDayChange(newDay)
                showDayDialog = false
            }
        )
    }
    if (showCustomTimeDialog && uiState.isCustomTime) {
        CustomTimeRangePickerBottomSheet(
            initialStartTime = uiState.customStartTime.ifBlank { "08:00" },
            initialEndTime = uiState.customEndTime.ifBlank { "09:45" },
            onDismissRequest = { showCustomTimeDialog = false },
            onTimeRangeSelected = { start, end ->
                viewModel.onCustomTimeChange(start, end)
                showCustomTimeDialog = false
            }
        )
    }
}
