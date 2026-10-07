package com.xingheyuzhuan.shiguangschedule.ui.campus.grade

import com.xingheyuzhuan.shiguangschedule.R

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CourseGrade
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
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
import com.xingheyuzhuan.shiguangschedule.ui.components.accessFailureText
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessFailure
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.needsRelogin
import com.xingheyuzhuan.shiguangschedule.ui.components.CampusAccessResult
import com.xingheyuzhuan.shiguangschedule.ui.components.campusAccess
import com.xingheyuzhuan.shiguangschedule.ui.components.shouldOfferWebVpnOnce

data class GradeUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val needLogin: Boolean = false,
    /** 是否给用户一个「改用 WebVPN」按钮（单次生效，不翻转全局开关）。 */
    val offerWebVpnOnce: Boolean = false,
    val semesterOptions: List<String> = emptyList(),
    val selectedSemester: String = "", // "" 表示全部学期
    val searchKeyword: String = "",
    val filterPass: String = "", // "" 全部，"1" 及格，"0" 不及格
    val filterKcxz: String = "", // "" 全部性质
    val allGrades: List<CourseGrade> = emptyList(),
    val filteredGrades: List<CourseGrade> = emptyList()
)

@HiltViewModel
class GradeQueryViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(GradeUiState())
    val uiState: StateFlow<GradeUiState> = _uiState.asStateFlow()

    /** 单次改用 WebVPN：只对下一次访问生效，**不写全局开关**。 */
    private var useWebVpnOnce = false

    /** 最近一次实际使用的通道：用户点过「改用 WebVPN」后，后续请求沿用同一条通道。 */
    private var lastUseVpn: Boolean? = null

    private val queryClient
        get() = WbuQueryClient(
            context,
            useVpn = lastUseVpn ?: (WbuSyncEngine.getSavedUseVpn(context))
        )

    init {
        loadGrades()
    }

    fun loadGrades() {
        val once = useWebVpnOnce
        useWebVpnOnce = false
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, errorMessage = null, needLogin = false, offerWebVpnOnce = false)
            }

            // 统一流水线：本地无凭据 / 登录态失效时先静默重建（缺输入就地弹小窗），
            // 只有真的需要用户介入才回落到登录 Sheet；网络不通则给内联重试 + 改用 WebVPN。
            val access = campusAccess(
                context = context,
                service = CredentialService.JIAOWU,
                flowTag = "GRADE",
                useWebVpnOnce = once
            ) { useVpn ->
                val client = WbuQueryClient(context, useVpn = useVpn)

                // 1. 获取学期列表（如尚未加载）
                if (_uiState.value.semesterOptions.isEmpty()) {
                    val semesters = client.fetchSemesterList()
                    if (semesters.isNotEmpty()) {
                        _uiState.update { it.copy(semesterOptions = semesters) }
                    }
                }

                // 2. 查询成绩
                val currentSemester = _uiState.value.selectedSemester
                val startXnxq = if (currentSemester.isNotBlank()) currentSemester else "2018-2019-1"
                val endXnxq = if (currentSemester.isNotBlank()) currentSemester else "2032-2033-2"
                client.queryGrades(startXnxq = startXnxq, endXnxq = endXnxq, pageSize = 300).getOrThrow()
            }

            when (access) {
                is CampusAccessResult.Ok -> {
                    lastUseVpn = access.useVpn
                    val queryResult = access.value
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
                            errorMessage = null,
                            needLogin = false,
                            offerWebVpnOnce = false
                        )
                    }
                    applyFilters()
                }

                is CampusAccessResult.Failed -> {
                    lastUseVpn = access.useVpn
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            // 只有「会话失效 / 凭据被拒」才弹登录 Sheet；用户取消小窗安静回落
                            needLogin = access.failure.needsRelogin,
                            errorMessage = accessFailureText(context, access.failure),
                            offerWebVpnOnce = access.failure.shouldOfferWebVpnOnce(access.useVpn)
                        )
                    }
                }
            }
        }
    }

    /** 「改用 WebVPN」按钮：本次访问改走校外通道（单次生效，不翻转全局开关）。 */
    fun retryWithWebVpnOnce() {
        useWebVpnOnce = true
        loadGrades()
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

        _uiState.update { it.copy(filteredGrades = filtered) }
    }
}
