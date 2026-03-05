package com.shiro.classflow.data.repository

import kotlinx.serialization.Serializable


object CourseImportExport {



    // 用于 JSON 导入和导出的配置模型
    @Serializable
    data class CourseConfigJsonModel(
        val semesterStartDate: String? = null,
        val semesterTotalWeeks: Int = 20,
        val defaultClassDuration: Int = 45,
        val defaultBreakDuration: Int = 10,
        val firstDayOfWeek: Int = 1
    )

    // 导入时使用的 JSON 模型
    @Serializable
    data class CourseTableImportModel(
        val courses: List<ImportCourseJsonModel>,
        val timeSlots: List<TimeSlotJsonModel>,
        val config: CourseConfigJsonModel? = null
    )

    @Serializable
    data class ImportCourseJsonModel(
        val id: String? = null,
        val name: String,
        val teacher: String,
        val position: String,
        val day: Int,
        val startSection: Int? = null,
        val endSection: Int? = null,
        val weeks: List<Int>,
        val isCustomTime: Boolean = false,
        val customStartTime: String? = null,
        val customEndTime: String? = null,
        val color: Int? = null
    )

    // 导出时使用的 JSON 模型
    @Serializable
    data class CourseTableExportModel(
        val courses: List<ExportCourseJsonModel>,
        val timeSlots: List<TimeSlotJsonModel>,
        val config: CourseConfigJsonModel
    )

    @Serializable
    data class ExportCourseJsonModel(
        val id: String, // 导出时id必须
        val name: String,
        val teacher: String,
        val position: String,
        val day: Int,
        val startSection: Int? = null,
        val endSection: Int? = null,
        val color: Int, // 导出时颜色必须
        val weeks: List<Int>,
        val isCustomTime: Boolean = false,
        val customStartTime: String? = null,
        val customEndTime: String? = null
    )

    // 导入和导出都通用的时间段模型
    @Serializable
    data class TimeSlotJsonModel(
        val number: Int,
        val startTime: String,
        val endTime: String
    )

}
