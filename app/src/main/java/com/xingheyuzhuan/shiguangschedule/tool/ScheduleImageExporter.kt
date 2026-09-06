package com.xingheyuzhuan.shiguangschedule.tool

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.xingheyuzhuan.shiguangschedule.data.db.main.Course
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfig
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWithWeeks
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max

/**
 * 课表图片导出工具类。
 *
 * 核心特性：
 * 1. 全部课程：整学期课程全量汇总网格，自动按有无周末展开列数（无周末则显示周一至周五）。
 * 2. 无色极简主题：直角卡片（无圆角），课程块背景色等于其起始节次的奇偶斑马条纹色，空白单元格与全表格同行平滑贯通。
 * 3. 两种排版模式：
 *    - 模式 A（autoSplitConflict == true，默认开启）：基于冲突端点的智能截断算法。
 *      同一天内有区间包含/错位交叉（如 5-8节 与 5-6节、7-8节）时，自动在冲突端点切开，对齐标准节次单元格，适合 OCR / 弱识别工具；
 *      无冲突的长课（如单门 3/4 节课）绝对不切，完整保留。
 *    - 模式 B（autoSplitConflict == false，穿插表达）：不截断课程，采用图着色分列算法（Sub-columns Packing）。
 *      长课保持原长跨节，短课在旁边并列分配子列，在同一天内穿插排列，不挤压不重叠。
 * 4. 动态精确测算行高，结合真实 StaticLayout 测高，彻底避免文字溢出被挤出。
 */
object ScheduleImageExporter {

    private const val SCALE = 2.0f // 2x 超清高倍率重绘

    private fun dp(v: Float): Float = v * SCALE
    private fun sp(v: Float): Float = v * SCALE

    data class CourseCellItem(
        val course: Course,
        val weeksText: String
    )

    /**
     * 排版后的视觉卡片描述单元
     */
    data class LayoutCell(
        val startSection: Int,
        val endSection: Int,
        val subColIndex: Int,
        val totalSubCols: Int,
        val items: MutableList<CourseCellItem>
    )

    private fun parseTimeToMinutes(t: String?): Int {
        if (t.isNullOrBlank()) return -1
        val parts = t.trim().split(":")
        if (parts.size != 2) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        return h * 60 + m
    }

    /**
     * 将离散周次转为紧凑易读的周次文字，例如："1-16周"、"1-15周·单周"、"2-16周·双周"、"1-4,7-12周"、"第3周"
     */
    fun formatWeeks(weeks: List<Int>): String {
        val ws = weeks.filter { it > 0 }.distinct().sorted()
        if (ws.isEmpty()) return ""
        if (ws.size == 1) return "第${ws[0]}周"

        val isOdd = ws.all { it % 2 == 1 }
        val isEven = ws.all { it % 2 == 0 }
        val isStep2 = ws.zipWithNext().all { (a, b) -> b - a == 2 }

        if (isStep2 && (isOdd || isEven)) {
            val typeStr = if (isOdd) "单周" else "双周"
            return "${ws.first()}-${ws.last()}周·$typeStr"
        }

        val runs = mutableListOf<String>()
        var start = ws[0]
        var prev = ws[0]
        for (i in 1 until ws.size) {
            val curr = ws[i]
            if (curr == prev + 1) {
                prev = curr
            } else {
                runs.add(if (start == prev) "$start" else "$start-$prev")
                start = curr
                prev = curr
            }
        }
        runs.add(if (start == prev) "$start" else "$start-$prev")
        return runs.joinToString(",") + "周"
    }

