package com.xingheyuzhuan.shiguangschedule.ui.campus.courseselection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.KkxFrom
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.RetakeCourse
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.SelectedCourse
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.SelectionBatch
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.TeachingClass
import com.xingheyuzhuan.shiguangschedule.data.network.wbu.WbuAuthMode
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet
import com.xingheyuzhuan.shiguangschedule.ui.components.NavigationRailWidth
import com.xingheyuzhuan.shiguangschedule.ui.components.isWideScreen
import com.xingheyuzhuan.shiguangschedule.ui.theme.ThemeGradients
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CourseSelectionScreen(
    navBridge: NavBridge,
    viewModel: CourseSelectionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showLoginSheet by remember { mutableStateOf(false) }

    // 首次使用：免责声明门控
    if (!uiState.disclaimerAccepted) {
        CourseSelectionDisclaimerScreen(
            onAccept = { viewModel.acceptDisclaimer() },
            onDecline = { navBridge.popBackStack() }
        )
        return
    }

    if (uiState.needLogin || showLoginSheet) {
        WbuCampusAuthSheet(
            onNavigateToAccount = { navBridge.navigate(Destination.CredentialManagement) },
            forceDirectCampus = true,
            defaultAuthMode = WbuAuthMode.JYXT_LEGACY,
            title = stringResource(R.string.title_login_course_selection),
            onDismiss = {
                showLoginSheet = false
                viewModel.onLoginDismissed()
            },
            onLoginSuccess = {
                showLoginSheet = false
                viewModel.onLoginSuccess()
            }
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.opMessage) {
        val msg = uiState.opMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeMessage()
        }
    }

    // 教学班详情
    uiState.detailClass?.let { tc ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissDetail() },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            TeachingClassDetailContent(
                teachingClass = tc,
                hideQuota = uiState.hideQuota,
                onCancelWaitlist = {
                    viewModel.dismissDetail()
                    viewModel.requestClassAction(tc, ClassAction.CANCEL_WAITLIST)
                },
                showCancelWaitlist = uiState.activeBatch?.allowWaitlist == true && tc.isFull && tc.isSelected
            )
        }
    }

    // 子教学班选择
    uiState.childPick?.let { pick ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissChildPick() },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            ChildClassPickerContent(
                request = pick,
                onPick = { childId -> viewModel.selectChildClass(childId) }
            )
        }
    }

    // 二次确认
    uiState.confirm?.let { request ->
        val isDrop = request.action == ClassAction.DROP ||
                request.action == ClassAction.CANCEL_ENROLL ||
                request.action == ClassAction.CANCEL_WAITLIST
        AlertDialog(
            onDismissRequest = { viewModel.dismissConfirm() },
            title = {
                Text(
                    stringResource(
                        if (isDrop) R.string.cs_confirm_drop_title else R.string.cs_confirm_select_title
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (isDrop) R.string.cs_confirm_drop_msg else R.string.cs_confirm_select_msg,
                        request.teachingClass.kcmc
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPending() }) {
                    Text(stringResource(R.string.cs_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissConfirm() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    val backgroundBrush = ThemeGradients.backgroundGradient()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .padding(start = if (isWideScreen) NavigationRailWidth else 0.dp)
            .statusBarsPadding()
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        // 暗号手势：3 秒内连续点击标题 7 次，切换 Mock 测试模式
                        var tapTimestamps by remember { mutableStateOf(emptyList<Long>()) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                val now = System.currentTimeMillis()
                                val recent = tapTimestamps.filter { now - it < 3000 } + now
                                tapTimestamps = recent
                                if (recent.size >= 7) {
                                    tapTimestamps = emptyList()
                                    viewModel.toggleMockMode()
                                }
                            }
                        ) {
                            Text(stringResource(R.string.title_course_selection))
                            if (uiState.mockEnabled) {
                                Spacer(Modifier.width(8.dp))
                                CsTag(
                                    text = stringResource(R.string.cs_mock_badge),
                                    container = Color(0xFFFFF3E0),
                                    content = Color(0xFFE65100)
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navBridge.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.a11y_back)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            showLoginSheet = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = stringResource(R.string.a11y_relogin_jwxt)
                            )
                        }
                        IconButton(onClick = { viewModel.loadInit(isRefresh = true) }) {
                            if (uiState.isLoading || uiState.isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.a11y_refresh)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (uiState.mockEnabled) {
                    MockBanner(onExit = { viewModel.toggleMockMode() })
                }
                CourseSelectionContent(
                    uiState = uiState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onSelectBatch = viewModel::selectBatch,
                    onTabChange = viewModel::setTabIndex,
                    onKeywordChange = viewModel::setKeyword,
                    onToggleFilterFull = viewModel::toggleFilterFull,
                    onToggleFilterConflict = viewModel::toggleFilterConflict,
                    onClassClick = viewModel::showDetail,
                    onClassAction = viewModel::requestClassAction,
                    onRetakeCourseClick = viewModel::pickRetakeCourse,
                    onRetakeAction = { tc, course ->
                        viewModel.confirmRetake(tc, course, drop = tc.isSelected)
                    },
                    onBackToRetakeList = { viewModel.clearRetakeCourse() },
                    onReload = { viewModel.loadInit(isRefresh = false) },
                    onLoadSelected = { viewModel.loadSelectedCourses() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CourseSelectionContent(
    uiState: CourseSelectionUiState,
    modifier: Modifier = Modifier,
    onSelectBatch: (SelectionBatch) -> Unit,
    onTabChange: (Int) -> Unit,
    onKeywordChange: (String) -> Unit,
    onToggleFilterFull: () -> Unit,
    onToggleFilterConflict: () -> Unit,
    onClassClick: (TeachingClass) -> Unit,
    onClassAction: (TeachingClass, ClassAction) -> Unit,
    onRetakeCourseClick: (RetakeCourse) -> Unit,
    onRetakeAction: (TeachingClass, RetakeCourse) -> Unit,
    onBackToRetakeList: () -> Unit,
    onReload: () -> Unit,
    onLoadSelected: () -> Unit
) {
    val loadingHint = if (uiState.mockEnabled) null else stringResource(R.string.cs_loading_campus_hint)

    when {
        uiState.isLoading && uiState.batches.isEmpty() -> LoadingState(modifier, loadingHint)

        // 批次为空时绝不能渲染 ScrollableTabRow：无 Tab 时它以 selectedTabIndex 取 tabPositions 会越界崩溃
        uiState.batches.isEmpty() -> {
            val message = uiState.errorMessage
            if (message != null) {
                ErrorState(message = message, onRetry = onReload, modifier = modifier)
            } else {
                EmptyState(
                    modifier = modifier,
                    message = stringResource(R.string.cs_empty_no_batch)
                )
            }
        }

        else -> Column(modifier = modifier) {
            // 学生 / 学期
            if (uiState.studentName.isNotBlank() || uiState.xkxnxq.isNotBlank()) {
                Text(
                    text = listOf(uiState.studentName, uiState.studentId, uiState.xkxnxq)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            // 批次 Tab
            val activeIndex = uiState.batches.indexOfFirst { it.pcid == uiState.activeBatch?.pcid }
                .coerceAtLeast(0)
            ScrollableTabRow(
                selectedTabIndex = activeIndex,
                containerColor = Color.Transparent,
                edgePadding = 12.dp,
                divider = {}
            ) {
                uiState.batches.forEachIndexed { index, batch ->
                    Tab(
                        selected = index == activeIndex,
                        enabled = batch.isSupported,
                        onClick = { onSelectBatch(batch) },
                        text = {
                            Text(
                                text = if (batch.isSupported) batch.name else "${batch.name}（${stringResource(R.string.cs_unsupported_batch)}）",
                                maxLines = 1
                            )
                        }
                    )
                }
            }

            // 可选课程 / 已选课程
            TabRow(
                selectedTabIndex = uiState.tabIndex,
                containerColor = Color.Transparent,
                divider = {}
            ) {
                Tab(
                    selected = uiState.tabIndex == 0,
                    onClick = { onTabChange(0) },
                    text = { Text(stringResource(R.string.cs_tab_classes)) }
                )
                Tab(
                    selected = uiState.tabIndex == 1,
                    onClick = { onTabChange(1) },
                    text = { Text(stringResource(R.string.cs_tab_selected)) }
                )
            }

            if (uiState.tabIndex == 1) {
                SelectedCoursesList(
                    uiState = uiState,
                    onReload = onLoadSelected
                )
                return@Column
            }

            val batch = uiState.activeBatch
            val isRetake = batch?.from == KkxFrom.CXXK

            // 批次温馨提醒
            if (!batch?.promptMessage.isNullOrBlank()) {
                Text(
                    text = batch.promptMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            val retakeCourse = uiState.activeRetakeCourse
            val showClassList = !isRetake || retakeCourse != null

            if (showClassList) {
                FilterBar(
                    keyword = uiState.keyword,
                    filterFull = uiState.filterFull,
                    filterConflict = uiState.filterConflict,
                    onKeywordChange = onKeywordChange,
                    onToggleFilterFull = onToggleFilterFull,
                    onToggleFilterConflict = onToggleFilterConflict
                )
            }

            when {
                uiState.isLoading -> LoadingState(Modifier.fillMaxSize(), loadingHint)

                uiState.errorMessage != null && uiState.allClasses.isEmpty() &&
                        uiState.retakeCourses.isEmpty() -> {
                    val message = uiState.errorMessage
                    ErrorState(
                        message = message,
                        onRetry = onReload,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                isRetake && retakeCourse == null -> {
                    if (uiState.retakeCourses.isEmpty()) {
                        EmptyState(Modifier.fillMaxSize(), stringResource(R.string.cs_empty_classes))
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                Text(
                                    text = stringResource(R.string.cs_retake_pick_course),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            items(uiState.retakeCourses) { course ->
                                RetakeCourseCard(course = course, onClick = { onRetakeCourseClick(course) })
                            }
                        }
                    }
                }

                uiState.displayedClasses.isEmpty() -> EmptyState(
                    Modifier.fillMaxSize(),
                    stringResource(R.string.cs_empty_classes)
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isRetake && retakeCourse != null) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onBackToRetakeList() }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = retakeCourse.kcmc,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    items(uiState.displayedClasses, key = { it.jxbid }) { tc ->
                        TeachingClassCard(
                            teachingClass = tc,
                            hideQuota = uiState.hideQuota,
                            batch = batch,
                            onClick = { onClassClick(tc) },
                            onAction = {
                                if (isRetake && retakeCourse != null) {
                                    onRetakeAction(tc, retakeCourse)
                                } else if (batch != null) {
                                    val action = classActionFor(tc, batch)
                                    onClassAction(tc, action)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 根据批次与教学班状态推导操作按钮
 */
private fun classActionFor(teachingClass: TeachingClass, batch: SelectionBatch): ClassAction = when {
    teachingClass.isSelected && batch.isLottery -> ClassAction.CANCEL_ENROLL
    teachingClass.isSelected -> ClassAction.DROP
    teachingClass.isFull && batch.allowWaitlist -> ClassAction.WAITLIST
    batch.isLottery -> ClassAction.ENROLL
    else -> ClassAction.SELECT
}

@Composable
private fun actionLabel(action: ClassAction): String = when (action) {
    ClassAction.SELECT -> stringResource(R.string.cs_action_select)
    ClassAction.DROP -> stringResource(R.string.cs_action_drop)
    ClassAction.ENROLL -> stringResource(R.string.cs_action_enroll)
    ClassAction.CANCEL_ENROLL -> stringResource(R.string.cs_action_cancel_enroll)
    ClassAction.WAITLIST -> stringResource(R.string.cs_action_waitlist)
    ClassAction.CANCEL_WAITLIST -> stringResource(R.string.cs_action_cancel_waitlist)
}

/**
 * 操作按钮配色：选课/报名=主题色，候补=tertiary，退课类=error（危险操作）
 */
@Composable
private fun actionButtonColors(action: ClassAction): ButtonColors = when (action) {
    ClassAction.SELECT, ClassAction.ENROLL -> ButtonDefaults.buttonColors()
    ClassAction.WAITLIST -> ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary
    )
    ClassAction.DROP, ClassAction.CANCEL_ENROLL, ClassAction.CANCEL_WAITLIST ->
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeachingClassCard(
    teachingClass: TeachingClass,
    hideQuota: Boolean,
    batch: SelectionBatch?,
    onClick: () -> Unit,
    onAction: () -> Unit
) {
    val action = batch?.let { classActionFor(teachingClass, it) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = teachingClass.kcmc,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                ExamTag(teachingClass.ksxs)
            }

            val subtitle = listOf(teachingClass.jxbmc, teachingClass.jxbbh)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val meta = listOf(teachingClass.teacher, teachingClass.jxms, teachingClass.campusName)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 标签：学分 / 人数额度 / 人满 / 冲突 / 已选
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (teachingClass.xf.isNotBlank() && teachingClass.xf != "0") {
                    CsTag(
                        text = stringResource(R.string.format_credits_val, teachingClass.xf),
                        container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        content = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                if (!hideQuota && teachingClass.quotaText.isNotBlank()) {
                    val (bg, fg) = when {
                        teachingClass.isFull -> Color(0xFFFFEBEE) to Color(0xFFC62828)
                        teachingClass.selectedCount >= 0 && teachingClass.capacity > 0 &&
                                teachingClass.selectedCount * 5 >= teachingClass.capacity * 4 ->
                            Color(0xFFFFF8E1) to Color(0xFFF57F17)
                        else -> Color(0xFFE8F5E9) to Color(0xFF2E7D32)
                    }
                    CsTag(text = teachingClass.quotaText, container = bg, content = fg)
                }
                if (teachingClass.isFull) {
                    CsTag(
                        text = stringResource(R.string.cs_tag_full),
                        container = Color(0xFFFFEBEE),
                        content = Color(0xFFC62828)
                    )
                }
                if (teachingClass.isSelected) {
                    val waitlisted = batch?.allowWaitlist == true && teachingClass.isFull
                    CsTag(
                        text = stringResource(
                            when {
                                batch?.isLottery == true -> R.string.cs_action_cancel_enroll
                                waitlisted -> R.string.cs_tag_waitlist
                                else -> R.string.cs_tag_selected
                            }
                        ),
                        container = Color(0xFFE8F5E9),
                        content = Color(0xFF2E7D32)
                    )
                }
                if (teachingClass.conflict == 1) {
                    CsTag(
                        text = stringResource(R.string.cs_tag_conflict),
                        container = Color(0xFFFFF3E0),
                        content = Color(0xFFE65100)
                    )
                } else if (teachingClass.conflict == 2) {
                    CsTag(
                        text = stringResource(R.string.cs_tag_partial_conflict),
                        container = Color(0xFFFFF3E0),
                        content = Color(0xFFE65100)
                    )
                }
            }

            // 上课时间：直接展示，不折叠
            if (teachingClass.classTime.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    teachingClass.classTime.split(";").filter { it.isNotBlank() }.forEach { line ->
                        Text(
                            text = line.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            if (action != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onAction,
                        colors = actionButtonColors(action)
                    ) {
                        Text(actionLabel(action))
                    }
                }
            }
        }
    }
}

@Composable
private fun MockBanner(onExit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF3E0))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.cs_mock_banner),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFE65100),
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onExit) {
            Text(
                text = stringResource(R.string.cs_mock_exit),
                color = Color(0xFFE65100)
            )
        }
    }
}

@Composable
private fun CsTag(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp
        )
    }
}

/**
 * 考核方式单字标签：[试] 考试 / [查] 考查
 */
@Composable
private fun ExamTag(rawKsxs: String) {
    val tag = when {
        rawKsxs.contains("试") -> "试"
        rawKsxs.contains("查") || rawKsxs.contains("察") -> "查"
        else -> ""
    }
    if (tag.isBlank()) return
    val isExam = tag == "试"
    CsTag(
        text = tag,
        container = if (isExam) Color(0xFFE3F2FD) else Color(0xFFF3E5F5),
        content = if (isExam) Color(0xFF1565C0) else Color(0xFF7B1FA2)
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(
    keyword: String,
    filterFull: Boolean,
    filterConflict: Boolean,
    onKeywordChange: (String) -> Unit,
    onToggleFilterFull: () -> Unit,
    onToggleFilterConflict: () -> Unit
) {
    Column {
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeywordChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text(stringResource(R.string.cs_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (keyword.isNotBlank()) {
                    IconButton(onClick = { onKeywordChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors()
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = filterFull,
                    onClick = onToggleFilterFull,
                    label = { Text(stringResource(R.string.cs_filter_full)) },
                    shape = RoundedCornerShape(10.dp)
                )
            }
            item {
                FilterChip(
                    selected = filterConflict,
                    onClick = onToggleFilterConflict,
                    label = { Text(stringResource(R.string.cs_filter_conflict)) },
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }
    }
}

@Composable
private fun SelectedCoursesList(
    uiState: CourseSelectionUiState,
    onReload: () -> Unit
) {
    if (uiState.isLoadingSelected && uiState.selectedCourses.isEmpty()) {
        LoadingState(
            Modifier.fillMaxSize(),
            if (uiState.mockEnabled) null else stringResource(R.string.cs_loading_campus_hint)
        )
        return
    }
    if (uiState.selectedCourses.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SelectedListHeader(onReload)
            EmptyState(Modifier.fillMaxSize(), stringResource(R.string.cs_empty_selected))
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { SelectedListHeader(onReload) }
        items(uiState.selectedCourses, key = { it.id }) { course ->
            SelectedCourseCard(course)
        }
    }
}

@Composable
private fun SelectedListHeader(onReload: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        IconButton(onClick = onReload) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.a11y_refresh)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedCourseCard(course: SelectedCourse) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = course.kcmc,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            val subtitle = listOf(course.jxbmc, course.jxbbh).filter { it.isNotBlank() }.joinToString(" ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val meta = listOf(course.teacher, course.xnxq, course.xkfs)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (course.xf.isNotBlank()) {
                    CsTag(
                        text = stringResource(R.string.format_credits_val, course.xf),
                        container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        content = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            if (course.classTime.isNotBlank()) {
                course.classTime.split(";").filter { it.isNotBlank() }.forEach { line ->
                    Text(
                        text = line.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun RetakeCourseCard(course: RetakeCourse, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = course.kcmc,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            val meta = listOf(course.xnxq, course.xf.takeIf { it.isNotBlank() }?.let {
                stringResource(R.string.format_credits_val, it)
            }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TeachingClassDetailContent(
    teachingClass: TeachingClass,
    hideQuota: Boolean,
    showCancelWaitlist: Boolean,
    onCancelWaitlist: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = teachingClass.kcmc,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        DetailSection(title = stringResource(R.string.cs_detail_title)) {
            DetailRow(stringResource(R.string.field_course_code), teachingClass.kcbh.ifBlank { "--" })
            DetailRow(stringResource(R.string.field_plan_credit), teachingClass.xf.ifBlank { "--" })
            if (!hideQuota) {
                DetailRow(
                    stringResource(R.string.cs_detail_quota),
                    teachingClass.quotaText.ifBlank { "--" }
                )
            }
            DetailRow(stringResource(R.string.cs_detail_campus), teachingClass.campusName.ifBlank { "--" })
            DetailRow(stringResource(R.string.cs_detail_jxms), teachingClass.jxms.ifBlank { "--" })
            DetailRow(stringResource(R.string.field_exam_type), teachingClass.ksxs.ifBlank { "--" })
            DetailRow(stringResource(R.string.cs_detail_class_time), teachingClass.classTime.ifBlank { "--" })
            DetailRow(stringResource(R.string.cs_detail_jxbzc), teachingClass.jxbzc.ifBlank { "--" })
            DetailRow(stringResource(R.string.cs_detail_bz), teachingClass.bz.ifBlank { "--" })
        }
        if (showCancelWaitlist) {
            Button(onClick = onCancelWaitlist, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cs_action_cancel_waitlist))
            }
        }
    }
}

@Composable
private fun ChildClassPickerContent(
    request: ChildPickRequest,
    onPick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = request.teachingClass.kcmc,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        request.childIds.forEachIndexed { index, childId ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(childId) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Text(
                    text = "${request.teachingClass.kcmc} (${index + 1})\n$childId",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier, hint: String? = null) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator()
            if (!hint.isNullOrBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, message: String) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp)
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Button(onClick = onRetry) {
                Text(stringResource(R.string.action_refresh))
            }
        }
    }
}

/**
 * 首次使用免责声明 / 警告屏：5 秒倒计时后方可同意
 */
@Composable
private fun CourseSelectionDisclaimerScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    // 返回键 / 返回手势等同「不同意」，直接退出该页面
    BackHandler(enabled = true) { onDecline() }

    var countdown by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    val backgroundBrush = ThemeGradients.backgroundGradient()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 顶部醒目警告三角
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = Color(0xFFD32F2F),
                modifier = Modifier
                    .padding(top = 16.dp)
                    .size(72.dp)
            )
            Text(
                text = stringResource(R.string.cs_disclaimer_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.cs_disclaimer_untested),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFD32F2F),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.cs_disclaimer_campus_only),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFD32F2F),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.cs_disclaimer_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    onClick = onDecline,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.cs_disclaimer_disagree))
                }
                Button(
                    onClick = onAccept,
                    enabled = countdown <= 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (countdown > 0) {
                            stringResource(R.string.cs_disclaimer_agree_countdown, countdown)
                        } else {
                            stringResource(R.string.cs_disclaimer_agree)
                        }
                    )
                }
            }
            Spacer(Modifier.height(0.dp))
        }
    }
}
