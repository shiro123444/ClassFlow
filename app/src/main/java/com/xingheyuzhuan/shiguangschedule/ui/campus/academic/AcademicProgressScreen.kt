package com.xingheyuzhuan.shiguangschedule.ui.campus.academic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.AcademicCourse
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.AcademicCourseGroup
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.AcademicProgressData
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.AcademicProgressSummary
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.AcademicStats
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.StudentProfile
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet
import com.xingheyuzhuan.shiguangschedule.ui.components.DockSafeBottomPadding
import com.xingheyuzhuan.shiguangschedule.ui.components.NavigationRailWidth
import com.xingheyuzhuan.shiguangschedule.ui.components.isWideScreen
import com.xingheyuzhuan.shiguangschedule.ui.theme.ThemeGradients

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcademicProgressScreen(
    navBridge: NavBridge,
    viewModel: AcademicProgressViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showLoginSheet by remember { mutableStateOf(false) }

    if (uiState.needLogin || showLoginSheet) {
        WbuCampusAuthSheet(
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
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.title_academic_progress),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navBridge.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.a11y_back))
                        }
                    },
                    actions = {
                        // 展开/折叠全部
                        IconButton(
                            onClick = {
                                if (uiState.expandedGroupIds.isEmpty()) {
                                    viewModel.expandAllGroups()
                                } else {
                                    viewModel.collapseAllGroups()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (uiState.expandedGroupIds.isEmpty()) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                                contentDescription = null
                            )
                        }
                        // 重新登录
                        IconButton(onClick = { showLoginSheet = true }) {
                            Icon(Icons.Default.LockOpen, contentDescription = stringResource(R.string.a11y_relogin_jwxt))
                        }
                        // 刷新
                        IconButton(
                            onClick = { viewModel.loadAcademicProgress(isRefresh = true) },
                            enabled = !uiState.isLoading && !uiState.isRefreshing
                        ) {
                            if (uiState.isLoading || uiState.isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.a11y_refresh))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { innerPadding ->
            if (uiState.isLoading && uiState.data == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.status_connecting_verifying),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val data = uiState.data
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(bottom = DockSafeBottomPadding + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (data != null) {
                        // 1. 学生基本名片
                        item {
                            StudentProfileCard(
                                profile = data.student,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        // 2. 培养方案总完成度卡片
                        item {
                            ProgressSummaryCard(
                                summary = data.summary,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        // 3. 核心学业指标网格 (官方综合 GPA, 专业排名, 加权均分, 累计学分, 挂科/重修)
                        item {
                            AcademicStatsBanner(
                                stats = data.stats,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        // 4. 双重视图分栏 Tab (课程进程按学期 vs 学业完成按性质)
                        item {
                            ViewModeTabs(
                                currentMode = uiState.viewMode,
                                semesterCount = data.semesterGroups.size,
                                natureCount = data.natureGroups.size,
                                onSelectMode = { viewModel.setViewMode(it) },
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        // 5. 搜索与状态过滤栏
                        item {
                            SearchAndFilterBar(
                                searchKeyword = uiState.searchKeyword,
                                onSearchChange = { viewModel.setSearchKeyword(it) },
                                filterStatus = uiState.filterStatus,
                                onFilterStatus = { viewModel.setFilterStatus(it) },
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        // 6. 课程组列表展示
                        val displayedGroups = uiState.displayedGroups
                        if (displayedGroups.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.empty_academic_filtered),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            items(displayedGroups, key = { it.nodeId.ifBlank { it.nodeName } }) { group ->
                                val isExpanded = uiState.expandedGroupIds.contains(group.nodeId)
                                AcademicGroupCard(
                                    group = group,
                                    isExpanded = isExpanded,
                                    onToggle = { viewModel.toggleGroup(group.nodeId) },
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    } else if (uiState.errorMessage != null) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 24.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = uiState.errorMessage ?: stringResource(R.string.empty_academic_data),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        textAlign = TextAlign.Center
                                    )
                                    IconButton(onClick = { viewModel.loadAcademicProgress(isRefresh = true) }) {
                                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.a11y_refresh))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 1. 学生基本名片
 */
@Composable
private fun StudentProfileCard(
    profile: StudentProfile,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 头像首字母
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = profile.name.take(1).ifBlank { "学" },
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 姓名与学号
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = profile.name.ifBlank { stringResource(R.string.label_unknown_number) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (profile.studentId.isNotBlank()) {
                            Text(
                                text = "(${profile.studentId})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    val deptMajor = listOf(profile.college, profile.major).filter { it.isNotBlank() }.joinToString(" · ")
                    if (deptMajor.isNotBlank()) {
                        Text(
                            text = deptMajor,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 班级、年级、预计毕业（支持自动换行，防窄屏或长文本溢出）
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (profile.className.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.format_student_class, profile.className),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                if (profile.gradeYear.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.format_student_grade, profile.gradeYear),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                if (profile.expectedGradDate.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.format_student_grad_date, profile.expectedGradDate),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/**
 * 2. 培养方案总完成度进度卡片
 */
@Composable
private fun ProgressSummaryCard(
    summary: AcademicProgressSummary,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_plan_progress_card),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.desc_plan_progress_calc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${summary.completionPercentage}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 进度条
            LinearProgressIndicator(
                progress = { (summary.completionPercentage / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                strokeCap = StrokeCap.Round,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )

            // 课程拆解分布（支持每项数据自适应换行，彻底防止挤压或溢出）
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.format_total_plan_courses, summary.totalPlanCourses),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.format_completed_courses_count, summary.totalCompletedCourses),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.format_studying_courses_count, summary.totalStudyingCourses),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFF57F17),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.format_uncompleted_courses_count, summary.totalUncompletedCourses),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * 3. 核心学业指标网格 (官方 GPA, 专业排名, 加权均分, 累计学分, 挂科/重修)
 */
@Composable
private fun AcademicStatsBanner(
    stats: AcademicStats,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 第一行：综合 GPA + 专业排名
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = stringResource(R.string.stat_official_gpa),
                    value = stats.gpa?.let { "%.2f".format(it) } ?: "--",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                StatItem(
                    label = stringResource(R.string.stat_major_rank),
                    value = stats.majorRank.ifBlank { "--/--" },
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            // 第二行：加权平均成绩 + 已获总学分 + 挂科/重修
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = stringResource(R.string.stat_avg_score),
                    value = stats.averageScore?.let { "%.1f".format(it) } ?: "--",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                StatItem(
                    label = stringResource(R.string.stat_earned_credits_total),
                    value = "${stats.earnedCredits}",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                StatItem(
                    label = stringResource(R.string.stat_fail_and_retake),
                    value = "${stats.failedCourseCount} / ${stats.retakeCourseCount}",
                    color = if (stats.failedCourseCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 4. 视图分栏 Tab
 */
@Composable
private fun ViewModeTabs(
    currentMode: AcademicViewMode,
    semesterCount: Int,
    natureCount: Int,
    onSelectMode: (AcademicViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = if (currentMode == AcademicViewMode.SEMESTER) 0 else 1

    TabRow(
        selectedTabIndex = selectedIndex,
        containerColor = Color.Transparent,
        divider = {},
        modifier = modifier
    ) {
        Tab(
            selected = selectedIndex == 0,
            onClick = { onSelectMode(AcademicViewMode.SEMESTER) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        text = "${stringResource(R.string.tab_semester_progress)} (${stringResource(R.string.format_semester_tab_count, semesterCount)})",
                        fontWeight = if (selectedIndex == 0) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp
                    )
                }
            }
        )
        Tab(
            selected = selectedIndex == 1,
            onClick = { onSelectMode(AcademicViewMode.NATURE) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Rounded.Category, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        text = "${stringResource(R.string.tab_nature_progress)} (${stringResource(R.string.format_nature_tab_count, natureCount)})",
                        fontWeight = if (selectedIndex == 1) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp
                    )
                }
            }
        )
    }
}

/**
 * 5. 搜索与状态过滤条
 */
@Composable
private fun SearchAndFilterBar(
    searchKeyword: String,
    onSearchChange: (String) -> Unit,
    filterStatus: String,
    onFilterStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = searchKeyword,
            onValueChange = onSearchChange,
            placeholder = { Text(stringResource(R.string.hint_search_academic_course), fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchKeyword.isNotBlank()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
            )
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = filterStatus.isEmpty(),
                    onClick = { onFilterStatus("") },
                    label = { Text(stringResource(R.string.status_chip_all)) },
                    shape = RoundedCornerShape(10.dp)
                )
            }
            item {
                FilterChip(
                    selected = filterStatus == "已修",
                    onClick = { onFilterStatus("已修") },
                    label = { Text(stringResource(R.string.status_chip_completed)) },
                    shape = RoundedCornerShape(10.dp)
                )
            }
            item {
                FilterChip(
                    selected = filterStatus == "修读中",
                    onClick = { onFilterStatus("修读中") },
                    label = { Text(stringResource(R.string.status_chip_studying)) },
                    shape = RoundedCornerShape(10.dp)
                )
            }
            item {
                FilterChip(
                    selected = filterStatus == "未修",
                    onClick = { onFilterStatus("未修") },
                    label = { Text(stringResource(R.string.status_chip_uncompleted)) },
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }
    }
}

/**
 * 6. 分组折叠卡片与课程条目列表
 */
@Composable
private fun AcademicGroupCard(
    group: AcademicCourseGroup,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "groupArrow"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column {
            // 组头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = group.nodeName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.format_group_course_count, group.courseCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.format_group_credits,
                            group.earnedCredits.toString(),
                            group.planCredits.toString()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.rotate(arrowRotation)
                )
            }

            // 课程条目展开列表
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    group.courses.forEach { course ->
                        CourseRowItem(course = course)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

/**
 * 单门课程卡片
 */
@Composable
private fun CourseRowItem(
    course: AcademicCourse
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 首行：课程名与状态 Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = course.courseName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                CourseStatusBadge(course = course)
            }

            // 次行：课程代码 · 性质 · 计划/获得学分 · 成绩/绩点
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (course.courseCode.isNotBlank()) {
                        Text(
                            text = course.courseCode,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val natureText = course.nature.ifBlank { course.category }
                    if (natureText.isNotBlank()) {
                        Text(
                            text = natureText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 学分与成绩
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${course.planCredit} 学分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (course.isCompleted && course.score.isNotBlank()) {
                        Text(
                            text = "${course.score}分",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (course.gpa.isNotBlank()) {
                            Text(
                                text = "(${course.gpa})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 状态 Badge
 */
@Composable
private fun CourseStatusBadge(course: AcademicCourse) {
    val (bgColor, textColor, icon, labelRes) = when {
        course.isCompleted -> {
            Tuple4(
                Color(0xFFE8F5E9),
                Color(0xFF2E7D32),
                Icons.Rounded.CheckCircle,
                R.string.status_completed_tag
            )
        }
        course.isStudying -> {
            Tuple4(
                Color(0xFFFFF8E1),
                Color(0xFFF57F17),
                Icons.Rounded.HourglassTop,
                R.string.status_studying_tag
            )
        }
        else -> {
            Tuple4(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                MaterialTheme.colorScheme.onSurfaceVariant,
                Icons.Rounded.RadioButtonUnchecked,
                R.string.status_uncompleted_tag
            )
        }
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp
            )
        }
    }
}

private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
