package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import com.xingheyuzhuan.shiguangschedule.data.model.wbu.BatchStage
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CourseSelectionInit
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.KkxFrom
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.RetakeCourse
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.SelectedCourse
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.SelectionBatch
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.SelectionOutcome
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.TeachingClass
import kotlinx.coroutines.delay

/**
 * 选课功能的本地 Mock 数据源（仅供暗号手势进入的测试模式使用）。
 *
 * 完全离线，不依赖校园网与登录；选/退课会**在内存中**改变状态，
 * 因此可以完整走通「选课 → 列表变为已选 → 退课 → 恢复」的闭环。
 * 覆盖：计划选课、公共选修（抽签，需二次确认）、重修选课、暂不支持批次，
 * 以及已满 / 候补 / 时间冲突 / 部分冲突 / 已选 / 子教学班等展示分支。
 */
class MockCourseSelectionDataSource : CourseSelectionDataSource {

    private companion object {
        const val DELAY_MS = 350L
        const val PC_JH = "MOCK_PC_JH"
        const val PC_GG = "MOCK_PC_GG"
        const val PC_CX = "MOCK_PC_CX"
        const val PC_UNSUPPORTED = "MOCK_PC_UNSUPPORTED"
        const val JXB_WITH_CHILDREN = "MOCK_JXB_001"
    }

    private val batches = listOf(
        SelectionBatch(
            pcid = PC_JH,
            name = "2026-2027-1 计划选课",
            kklx = "1",
            from = KkxFrom.JHXK,
            stage = BatchStage.OPEN,
            countdownMs = 3_600_000L,
            pcenc = "mock-enc-jh",
            isLottery = false,
            allowWaitlist = true,
            promptMessage = "Mock 数据：本地模拟，不会写入教务系统",
            quotaPerBatch = 0,
            isSupported = true
        ),
        SelectionBatch(
            pcid = PC_GG,
            name = "公共选修（抽签）",
            kklx = "2",
            from = KkxFrom.GGXXK,
            stage = BatchStage.OPEN,
            countdownMs = 1_800_000L,
            pcenc = "mock-enc-gg",
            isLottery = true,
            allowWaitlist = false,
            promptMessage = "",
            quotaPerBatch = 2,
            isSupported = true
        ),
        SelectionBatch(
            pcid = PC_CX,
            name = "重修选课",
            kklx = "5",
            from = KkxFrom.CXXK,
            stage = BatchStage.OPEN,
            countdownMs = 3_600_000L,
            pcenc = "",
            isLottery = false,
            allowWaitlist = false,
            promptMessage = "",
            quotaPerBatch = 0,
            isSupported = true
        ),
        SelectionBatch(
            pcid = PC_UNSUPPORTED,
            name = "示例批次",
            kklx = "99",
            from = KkxFrom.UNSUPPORTED,
            stage = BatchStage.CLOSED,
            countdownMs = 0L,
            pcenc = "",
            isLottery = false,
            allowWaitlist = false,
            promptMessage = "",
            quotaPerBatch = 0,
            isSupported = false
        )
    )

    private val classesByPcid: MutableMap<String, MutableList<TeachingClass>> = mutableMapOf(
        PC_JH to mutableListOf(
            mkClass(
                jxbid = JXB_WITH_CHILDREN,
                kcmc = "离散数学",
                kcbh = "BD0600018",
                xf = "4",
                selected = 20,
                capacity = 60,
                teacher = "胡康",
                ksxs = "考试",
                classTime = "第2-17周 星期二 1-2节【南①－405】;第2-17周 星期四 1-2节【南①－东-102(投2)】"
            ),
            mkClass(
                jxbid = "MOCK_JXB_002",
                kcmc = "高等数学",
                kcbh = "BD0600019",
                xf = "5",
                selected = 60,
                capacity = 60,
                teacher = "张三",
                ksxs = "考试",
                classTime = "第2-17周 星期一 3-4节【南①－101】"
            ),
            mkClass(
                jxbid = "MOCK_JXB_003",
                kcmc = "数据结构",
                kcbh = "BD0600020",
                xf = "3",
                selected = 55,
                capacity = 60,
                teacher = "李四",
                ksxs = "考查",
                classTime = "第2-17周 星期三 5-6节【南②－210】",
                conflict = 1
            ),
            mkClass(
                jxbid = "MOCK_JXB_004",
                kcmc = "大学英语",
                kcbh = "BD0600021",
                xf = "2",
                selected = 30,
                capacity = 60,
                teacher = "王五",
                ksxs = "考试",
                classTime = "第2-17周 星期五 1-2节【南①－305】",
                status = "1"
            ),
            mkClass(
                jxbid = "MOCK_JXB_005",
                kcmc = "大学体育",
                kcbh = "BD0600022",
                xf = "1",
                selected = 40,
                capacity = 60,
                teacher = "赵六",
                ksxs = "考查",
                classTime = "第3-16周 星期四 7-8节【体育馆】",
                conflict = 2,
                kclbName = "集中性实践环节"
            )
        ),
        PC_GG to mutableListOf(
            mkClass(
                jxbid = "MOCK_JXB_101",
                kcmc = "中国传统文化",
                kcbh = "GG0100001",
                xf = "2",
                selected = 10,
                capacity = 50,
                teacher = "陈七",
                ksxs = "考查",
                classTime = "第2-17周 星期二 7-8节【南①－401】"
            ),
            mkClass(
                jxbid = "MOCK_JXB_102",
                kcmc = "艺术鉴赏",
                kcbh = "GG0100002",
                xf = "1",
                selected = 50,
                capacity = 50,
                teacher = "周八",
                ksxs = "考查",
                classTime = "第2-17周 星期三 9-10节【南②－108】"
            )
        )
    )

