package com.xingheyuzhuan.shiguangschedule.data.model.wbu

/**
 * 校区固定/真实 ID
 */
const val CAMPUS_HGH_UUID = "81FF19E83E534C8E8D40B3BB00B676C2"
const val CAMPUS_MYH_ID = "2"

/**
 * 教室类型字典
 */
val JSLX_DICT: Map<String, String> = mapOf(
    "05" to "多媒体教室",
    "11" to "智慧教室",
    "01" to "一般教室",
    "02" to "制图室",
    "03" to "实验室",
    "04" to "语音室",
    "06" to "多媒体授课室",
    "07" to "视听教室",
    "08" to "计算机房",
    "10" to "练功房",
    "12" to "琴室",
    "13" to "画室",
    "14" to "办公室",
    "15" to "体育馆",
    "29" to "实训室",
    "31" to "实训室(机房)"
)

/**
 * 校区项
 */
data class CampusOption(
    val id: String,
    val name: String
)

/**
 * 教学楼项
 */
data class BuildingOption(
    val code: String,
    val name: String,
    val campusId: String = ""
)

/**
 * 预设常用教学楼字典（按校区归类）
 */
val DEFAULT_BUILDINGS_HGH = listOf(
    BuildingOption("", "全部教学楼", CAMPUS_HGH_UUID),
    BuildingOption("B24B2B3F15F9419D94B9B3BB00B75504", "北区④号楼", CAMPUS_HGH_UUID),
    BuildingOption("D162762A5F6B40D1A0C7B3BB00B75504", "北区①号楼", CAMPUS_HGH_UUID),
    BuildingOption("AEEB22385F9947CCB449B3BB00B75504", "南区①号楼", CAMPUS_HGH_UUID),
    BuildingOption("5E4D0CD04FC14AE69198B3BB00B75504", "北区③号楼", CAMPUS_HGH_UUID),
    BuildingOption("8DD48D6D0F48402D98A3B3BB00B75504", "南区⑥号楼", CAMPUS_HGH_UUID),
    BuildingOption("49EC51CDF98342D3A001B3BB00B75504", "北区②号楼", CAMPUS_HGH_UUID),
    BuildingOption("02342CD455CA4A66BB29B3BB00B75504", "北区⑤号楼", CAMPUS_HGH_UUID),
    BuildingOption("C4E045EDDA8143048DF5B3BB00B75504", "南区④号楼", CAMPUS_HGH_UUID),
    BuildingOption("A88C18DD8C774B10AA88B3BB00B75504", "南区③号楼", CAMPUS_HGH_UUID),
    BuildingOption("F9C10030E3BF49858FF9B3BB00B75504", "南区②号楼", CAMPUS_HGH_UUID),
    BuildingOption("C242948EF37A46A4BCB2B3BB00B75504", "体育馆", CAMPUS_HGH_UUID),
    BuildingOption("8A6FF702AE604C89856FB3BB00B75504", "南区大学生中心", CAMPUS_HGH_UUID),
    BuildingOption("37847854D9F449298FE0B3BB00B75504", "食堂", CAMPUS_HGH_UUID)
)

val DEFAULT_BUILDINGS_MYH = listOf(
    BuildingOption("", "全部教学楼", CAMPUS_MYH_ID),
    BuildingOption("101", "马影河校区①号楼", CAMPUS_MYH_ID),
    BuildingOption("102", "马影河校区②号楼", CAMPUS_MYH_ID),
    BuildingOption("104", "马影河校区 图书馆", CAMPUS_MYH_ID),
    BuildingOption("110", "马影河校区 体育馆", CAMPUS_MYH_ID)
)

val DEFAULT_BUILDINGS_ALL = listOf(
    BuildingOption("", "全部教学楼", "")
)

/**
 * 5个标准核心大节
 */
data class StandardPeriod(
    val periodId: Int,
    val name: String,
    val timeRange: String,
    val jcStr: String
)

val STANDARD_PERIODS = listOf(
    StandardPeriod(1, "第 1-2 节", "08:00 - 09:40", "1,2"),
    StandardPeriod(2, "第 3-4 节", "10:00 - 11:40", "3,4"),
    StandardPeriod(3, "第 5-6 节", "14:00 - 15:40", "5,6"),
    StandardPeriod(4, "第 7-8 节", "16:00 - 17:40", "7,8"),
    StandardPeriod(5, "第 9-11 节", "18:30 - 21:00", "9,10,11")
)

/**
 * 单个空教室信息
 */
data class FreeClassroom(
    val id: String,
    val jsbh: String,
    val jsmc: String,
    val cleanRoomName: String,
    val jxlmc: String,
    val jxldm: String,
    val xqmc: String,
    val xqdm: String,
    val capacity: Int,
    val floor: String,
    val roomType: String,
    val gnqmc: String
)

/**
 * 空教室查询结果列表
 */
data class FreeClassroomQueryResult(
    val ret: Int,
    val msg: String,
    val total: Int,
    val totalPages: Int,
    val classrooms: List<FreeClassroom>
)

/**
 * 单个教室整周空闲课表状态
 * schedule 格式：day(1..7) -> periodId(1..5) -> isFree(Boolean)
 */
data class ClassroomWeeklySchedule(
    val roomName: String,
    val week: Int,
    val schedule: Map<Int, Map<Int, Boolean>>,
    val timestamp: Long = System.currentTimeMillis()
)
