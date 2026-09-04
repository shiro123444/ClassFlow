package com.xingheyuzhuan.shiguangschedule.ui.settings.coursetables

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTable
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWithWeeks
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseTableRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 负责管理课表管理界面的所有状态和业务逻辑。
 */
@HiltViewModel
class ManageCourseTablesViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val courseTableRepository: CourseTableRepository
) : ViewModel() {

    // 组合两个数据流，提供一个包含课表列表和当前选中ID的单一UI状态
    val uiState: StateFlow<ManageCourseTablesUiState> = combine(
        courseTableRepository.getAllCourseTables(),
        appSettingsRepository.getAppSettings()
    ) { courseTables, appSettings ->
        ManageCourseTablesUiState(
            courseTables = courseTables,
            currentActiveTableId = appSettings.currentCourseTableId
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ManageCourseTablesUiState()
    )

    /**
     * 创建一个新的课表。
     * @param newTableName 新课表的名称。
     */
    fun createNewCourseTable(newTableName: String) {
        viewModelScope.launch {
            courseTableRepository.createNewCourseTable(newTableName)
        }
    }

    /**
     * 更新一个课表。
     * @param updatedCourseTable 包含新信息的课表对象。
     */
    fun updateCourseTable(updatedCourseTable: CourseTable) {
        viewModelScope.launch {
            courseTableRepository.updateCourseTable(updatedCourseTable)
        }
    }

    suspend fun createAndSwitchTable(
        name: String,
        studentId: String? = null,
        semesterCode: String? = null
    ): CourseTable {
        val newTable = courseTableRepository.createNewCourseTable(
            name = name,
            studentId = studentId,
            semesterCode = semesterCode
        )
        switchCourseTable(newTable.id)
        return newTable
    }

    suspend fun importCourses(courses: List<CourseWithWeeks>, targetTableId: String) {
        val existingCourses = courseTableRepository.getCoursesWithWeeksByTableId(targetTableId).firstOrNull() ?: emptyList()
        if (existingCourses.isNotEmpty()) {
            val idsToDelete = existingCourses.map { it.course.id }
            courseTableRepository.deleteCoursesByIds(idsToDelete)
        }
        courses.forEach { courseWithWeeks ->
            val courseToInsert = courseWithWeeks.course.copy(courseTableId = targetTableId)
            val weeks = courseWithWeeks.weeks.map { it.weekNumber }
            courseTableRepository.upsertCourse(courseToInsert, weeks)
        }
    }

    suspend fun applySemesterConfig(
        config: com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSemesterConfig?,
        targetTableId: String
    ) {
        if (config == null) return
        val current = appSettingsRepository.getCourseConfigOnce(targetTableId)
        val updated = (current ?: com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfig(courseTableId = targetTableId)).copy(
            semesterStartDate = config.semesterStartDate ?: current?.semesterStartDate,
            semesterTotalWeeks = config.semesterTotalWeeks
        )
        appSettingsRepository.insertOrUpdateCourseConfig(updated)
    }

    /**
     * 切换当前激活的课表。
     * @param tableId 要切换到的课表ID。
     */
    fun switchCourseTable(tableId: String) {
        viewModelScope.launch {
            val currentSettings = appSettingsRepository.getAppSettings().first()
            val newSettings = currentSettings.copy(currentCourseTableId = tableId)
            appSettingsRepository.insertOrUpdateAppSettings(newSettings)
        }
    }

    /**
     * 删除一个课表。
     * @param courseTable 要删除的课表对象。
     */
    fun deleteCourseTable(courseTable: CourseTable) {
        viewModelScope.launch {
            val success = courseTableRepository.deleteCourseTable(courseTable)
            if (success) {
                // 如果删除的是当前激活的课表，自动切换到列表中的第一个课表
                if (courseTable.id == uiState.value.currentActiveTableId) {
                    val remainingTables = courseTableRepository.getAllCourseTables().first()
                    if (remainingTables.isNotEmpty()) {
                        switchCourseTable(remainingTables.first().id)
                    }
                }
            }
        }
    }

    /**
     * 伴生对象，用于创建 ViewModel 实例。
     */
}

/**
 * 封装 UI 状态的数据类，减少 UI 层的复杂性。
 */
data class ManageCourseTablesUiState(
    val courseTables: List<CourseTable> = emptyList(),
    val currentActiveTableId: String? = null
)