    /**
     * 针对单天课程：模式 A（自动截断冲突课程）
     * 原则：
     * 1. 先在午休/晚修断点处自然断开；
     * 2. 收集所有起止端点，将时间轴划分为原子区间；
     * 3. 相邻原子区间如果包含的课程集合完全一致，则融合成一段（无冲突的长课保持大块不切）；
     * 4. 仅在课程集合发生改变的冲突端点处执行切分。
     */
    private fun resolveBySmartSplitting(
        dayCourses: List<CourseWithWeeks>,
        breakNodes: List<Int>,
        totalSections: Int
    ): List<LayoutCell> {
        data class TempSeg(val start: Int, val end: Int, val item: CourseCellItem)

        // 1. 先切断跨午休/晚修的课程
        val splitByBreaks = mutableListOf<TempSeg>()
        for (cw in dayCourses) {
            val c = cw.course
            val s0 = (c.startSection ?: 1).coerceIn(1, totalSections)
            val e0 = (c.endSection ?: s0).coerceIn(s0, totalSections)
            val item = CourseCellItem(c, formatWeeks(cw.weeks.map { it.weekNumber }))

            var curStart = s0
            for (bx in breakNodes.sorted()) {
                if (bx in curStart until e0) {
                    splitByBreaks.add(TempSeg(curStart, bx, item))
                    curStart = bx + 1
                }
            }
            if (curStart <= e0) {
                splitByBreaks.add(TempSeg(curStart, e0, item))
            }
        }

        if (splitByBreaks.isEmpty()) return emptyList()

        // 2. 收集关键切分端点（起止点与后一点）
        val points = sortedSetOf<Int>()
        for (seg in splitByBreaks) {
            points.add(seg.start)
            if (seg.end + 1 <= totalSections + 1) {
                points.add(seg.end + 1)
            }
        }
        val pointList = points.toList()

        // 3. 构建原子区间与对应的课程集合
        data class AtomicSpan(val start: Int, val end: Int, val items: List<CourseCellItem>)
        val atomics = mutableListOf<AtomicSpan>()
        for (i in 0 until pointList.size - 1) {
            val aStart = pointList[i]
            val aEnd = pointList[i + 1] - 1
            if (aStart > aEnd) continue
            // 找出覆盖这个原子区间的课程
            val activeItems = splitByBreaks.filter { it.start <= aStart && it.end >= aEnd }.map { it.item }
            if (activeItems.isNotEmpty()) {
                atomics.add(AtomicSpan(aStart, aEnd, activeItems))
            }
        }

        if (atomics.isEmpty()) return emptyList()

        // 4. 相邻原子区间融合：如果课程完全相同且未跨越午休，则合并为一个连贯大卡片
        val mergedCells = mutableListOf<LayoutCell>()
        var curStart = atomics[0].start
        var curEnd = atomics[0].end
        var curItems = atomics[0].items

        for (i in 1 until atomics.size) {
            val next = atomics[i]
            val hasBreakBetween = breakNodes.any { it in curEnd until next.start }
            val sameItems = curItems.map { it.course.id }.toSet() == next.items.map { it.course.id }.toSet()

            if (curEnd + 1 == next.start && sameItems && !hasBreakBetween) {
                // 相同集合无缝延续，延伸当前区间
                curEnd = next.end
            } else {
                mergedCells.add(
                    LayoutCell(
                        startSection = curStart,
                        endSection = curEnd,
                        subColIndex = 0,
                        totalSubCols = 1,
                        items = curItems.distinctBy { it.course.id to it.weeksText }.toMutableList()
                    )
                )
                curStart = next.start
                curEnd = next.end
                curItems = next.items
            }
        }

        mergedCells.add(
            LayoutCell(
                startSection = curStart,
                endSection = curEnd,
                subColIndex = 0,
                totalSubCols = 1,
                items = curItems.distinctBy { it.course.id to it.weeksText }.toMutableList()
            )
        )

        return mergedCells
    }

