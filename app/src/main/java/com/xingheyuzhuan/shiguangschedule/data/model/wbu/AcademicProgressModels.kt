package com.xingheyuzhuan.shiguangschedule.data.model.wbu

/**
 * 学生学籍基本信息
 * 对应接口：/admin/xsd/xskp/xskp?fasz=2
 */
data class StudentProfile(
    val id: String = "",
    val studentId: String = "",       // xh
    val name: String = "",            // xm
    val gender: String = "",          // xb
    val ethnicity: String = "",       // mz
    val gradeYear: String = "",       // sznj (如 2025)
    val college: String = "",         // skyx (如 人工智能与大数据学院)
    val major: String = "",           // zymc (如 人工智能)
    val className: String = "",       // bjmc (如 25人工智能(本科)(2))
    val expectedGradDate: String = "" // yjbyrq (如 2029-06-30)
)

/**
 * 学业综合统计指标与官方排名
 * 对应接口：/admin/xsd/xskp/xyqk?fasz=2
 */
data class AcademicStats(
    val gpa: Double? = null,          // 官方综合 GPA
    val averageScore: Double? = null, // 加权平均成绩 (pjcj)
    val majorRank: String = "",       // GPA 专业排名 (gpazypm，如 "19/59")
    val earnedCredits: Double = 0.0,  // 累计获得总学分 (hdzxf)
    val failedCourseCount: Int = 0,   // 不及格门数 (bjgms)
    val retakeCourseCount: Int = 0,   // 重修门数 (cxmcs)
    val selectedCourseCount: Int = 0  // 已选修门数 (yxkms)
)

/**
 * 培养方案学分与课程完成度概览
 * 对应接口：/admin/xsd/xskp/xywcd?fasz=2 + 聚合统计
 */
data class AcademicProgressSummary(
    val completionPercentage: Double = 0.0, // 学分完成度百分比 (xfwcd，如 67.29)
    val totalPlanCourses: Int = 0,          // 培养方案总计划门数
    val totalCompletedCourses: Int = 0,     // 已修门数
    val totalStudyingCourses: Int = 0,      // 修读中/待录分门数
    val totalUncompletedCourses: Int = 0    // 未修门数
)

/**
 * 课程节点明细
 */
data class AcademicCourse(
    val courseCode: String = "",            // kcbh (课程编号)
    val courseName: String = "",            // kcmc (课程名称)
    val college: String = "",               // kkyx (开课学院)
    val planCredit: Double = 0.0,           // xf (额定/计划学分)
    val earnedCredit: Double = 0.0,         // hdxf (已获学分)
    val category: String = "",              // kclb (课程类别，如 集中性实践环节)
    val nature: String = "",                // kcxz (课程性质，如 专业必修课)
    val score: String = "",                 // zhcj (综合成绩)
    val gpa: String = "",                   // jd (单科绩点)
    val status: String = ""                 // wczt (修读状态: "已修" / "已选课，未录成绩" / "未修")
) {
    val isCompleted: Boolean get() = status == "已修"
    val isStudying: Boolean get() = status.contains("已选课") || status.contains("未录")
    val isUncompleted: Boolean get() = !isCompleted && !isStudying
}

/**
 * 分组节点（支持课程性质分组或学年学期分组）
 * 对应接口：/admin/xsd/xskp/xyjc?fasz=2 (性质) 或 ?fasz=3 (学期)
 */
data class AcademicCourseGroup(
    val nodeId: String = "",
    val nodeName: String = "",
    val earnedCredits: Double = 0.0,        // hdxf
    val planCredits: Double = 0.0,          // 组内所有课程计划学分之和
    val courses: List<AcademicCourse> = emptyList()
) {
    val courseCount: Int get() = courses.size
}

/**
 * 学业完成度与课程进程聚合数据
 */
data class AcademicProgressData(
    val student: StudentProfile = StudentProfile(),
    val stats: AcademicStats = AcademicStats(),
    val summary: AcademicProgressSummary = AcademicProgressSummary(),
    val natureGroups: List<AcademicCourseGroup> = emptyList(),    // fasz=2 模块性质分类树
    val semesterGroups: List<AcademicCourseGroup> = emptyList()   // fasz=3 学年学期推进树
)
