package com.xingheyuzhuan.shiguangschedule.ui.campus.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.BookDetail
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.BorrowHistoryBook
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.BorrowedBook
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.LibraryDashboardData
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuQueryClient
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSessionExpiredException
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuSyncEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryTab {
    CURRENT_BORROW,
    BORROW_HISTORY
}

data class LibraryUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val selectedTab: LibraryTab = LibraryTab.CURRENT_BORROW,
    val dashboardData: LibraryDashboardData? = null,
    val searchQuery: String = "",
    val errorMessage: String? = null,
    val isSessionExpired: Boolean = false,
    val isRenewing: Boolean = false,
    val renewingBarcode: String? = null,
    val selectedBookDetail: BookDetail? = null,
    val isLoadingBookDetail: Boolean = false
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    private val queryClient: WbuQueryClient
        get() {
            val useVpn = WbuSyncEngine.getSavedUseVpn(getApplication()) ?: false
            return WbuQueryClient(getApplication(), useVpn = useVpn)
        }

    init {
        loadLibraryData()
    }

    fun selectTab(tab: LibraryTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun loadLibraryData(isRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = !isRefresh,
                    isRefreshing = isRefresh,
                    errorMessage = null,
                    isSessionExpired = false
                )
            }
            try {
                val data = queryClient.queryLibraryDashboard()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        dashboardData = data,
                        errorMessage = null,
                        isSessionExpired = false
                    )
                }
            } catch (e: Exception) {
                val isExpired = e is WbuSessionExpiredException
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = e.message ?: "加载图书馆数据失败",
                        isSessionExpired = isExpired
                    )
                }
            }
        }
    }

    /**
     * 单本续借
     */
    fun renewSingleBook(book: BorrowedBook) {
        if (!book.canRenew || book.renewCheck.isBlank()) {
            viewModelScope.launch { _toastEvent.emit("该图书暂不支持续借或已超过限制") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isRenewing = true, renewingBarcode = book.barcode) }
            try {
                val result = queryClient.renewBook(book.barcode, book.renewCheck)
                _toastEvent.emit(result.message)
                if (result.success) {
                    // 重新静默刷新当前在借数据
                    val updatedBorrows = queryClient.queryCurrentBorrows()
                    _uiState.update { current ->
                        val newDashboard = current.dashboardData?.copy(currentBorrows = updatedBorrows)
                        current.copy(
                            isRenewing = false,
                            renewingBarcode = null,
                            dashboardData = newDashboard
                        )
                    }
                } else {
                    _uiState.update { it.copy(isRenewing = false, renewingBarcode = null) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRenewing = false, renewingBarcode = null) }
                _toastEvent.emit(e.message ?: "续借操作失败")
            }
        }
    }

    /**
     * 批量一键续借所有可续借图书
     */
    fun renewAllEligibleBooks() {
        val eligibleList = _uiState.value.dashboardData?.currentBorrows?.filter { it.canRenew && it.renewCheck.isNotBlank() } ?: emptyList()
        if (eligibleList.isEmpty()) {
            viewModelScope.launch { _toastEvent.emit("没有可续借的图书") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isRenewing = true) }
            var successCount = 0
            var failCount = 0
            for (book in eligibleList) {
                try {
                    val result = queryClient.renewBook(book.barcode, book.renewCheck)
                    if (result.success) successCount++ else failCount++
                } catch (e: Exception) {
                    failCount++
                }
            }

            // 刷新在借列表
            runCatching {
                val updatedBorrows = queryClient.queryCurrentBorrows()
                _uiState.update { current ->
                    val newDashboard = current.dashboardData?.copy(currentBorrows = updatedBorrows)
                    current.copy(dashboardData = newDashboard)
                }
            }

            _uiState.update { it.copy(isRenewing = false, renewingBarcode = null) }
            _toastEvent.emit("续借完成: 成功 $successCount 本，失败 $failCount 本")
        }
    }

    /**
     * 查询图书详情并弹出 BottomSheet
     */
    fun showBookDetail(marcNo: String) {
        if (marcNo.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBookDetail = true, selectedBookDetail = null) }
            try {
                val detail = queryClient.queryBookDetail(marcNo)
                _uiState.update { it.copy(isLoadingBookDetail = false, selectedBookDetail = detail) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingBookDetail = false) }
                _toastEvent.emit("获取图书详情失败: ${e.message}")
            }
        }
    }

    fun dismissBookDetail() {
        _uiState.update { it.copy(selectedBookDetail = null, isLoadingBookDetail = false) }
    }
}
