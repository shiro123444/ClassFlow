package com.xingheyuzhuan.classflow.ui.settings.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.xingheyuzhuan.classflow.data.db.main.Course
import com.xingheyuzhuan.classflow.data.db.main.TimeSlot
import com.xingheyuzhuan.classflow.data.repository.AppSettingsRepository
import com.xingheyuzhuan.classflow.data.repository.CourseTableRepository
import com.xingheyuzhuan.classflow.data.repository.TimeSlotRepository
import com.xingheyuzhuan.classflow.data.repository.StyleSettingsRepository
import com.xingheyuzhuan.classflow.data.model.DualColor
import com.xingheyuzhuan.classflow.MyApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import com.xingheyuzhuan.classflow.navigation.AddEditCourseChannel
import com.xingheyuzhuan.classflow.navigation.PresetCourseData
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalCoroutinesApi::class)
class AddEditCourseViewModel(
    private val courseTableRepository: CourseTableRepository,
    private val timeSlotRepository: TimeSlotRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val styleSettingsRepository: StyleSettingsRepository,
    private val courseId: String?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddEditCourseUiState())
    val uiState: StateFlow<AddEditCourseUiState> = _uiState.asStateFlow()

    private val _uiEvent = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    init {
        viewModelScope.launch {

            val initialPresetData: PresetCourseData? = if (courseId == null) {
                try {
                    AddEditCourseChannel.presetDataFlow.first()
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }

            val appSettingsFlow = appSettingsRepository.getAppSettings()
            val styleFlow = styleSettingsRepository.styleFlow

            @OptIn(ExperimentalCoroutinesApi::class)
            val timeSlotsFlow = appSettingsFlow.flatMapLatest { settings ->
                val courseTableId = settings.currentCourseTableId
                if (courseTableId != null) {
                    timeSlotRepository.getTimeSlotsByCourseTableId(courseTableId)
                } else {
                    flowOf(emptyList())
                }
            }

            @OptIn(ExperimentalCoroutinesApi::class)
            val courseConfigFlow = appSettingsFlow.flatMapLatest { settings ->
                val courseTableId = settings.currentCourseTableId
                if (courseTableId != null) {
                    appSettingsRepository.getCourseTableConfigFlow(courseTableId)
                } else {
                    flowOf(null)
                }
            }

            combine(
                timeSlotsFlow,
                appSettingsFlow,
                courseConfigFlow,
                styleFlow,
                if (courseId != null) {
                    appSettingsFlow.flatMapLatest { settings ->
                        courseTableRepository.getCoursesWithWeeksByTableId(settings.currentCourseTableId.orEmpty())
                            .map { courses -> courses.find { it.course.id == courseId } }
                    }
                } else {
                    flowOf(null)
                }
            ) { timeSlots, appSettings, courseConfig, currentStyle, courseWithWeeks ->
                _uiState.update { currentState ->

                    val totalWeeks = courseConfig?.semesterTotalWeeks ?: 20
                    val currentColorMaps = currentStyle.courseColorMaps
                    val maxColorIndex = currentColorMaps.size - 1

                    val (course: Course?, initialColorIndex: Int) = if (currentState.course != null) {
                        Pair(currentState.course, currentState.colorIndex)
                    } else if (courseId == null) {
                        // 使用当前样式的随机逻辑
                        val newColorIndex = currentStyle.generateRandomColorIndex()
                        val newCourse = Course(
                            id = UUID.randomUUID().toString(),
                            courseTableId = appSettings.currentCourseTableId.orEmpty(),
                            name = "", teacher = "", position = "",
                            day = 1,
                            startSection = 1,
                            endSection = 1,
                            isCustomTime = false,
                            customStartTime = null,
                            customEndTime = null,
                            colorInt = newColorIndex
                        )
                        Pair(newCourse, newColorIndex)
                    } else {
                        val existingCourse = courseWithWeeks?.course
                        val existingColorIndex = existingCourse?.colorInt

                        // 校验索引是否在当前样式的有效范围内
                        val validatedIndex = if (existingColorIndex != null && existingColorIndex >= 0 && existingColorIndex <= maxColorIndex) {
                            existingColorIndex
                        } else {
                            currentStyle.generateRandomColorIndex()
                        }

                        Pair(existingCourse, validatedIndex)
                    }

                    val weeks = currentState.weeks.takeIf { it.isNotEmpty() } ?: if(courseId == null) {
                        (1..totalWeeks).toSet()
                    } else {
                        courseWithWeeks?.weeks?.map { it.weekNumber }?.toSet() ?: emptySet()
                    }

                    val finalDay = initialPresetData?.day ?: course?.day ?: 1
                    val finalStartSection = initialPresetData?.startSection ?: course?.startSection ?: 1
                    val finalEndSection = initialPresetData?.endSection ?: course?.endSection ?: 1

                    currentState.copy(
                        isEditing = courseId != null,
                        course = course,
                        name = initialPresetData?.name ?: course?.name.orEmpty(),
                        teacher = initialPresetData?.teacher ?: course?.teacher.orEmpty(),
                        position = initialPresetData?.position ?: course?.position.orEmpty(),
                        day = finalDay,
                        startSection = finalStartSection,
                        endSection = finalEndSection,
                        isCustomTime = course?.isCustomTime ?: false,
                        customStartTime = course?.customStartTime.orEmpty(),
                        customEndTime = course?.customEndTime.orEmpty(),
                        colorIndex = initialColorIndex,
                        weeks = weeks,
                        timeSlots = timeSlots,
                        currentCourseTableId = appSettings.currentCourseTableId,
                        semesterTotalWeeks = totalWeeks,
                        courseColorMaps = currentColorMaps
                    )
                }
            }.collect()
        }
    }

    fun onNameChange(name: String) { _uiState.update { it.copy(name = name) } }
    fun onTeacherChange(teacher: String) { _uiState.update { it.copy(teacher = teacher) } }
    fun onPositionChange(position: String) { _uiState.update { it.copy(position = position) } }
    fun onDayChange(day: Int) { _uiState.update { it.copy(day = day) } }
    fun onStartSectionChange(startSection: Int) { _uiState.update { it.copy(startSection = startSection) } }
    fun onEndSectionChange(endSection: Int) { _uiState.update { it.copy(endSection = endSection) } }
    fun onWeeksChange(newWeeks: Set<Int>) { _uiState.update { it.copy(weeks = newWeeks) } }
    fun onColorChange(colorIndex: Int) { _uiState.update { it.copy(colorIndex = colorIndex) } }
    fun onIsCustomTimeChange(isCustom: Boolean) { _uiState.update { it.copy(isCustomTime = isCustom) } }

    fun onCustomTimeChange(startTime: String, endTime: String) {
        _uiState.update {
            it.copy(
                customStartTime = startTime,
                customEndTime = endTime
            )
        }
    }

    fun onSave() {
        viewModelScope.launch {
            val state = uiState.value
            val courseToSave = state.course?.copy(
                name = state.name,
                teacher = state.teacher,
                position = state.position,
                day = state.day,
                startSection = state.startSection.takeUnless { state.isCustomTime },
                endSection = state.endSection.takeUnless { state.isCustomTime },
                isCustomTime = state.isCustomTime,
                customStartTime = state.customStartTime.takeIf { state.isCustomTime && it.isNotEmpty() },
                customEndTime = state.customEndTime.takeIf { state.isCustomTime && it.isNotEmpty() },
                colorInt = state.colorIndex,
                courseTableId = state.currentCourseTableId.orEmpty()
            )
            if (courseToSave != null) {
                courseTableRepository.upsertCourse(courseToSave, state.weeks.toList())
                _uiEvent.send(UiEvent.SaveSuccess)
            }
        }
    }

    fun onDelete() {
        viewModelScope.launch {
            uiState.value.course?.let { course ->
                courseTableRepository.deleteCourse(course)
                _uiEvent.send(UiEvent.DeleteSuccess)
            }
        }
    }

    fun onCancel() {
        viewModelScope.launch {
            _uiEvent.send(UiEvent.Cancel)
        }
    }

    companion object {
        fun Factory(courseId: String?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    if (modelClass.isAssignableFrom(AddEditCourseViewModel::class.java)) {
                        val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as MyApplication
                        return AddEditCourseViewModel(
                            courseTableRepository = application.courseTableRepository,
                            timeSlotRepository = application.timeSlotRepository,
                            appSettingsRepository = application.appSettingsRepository,
                            styleSettingsRepository = application.styleSettingsRepository,
                            courseId = courseId,
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
    }
}

sealed interface UiEvent {
    object SaveSuccess : UiEvent
    object DeleteSuccess : UiEvent
    object Cancel : UiEvent
}

data class AddEditCourseUiState(
    val isEditing: Boolean = false,
    val course: Course? = null,
    val name: String = "",
    val teacher: String = "",
    val position: String = "",
    val day: Int = 1,
    val startSection: Int = 1,
    val endSection: Int = 2,
    val isCustomTime: Boolean = false,
    val customStartTime: String = "",
    val customEndTime: String = "",
    val colorIndex: Int = 0,
    val weeks: Set<Int> = emptySet(),
    val timeSlots: List<TimeSlot> = emptyList(),
    val currentCourseTableId: String? = null,
    val semesterTotalWeeks: Int = 20,
    val courseColorMaps: List<DualColor> = emptyList()
)
