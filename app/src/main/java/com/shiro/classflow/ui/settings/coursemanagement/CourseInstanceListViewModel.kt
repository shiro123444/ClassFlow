package com.shiro.classflow.ui.settings.coursemanagement

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.shiro.classflow.MyApplication
import com.shiro.classflow.data.db.main.CourseWithWeeks
import com.shiro.classflow.data.repository.AppSettingsRepository
import com.shiro.classflow.data.repository.CourseTableRepository
import com.shiro.classflow.data.repository.StyleSettingsRepository
import com.shiro.classflow.data.model.DualColor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// 导航参数的 Key
const val COURSE_NAME_ARG = "courseName"

class CourseInstanceListViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val appSettingsRepository: AppSettingsRepository,
    private val courseTableRepository: CourseTableRepository,
    private val styleSettingsRepository: StyleSettingsRepository
) : ViewModel() {

    private val selectedCourseName: String = savedStateHandle[COURSE_NAME_ARG] ?: ""

    private val currentTableIdFlow = appSettingsRepository.getAppSettings()
        .map { it.currentCourseTableId ?: "" }

    /**
     * 课程实例列表流
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val courseInstances: StateFlow<List<CourseWithWeeks>> = currentTableIdFlow
        .flatMapLatest { tableId ->
            if (tableId.isEmpty() || selectedCourseName.isEmpty()) {
                flowOf(emptyList())
            } else {
                courseTableRepository.getCoursesWithWeeksByTableId(tableId)
            }
        }
        .map { allCourses ->
            allCourses.filter { it.course.name == selectedCourseName }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _isSelectionMode = MutableStateFlow(false)
    private val _selectedCourseIds = MutableStateFlow(emptySet<String>())

    /**
     * 核心修改：封装 UI 状态流，包含动态颜色池
     */
    val uiState: StateFlow<CourseInstanceUiState> = combine(
        _isSelectionMode,
        _selectedCourseIds,
        styleSettingsRepository.styleFlow
    ) { isSelection, selectedIds, currentStyle ->
        CourseInstanceUiState(
            isSelectionMode = isSelection,
            selectedCourseIds = selectedIds,
            courseColorMaps = currentStyle.courseColorMaps
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CourseInstanceUiState()
    )

    // 常用操作函数保持不变
    fun toggleSelectionMode() {
        _isSelectionMode.update { !it }
        if (!_isSelectionMode.value) _selectedCourseIds.value = emptySet()
    }

    fun toggleCourseSelection(courseId: String) {
        _selectedCourseIds.update { currentIds ->
            if (currentIds.contains(courseId)) currentIds - courseId else currentIds + courseId
        }
        if (_selectedCourseIds.value.isNotEmpty() && !_isSelectionMode.value) {
            _isSelectionMode.value = true
        }
    }

    fun toggleSelectAll() {
        val allIds = courseInstances.value.map { it.course.id }.toSet()
        if (_selectedCourseIds.value.size == allIds.size && allIds.isNotEmpty()) {
            _selectedCourseIds.value = emptySet()
        } else {
            _selectedCourseIds.value = allIds
            _isSelectionMode.value = true
        }
    }

    fun deleteSelectedCourses() {
        val idsToDelete = _selectedCourseIds.value.toList()
        if (idsToDelete.isNotEmpty()) {
            viewModelScope.launch {
                courseTableRepository.deleteCoursesByIds(idsToDelete)
                _selectedCourseIds.value = emptySet()
                _isSelectionMode.value = false
            }
        }
    }

    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()
    val selectedCourseIds: StateFlow<Set<String>> = _selectedCourseIds.asStateFlow()

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as MyApplication
                if (modelClass.isAssignableFrom(CourseInstanceListViewModel::class.java)) {
                    val savedStateHandle = extras.createSavedStateHandle()
                    return CourseInstanceListViewModel(
                        savedStateHandle,
                        application.appSettingsRepository,
                        application.courseTableRepository,
                        application.styleSettingsRepository
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }
}

/**
 * UI 状态包装类
 */
data class CourseInstanceUiState(
    val isSelectionMode: Boolean = false,
    val selectedCourseIds: Set<String> = emptySet(),
    val courseColorMaps: List<DualColor> = emptyList()
)
