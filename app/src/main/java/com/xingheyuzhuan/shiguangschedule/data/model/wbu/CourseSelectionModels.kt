package com.xingheyuzhuan.shiguangschedule.data.model.wbu

/**
 * 批次阶段（getStartEndTime / listV2 的 type）
 */
enum class BatchStage {
    PREVIEW, // 1 选课预览（未开始）
    OPEN,    // 2 选课进行中
    CLOSED,  // 3 退课阶段 / 0 已结束
    UNKNOWN
}

fun parseBatchStage(raw: String): BatchStage = when (raw.trim()) {
    "1" -> BatchStage.PREVIEW
    "2" -> BatchStage.OPEN
    "3", "0" -> BatchStage.CLOSED
    else -> BatchStage.UNKNOWN
}

/**
 * 开课类型决定教学班列表接口来源
 */
enum class KkxFrom(val raw: String) {
    JHXK("jhxk"),      // 计划选课
    GGXXK("ggxxk"),    // 公共选修
    FJJX("fjjx"),      // 附加/分级
    CXXK("cxxk"),      // 重修/补修
    UNSUPPORTED("")    // 4 / 99
}

fun kklxToFrom(kklx: String): KkxFrom = when (kklx.trim()) {
    "1", "6", "7", "8", "22" -> KkxFrom.JHXK
    "2", "16" -> KkxFrom.GGXXK
    "3" -> KkxFrom.FJJX
    "5", "18" -> KkxFrom.CXXK
    else -> KkxFrom.UNSUPPORTED
}

/**
 * 选课批次
 */
data class SelectionBatch(
    val pcid: String,
    val name: String,
    val kklx: String,
    val from: KkxFrom,
    val stage: BatchStage,
    val countdownMs: Long,
    val pcenc: String,
    val isLottery: Boolean,
    val allowWaitlist: Boolean,
    val promptMessage: String,
    val quotaPerBatch: Int,
    val isSupported: Boolean
)

/**
 * 教学班（可选课程）
 */
data class TeachingClass(
    val jxbid: String,
    val kcbh: String,
    val kcmc: String,
    val xf: String,
    val status: String,
    val conflict: Int,
    val quotaText: String,
    val selectedCount: Int,
    val capacity: Int,
    val isFull: Boolean,
    val classTime: String,
    val classTimeCodes: String,
    val kcxz: String,
    val kclbName: String,
    val kcgs: String,
    val jxms: String,
    val campusName: String,
    val teacher: String,
    val jxbmc: String,
    val jxbbh: String,
    val ksxs: String,
    val kclx: String,
    val jxbzc: String,
    val bz: String,
    val waitlistJxbid: String
) {
    /** 是否已选（status 非 0） */
    val isSelected: Boolean get() = status.isNotBlank() && status != "0"

    /** 是否有时间冲突 */
    val hasConflict: Boolean get() = conflict == 1 || conflict == 2
}

/**
 * 已选课程（/admin/xsd/yxkccx/listYxkc）
 */
data class SelectedCourse(
    val id: String,
    val kcbh: String,
    val kcmc: String,
    val jxbmc: String,
    val jxbbh: String,
    val kcid: String,
    val kcxz: String,
    val kclbName: String,
    val kcgs: String,
    val xf: String,
    val zxs: String,
    val classTime: String,
    val jxbzc: String,
    val teacher: String,
    val xnxq: String,
    val xkfs: String,
    val xklx: String
)

/**
 * 重修/补修待选课程（listJxb/cxxk/bind）
 */
data class RetakeCourse(
    val cxmdid: String,
    val kcid: String,
    val kcmc: String,
    val xnxq: String,
    val xf: String
)

/**
 * 选课页初始化结果（/admin/xsd/xk/listV2）
 */
data class CourseSelectionInit(
    val studentId: String,
    val studentName: String,
    val xkxnxq: String,
    val batches: List<SelectionBatch>,
    val hideQuota: Boolean
)

/**
 * 选/退课操作结果
 */
data class SelectionOutcome(
    val success: Boolean,
    val needConfirm: Boolean,
    val message: String
)
