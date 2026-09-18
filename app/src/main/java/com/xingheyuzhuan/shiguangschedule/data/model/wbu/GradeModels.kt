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
    "16" to "微专业必修",
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
 * 课程类型字典映射表
 */
val KCLX_DICT: Map<String, String> = mapOf(
    "13" to "本校本科生课",
    "15" to "普通",
    "16" to "通选课 鹤鸣工程",
    "17" to "通选课 四史",
    "18" to "通选课 鹤鸣工程+四史",
    "19" to "通选课 网络课程",
    "99" to "其它"
)

/**
 * 课程归属字典映射表
 */
val KCGS_DICT: Map<String, String> = mapOf(
    "01" to "普通课",
    "02" to "实验课",
    "04" to "毕业设计",
    "05" to "课程设计",
    "06" to "实习",
    "07" to "毕业实习",
    "08" to "素质课",
    "2" to "体育课",
    "09" to "其它"
)

/**
 * 考试形式字典映射表
 */
val KSXS_DICT: Map<String, String> = mapOf(
    "1" to "考试",
    "2" to "考查",
    "3" to "考查",
    "4" to "不考试"
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
