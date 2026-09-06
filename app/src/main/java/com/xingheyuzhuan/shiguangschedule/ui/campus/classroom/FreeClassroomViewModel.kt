package com.xingheyuzhuan.shiguangschedule.ui.campus.classroom

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.BuildingOption
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CAMPUS_HGH_UUID
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CAMPUS_MYH_ID
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CampusOption
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.ClassroomWeeklySchedule
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.DEFAULT_BUILDINGS_ALL
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.DEFAULT_BUILDINGS_HGH
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.DEFAULT_BUILDINGS_MYH
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.FreeClassroom
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuQueryClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSessionExpiredException
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

enum class FreeClassroomQueryMode {
    SECTION, // 按节次
    TIME     // 按时间段
}

data class FreeClassroomUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val needLogin: Boolean = false,
    val campusList: List<CampusOption> = emptyList(),
    val selectedCampusId: String = CAMPUS_HGH_UUID,
    val buildingList: List<BuildingOption> = DEFAULT_BUILDINGS_HGH,
    val selectedBuildingCode: String = "",

    // 查询时间模式：按节次 vs 按时段
    val queryMode: FreeClassroomQueryMode = FreeClassroomQueryMode.SECTION,
    val beginTime: String = "08:00",
    val endTime: String = "11:40",

    // 周次多选集合，空集合表示不筛选周次
    val selectedWeeks: Set<Int> = setOf(1),
    val currentWeekNumber: Int? = null,
    val totalWeeks: Int = 25,

    // 当仅选定单一周次时，周一至周日映射的日期字符串，如 1 -> "11.20"
    val singleWeekDates: Map<Int, String>? = null,

    // 星期多选集合，空集合表示不筛选星期（与全选效果一致）
    val selectedDays: Set<Int> = setOf(1),

    // 节次多选集合，1..13 节，空集合表示不筛选节次
    val selectedSections: Set<Int> = setOf(1, 2),

    val selectedRoomType: String = "", // 教室类型代码，空为全部
    val searchRoomName: String = "",
    val classrooms: List<FreeClassroom> = emptyList(),
    val totalCount: Int = 0,

    // 单个教室详情及课表探测状态
    val detailClassroom: FreeClassroom? = null,
    val detailWeek: Int = 1, // 抽屉内当前探测/展示的周次
    val isProbing: Boolean = false,
    val probeProgress: Pair<Int, Int>? = null, // current to total (e.g. 18 to 35)
    val probeScheduleCache: Map<String, ClassroomWeeklySchedule> = emptyMap(),
    val probeError: String? = null
)

