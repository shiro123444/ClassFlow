package com.xingheyuzhuan.shiguangschedule.data.model.wbu

/**
 * 课程性质字典映射表
 */
val KCXZ_DICT: Map<String, String> = mapOf(
    "08" to "专业必修",
    "01" to "专业选修",
    "02" to "专业限选",
    "09" to "专业任选",
    "14" to "学科必修",
    "05" to "公共必修",
    "15" to "通识必修",
    "13" to "通识选修",
    "12" to "综合必修",
    "11" to "综合素质",
    "04" to "实践教学",
    "99" to "公共选修"
)

/**
 * 考核方式字典
 */
val KHFS_DICT: Map<String, String> = mapOf(
    "1" to "考试",
    "3" to "考查"
)

/**
 * 单门课程成绩实体
 */
data class CourseGrade(
    val id: String,
    val xnxq: String,
    val kcbh: String,
    val courseName: String,
    val credit: Double,
    val earnedCredit: Double,
    val score: String,
    val gradePoint: Double,
    val propertyCode: String,
    val propertyName: String,
    val teacher: String,
    val examMethod: String,
    val studyNature: String,
    val isMakeup: Boolean,
    val isPassed: Boolean
)

/**
 * 成绩综合统计数据（加权 GPA、加权均分、总学分等）
 */
data class GradeStats(
    val totalCredits: Double = 0.0,
    val earnedCredits: Double = 0.0,
    val weightedGpa: Double = 0.0,
    val weightedScore: Double = 0.0,
    val courseCount: Int = 0,
    val passedCount: Int = 0,
    val failedCount: Int = 0
)

/**
 * 成绩查询结果包装
 */
data class GradeQueryResult(
    val ret: Int,
    val msg: String,
    val total: Int,
    val courses: List<CourseGrade>,
    val stats: GradeStats
)
