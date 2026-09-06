package com.xingheyuzhuan.shiguangschedule.ui.campus.academic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.AcademicCourseGroup
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.AcademicProgressData
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuQueryClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSessionExpiredException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 视图类型：按学期推进 vs 按课程性质分类
 */
enum class AcademicViewMode {
    SEMESTER, // 按学年学期推进 (fasz=3)
    NATURE    // 按课程性质归纳 (fasz=2)
}

/**
 * 页面 UI 状态
 */
data class AcademicProgressUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val data: AcademicProgressData? = null,
    val errorMessage: String? = null,
    val viewMode: AcademicViewMode = AcademicViewMode.SEMESTER,
    val searchKeyword: String = "",
    val filterStatus: String = "", // "" (全部), "已修", "修读中", "未修"
    val expandedGroupIds: Set<String> = emptySet(),
    val needLogin: Boolean = false
) {
    /**
     * 过滤后展示的课程组列表
     */
    val displayedGroups: List<AcademicCourseGroup>
        get() {
            val rawGroups = if (viewMode == AcademicViewMode.SEMESTER) {
                data?.semesterGroups.orEmpty()
            } else {
                data?.natureGroups.orEmpty()
            }

            if (searchKeyword.isBlank() && filterStatus.isBlank()) {
                return rawGroups
            }

            val kw = searchKeyword.trim().lowercase()

            return rawGroups.mapNotNull { group ->
                val filteredCourses = group.courses.filter { course ->
                    val matchesKw = kw.isEmpty() ||
                            course.courseName.lowercase().contains(kw) ||
                            course.courseCode.lowercase().contains(kw) ||
                            course.college.lowercase().contains(kw) ||
                            course.nature.lowercase().contains(kw)

                    val matchesStatus = when (filterStatus) {
                        "已修" -> course.isCompleted
                        "修读中" -> course.isStudying
                        "未修" -> course.isUncompleted
                        else -> true
                    }

                    matchesKw && matchesStatus
                }

                if (filteredCourses.isNotEmpty()) {
                    group.copy(courses = filteredCourses)
                } else null
            }
        }
}

@HiltViewModel
class AcademicProgressViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val queryClient get() = WbuQueryClient(getApplication())

    private val _uiState = MutableStateFlow(AcademicProgressUiState())
    val uiState: StateFlow<AcademicProgressUiState> = _uiState.asStateFlow()

    init {
        loadAcademicProgress(isRefresh = false)
    }

    /**
     * 加载或刷新学业完成度与课程进程
     */
    fun loadAcademicProgress(isRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = !isRefresh,
                    isRefreshing = isRefresh,
                    errorMessage = null
                )
            }

            val result = queryClient.queryAcademicProgress()
            result.onSuccess { progressData ->
                // 默认将全部组节点展开
                val allGroupIds = (progressData.semesterGroups.map { it.nodeId } +
                        progressData.natureGroups.map { it.nodeId }).toSet()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        data = progressData,
                        expandedGroupIds = if (it.expandedGroupIds.isEmpty()) allGroupIds else it.expandedGroupIds,
                        errorMessage = null,
                        needLogin = false
                    )
                }
            }.onFailure { err ->
                val isExpired = err is WbuSessionExpiredException
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = err.message ?: "获取学业进程失败",
                        needLogin = isExpired
                    )
                }
            }
        }
    }

    fun setViewMode(mode: AcademicViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun setSearchKeyword(kw: String) {
        _uiState.update { it.copy(searchKeyword = kw) }
    }

    fun setFilterStatus(status: String) {
        _uiState.update { it.copy(filterStatus = status) }
    }

    fun toggleGroup(groupId: String) {
        _uiState.update { current ->
            val set = current.expandedGroupIds.toMutableSet()
            if (set.contains(groupId)) {
                set.remove(groupId)
            } else {
                set.add(groupId)
            }
            current.copy(expandedGroupIds = set)
        }
    }

    fun expandAllGroups() {
        val data = _uiState.value.data ?: return
        val allIds = (data.semesterGroups.map { it.nodeId } + data.natureGroups.map { it.nodeId }).toSet()
        _uiState.update { it.copy(expandedGroupIds = allIds) }
    }

    fun collapseAllGroups() {
        _uiState.update { it.copy(expandedGroupIds = emptySet()) }
    }

    fun onLoginSuccess() {
        _uiState.update { it.copy(needLogin = false) }
        loadAcademicProgress(isRefresh = true)
    }

    fun onLoginDismissed() {
        _uiState.update { it.copy(needLogin = false) }
    }
}
