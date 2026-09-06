package com.xingheyuzhuan.shiguangschedule.ui.campus.grade

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CourseGrade
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.GradeStats
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuQueryClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSessionExpiredException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GradeUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val needLogin: Boolean = false,
    val semesterOptions: List<String> = emptyList(),
    val selectedSemester: String = "", // "" 表示全部学期
    val searchKeyword: String = "",
    val filterPass: String = "", // "" 全部，"1" 及格，"0" 不及格
    val filterKcxz: String = "", // "" 全部性质
    val allGrades: List<CourseGrade> = emptyList(),
    val filteredGrades: List<CourseGrade> = emptyList(),
    val stats: GradeStats = GradeStats()
)

@HiltViewModel
class GradeQueryViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(GradeUiState())
    val uiState: StateFlow<GradeUiState> = _uiState.asStateFlow()

    private val queryClient get() = WbuQueryClient(context)

    init {
        loadGrades()
    }

    fun loadGrades() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, needLogin = false) }

            // 1. 获取学期列表（如尚未加载）
            if (_uiState.value.semesterOptions.isEmpty()) {
                val semesters = queryClient.fetchSemesterList()
                if (semesters.isNotEmpty()) {
                    _uiState.update { it.copy(semesterOptions = semesters) }
                }
            }

            // 2. 查询成绩
            val currentSemester = _uiState.value.selectedSemester
            val startXnxq = if (currentSemester.isNotBlank()) currentSemester else "2018-2019-1"
            val endXnxq = if (currentSemester.isNotBlank()) currentSemester else "2032-2033-2"

            val result = queryClient.queryGrades(
                startXnxq = startXnxq,
                endXnxq = endXnxq,
                pageSize = 300
            )

            result.fold(
                onSuccess = { queryResult ->
                    // 若此前没拿到学期列表，可以从成绩项中动态聚合历史学期
                    val semesters = if (_uiState.value.semesterOptions.isEmpty()) {
                        queryResult.courses.map { it.xnxq }.distinct().filter { it.isNotBlank() }
                    } else {
                        _uiState.value.semesterOptions
                    }

                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            allGrades = queryResult.courses,
                            semesterOptions = semesters,
                            errorMessage = null
                        )
                    }
                    applyFilters()
                },
                onFailure = { err ->
                    val isSessionExpired = err is WbuSessionExpiredException
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            needLogin = isSessionExpired,
                            errorMessage = err.message ?: "获取成绩失败"
                        )
                    }
                }
            )
        }
    }

    fun setSemester(semester: String) {
        if (_uiState.value.selectedSemester == semester) return
        _uiState.update { it.copy(selectedSemester = semester) }
        loadGrades()
    }

    fun setSearchKeyword(keyword: String) {
        _uiState.update { it.copy(searchKeyword = keyword) }
        applyFilters()
    }

    fun setFilterPass(pass: String) {
        val next = if (_uiState.value.filterPass == pass) "" else pass
        _uiState.update { it.copy(filterPass = next) }
        applyFilters()
    }

    fun setFilterKcxz(kcxz: String) {
        val next = if (_uiState.value.filterKcxz == kcxz) "" else kcxz
        _uiState.update { it.copy(filterKcxz = next) }
        applyFilters()
    }

    fun onLoginDismissed() {
        _uiState.update { it.copy(needLogin = false) }
    }

    fun onLoginSuccess() {
        _uiState.update { it.copy(needLogin = false) }
        loadGrades()
    }

    private fun applyFilters() {
        val state = _uiState.value
        val filtered = state.allGrades.filter { item ->
            val matchKw = state.searchKeyword.isBlank() ||
                item.courseName.contains(state.searchKeyword, ignoreCase = true) ||
                item.teacher.contains(state.searchKeyword, ignoreCase = true)

            val matchPass = when (state.filterPass) {
                "1" -> item.isPassed
                "0" -> !item.isPassed
                else -> true
            }

            val matchKcxz = state.filterKcxz.isBlank() || item.propertyCode == state.filterKcxz

            matchKw && matchPass && matchKcxz
        }

        // 重新计算筛选后的统计指标
        var totalXf = 0.0
        var totalPassedXf = 0.0
        var sumScoreXf = 0.0
        var sumWeightedScore = 0.0
        var sumXfjd = 0.0
        var passedCount = 0
        var failedCount = 0

        filtered.forEach { item ->
            totalXf += item.credit
            totalPassedXf += item.earnedCredit
            sumXfjd += item.gradePoint
            if (item.isPassed) passedCount++ else failedCount++

            val numericScore = item.score.toDoubleOrNull()
            if (numericScore != null && item.credit > 0) {
                sumWeightedScore += numericScore * item.credit
                sumScoreXf += item.credit
            }
        }

        val weightedGpa = if (totalXf > 0) Math.round((sumXfjd / totalXf) * 100.0) / 100.0 else 0.0
        val weightedScore = if (sumScoreXf > 0) Math.round((sumWeightedScore / sumScoreXf) * 100.0) / 100.0 else 0.0

        val newStats = GradeStats(
            totalCredits = Math.round(totalXf * 10.0) / 10.0,
            earnedCredits = Math.round(totalPassedXf * 10.0) / 10.0,
            weightedGpa = weightedGpa,
            weightedScore = weightedScore,
            courseCount = filtered.size,
            passedCount = passedCount,
            failedCount = failedCount
        )

        _uiState.update { it.copy(filteredGrades = filtered, stats = newStats) }
    }
}
