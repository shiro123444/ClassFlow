package com.xingheyuzhuan.shiguangschedule.data.model.wbu

/**
 * 读者基础档案信息
 */
data class ReaderProfile(
    val name: String = "",
    val certNo: String = "",
    val department: String = "",
    val readerType: String = "",
    val maxLend: Int = 0,
    val overdue: Int = 0,
    val totalBorrow: Int = 0,
    val certEndDate: String = ""
)

/**
 * 当前在借图书实体
 */
data class BorrowedBook(
    val barcode: String,
    val title: String,
    val author: String,
    val borrowDate: String,
    val dueDate: String,
    val daysRemaining: Int? = null,
    val isOverdue: Boolean = false,
    val renewCount: Int = 0,
    val location: String = "",
    val attachment: String = "",
    val marcNo: String = "",
    val renewCheck: String = "",
    val canRenew: Boolean = false
)

/**
 * 借阅历史图书实体
 */
data class BorrowHistoryBook(
    val index: String = "",
    val barcode: String = "",
    val title: String = "",
    val author: String = "",
    val borrowDate: String = "",
    val returnDate: String = "",
    val location: String = "",
    val marcNo: String = ""
)

/**
 * 单本纸质副本分布情况
 */
data class HoldingItem(
    val callNo: String = "",
    val barcode: String = "",
    val location: String = "",
    val status: String = ""
)

/**
 * 图书元数据详情
 */
data class BookDetail(
    val title: String = "",
    val publisher: String = "",
    val isbn: String = "",
    val cleanIsbn: String = "",
    val callNo: String = "",
    val summary: String = "",
    val coverUrl: String = "",
    val holdings: List<HoldingItem> = emptyList()
)

/**
 * 续借结果
 */
data class RenewResult(
    val success: Boolean,
    val message: String
)

/**
 * 图书馆整体数据集
 */
data class LibraryDashboardData(
    val profile: ReaderProfile = ReaderProfile(),
    val currentBorrows: List<BorrowedBook> = emptyList(),
    val historyBorrows: List<BorrowHistoryBook> = emptyList()
)