@HiltViewModel
class FreeClassroomViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettingsRepository: AppSettingsRepository
) : ViewModel() {

    private val prefs by lazy { context.getSharedPreferences("wbu_campus_query", Context.MODE_PRIVATE) }
    private val KEY_LAST_CAMPUS_ID = "last_selected_campus_id"

    // 会话级状态：退出当前界面（ViewModel 销毁）前，如果用户确认探测过课表，后续点开教室或切换周次直接自动探测不再询问
    private var autoProbeConfirmed: Boolean = false

    // 缓存从网络或者本地计算过的单周日期：week -> Map<day, "MM.dd">
    private val weekDateCache = mutableMapOf<Int, Map<Int, String>>()

    private val _uiState = MutableStateFlow(
        FreeClassroomUiState(
            selectedCampusId = prefs.getString(KEY_LAST_CAMPUS_ID, CAMPUS_HGH_UUID) ?: CAMPUS_HGH_UUID,
            selectedDays = run {
                val day = LocalDate.now().dayOfWeek.value.coerceIn(1, 7)
                setOf(day)
            }
        )
    )
    val uiState: StateFlow<FreeClassroomUiState> = _uiState.asStateFlow()

    private val queryClient get() = WbuQueryClient(context)

    init {
        initData()
    }

    private fun initData() {
        viewModelScope.launch {
            // 1. 读取当前教学周
            runCatching {
                val currentWeek = appSettingsRepository.calculateCurrentWeekFromDb().firstOrNull()
                if (currentWeek != null && currentWeek > 0) {
                    val safeWeek = currentWeek.coerceIn(1, 25)
                    _uiState.update {
                        it.copy(
                            selectedWeeks = setOf(safeWeek),
                            currentWeekNumber = safeWeek,
                            detailWeek = safeWeek
                        )
                    }
                }
            }

            // 更新单周日期
            updateSingleWeekDatesForSelected()

            // 2. 拉取校区列表
            val campuses = queryClient.fetchCampusList()
            _uiState.update { it.copy(campusList = campuses) }
            updateBuildingListForCampus(_uiState.value.selectedCampusId)
            loadFreeClassrooms()
        }
    }

    fun loadFreeClassrooms() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, needLogin = false) }

            val state = _uiState.value
            val weeksStr = state.selectedWeeks.sorted().joinToString(",")
            val daysStr = state.selectedDays.sorted().joinToString(",")
            val sectionsStr = state.selectedSections.sorted().joinToString(",")

            val result = queryClient.queryFreeClassrooms(
                campusId = state.selectedCampusId,
                buildingCode = state.selectedBuildingCode,
                roomType = state.selectedRoomType,
                roomName = state.searchRoomName,
                week = if (state.selectedWeeks.size == 1) state.selectedWeeks.first() else null,
                dayOfWeek = daysStr,
                sections = sectionsStr,
                queryType = if (state.queryMode == FreeClassroomQueryMode.SECTION) "1" else "2",
                beginTime = state.beginTime,
                endTime = state.endTime,
                pageSize = 100
            )

            result.fold(
                onSuccess = { res ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            classrooms = res.classrooms,
                            totalCount = res.total,
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    val isSessionExpired = err is WbuSessionExpiredException
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            needLogin = isSessionExpired,
                            errorMessage = err.message ?: "查询空教室失败"
                        )
                    }
                }
            )
        }
    }

    /**
     * 记住上次浏览的 Tab（校区）
     */
    fun setCampus(campusId: String) {
        if (_uiState.value.selectedCampusId == campusId) return
        prefs.edit().putString(KEY_LAST_CAMPUS_ID, campusId).apply()
        _uiState.update { it.copy(selectedCampusId = campusId, selectedBuildingCode = "") }
        updateBuildingListForCampus(campusId)
        loadFreeClassrooms()
    }

    private fun updateBuildingListForCampus(campusId: String) {
        val list = when (campusId) {
            CAMPUS_HGH_UUID, "1", "HGH" -> DEFAULT_BUILDINGS_HGH
            CAMPUS_MYH_ID, "MYH" -> DEFAULT_BUILDINGS_MYH
            else -> DEFAULT_BUILDINGS_ALL
        }
        _uiState.update { it.copy(buildingList = list) }
    }

    fun setBuilding(code: String) {
        if (_uiState.value.selectedBuildingCode == code) return
        _uiState.update { it.copy(selectedBuildingCode = code) }
        loadFreeClassrooms()
    }

    /**
     * 周次选择：支持多选与取消勾选。全部取消时表示不筛选周次。
     */
    fun toggleWeek(week: Int) {
        val current = _uiState.value.selectedWeeks
        val updated = if (current.contains(week)) {
            current - week
        } else {
            current + week
        }
        _uiState.update { it.copy(selectedWeeks = updated) }
        updateSingleWeekDatesForSelected()
        loadFreeClassrooms()
    }

    fun selectAllWeeks() {
        val all = (1.._uiState.value.totalWeeks).toSet()
        if (_uiState.value.selectedWeeks == all) return
        _uiState.update { it.copy(selectedWeeks = all) }
        updateSingleWeekDatesForSelected()
        loadFreeClassrooms()
    }

    fun clearWeeks() {
        if (_uiState.value.selectedWeeks.isEmpty()) return
        _uiState.update { it.copy(selectedWeeks = emptySet()) }
        updateSingleWeekDatesForSelected()
        loadFreeClassrooms()
    }

    fun setQueryMode(mode: FreeClassroomQueryMode) {
        if (_uiState.value.queryMode == mode) return
        _uiState.update { it.copy(queryMode = mode) }
        loadFreeClassrooms()
    }

    fun setTimeRange(begin: String, end: String) {
        _uiState.update { it.copy(beginTime = begin, endTime = end) }
        loadFreeClassrooms()
    }

    /**
     * 节次手动多选：点击某节切换选中状态；允许全部取消勾选（空集合表示不筛选节次）
     */
    fun toggleSection(section: Int) {
        val current = _uiState.value.selectedSections
        val updated = if (current.contains(section)) {
            current - section
        } else {
            current + section
        }
        _uiState.update { it.copy(selectedSections = updated) }
        loadFreeClassrooms()
    }

    fun selectAllSections() {
        val all = (1..13).toSet()
        if (_uiState.value.selectedSections == all) return
        _uiState.update { it.copy(selectedSections = all) }
        loadFreeClassrooms()
    }

    fun clearSections() {
        if (_uiState.value.selectedSections.isEmpty()) return
        _uiState.update { it.copy(selectedSections = emptySet()) }
        loadFreeClassrooms()
    }

    /**
     * 星期多选：点击某天切换选中状态；允许全选和不选（空集合表示不限）
     */
    fun toggleDayOfWeek(day: Int) {
        val current = _uiState.value.selectedDays
        val updated = if (current.contains(day)) {
            current - day
        } else {
            current + day
        }
        _uiState.update { it.copy(selectedDays = updated) }
        loadFreeClassrooms()
    }

    fun selectAllDays() {
        val all = (1..7).toSet()
        if (_uiState.value.selectedDays == all) return
        _uiState.update { it.copy(selectedDays = all) }
        loadFreeClassrooms()
    }

    fun clearDays() {
        if (_uiState.value.selectedDays.isEmpty()) return
        _uiState.update { it.copy(selectedDays = emptySet()) }
        loadFreeClassrooms()
    }

    fun setDaysPreset(days: Set<Int>) {
        if (_uiState.value.selectedDays == days) return
        _uiState.update { it.copy(selectedDays = days) }
        loadFreeClassrooms()
    }

    fun setRoomType(type: String) {
        if (_uiState.value.selectedRoomType == type) return
        _uiState.update { it.copy(selectedRoomType = type) }
        loadFreeClassrooms()
    }

    fun setSearchRoomName(name: String) {
        _uiState.update { it.copy(searchRoomName = name) }
    }

    /**
     * 只有当单选一个周时，才去计算或获取该周周一至周日的具体阳历日期（格式 MM.dd，如 11.20）
     */
    private fun updateSingleWeekDatesForSelected() {
        val weeks = _uiState.value.selectedWeeks
        if (weeks.size != 1) {
            _uiState.update { it.copy(singleWeekDates = null) }
            return
        }

        val singleWeek = weeks.first()

        // 1. 检查内存缓存
        weekDateCache[singleWeek]?.let { cached ->
            _uiState.update { it.copy(singleWeekDates = cached) }
            return
        }

        viewModelScope.launch {
            // 2. 优先利用本地课表配置的开学日期计算
            val datesFromLocal = runCatching {
                val appSettings = appSettingsRepository.getAppSettingsOnce()
                val tableId = appSettings.currentCourseTableId
                val config = if (tableId.isNotBlank()) appSettingsRepository.getCourseConfigOnce(tableId) else null
                val startDateStr = config?.semesterStartDate
                if (!startDateStr.isNullOrBlank()) {
                    val startDate = LocalDate.parse(startDateStr)
                    val week1Monday = startDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    val targetMonday = week1Monday.plusWeeks((singleWeek - 1).toLong())
                    val fmt = DateTimeFormatter.ofPattern("MM.dd")
                    (1..7).associateWith { d ->
                        targetMonday.plusDays((d - 1).toLong()).format(fmt)
                    }
                } else null
            }.getOrNull()

            if (datesFromLocal != null) {
                weekDateCache[singleWeek] = datesFromLocal
                if (_uiState.value.selectedWeeks == setOf(singleWeek)) {
                    _uiState.update { it.copy(singleWeekDates = datesFromLocal) }
                }
                return@launch
            }

            // 3. 本地无开学日期，回退调用教务 getXqrqxx 接口
            val datesFromApi = queryClient.fetchWeekDates(singleWeek)
            if (datesFromApi != null) {
                weekDateCache[singleWeek] = datesFromApi
                if (_uiState.value.selectedWeeks == setOf(singleWeek)) {
                    _uiState.update { it.copy(singleWeekDates = datesFromApi) }
                }
            }
        }
    }

    /**
     * 点击教室查看详情：
     * 如果在本次退出前已经确认探测过课表，直接自动启动探测，不再让用户二次确认！
     */
    fun selectClassroomForDetail(classroom: FreeClassroom?) {
        if (classroom == null) {
            _uiState.update {
                it.copy(
                    detailClassroom = null,
                    probeError = null,
                    probeProgress = null
                )
            }
            return
        }

        val initialDetailWeek = _uiState.value.selectedWeeks.firstOrNull() ?: _uiState.value.currentWeekNumber ?: 1
        val cacheKey = "${classroom.cleanRoomName}_week_$initialDetailWeek"
        val hasCached = _uiState.value.probeScheduleCache.containsKey(cacheKey)

        _uiState.update {
            it.copy(
                detailClassroom = classroom,
                detailWeek = initialDetailWeek,
                probeError = null,
                probeProgress = if (autoProbeConfirmed && !hasCached) Pair(0, 35) else null,
                isProbing = autoProbeConfirmed && !hasCached
            )
        }

        // 若之前确认过探测，且当前该周无缓存，则自动触发探测
        if (autoProbeConfirmed && !hasCached) {
            executeProbe(classroom, initialDetailWeek, forceRefresh = false)
        }
    }

    /**
     * 在详情抽屉中切换周次：
     * 如果在退出界面前已经确认探测过课表，切换周次时绝不再询问，直接自动探测！
     */
    fun setDetailWeek(week: Int) {
        if (_uiState.value.detailWeek == week) return
        val room = _uiState.value.detailClassroom ?: return
        val cacheKey = "${room.cleanRoomName}_week_$week"
        val hasCached = _uiState.value.probeScheduleCache.containsKey(cacheKey)

        _uiState.update {
            it.copy(
                detailWeek = week,
                probeError = null,
                isProbing = autoProbeConfirmed && !hasCached,
                probeProgress = if (autoProbeConfirmed && !hasCached) Pair(0, 35) else null
            )
        }

        if (autoProbeConfirmed && !hasCached) {
            executeProbe(room, week, forceRefresh = false)
        }
    }

    /**
     * 用户点开教室详情抽屉后，手动点击“探测本周课表”，或者重新探测
     */
    fun probeScheduleForCurrentRoom(forceRefresh: Boolean = false) {
        autoProbeConfirmed = true
        val room = _uiState.value.detailClassroom ?: return
        val week = _uiState.value.detailWeek
        executeProbe(room, week, forceRefresh)
    }

    private fun executeProbe(room: FreeClassroom, week: Int, forceRefresh: Boolean) {
        val cacheKey = "${room.cleanRoomName}_week_$week"

        if (!forceRefresh && _uiState.value.probeScheduleCache.containsKey(cacheKey)) {
            _uiState.update { it.copy(isProbing = false, probeProgress = null) }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isProbing = true,
                    probeError = null,
                    probeProgress = Pair(0, 35)
                )
            }

            val result = queryClient.probeClassroomWeeklySchedule(
                roomName = room.cleanRoomName,
                week = week,
                onProgress = { cur, tot ->
                    _uiState.update { it.copy(probeProgress = Pair(cur, tot)) }
                }
            )

            result.fold(
                onSuccess = { weeklySchedule ->
                    _uiState.update {
                        val newCache = it.probeScheduleCache.toMutableMap()
                        newCache[cacheKey] = weeklySchedule
                        it.copy(
                            isProbing = false,
                            probeScheduleCache = newCache,
                            probeError = null,
                            probeProgress = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isProbing = false,
                            probeError = err.message ?: "探测课表失败",
                            probeProgress = null
                        )
                    }
                }
            )
        }
    }

    fun onLoginDismissed() {
        _uiState.update { it.copy(needLogin = false) }
    }

    fun onLoginSuccess() {
        _uiState.update { it.copy(needLogin = false) }
        loadFreeClassrooms()
    }
}