    /**
     * 针对单天课程：模式 B（不截断，图着色子列穿插表达）
     * 遇时间冲突时，在同一天内横向分配不同的 subColIndex（宽度对半开或多分列），保持课程原长
     */
    private fun resolveBySubColumnsPacking(
        dayCourses: List<CourseWithWeeks>,
        totalSections: Int
    ): List<LayoutCell> {
        if (dayCourses.isEmpty()) return emptyList()

        data class RawCourseInfo(
            val start: Int,
            val end: Int,
            val item: CourseCellItem
        )

        val rawList = dayCourses.map { cw ->
            val s = (cw.course.startSection ?: 1).coerceIn(1, totalSections)
            val e = (cw.course.endSection ?: s).coerceIn(s, totalSections)
            RawCourseInfo(s, e, CourseCellItem(cw.course, formatWeeks(cw.weeks.map { it.weekNumber })))
        }.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))

        // 1. 将重叠的课程划分到冲突连通分量（Cluster）
        val clusters = mutableListOf<MutableList<RawCourseInfo>>()
        var curCluster = mutableListOf<RawCourseInfo>()
        var clusterEnd = -1

        for (item in rawList) {
            if (curCluster.isEmpty()) {
                curCluster.add(item)
                clusterEnd = item.end
            } else {
                if (item.start <= clusterEnd) {
                    // 与当前冲突组有重叠，并入
                    curCluster.add(item)
                    clusterEnd = max(clusterEnd, item.end)
                } else {
                    clusters.add(curCluster)
                    curCluster = mutableListOf(item)
                    clusterEnd = item.end
                }
            }
        }
        if (curCluster.isNotEmpty()) {
            clusters.add(curCluster)
        }

        // 2. 对每个冲突组进行区间贪心着色，分配子列索引 (subColIndex)
        val resultCells = mutableListOf<LayoutCell>()

        for (cluster in clusters) {
            // track 各子列上最后结束的节次
            val colEndSections = mutableListOf<Int>()
            val assignments = mutableListOf<Pair<RawCourseInfo, Int>>()

            for (courseInfo in cluster) {
                // 查找第一个在 courseInfo.start 之前已结束的子列
                var assignedCol = -1
                for (colIdx in 0 until colEndSections.size) {
                    if (colEndSections[colIdx] < courseInfo.start) {
                        assignedCol = colIdx
                        colEndSections[colIdx] = courseInfo.end
                        break
                    }
                }
                if (assignedCol == -1) {
                    // 新增一列
                    assignedCol = colEndSections.size
                    colEndSections.add(courseInfo.end)
                }
                assignments.add(courseInfo to assignedCol)
            }

            val totalSubCols = colEndSections.size.coerceAtLeast(1)

            // 同一子列中，若有起点终点完全相同的课程，进行聚类堆叠
            val groupedByColAndSpan = assignments.groupBy { (info, col) ->
                Triple(col, info.start, info.end)
            }

            for ((triple, list) in groupedByColAndSpan) {
                val col = triple.first
                val s = triple.second
                val e = triple.third
                val items = list.map { it.first.item }.distinctBy { it.course.id to it.weeksText }.toMutableList()

                resultCells.add(
                    LayoutCell(
                        startSection = s,
                        endSection = e,
                        subColIndex = col,
                        totalSubCols = totalSubCols,
                        items = items
                    )
                )
            }
        }

        return resultCells
    }

    /**
     * 导出全课表无色主题高清图片并直接保存到系统相册。
     *
     * @param showDashedDivider 是否在同格多课之间绘制虚线分割（默认关闭）
     * @param autoSplitConflict 是否自动截断冲突课程（默认开启，推荐用于识别；不选则在当天并列穿插表达）
     */
    fun exportFullScheduleImage(
        context: Context,
        tableName: String,
        courses: List<CourseWithWeeks>,
        timeSlots: List<TimeSlot>,
        config: CourseTableConfig?,
        showDashedDivider: Boolean = false,
        autoSplitConflict: Boolean = true
    ): Uri? {
        // 1. 自动计算周次与日期天数列（自动包含周末）
        val sundayFirst = (config?.firstDayOfWeek ?: 1) == 7
        val hasSat = courses.any { it.course.day == 6 }
        val hasSun = courses.any { it.course.day == 7 }
        val baseOrder = if (sundayFirst) listOf(7, 1, 2, 3, 4, 5, 6) else listOf(1, 2, 3, 4, 5, 6, 7)
        val days = baseOrder.filter { d ->
            if (d == 6) hasSat
            else if (d == 7) hasSun
            else true
        }

        // 2. 统计节次数与时间段映射
        val maxSectionFromCourses = courses.mapNotNull { it.course.endSection }.maxOrNull() ?: 10
        val maxSectionFromSlots = timeSlots.map { it.number }.maxOrNull() ?: 10
        val totalSections = maxOf(maxSectionFromSlots, maxSectionFromCourses).coerceAtLeast(8)
        val timeSlotMap = timeSlots.associateBy { it.number }

        // 3. 计算午休/晚修分隔行（两节之间间隔 >= 45 分钟）
        val breakNodes = mutableListOf<Int>()
        for (n in 1 until totalSections) {
            val cur = timeSlotMap[n]
            val next = timeSlotMap[n + 1]
            if (cur != null && next != null) {
                val curEnd = parseTimeToMinutes(cur.endTime)
                val nextStart = parseTimeToMinutes(next.startTime)
                if (curEnd > 0 && nextStart > 0 && (nextStart - curEnd) >= 45) {
                    breakNodes.add(n)
                }
            }
        }

        // 4. 根据所选模式计算每天的排版单元格
        val dayCellsMap = mutableMapOf<Int, List<LayoutCell>>()
        for (d in days) {
            val dayCourses = courses.filter { it.course.day == d }
            val cells = if (autoSplitConflict) {
                resolveBySmartSplitting(dayCourses, breakNodes, totalSections)
            } else {
                resolveBySubColumnsPacking(dayCourses, totalSections)
            }
            dayCellsMap[d] = cells
        }

        // 5. 尺寸基准计算
        val pad = dp(18f)
        val titleH = dp(62f)
        val headerH = dp(36f)
        val breakRowH = dp(22f)
        val nodeColW = dp(58f)

        // 考虑穿插模式可能有多列，适当增加基础单天宽度
        val minDayW = if (!autoSplitConflict && dayCellsMap.values.any { cells -> cells.any { it.totalSubCols > 1 } }) {
            dp(140f)
        } else {
            dp(110f)
        }

        val minCanvasW = dp(840f)
        val dayColW = max(minDayW, (minCanvasW - pad * 2 - nodeColW) / days.size)
        val totalCanvasW = (pad * 2 + nodeColW + dayColW * days.size).toInt()

        // 准备测高 TextPaint
        val titleMeasurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sp(12f)
        }

        // 6. 动态精确测算各节行高（杜绝文字被挤出）
        val defaultRowH = dp(66f).toInt()
        val rowHMap = IntArray(totalSections + 1) { defaultRowH }

        for ((_, cells) in dayCellsMap) {
            for (cell in cells) {
                // 计算当前卡片内部的可用宽度
                val subColW = dayColW / cell.totalSubCols
                val textInnerW = (subColW - dp(14f)).coerceAtLeast(dp(20f)).toInt()

                var totalNeedH = dp(16f) // 上下内边距

                for ((idx, item) in cell.items.withIndex()) {
                    // 课程名高度计算
                    val titleLayout = StaticLayout.Builder.obtain(
                        item.course.name,
                        0,
                        item.course.name.length,
                        titleMeasurePaint,
                        textInnerW
                    ).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setMaxLines(3)
                        .build()
                    totalNeedH += titleLayout.height + dp(4f)

                    // 属性行高度（教师、周次、教室）
                    if (item.course.teacher.isNotBlank()) totalNeedH += dp(14f)
                    if (item.weeksText.isNotBlank()) totalNeedH += dp(14f)
                    if (item.course.position.isNotBlank()) totalNeedH += dp(14f)

                    // 课间间隔（虚线或自然留白）
                    if (idx < cell.items.size - 1) {
                        totalNeedH += if (showDashedDivider) dp(16f) else dp(10f)
                    }
                }

                val span = (cell.endSection - cell.startSection + 1).coerceAtLeast(1)
                val perSection = ceil(totalNeedH / span).toInt()
                for (s in cell.startSection..cell.endSection) {
                    if (s in 1..totalSections) {
                        rowHMap[s] = max(rowHMap[s], perSection)
                    }
                }
            }
        }

        // 累计各节次 Y 坐标及 break 行 Y 坐标
        val yOfSection = IntArray(totalSections + 1)
        val breakYMap = mutableMapOf<Int, Float>()
        var curY = 0f

        for (n in 1..totalSections) {
            yOfSection[n] = curY.toInt()
            curY += rowHMap[n]
            if (breakNodes.contains(n)) {
                breakYMap[n] = curY + breakRowH / 2f
                curY += breakRowH
            }
        }
        val gridH = curY
        val totalCanvasH = (pad + titleH + headerH + gridH + pad + dp(10f)).toInt()

        // 7. 创建 Canvas 开始高清绘制
        val bitmap = Bitmap.createBitmap(totalCanvasW, totalCanvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            isSubpixelText = true
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // ── 绘制顶部标题与元信息 ──
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.textSize = sp(20f)
        textPaint.color = Color.parseColor("#1F2329")
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(tableName.ifBlank { "我的课表" }, pad, pad + dp(22f), textPaint)

        // 元数据描述
        val totalCoursesCount = courses.map { it.course.id }.distinct().size
        val totalArrangements = courses.size
        val totalWeeks = config?.semesterTotalWeeks?.coerceIn(1, 60) ?: 20
        val startDateStr = config?.semesterStartDate?.takeIf { it.isNotBlank() }
        val metaStr = buildString {
            if (startDateStr != null) append("$startDateStr 开学 · ")
            append("共 $totalWeeks 周 · ")
            append("$totalCoursesCount 门课程 / $totalArrangements 条安排")
        }
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = sp(12f)
        textPaint.color = Color.parseColor("#646A73")
        canvas.drawText(metaStr, pad, pad + dp(44f), textPaint)

        // 右上角标识
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.textSize = sp(14.5f)
        textPaint.color = Color.parseColor("#4C6EF5")
        val modeTag = if (autoSplitConflict) "全部课程 · 无色主题" else "全部课程 · 穿插并列"
        canvas.drawText(modeTag, totalCanvasW - pad, pad + dp(22f), textPaint)

        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = sp(10.5f)
        textPaint.color = Color.parseColor("#8F959E")
        val exportTimeStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + " 导出"
        canvas.drawText(exportTimeStr, totalCanvasW - pad, pad + dp(44f), textPaint)

        val gridTop = pad + titleH

        // ── 绘制表头（周一至周日） ──
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#F2F3F5")
        canvas.drawRect(pad, gridTop, totalCanvasW - pad, gridTop + headerH, paint)

        val dayNames = mapOf(1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四", 5 to "周五", 6 to "周六", 7 to "周日")
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.textSize = sp(13.5f)
        textPaint.color = Color.parseColor("#1F2329")

        for ((idx, d) in days.withIndex()) {
            val cx = pad + nodeColW + idx * dayColW + dayColW / 2f
            canvas.drawText(dayNames[d] ?: "周$d", cx, gridTop + dp(23f), textPaint)
        }

        // 表头底部分割线
        paint.color = Color.parseColor("#D0D3D6")
        paint.strokeWidth = dp(1f)
        canvas.drawLine(pad, gridTop + headerH, totalCanvasW - pad, gridTop + headerH, paint)

        // ── 绘制全表格斑马条纹背景 ──
        val stripeColorOdd = Color.parseColor("#FFFFFF")
        val stripeColorEven = Color.parseColor("#F7F8FA")
        val breakBgColor = Color.parseColor("#ECEEF2")

        for (n in 1..totalSections) {
            val y = gridTop + headerH + yOfSection[n]
            paint.style = Paint.Style.FILL
            paint.color = if (n % 2 == 1) stripeColorOdd else stripeColorEven
            canvas.drawRect(pad, y.toFloat(), totalCanvasW - pad, (y + rowHMap[n]).toFloat(), paint)
        }

        // 午休/晚修背景条
        for ((_, midY) in breakYMap) {
            val by = gridTop + headerH + midY - breakRowH / 2f
            paint.style = Paint.Style.FILL
            paint.color = breakBgColor
            canvas.drawRect(pad, by, totalCanvasW - pad, by + breakRowH, paint)
        }

        // ── 绘制左侧节次与时间列文字 ──
        textPaint.textAlign = Paint.Align.CENTER
        for (n in 1..totalSections) {
            val y = gridTop + headerH + yOfSection[n]
            val slotH = rowHMap[n]
            val td = timeSlotMap[n]

            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textSize = sp(12f)
            textPaint.color = Color.parseColor("#2A2E39")
            val baseTextY = y + if (td != null) dp(22f) else slotH / 2f + dp(4f)
            canvas.drawText("$n", pad + nodeColW / 2f, baseTextY, textPaint)

            if (td != null) {
                textPaint.typeface = Typeface.DEFAULT
                textPaint.textSize = sp(9f)
                textPaint.color = Color.parseColor("#656D7E")
                canvas.drawText(td.startTime, pad + nodeColW / 2f, baseTextY + dp(14f), textPaint)
                canvas.drawText(td.endTime, pad + nodeColW / 2f, baseTextY + dp(27f), textPaint)
            }
        }

        // ── 绘制课程单元格 ──
        val dashEffect = DashPathEffect(floatArrayOf(dp(5f), dp(3f)), 0f)
        val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B0B6C2")
            strokeWidth = dp(1f)
            pathEffect = dashEffect
        }
        val cellBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val cellBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#D0D4DC")
            strokeWidth = dp(1f)
        }

        for ((dayIdx, d) in days.withIndex()) {
            val cells = dayCellsMap[d] ?: continue
            val colX = pad + nodeColW + dayIdx * dayColW

            for (cell in cells) {
                val startY = gridTop + headerH + yOfSection[cell.startSection]
                // 跨午休/晚修时计入 breakRowH 的跨度高度
                val breaksBetween = breakNodes.count { it in cell.startSection until cell.endSection }
                val endY = gridTop + headerH + yOfSection[cell.endSection] + rowHMap[cell.endSection] + breaksBetween * breakRowH

                val subColW = dayColW / cell.totalSubCols
                val leftX = colX + cell.subColIndex * subColW
                val rightX = leftX + subColW
                val cellRect = RectF(leftX, startY.toFloat(), rightX, endY)

                // 课程格子背景色取其起始节次的条纹色（直角平铺）
                cellBgPaint.color = if (cell.startSection % 2 == 1) stripeColorOdd else stripeColorEven
                canvas.drawRect(cellRect, cellBgPaint)

                // 若处于穿插分列状态，画出子列之间的竖向内分割线
                if (cell.totalSubCols > 1) {
                    canvas.drawRect(cellRect, cellBorderPaint)
                }

                // 绘制格子内的堆叠课程内容
                var curTextY = cellRect.top + dp(12f)
                val textLeft = cellRect.left + dp(6f)
                val textInnerW = (cellRect.width() - dp(12f)).coerceAtLeast(dp(20f)).toInt()

                for ((idx, item) in cell.items.withIndex()) {
                    // 1. 课程名称
                    textPaint.textAlign = Paint.Align.LEFT
                    textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textPaint.textSize = sp(12f)
                    textPaint.color = Color.parseColor("#1A1D24")

                    val titleLayout = StaticLayout.Builder.obtain(
                        item.course.name,
                        0,
                        item.course.name.length,
                        textPaint,
                        textInnerW
                    ).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setMaxLines(3)
                        .build()

                    canvas.save()
                    canvas.translate(textLeft, curTextY)
                    titleLayout.draw(canvas)
                    canvas.restore()
                    curTextY += titleLayout.height + dp(4f)

                    // 2. 教师、周次、教室
                    textPaint.typeface = Typeface.DEFAULT
                    textPaint.textSize = sp(10.5f)
                    textPaint.color = Color.parseColor("#555D6E")

                    if (item.course.teacher.isNotBlank()) {
                        val teacherText = "教师：${item.course.teacher}"
                        canvas.drawText(truncateText(teacherText, textPaint, textInnerW.toFloat()), textLeft, curTextY + dp(10f), textPaint)
                        curTextY += dp(14f)
                    }

                    if (item.weeksText.isNotBlank()) {
                        val weekText = "周次：${item.weeksText}"
                        canvas.drawText(truncateText(weekText, textPaint, textInnerW.toFloat()), textLeft, curTextY + dp(10f), textPaint)
                        curTextY += dp(14f)
                    }

                    if (item.course.position.isNotBlank()) {
                        val roomText = "教室：${item.course.position}"
                        canvas.drawText(truncateText(roomText, textPaint, textInnerW.toFloat()), textLeft, curTextY + dp(10f), textPaint)
                        curTextY += dp(14f)
                    }

                    // 课程之间的分割（可选虚线或留白）
                    if (idx < cell.items.size - 1) {
                        if (showDashedDivider) {
                            val dashY = curTextY + dp(6f)
                            canvas.drawLine(textLeft, dashY, cellRect.right - dp(6f), dashY, dashedPaint)
                            curTextY = dashY + dp(12f)
                        } else {
                            curTextY += dp(10f)
                        }
                    }
                }
            }
        }

        // ── 绘制网格横线与外框 ──
        val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#D0D4DC")
            strokeWidth = dp(1f)
        }

        // 纵线：节次列右侧以及各天之间（在午休/晚修断开）
        val vSeg = { x: Float ->
            var sy = gridTop
            val ey = gridTop + headerH + gridH
            for (bx in breakNodes) {
                val midY = breakYMap[bx] ?: continue
                val bt = gridTop + headerH + midY - breakRowH / 2f
                if (bt > sy) {
                    canvas.drawLine(x, sy, x, bt, gridLinePaint)
                }
                sy = max(sy, gridTop + headerH + midY + breakRowH / 2f)
            }
            if (sy < ey) {
                canvas.drawLine(x, sy, x, ey, gridLinePaint)
            }
        }

        vSeg(pad)
        vSeg(pad + nodeColW)
        for (i in 1..days.size) {
            vSeg(pad + nodeColW + i * dayColW)
        }

        // 顶底外框线
        canvas.drawLine(pad, gridTop, totalCanvasW - pad, gridTop, gridLinePaint)
        canvas.drawLine(pad, gridTop + headerH + gridH, totalCanvasW - pad, gridTop + headerH + gridH, gridLinePaint)

        // 节次之间的横向行线：节次时间列恒画；各天列中如果有跨节连续块则断开横线
        for (n in 2..totalSections) {
            if (breakNodes.contains(n - 1)) continue
            val yg = (gridTop + headerH + yOfSection[n]).toFloat()
            // 节次列恒画
            canvas.drawLine(pad, yg, pad + nodeColW, yg, gridLinePaint)

            // 各天列检测是否被跨节单元格覆盖
            for ((dayIdx, d) in days.withIndex()) {
                val cells = dayCellsMap[d] ?: emptyList()
                val isSpanCovered = cells.any { cell -> cell.startSection <= n - 1 && cell.endSection >= n }
                if (!isSpanCovered) {
                    val x0 = pad + nodeColW + dayIdx * dayColW
                    canvas.drawLine(x0, yg, x0 + dayColW, yg, gridLinePaint)
                }
            }
        }

        // 午休/晚修上下分隔线与文字
        val breakBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCD2DC")
            strokeWidth = dp(1f)
        }
        for ((bn, midY) in breakYMap) {
            val topY = gridTop + headerH + midY - breakRowH / 2f
            val botY = topY + breakRowH
            canvas.drawLine(pad, topY, totalCanvasW - pad, topY, breakBorderPaint)
            canvas.drawLine(pad, botY, totalCanvasW - pad, botY, breakBorderPaint)

            val label = if (bn == breakNodes.firstOrNull()) "午休" else "晚修"
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textSize = sp(11f)
            textPaint.color = Color.parseColor("#5A6273")
            canvas.drawText(label, totalCanvasW / 2f, topY + breakRowH / 2f + dp(4f), textPaint)
        }

        // 8. 直接保存到系统相册 (Pictures/ClassFlow)
        val safeTableName = tableName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "课表" }
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val displayName = "${safeTableName}_课表_$timestamp.png"

        val savedUri = saveBitmapToGallery(context, bitmap, displayName)
        bitmap.recycle()

        return savedUri
    }

    /**
     * 将 Bitmap 直接保存到手机系统相册（Pictures/ClassFlow 目录）
     */
    private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, displayName: String): Uri? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ClassFlow")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                return uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                return null
            }
        } else {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val appPicturesDir = File(picturesDir, "ClassFlow").apply {
                if (!exists()) mkdirs()
            }
            val file = File(appPicturesDir, displayName)
            try {
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf("image/png"),
                    null
                )
                return Uri.fromFile(file)
            } catch (e: Exception) {
                return null
            }
        }
    }

    private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length - 1
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return if (end > 0) text.substring(0, end) + "…" else text
    }
}
