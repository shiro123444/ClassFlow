package com.xingheyuzhuan.shiguangschedule.ui.campus.library

import com.xingheyuzhuan.shiguangschedule.R

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.BookDetail
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.BorrowHistoryBook
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.BorrowedBook
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.LibraryDashboardData
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CredentialService
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthTransport
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
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.AccessFailure
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.needsRelogin
import com.xingheyuzhuan.shiguangschedule.ui.components.CampusAccessResult
import com.xingheyuzhuan.shiguangschedule.ui.components.campusAccess
import com.xingheyuzhuan.shiguangschedule.ui.components.shouldOfferWebVpnOnce
import com.xingheyuzhuan.shiguangschedule.ui.components.accessFailureText

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
    /** 是否给用户一个「改用 WebVPN」按钮（单次生效，不翻转全局开关）。 */
    val offerWebVpnOnce: Boolean = false,
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

    /**
     * 单次改用 WebVPN：只对下一次加载生效，**不写全局开关**（用户点一次按钮走一次）。
     */
    private var useWebVpnOnce = false

    /** 最近一次实际使用的通道：点过「改用 WebVPN」后，续借/详情等后续请求要沿用同一条通道。 */
    private var lastUseVpn: Boolean? = null

    private fun queryClient(): WbuQueryClient =
        WbuQueryClient(
            getApplication(),
            useVpn = lastUseVpn ?: (WbuSyncEngine.getSavedUseVpn(getApplication()) ?: false)
        )

    /** 「改用 WebVPN」按钮：本次访问改走校外通道。 */
    fun retryWithWebVpnOnce() {
        useWebVpnOnce = true
        loadLibraryData(isRefresh = true)
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
        val once = useWebVpnOnce
        useWebVpnOnce = false
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = !isRefresh,
                    isRefreshing = isRefresh,
                    errorMessage = null,
                    isSessionExpired = false,
                    offerWebVpnOnce = false
                )
            }

            // 统一流水线：本地无凭据 / 登录态失效时先静默重建（缺输入就地弹小窗），
            // 只有真的需要用户介入才回落到登录 Sheet；网络不通则给内联重试 + 改用 WebVPN。
            val result = campusAccess(
                context = getApplication(),
                service = CredentialService.LIBRARY,
                flowTag = "LIBRARY",
                useWebVpnOnce = once
            ) { useVpn ->
                WbuQueryClient(getApplication(), useVpn = useVpn).queryLibraryDashboard()
            }

            when (result) {
                is CampusAccessResult.Ok -> {
                    lastUseVpn = result.useVpn
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            dashboardData = result.value,
                            errorMessage = null,
                            isSessionExpired = false,
                            offerWebVpnOnce = false
                        )
                    }
                }

                is CampusAccessResult.Failed -> {
                    lastUseVpn = result.useVpn
                    val failureText = accessFailureText(getApplication(), result.failure)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            // 用户主动取消小窗时不显示任何错误文案（accessFailureText 返回 null）
                            errorMessage = failureText,
                            // 取消 = 「补输入没补上」，回落到完整登录 Sheet；而不是报错
                            isSessionExpired = result.failure.needsRelogin ||
                                result.failure is AccessFailure.Cancelled,
                            offerWebVpnOnce = result.failure.shouldOfferWebVpnOnce(result.useVpn)
                        )
                    }
                }
            }
        }
    }

    /**
     * 单本续借
     */
    fun renewSingleBook(book: BorrowedBook) {
        if (!book.canRenew || book.renewCheck.isBlank()) {
            viewModelScope.launch { _toastEvent.emit(getApplication<Application>().getString(R.string.err_book_renew_not_supported)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isRenewing = true, renewingBarcode = book.barcode) }
            try {
                val result = queryClient().renewBook(book.barcode, book.renewCheck)
                _toastEvent.emit(result.message)
                if (result.success) {
                    // 重新静默刷新当前在借数据
                    val updatedBorrows = queryClient().queryCurrentBorrows()
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
                _toastEvent.emit(
                    accessFailureText(getApplication(), e)
                        ?: getApplication<Application>().getString(R.string.err_renew_failed)
                )
            }
        }
    }

    /**
     * 批量一键续借所有可续借图书
     */
    fun renewAllEligibleBooks() {
        val eligibleList = _uiState.value.dashboardData?.currentBorrows?.filter { it.canRenew && it.renewCheck.isNotBlank() } ?: emptyList()
        if (eligibleList.isEmpty()) {
            viewModelScope.launch { _toastEvent.emit(getApplication<Application>().getString(R.string.toast_no_renewable_books)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isRenewing = true) }
            var successCount = 0
            var failCount = 0
            for (book in eligibleList) {
                try {
                    val result = queryClient().renewBook(book.barcode, book.renewCheck)
                    if (result.success) successCount++ else failCount++
                } catch (e: Exception) {
                    failCount++
                }
            }

            // 刷新在借列表
            runCatching {
                val updatedBorrows = queryClient().queryCurrentBorrows()
                _uiState.update { current ->
                    val newDashboard = current.dashboardData?.copy(currentBorrows = updatedBorrows)
                    current.copy(dashboardData = newDashboard)
                }
            }

            _uiState.update { it.copy(isRenewing = false, renewingBarcode = null) }
            _toastEvent.emit(getApplication<Application>().getString(R.string.format_renew_result, successCount, failCount))
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
                val detail = queryClient().queryBookDetail(marcNo)
                _uiState.update { it.copy(isLoadingBookDetail = false, selectedBookDetail = detail) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingBookDetail = false) }
                _toastEvent.emit(
                    getApplication<Application>().getString(
                        R.string.format_err_book_detail,
                        accessFailureText(getApplication(), e) ?: ""
                    )
                )
            }
        }
    }

    fun dismissBookDetail() {
        _uiState.update { it.copy(selectedBookDetail = null, isLoadingBookDetail = false) }
    }
}