    private val retakeBinds = listOf(
        RetakeCourse(cxmdid = "MOCK_CX_1", kcid = "MOCK_KC_1", kcmc = "数据结构", xnxq = "2025-2026-1", xf = "3"),
        RetakeCourse(cxmdid = "MOCK_CX_2", kcid = "MOCK_KC_2", kcmc = "线性代数", xnxq = "2025-2026-2", xf = "2")
    )

    private val retakeClassesByCxmdid: MutableMap<String, MutableList<TeachingClass>> = mutableMapOf(
        "MOCK_CX_1" to mutableListOf(
            mkClass(
                jxbid = "MOCK_CX_JXB_1",
                kcmc = "数据结构（重修班）",
                kcbh = "BD0600020",
                xf = "3",
                selected = 10,
                capacity = 40,
                teacher = "孙九",
                ksxs = "考试",
                classTime = "第2-17周 星期六 1-2节【南②－210】"
            ),
            mkClass(
                jxbid = "MOCK_CX_JXB_2",
                kcmc = "数据结构（重修班·晚）",
                kcbh = "BD0600020",
                xf = "3",
                selected = 38,
                capacity = 40,
                teacher = "孙九",
                ksxs = "考试",
                classTime = "第2-17周 星期六 3-4节【南②－211】"
            )
        ),
        "MOCK_CX_2" to mutableListOf(
            mkClass(
                jxbid = "MOCK_CX_JXB_3",
                kcmc = "线性代数（重修班）",
                kcbh = "BD0600023",
                xf = "2",
                selected = 5,
                capacity = 30,
                teacher = "钱十",
                ksxs = "考试",
                classTime = "第2-17周 星期日 1-2节【南①－102】"
            )
        )
    )

    override suspend fun queryInit(): Result<CourseSelectionInit> = mock {
        CourseSelectionInit(
            studentId = "250594036",
            studentName = "Mock 学生",
            xkxnxq = "2026-2027-1",
            batches = batches,
            hideQuota = false
        )
    }

    override suspend fun queryClasses(
        from: KkxFrom,
        pcid: String,
        pcenc: String
    ): Result<List<TeachingClass>> = mock {
        classesByPcid[pcid]?.toList().orEmpty()
    }

    override suspend fun queryChildClasses(
        jxbid: String,
        pcid: String,
        pcenc: String,
        from: String
    ): Result<List<String>> = mock {
        if (jxbid == JXB_WITH_CHILDREN) listOf("MOCK_CHILD_A", "MOCK_CHILD_B") else emptyList()
    }

    override suspend fun selectClass(
        jxbid: String,
        pcid: String,
        zjxbid: String,
        sfqc: Boolean
    ): Result<SelectionOutcome> = mock {
        val list = classesByPcid[pcid] ?: return@mock SelectionOutcome(false, false, "教学班不存在")
        val index = list.indexOfFirst { it.jxbid == jxbid }
        if (index < 0) return@mock SelectionOutcome(false, false, "教学班不存在")
        val batch = batches.firstOrNull { it.pcid == pcid }
        val target = list[index]

        // 抽签批次首次点「报名」时模拟需要二次确认
        if (batch?.isLottery == true && !sfqc) {
            return@mock SelectionOutcome(false, true, "该教学班与已选课程时间冲突，是否继续报名？")
        }
        if (target.isFull && batch?.allowWaitlist != true) {
            return@mock SelectionOutcome(false, false, "该教学班名额已满")
        }

        val newCount = target.selectedCount + 1
        list[index] = target.copy(
            status = "1",
            selectedCount = newCount,
            quotaText = "$newCount/${target.capacity}",
            isFull = newCount >= target.capacity
        )
        SelectionOutcome(
            success = true,
            needConfirm = false,
            message = if (batch?.isLottery == true) "报名成功" else "选课成功"
        )
    }

