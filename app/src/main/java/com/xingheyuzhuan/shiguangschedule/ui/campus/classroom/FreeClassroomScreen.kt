package com.xingheyuzhuan.shiguangschedule.ui.campus.classroom

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CAMPUS_HGH_UUID
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.CAMPUS_MYH_ID
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.FreeClassroom
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.JSLX_DICT
import com.xingheyuzhuan.shiguangschedule.ui.campus.components.WbuCampusAuthSheet
import com.xingheyuzhuan.shiguangschedule.ui.components.DockSafeBottomPadding
import com.xingheyuzhuan.shiguangschedule.ui.components.NavigationRailWidth
import com.xingheyuzhuan.shiguangschedule.ui.components.isWideScreen
import com.xingheyuzhuan.shiguangschedule.ui.theme.ThemeGradients
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeClassroomScreen(
    navBridge: NavBridge,
    viewModel: FreeClassroomViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showLoginDialog by remember { mutableStateOf(false) }

    val dayNames = listOf(
        stringResource(R.string.header_day_monday),
        stringResource(R.string.header_day_tuesday),
        stringResource(R.string.header_day_wednesday),
        stringResource(R.string.header_day_thursday),
        stringResource(R.string.header_day_friday),
        stringResource(R.string.header_day_saturday),
        stringResource(R.string.header_day_sunday)
    )

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

    // 单个教室详情及探测抽屉
    if (uiState.detailClassroom != null) {
        val detailRoom = uiState.detailClassroom!!
        val cacheKey = "${detailRoom.cleanRoomName}_week_${uiState.detailWeek}"
        val cachedSchedule = uiState.probeScheduleCache[cacheKey]

        ClassroomDetailBottomSheet(
            classroom = detailRoom,
            week = uiState.detailWeek,
            schedule = cachedSchedule,
            isProbing = uiState.isProbing,
            probeProgress = uiState.probeProgress,
            probeError = uiState.probeError,
            onWeekChange = { viewModel.setDetailWeek(it) },
            onProbeClick = { forceRefresh ->
                viewModel.probeScheduleForCurrentRoom(forceRefresh = forceRefresh)
            },
            onDismiss = { viewModel.selectClassroomForDetail(null) }
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
                            text = stringResource(R.string.title_free_classroom_query),
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
                            onClick = { viewModel.loadFreeClassrooms() },
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
                // 1. 校区选择 Tab（记住上次浏览选项）
                item {
                    val campusTabs = listOf(
                        Pair(stringResource(R.string.tab_campus_hgh), CAMPUS_HGH_UUID),
                        Pair(stringResource(R.string.tab_campus_myh), CAMPUS_MYH_ID),
                        Pair(stringResource(R.string.tab_campus_all), "")
                    )

                    val selectedIndex = when (uiState.selectedCampusId) {
                        CAMPUS_HGH_UUID, "1", "HGH" -> 0
                        CAMPUS_MYH_ID, "MYH" -> 1
                        else -> 2
                    }

                    PrimaryTabRow(
                        selectedTabIndex = selectedIndex,
                        containerColor = Color.Transparent,
                        divider = {}
                    ) {
                        campusTabs.forEachIndexed { idx, (label, id) ->
                            Tab(
                                selected = selectedIndex == idx,
                                onClick = { viewModel.setCampus(id) },
                                text = {
                                    Text(
                                        text = label,
                                        fontWeight = if (selectedIndex == idx) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 14.sp
                                    )
                                }
                            )
                        }
                    }
                }

                // 2. 完整筛选控制面板
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // (1) 搜索框
                            OutlinedTextField(
                                value = uiState.searchRoomName,
                                onValueChange = {
                                    viewModel.setSearchRoomName(it)
                                    viewModel.loadFreeClassrooms()
                                },
                                placeholder = { Text(stringResource(R.string.hint_search_room), fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (uiState.searchRoomName.isNotBlank()) {
                                        IconButton(onClick = {
                                            viewModel.setSearchRoomName("")
                                            viewModel.loadFreeClassrooms()
                                        }) {
                                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.a11y_clear_search))
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                                )
                            )

                            // (2) 教学楼与教室类型并排下拉框
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    BuildingDropdownSelector(
                                        buildings = uiState.buildingList,
                                        selectedCode = uiState.selectedBuildingCode,
                                        onSelect = { viewModel.setBuilding(it) }
                                    )
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    RoomTypeDropdownSelector(
                                        selectedCode = uiState.selectedRoomType,
                                        onSelect = { viewModel.setRoomType(it) }
                                    )
                                }
                            }

                            // (3) 查询模式切换：按节次 vs 按时间段
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = uiState.queryMode == FreeClassroomQueryMode.SECTION,
                                    onClick = { viewModel.setQueryMode(FreeClassroomQueryMode.SECTION) },
                                    label = { Text(stringResource(R.string.mode_by_section), fontSize = 12.sp) },
                                    shape = RoundedCornerShape(10.dp)
                                )
                                FilterChip(
                                    selected = uiState.queryMode == FreeClassroomQueryMode.TIME,
                                    onClick = { viewModel.setQueryMode(FreeClassroomQueryMode.TIME) },
                                    label = { Text(stringResource(R.string.mode_by_time), fontSize = 12.sp) },
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }

                            // (4) 周次选择：多选、全选或全不选（点击即可取消勾选，不加额外的不限按钮）
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.label_select_week),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(
                                            onClick = { viewModel.selectAllWeeks() },
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text(stringResource(R.string.action_select_all_short), fontSize = 11.sp)
                                        }
                                        if (uiState.selectedWeeks.isNotEmpty()) {
                                            TextButton(
                                                onClick = { viewModel.clearWeeks() },
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text(stringResource(R.string.action_clear_filter), fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                                val weekListState = rememberLazyListState()
                                LaunchedEffect(uiState.selectedWeeks) {
                                    val firstSelected = uiState.selectedWeeks.minOrNull()
                                    if (firstSelected != null) {
                                        val targetIndex = (firstSelected - 3).coerceAtLeast(0)
                                        weekListState.animateScrollToItem(targetIndex)
                                    }
                                }
                                LazyRow(
                                    state = weekListState,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(uiState.totalWeeks) { i ->
                                        val weekNum = i + 1
                                        val isSelected = uiState.selectedWeeks.contains(weekNum)
                                        val isCurrent = weekNum == uiState.currentWeekNumber
                                        val labelText = if (isCurrent) {
                                            stringResource(R.string.format_current_week_tag, weekNum)
                                        } else {
                                            stringResource(R.string.format_week_number, weekNum)
                                        }
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { viewModel.toggleWeek(weekNum) },
                                            label = { Text(labelText, fontSize = 12.sp) },
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                    }
                                }
                            }

                            // (5) 星期选择（支持多选、全选和不选；当只选择一个周时，后面显示日期如“周一 11.20”）
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.label_select_day_of_week),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(
                                            onClick = {
                                                val today = LocalDate.now().dayOfWeek.value.coerceIn(1, 7)
                                                viewModel.setDaysPreset(setOf(today))
                                            },
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text(stringResource(R.string.preset_days_today), fontSize = 11.sp)
                                        }
                                        TextButton(
                                            onClick = { viewModel.setDaysPreset(setOf(1, 2, 3, 4, 5)) },
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text(stringResource(R.string.preset_days_workdays), fontSize = 11.sp)
                                        }
                                        TextButton(
                                            onClick = { viewModel.setDaysPreset(setOf(6, 7)) },
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text(stringResource(R.string.preset_days_weekends), fontSize = 11.sp)
                                        }
                                        TextButton(
                                            onClick = { viewModel.selectAllDays() },
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text(stringResource(R.string.action_select_all_short), fontSize = 11.sp)
                                        }
                                        if (uiState.selectedDays.isNotEmpty()) {
                                            TextButton(
                                                onClick = { viewModel.clearDays() },
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text(stringResource(R.string.action_clear_filter), fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(7) { i ->
                                        val day = i + 1
                                        val isSelected = uiState.selectedDays.contains(day)
                                        val dateStr = uiState.singleWeekDates?.get(day)
                                        val chipLabel = if (dateStr != null) {
                                            "${dayNames[i]} $dateStr"
                                        } else {
                                            dayNames[i]
                                        }
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { viewModel.toggleDayOfWeek(day) },
                                            label = { Text(chipLabel, fontSize = 12.sp) },
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                    }
                                }
                            }

                            // (6) 动态时段/节次筛选面板
                            if (uiState.queryMode == FreeClassroomQueryMode.SECTION) {
                                // 节次手动多选（1 ~ 13 节，点击可取消勾选，全部取消即不筛选节次）
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.label_select_sections),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(
                                                onClick = { viewModel.selectAllSections() },
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text(stringResource(R.string.action_select_all_short), fontSize = 11.sp)
                                            }
                                            if (uiState.selectedSections.isNotEmpty()) {
                                                TextButton(
                                                    onClick = { viewModel.clearSections() },
                                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text(stringResource(R.string.action_clear_filter), fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(13) { i ->
                                            val sectionNum = i + 1
                                            val isSelected = uiState.selectedSections.contains(sectionNum)
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = { viewModel.toggleSection(sectionNum) },
                                                label = { Text(stringResource(R.string.format_section_short, sectionNum), fontSize = 12.sp) },
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                // 按时间段筛选模式 (HH:mm)
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = stringResource(R.string.label_free_time_range),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = uiState.beginTime,
                                            onValueChange = { viewModel.setTimeRange(it, uiState.endTime) },
                                            label = { Text("开始时间", fontSize = 11.sp) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                                            )
                                        )
                                        Text(
                                            text = stringResource(R.string.label_time_to),
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        OutlinedTextField(
                                            value = uiState.endTime,
                                            onValueChange = { viewModel.setTimeRange(uiState.beginTime, it) },
                                            label = { Text("结束时间", fontSize = 11.sp) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                                            )
                                        )
                                    }
                                    // 快捷时段预设
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        item {
                                            FilterChip(
                                                selected = uiState.beginTime == "08:00" && uiState.endTime == "09:40",
                                                onClick = { viewModel.setTimeRange("08:00", "09:40") },
                                                label = { Text(stringResource(R.string.time_preset_morning_1_2), fontSize = 11.sp) },
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                        }
                                        item {
                                            FilterChip(
                                                selected = uiState.beginTime == "10:00" && uiState.endTime == "11:40",
                                                onClick = { viewModel.setTimeRange("10:00", "11:40") },
                                                label = { Text(stringResource(R.string.time_preset_morning_3_4), fontSize = 11.sp) },
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                        }
                                        item {
                                            FilterChip(
                                                selected = uiState.beginTime == "08:00" && uiState.endTime == "11:40",
                                                onClick = { viewModel.setTimeRange("08:00", "11:40") },
                                                label = { Text(stringResource(R.string.time_preset_morning_all), fontSize = 11.sp) },
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                        }
                                        item {
                                            FilterChip(
                                                selected = uiState.beginTime == "14:00" && uiState.endTime == "17:40",
                                                onClick = { viewModel.setTimeRange("14:00", "17:40") },
                                                label = { Text(stringResource(R.string.time_preset_afternoon_all), fontSize = 11.sp) },
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                        }
                                        item {
                                            FilterChip(
                                                selected = uiState.beginTime == "18:30" && uiState.endTime == "21:00",
                                                onClick = { viewModel.setTimeRange("18:30", "21:00") },
                                                label = { Text(stringResource(R.string.time_preset_evening), fontSize = 11.sp) },
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. 统计提示条
                item {
                    val probeTipText = stringResource(R.string.tip_click_to_probe_schedule)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.format_free_classrooms_count, uiState.classrooms.size),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (probeTipText.isNotBlank()) {
                            Text(
                                text = probeTipText,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // 4. 异常提示
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

                // 5. 空教室卡片列表
                if (uiState.classrooms.isEmpty() && !uiState.isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (uiState.errorMessage != null) {
                                    stringResource(R.string.empty_classroom_error)
                                } else {
                                    stringResource(R.string.empty_classroom_none)
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    items(uiState.classrooms, key = { it.id.ifBlank { "${it.jsbh}_${it.jsmc}" } }) { room ->
                        FreeClassroomItemCard(
                            classroom = room,
                            onClick = { viewModel.selectClassroomForDetail(room) },
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FreeClassroomItemCard(
    classroom: FreeClassroom,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = classroom.jsmc,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${classroom.jxlmc} ${if (classroom.floor.isNotBlank()) "· ${classroom.floor}F" else ""}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = classroom.roomType,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (classroom.capacity > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.format_seats_only, classroom.capacity),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BuildingDropdownSelector(
    buildings: List<com.xingheyuzhuan.shiguangschedule.data.model.wbu.BuildingOption>,
    selectedCode: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentBuildingName = buildings.firstOrNull { it.code == selectedCode }?.name ?: buildings.firstOrNull()?.name ?: ""

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = currentBuildingName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.label_building_dropdown), fontSize = 12.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            buildings.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name, fontSize = 14.sp) },
                    onClick = {
                        onSelect(option.code)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomTypeDropdownSelector(
    selectedCode: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val allLabel = stringResource(R.string.room_type_all)

    val options = remember(allLabel) {
        listOf(Pair("", allLabel)) + JSLX_DICT.map { Pair(it.key, it.value) }
    }

    val currentName = options.firstOrNull { it.first == selectedCode }?.second ?: allLabel

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = currentName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.label_select_room_type), fontSize = 12.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name, fontSize = 14.sp) },
                    onClick = {
                        onSelect(code)
                        expanded = false
                    }
                )
            }
        }
    }
}
