package com.xingheyuzhuan.shiguangschedule.ui.campus.grade

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CourseGrade
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet
import com.xingheyuzhuan.shiguangschedule.ui.components.DockSafeBottomPadding
import com.xingheyuzhuan.shiguangschedule.ui.components.NavigationRailWidth
import com.xingheyuzhuan.shiguangschedule.ui.components.isWideScreen
import com.xingheyuzhuan.shiguangschedule.ui.theme.ThemeGradients

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradeQueryScreen(
    navBridge: NavBridge,
    viewModel: GradeQueryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showLoginDialog by remember { mutableStateOf(false) }

    if (uiState.needLogin || showLoginDialog) {
        WbuCampusAuthSheet(
            onDismiss = {
                showLoginDialog = false
                viewModel.onLoginDismissed()
            },
            onLoginSuccess = {
                showLoginDialog = false
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
                            text = stringResource(R.string.title_grade_query),
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
                        IconButton(onClick = { showLoginDialog = true }) {
                            Icon(Icons.Default.LockOpen, contentDescription = stringResource(R.string.a11y_relogin_jwxt))
                        }
                        IconButton(
                            onClick = { viewModel.loadGrades() },
                            enabled = !uiState.isLoading
                        ) {
                            if (uiState.isLoading) {
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = DockSafeBottomPadding + 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. 学期选择标签页
                item {
                    val labelAllSemesters = stringResource(R.string.tab_all_semesters)
                    val semesters = listOf(labelAllSemesters) + uiState.semesterOptions
                    val selectedIndex = if (uiState.selectedSemester.isBlank()) 0 else {
                        val idx = uiState.semesterOptions.indexOf(uiState.selectedSemester)
                        if (idx >= 0) idx + 1 else 0
                    }

                    ScrollableTabRow(
                        selectedTabIndex = selectedIndex,
                        containerColor = Color.Transparent,
                        edgePadding = 16.dp,
                        divider = {}
                    ) {
                        semesters.forEachIndexed { index, sem ->
                            Tab(
                                selected = selectedIndex == index,
                                onClick = {
                                    val target = if (index == 0) "" else sem
                                    viewModel.setSemester(target)
                                },
                                text = {
                                    Text(
                                        text = sem,
                                        fontWeight = if (selectedIndex == index) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 14.sp
                                    )
                                }
                            )
                        }
                    }
                }

                // 2. 统计看板卡片
                item {
                    GradeStatsBanner(
                        gpa = uiState.stats.weightedGpa,
                        score = uiState.stats.weightedScore,
                        earnedCredits = uiState.stats.earnedCredits,
                        failedCount = uiState.stats.failedCount,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                // 3. 筛选与搜索区域
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 搜索框
                        OutlinedTextField(
                            value = uiState.searchKeyword,
                            onValueChange = { viewModel.setSearchKeyword(it) },
                            placeholder = { Text(stringResource(R.string.hint_search_grade), fontSize = 14.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (uiState.searchKeyword.isNotBlank()) {
                                    IconButton(onClick = { viewModel.setSearchKeyword("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.a11y_clear_search))
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

                        // 快捷分类 Chips
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                FilterChip(
                                    selected = uiState.filterPass == "",
                                    onClick = { viewModel.setFilterPass("") },
                                    label = { Text(stringResource(R.string.filter_chip_all)) },
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                            item {
                                FilterChip(
                                    selected = uiState.filterPass == "1",
                                    onClick = { viewModel.setFilterPass("1") },
                                    label = { Text(stringResource(R.string.filter_chip_passed_only)) },
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                            item {
                                FilterChip(
                                    selected = uiState.filterPass == "0",
                                    onClick = { viewModel.setFilterPass("0") },
                                    label = { Text(stringResource(R.string.filter_chip_failed_only)) },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                )
                            }
                            item {
                                FilterChip(
                                    selected = uiState.filterKcxz == "08",
                                    onClick = { viewModel.setFilterKcxz("08") },
                                    label = { Text(stringResource(R.string.filter_chip_major_required)) },
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                            item {
                                FilterChip(
                                    selected = uiState.filterKcxz == "01",
                                    onClick = { viewModel.setFilterKcxz("01") },
                                    label = { Text(stringResource(R.string.filter_chip_major_elective)) },
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                            item {
                                FilterChip(
                                    selected = uiState.filterKcxz == "15",
                                    onClick = { viewModel.setFilterKcxz("15") },
                                    label = { Text(stringResource(R.string.filter_chip_general_required)) },
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }
                }

                // 4. 错误提示
                if (uiState.errorMessage != null && !uiState.isLoading) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = uiState.errorMessage ?: "",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.action_relogin),
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    modifier = Modifier.clickable { showLoginDialog = true }
                                )
                            }
                        }
                    }
                }

                // 5. 课程成绩列表
                if (uiState.filteredGrades.isEmpty() && !uiState.isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (uiState.allGrades.isEmpty()) {
                                    stringResource(R.string.empty_grades_no_record)
                                } else {
                                    stringResource(R.string.empty_grades_no_match)
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    items(uiState.filteredGrades, key = { it.id.ifBlank { "${it.xnxq}_${it.kcbh}_${it.courseName}" } }) { course ->
                        CourseGradeCard(
                            course = course,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 统计看板：采用 2 行 × 2 列网格卡片，彻底解决单行并排在英文下溢出折行的问题
 */
@Composable
private fun GradeStatsBanner(
    gpa: Double,
    score: Double,
    earnedCredits: Double,
    failedCount: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 第一行：加权 GPA 与 加权均分
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    StatItem(label = stringResource(R.string.stat_weighted_gpa), value = String.format("%.2f", gpa), highlight = true)
                }
                StatVerticalDivider()
                Box(modifier = Modifier.weight(1f)) {
                    StatItem(label = stringResource(R.string.stat_weighted_score), value = String.format("%.1f", score))
                }
            }

            // 中间横向分割线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.8.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            )

            // 第二行：已获学分 与 挂科门数
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    StatItem(label = stringResource(R.string.stat_earned_credits), value = String.format("%.1f", earnedCredits))
                }
                StatVerticalDivider()
                Box(modifier = Modifier.weight(1f)) {
                    StatItem(
                        label = stringResource(R.string.stat_failed_count),
                        value = failedCount.toString(),
                        isWarning = failedCount > 0
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    highlight: Boolean = false,
    isWarning: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = when {
                isWarning -> MaterialTheme.colorScheme.error
                highlight -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatVerticalDivider() {
    Box(
        modifier = Modifier
            .width(0.8.dp)
            .height(36.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    )
}

/**
 * 单门课程成绩卡片
 */
@Composable
private fun CourseGradeCard(
    course: CourseGrade,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val isNumeric = course.score.toDoubleOrNull() != null
    val scoreColor = when {
        !course.isPassed -> MaterialTheme.colorScheme.error
        isNumeric && (course.score.toDoubleOrNull() ?: 0.0) >= 90.0 -> Color(0xFF10B981) // 翠绿
        isNumeric && (course.score.toDoubleOrNull() ?: 0.0) >= 80.0 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 课程名称与标签
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = course.courseName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TagBadge(text = course.propertyName)
                        if (course.teacher.isNotBlank()) {
                            Text(
                                text = course.teacher,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 成绩徽章
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = course.score,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = scoreColor
                    )
                    Text(
                        text = stringResource(R.string.format_credit_and_gpa, course.credit.toString(), course.gradePoint.toString()),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            // 展开详细属性
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    DetailRow(label = stringResource(R.string.detail_label_semester), value = course.xnxq)
                    if (course.kcbh.isNotBlank()) DetailRow(label = stringResource(R.string.detail_label_course_code), value = course.kcbh)
                    if (course.examMethod.isNotBlank()) DetailRow(label = stringResource(R.string.detail_label_exam_method), value = course.examMethod)
                    if (course.studyNature.isNotBlank()) DetailRow(label = stringResource(R.string.detail_label_study_nature), value = course.studyNature)
                    DetailRow(label = stringResource(R.string.detail_label_earned_credit), value = "${course.earnedCredit} / ${course.credit}")
                    if (course.isMakeup) DetailRow(label = stringResource(R.string.detail_label_is_makeup), value = stringResource(R.string.text_yes))
                }
            }
        }
    }
}

@Composable
private fun TagBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}
