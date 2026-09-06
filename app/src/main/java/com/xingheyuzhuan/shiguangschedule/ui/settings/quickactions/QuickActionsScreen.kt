package com.xingheyuzhuan.shiguangschedule.ui.settings.quickactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.Destination

/**
 * 快捷操作二级页面
 * 用于收纳高频使用的工具类功能，如调课、临时改动等
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionsScreen(
    navBridge: NavBridge
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.item_quick_actions)) },
                navigationIcon = {
                    IconButton(onClick = { navBridge.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                // 课表调整分类卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 分类标题
                        Text(
                            text = stringResource(R.string.label_quick_action_category_schedule),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )

                        // 调课功能项
                        QuickActionItem(
                            title = stringResource(R.string.item_schedule_tweak),
                            subtitle = stringResource(R.string.desc_schedule_tweak),
                            onClick = { navBridge.navigate(Destination.TweakSchedule) }
                        )

                        // 2. 快速删除功能项
                        QuickActionItem(
                            title = stringResource(R.string.item_quick_delete),
                            subtitle = stringResource(R.string.quick_delete_subtitle),
                            onClick = { navBridge.navigate(Destination.QuickDelete) }
                        )
                    }
                }
            }

            item {
                // 校园服务分类卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.section_campus_service),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )

                        QuickActionItem(
                            title = stringResource(R.string.item_grade_query),
                            subtitle = stringResource(R.string.desc_quick_grade_query),
                            onClick = { navBridge.navigate(Destination.GradeQuery) }
                        )

                        QuickActionItem(
                            title = stringResource(R.string.item_free_classroom_query),
                            subtitle = stringResource(R.string.desc_free_classroom_query),
                            onClick = { navBridge.navigate(Destination.FreeClassroomQuery) }
                        )

                        QuickActionItem(
                            title = stringResource(R.string.item_academic_progress),
                            subtitle = stringResource(R.string.desc_academic_progress),
                            onClick = { navBridge.navigate(Destination.AcademicProgress) }
                        )

                        QuickActionItem(
                            title = stringResource(R.string.item_library_borrow),
                            subtitle = stringResource(R.string.desc_library_borrow),
                            onClick = { navBridge.navigate(Destination.LibraryBorrow) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 快捷操作单项组件
 * 布局复用设置页逻辑：左侧标题+描述，右侧导航箭头
 */
@Composable
private fun QuickActionItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            modifier = Modifier.padding(start = 4.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
