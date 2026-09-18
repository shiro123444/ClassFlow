package com.xingheyuzhuan.shiguangschedule.ui.campus.courseselection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.KkxFrom
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.RetakeCourse
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.SelectedCourse
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.SelectionBatch
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.TeachingClass
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.CourseSelectionDataSource
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.CourseSelectionPrefs
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.MockCourseSelectionDataSource
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuCourseSelectionClient
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSessionExpiredException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 教学班可执行操作
 */
enum class ClassAction {
    SELECT,         // 选课
    DROP,           // 退课
    ENROLL,         // 抽签报名
    CANCEL_ENROLL,  // 取消报名
    WAITLIST,       // 候补
    CANCEL_WAITLIST // 取消候补（详情页）
}

/**
 * 待二次确认的操作
 */
data class ConfirmRequest(
    val action: ClassAction,
    val teachingClass: TeachingClass,
    val retakeCourse: RetakeCourse? = null
)

/**
 * 子教学班选择请求
 */
data class ChildPickRequest(
    val teachingClass: TeachingClass,
    val childIds: List<String>
)

data class CourseSelectionUiState(
    val disclaimerAccepted: Boolean = false,
    val mockEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val studentName: String = "",
    val studentId: String = "",
    val xkxnxq: String = "",
    val hideQuota: Boolean = false,
    val batches: List<SelectionBatch> = emptyList(),
    val activeBatch: SelectionBatch? = null,
    val tabIndex: Int = 0, // 0 可选课程 / 1 已选课程
    val keyword: String = "",
    val filterFull: Boolean = false,
    val filterConflict: Boolean = false,
    val allClasses: List<TeachingClass> = emptyList(),
    val selectedCourses: List<SelectedCourse> = emptyList(),
    val isLoadingSelected: Boolean = false,
    val retakeCourses: List<RetakeCourse> = emptyList(),
    val activeRetakeCourse: RetakeCourse? = null,
    val opInFlight: Boolean = false,
    val opMessage: String? = null,
    val confirm: ConfirmRequest? = null,
    val childPick: ChildPickRequest? = null,
    val detailClass: TeachingClass? = null,
    val needLogin: Boolean = false,
    val errorMessage: String? = null
) {
    /** 客户端筛选后的教学班列表（listjxbDataV2 为全量返回） */
    val displayedClasses: List<TeachingClass>
        get() {
            val kw = keyword.trim().lowercase()
            return allClasses.filter { c ->
                val matchKw = kw.isEmpty() ||
                        c.kcmc.lowercase().contains(kw) ||
                        c.kcbh.lowercase().contains(kw) ||
                        c.teacher.lowercase().contains(kw) ||
                        c.jxbmc.lowercase().contains(kw)
                val matchFull = !filterFull || !c.isFull
                val matchConflict = !filterConflict || !c.hasConflict
                matchKw && matchFull && matchConflict
            }
        }
}