    override suspend fun dropClass(jxbid: String, pcid: String): Result<String> = mock {
        releaseClass(jxbid, pcid)
        "退课成功"
    }

    override suspend fun cancelWaitlist(jxbid: String, pcid: String): Result<String> = mock {
        releaseClass(jxbid, pcid)
        "已取消候补"
    }

    override suspend fun querySelectedCourses(): Result<List<SelectedCourse>> = mock {
        classesByPcid.values.flatten()
            .filter { it.isSelected }
            .map { c ->
                SelectedCourse(
                    id = c.jxbid,
                    kcbh = c.kcbh,
                    kcmc = c.kcmc,
                    jxbmc = c.jxbmc,
                    jxbbh = c.jxbbh,
                    kcid = "MOCK_KCID_${c.jxbid}",
                    kcxz = c.kcxz,
                    kclbName = c.kclbName,
                    kcgs = c.kcgs,
                    xf = c.xf,
                    zxs = "64",
                    classTime = c.classTime,
                    jxbzc = c.jxbzc,
                    teacher = c.teacher,
                    xnxq = "2026-2027-1",
                    xkfs = "选课",
                    xklx = "其他"
                )
            }
    }

    override suspend fun queryRetakeBind(pcid: String): Result<List<RetakeCourse>> = mock {
        retakeBinds
    }

    override suspend fun queryRetakeClasses(
        cxmdid: String,
        kcid: String,
        pcid: String
    ): Result<List<TeachingClass>> = mock {
        retakeClassesByCxmdid[cxmdid]?.toList().orEmpty()
    }

    override suspend fun retakeSelect(
        jxbid: String,
        fjxbid: String,
        kcid: String,
        cxmdid: String,
        pcid: String
    ): Result<SelectionOutcome> = mock {
        val list = retakeClassesByCxmdid[cxmdid]
        val index = list?.indexOfFirst { it.jxbid == jxbid } ?: -1
        if (list != null && index >= 0) {
            list[index] = list[index].copy(status = "1")
        }
        SelectionOutcome(success = true, needConfirm = false, message = "重修选课成功")
    }

    override suspend fun retakeDrop(
        jxbid: String,
        kcid: String,
        cxmdid: String,
        pcid: String
    ): Result<String> = mock {
        val list = retakeClassesByCxmdid[cxmdid]
        val index = list?.indexOfFirst { it.jxbid == jxbid } ?: -1
        if (list != null && index >= 0) {
            list[index] = list[index].copy(status = "0")
        }
        "重修退课成功"
    }

    private fun releaseClass(jxbid: String, pcid: String) {
        val list = classesByPcid[pcid] ?: return
        val index = list.indexOfFirst { it.jxbid == jxbid }
        if (index < 0) return
        val target = list[index]
        val newCount = (target.selectedCount - 1).coerceAtLeast(0)
        list[index] = target.copy(
            status = "0",
            selectedCount = newCount,
            quotaText = "$newCount/${target.capacity}",
            isFull = newCount >= target.capacity
        )
    }

    private suspend fun <T> mock(block: () -> T): Result<T> {
        delay(DELAY_MS)
        return runCatching(block)
    }

    private fun mkClass(
        jxbid: String,
        kcmc: String,
        kcbh: String,
        xf: String,
        selected: Int,
        capacity: Int,
        teacher: String,
        ksxs: String,
        classTime: String,
        status: String = "0",
        conflict: Int = 0,
        kcxz: String = "14",
        kclbName: String = "理论课（不含实践）"
    ): TeachingClass = TeachingClass(
        jxbid = jxbid,
        kcbh = kcbh,
        kcmc = kcmc,
        xf = xf,
        status = status,
        conflict = conflict,
        quotaText = "$selected/$capacity",
        selectedCount = selected,
        capacity = capacity,
        isFull = selected >= capacity,
        classTime = classTime,
        classTimeCodes = "",
        kcxz = kcxz,
        kclbName = kclbName,
        kcgs = "01",
        jxms = "线下",
        campusName = "后官湖校区",
        teacher = teacher,
        jxbmc = "$kcmc${jxbid.takeLast(3)}",
        jxbbh = "2627100${jxbid.takeLast(3)}",
        ksxs = ksxs,
        kclx = "普通",
        jxbzc = "25人工智能(本科)(1)",
        bz = "",
        waitlistJxbid = ""
    )
}