@HiltViewModel
class CourseSelectionViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    /** 真实数据源：强制校园网直连（客户端内部固定 useVpn=false） */
    private val realDataSource by lazy { WbuCourseSelectionClient(getApplication()) }

    /** Mock 数据源：单例持有，保证选/退课状态在本页生命周期内累积 */
    private val mockDataSource = MockCourseSelectionDataSource()

    private val dataSource: CourseSelectionDataSource
        get() = if (_uiState.value.mockEnabled) mockDataSource else realDataSource

    private val _uiState = MutableStateFlow(CourseSelectionUiState())
    val uiState: StateFlow<CourseSelectionUiState> = _uiState.asStateFlow()

    init {
        val accepted = CourseSelectionPrefs.isDisclaimerAccepted(getApplication())
        val mock = CourseSelectionPrefs.isMockEnabled(getApplication())
        _uiState.update { it.copy(disclaimerAccepted = accepted, mockEnabled = mock) }
        if (accepted) {
            loadInit(isRefresh = false)
        }
    }

    /**
     * 切换 Mock 测试模式（由页面的暗号手势触发），并清空运行时状态后重新加载
     */
    fun toggleMockMode() {
        val enabled = !_uiState.value.mockEnabled
        CourseSelectionPrefs.setMockEnabled(getApplication(), enabled)
        _uiState.update {
            it.copy(
                mockEnabled = enabled,
                batches = emptyList(),
                activeBatch = null,
                allClasses = emptyList(),
                retakeCourses = emptyList(),
                activeRetakeCourse = null,
                selectedCourses = emptyList(),
                errorMessage = null,
                needLogin = false,
                opMessage = getApplication<Application>().getString(
                    if (enabled) R.string.cs_mock_enabled else R.string.cs_mock_disabled
                )
            )
        }
        loadInit(isRefresh = false)
    }

    fun acceptDisclaimer() {
        CourseSelectionPrefs.markDisclaimerAccepted(getApplication())
        _uiState.update { it.copy(disclaimerAccepted = true) }
        if (_uiState.value.batches.isEmpty()) {
            loadInit(isRefresh = false)
        }
    }

    /**
     * 加载选课页初始化数据（批次 + 学生信息）
     */
    fun loadInit(isRefresh: Boolean = false) {
        // 本地无教务凭据时直接进入登录引导，不空跑请求（Mock 模式无需凭据）
        if (!_uiState.value.mockEnabled &&
            !WbuAuthTransport.hasLocalSession(getApplication(), CredentialService.JIAOWU, useVpn = false)
        ) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = null,
                    needLogin = true
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = !isRefresh, isRefreshing = isRefresh, errorMessage = null)
            }
            dataSource.queryInit()
                .onSuccess { init ->
                    val batches = init.batches
                    val active = batches.firstOrNull { it.pcid == _uiState.value.activeBatch?.pcid }
                        ?: batches.firstOrNull()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            studentName = init.studentName,
                            studentId = init.studentId,
                            xkxnxq = init.xkxnxq,
                            hideQuota = init.hideQuota,
                            batches = batches,
                            activeBatch = active,
                            errorMessage = null,
                            needLogin = false
                        )
                    }
                    active?.let { batch ->
                        if (batch.from == KkxFrom.CXXK) loadRetakeCourses(batch) else loadClasses(batch)
                    }
                }
                .onFailure { err -> handleFailure(err, "获取选课批次失败") }
        }
    }

    /**
     * 切换批次
     */
    fun selectBatch(batch: SelectionBatch) {
        if (!batch.isSupported) return
        _uiState.update {
            it.copy(
                activeBatch = batch,
                allClasses = emptyList(),
                retakeCourses = emptyList(),
                activeRetakeCourse = null,
                tabIndex = 0
            )
        }
        if (batch.from == KkxFrom.CXXK) loadRetakeCourses(batch) else loadClasses(batch)
    }

    fun setTabIndex(index: Int) {
        _uiState.update { it.copy(tabIndex = index) }
        if (index == 1 && _uiState.value.selectedCourses.isEmpty()) {
            loadSelectedCourses()
        }
    }

    fun setKeyword(keyword: String) {
        _uiState.update { it.copy(keyword = keyword) }
    }

    fun toggleFilterFull() {
        _uiState.update { it.copy(filterFull = !it.filterFull) }
    }

    fun toggleFilterConflict() {
        _uiState.update { it.copy(filterConflict = !it.filterConflict) }
    }

    private fun loadClasses(batch: SelectionBatch) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            dataSource.queryClasses(from = batch.from, pcid = batch.pcid, pcenc = batch.pcenc)
                .onSuccess { list ->
                    _uiState.update {
                        it.copy(isLoading = false, allClasses = list.sortedBy { c -> c.kcmc })
                    }
                }
                .onFailure { err -> handleFailure(err, "获取教学班失败") }
        }
    }

    fun loadSelectedCourses() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSelected = true) }
            dataSource.querySelectedCourses()
                .onSuccess { list ->
                    _uiState.update { it.copy(isLoadingSelected = false, selectedCourses = list) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isLoadingSelected = false) }
                    handleFailure(err, "获取已选课程失败")
                }
        }
    }

    private fun loadRetakeCourses(batch: SelectionBatch) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            dataSource.queryRetakeBind(batch.pcid)
                .onSuccess { list ->
                    _uiState.update { it.copy(isLoading = false, retakeCourses = list) }
                }
                .onFailure { err -> handleFailure(err, "获取重修课程失败") }
        }
    }

    fun pickRetakeCourse(course: RetakeCourse) {
        val batch = _uiState.value.activeBatch ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, activeRetakeCourse = course, allClasses = emptyList())
            }
            dataSource.queryRetakeClasses(cxmdid = course.cxmdid, kcid = course.kcid, pcid = batch.pcid)
                .onSuccess { list ->
                    _uiState.update { it.copy(isLoading = false, allClasses = list) }
                }
                .onFailure { err -> handleFailure(err, "获取重修教学班失败") }
        }
    }

    /** 返回重修课程列表 */
    fun clearRetakeCourse() {
        _uiState.update { it.copy(activeRetakeCourse = null, allClasses = emptyList()) }
    }

    /**
     * 点击教学班操作按钮 → 生成二次确认
     */
    fun requestClassAction(teachingClass: TeachingClass, action: ClassAction) {
        _uiState.update { it.copy(confirm = ConfirmRequest(action, teachingClass)) }
    }

    fun dismissConfirm() {
        _uiState.update { it.copy(confirm = null) }
    }

    fun dismissChildPick() {
        _uiState.update { it.copy(childPick = null) }
    }

    fun showDetail(teachingClass: TeachingClass) {
        _uiState.update { it.copy(detailClass = teachingClass) }
    }

    fun dismissDetail() {
        _uiState.update { it.copy(detailClass = null) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(opMessage = null) }
    }

    /**
     * 执行二次确认后的操作
     */
    fun confirmPending() {
        val request = _uiState.value.confirm ?: return
        _uiState.update { it.copy(confirm = null) }
        when (request.action) {
            ClassAction.SELECT, ClassAction.ENROLL -> prepareSelect(request)
            ClassAction.DROP, ClassAction.CANCEL_ENROLL -> doDrop(request.teachingClass)
            ClassAction.WAITLIST -> doSelect(request.teachingClass, childJxbid = "")
            ClassAction.CANCEL_WAITLIST -> doCancelWaitlist(request.teachingClass)
        }
    }

    /**
     * 选课前：计划选课需要先探测子教学班
     */
    private fun prepareSelect(request: ConfirmRequest) {
        val tc = request.teachingClass
        val batch = _uiState.value.activeBatch
        if (request.action == ClassAction.SELECT && batch != null && batch.from == KkxFrom.JHXK) {
            viewModelScope.launch {
                _uiState.update { it.copy(opInFlight = true) }
                val ids = dataSource.queryChildClasses(
                    jxbid = tc.jxbid,
                    pcid = batch.pcid,
                    pcenc = batch.pcenc,
                    from = batch.from.raw
                ).getOrDefault(emptyList())
                _uiState.update { it.copy(opInFlight = false) }
                if (ids.size > 1) {
                    _uiState.update { it.copy(childPick = ChildPickRequest(tc, ids)) }
                } else {
                    doSelect(tc, childJxbid = "")
                }
            }
        } else {
            doSelect(tc, childJxbid = "")
        }
    }

    fun selectChildClass(childJxbid: String) {
        val pick = _uiState.value.childPick ?: return
        _uiState.update { it.copy(childPick = null) }
        doSelect(pick.teachingClass, childJxbid = childJxbid)
    }

    private fun doSelect(teachingClass: TeachingClass, childJxbid: String, sfqc: Boolean = false) {
        val batch = _uiState.value.activeBatch ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(opInFlight = true) }
            dataSource.selectClass(
                jxbid = teachingClass.jxbid,
                pcid = batch.pcid,
                zjxbid = childJxbid,
                sfqc = sfqc
            )
                .onSuccess { outcome ->
                    _uiState.update { it.copy(opInFlight = false) }
                    if (outcome.needConfirm) {
                        // 抽签冲突：带 sfqc=1 重试
                        doSelect(teachingClass, childJxbid, sfqc = true)
                    } else if (outcome.success) {
                        _uiState.update { it.copy(opMessage = "选课成功") }
                        refreshAfterOperation()
                    } else {
                        _uiState.update { it.copy(opMessage = outcome.message.ifBlank { "选课失败" }) }
                    }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(opInFlight = false) }
                    handleFailure(err, "选课失败")
                }
        }
    }

    private fun doDrop(teachingClass: TeachingClass) {
        val batch = _uiState.value.activeBatch ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(opInFlight = true) }
            dataSource.dropClass(teachingClass.jxbid, batch.pcid)
                .onSuccess { msg ->
                    _uiState.update {
                        it.copy(opInFlight = false, opMessage = msg.ifBlank { "退课成功" })
                    }
                    refreshAfterOperation()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(opInFlight = false) }
                    handleFailure(err, "退课失败")
                }
        }
    }

    private fun doCancelWaitlist(teachingClass: TeachingClass) {
        val batch = _uiState.value.activeBatch ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(opInFlight = true) }
            dataSource.cancelWaitlist(teachingClass.jxbid, batch.pcid)
                .onSuccess { msg ->
                    _uiState.update {
                        it.copy(opInFlight = false, opMessage = msg.ifBlank { "已取消候补" })
                    }
                    refreshAfterOperation()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(opInFlight = false) }
                    handleFailure(err, "取消候补失败")
                }
        }
    }

    /**
     * 选/退课成功后刷新当前列表与已选课程
     */
    private fun refreshAfterOperation() {
        val batch = _uiState.value.activeBatch
        if (batch != null) {
            if (batch.from == KkxFrom.CXXK) {
                _uiState.value.activeRetakeCourse?.let { pickRetakeCourse(it) } ?: loadRetakeCourses(batch)
            } else {
                loadClasses(batch)
            }
        }
        loadSelectedCourses()
    }

    /**
     * 重修选课 / 退课
     */
    fun confirmRetake(tc: TeachingClass, course: RetakeCourse, drop: Boolean) {
        val batch = _uiState.value.activeBatch ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(opInFlight = true) }
            val result = if (drop) {
                dataSource.retakeDrop(
                    jxbid = tc.jxbid,
                    kcid = course.kcid,
                    cxmdid = course.cxmdid,
                    pcid = batch.pcid
                ).map { it.ifBlank { "重修退课成功" } }
            } else {
                dataSource.retakeSelect(
                    jxbid = tc.jxbid,
                    fjxbid = "",
                    kcid = course.kcid,
                    cxmdid = course.cxmdid,
                    pcid = batch.pcid
                ).map { it.message.ifBlank { "重修选课成功" } }
            }
            result
                .onSuccess { msg ->
                    _uiState.update { it.copy(opInFlight = false, opMessage = msg) }
                    refreshAfterOperation()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(opInFlight = false) }
                    handleFailure(err, if (drop) "重修退课失败" else "重修选课失败")
                }
        }
    }

    fun onLoginSuccess() {
        _uiState.update { it.copy(needLogin = false) }
        loadInit(isRefresh = true)
    }

    fun onLoginDismissed() {
        _uiState.update { it.copy(needLogin = false) }
    }

    private fun handleFailure(err: Throwable, fallback: String) {
        val expired = err is WbuSessionExpiredException
        _uiState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                errorMessage = err.message ?: fallback,
                opMessage = if (expired) null else (err.message ?: fallback),
                needLogin = expired
            )
        }
    }
}
