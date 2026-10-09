package com.xingheyuzhuan.shiguangschedule.ui.campus.hairdryer

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerDetectionMode
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerDeviceType
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerHistoryEntry
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerLaunchSource
import com.xingheyuzhuan.shiguangschedule.data.model.wbu.HairdryerTypeSource

/**
 * 吹风机页（校园服务 → 吹风机）。
 *
 * 两件事：**这台该跳哪**（判型设置）与**我用过哪几台**（记录）。
 * 记录里点开一台就是它的全部动作：直接使用 / 重命名 / 改类型 / 添加到桌面 / 删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HairdryerHubScreen(
    navBridge: NavBridge,
    viewModel: HairdryerHubViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var expandedCd by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<HairdryerHistoryEntry?>(null) }
    var pinTarget by remember { mutableStateOf<HairdryerHistoryEntry?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { resId ->
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.title_hairdryer_hub),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navBridge.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.hairdryer_action_settings)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ModeCard(
                    mode = state.settings.mode,
                    onOpenSettings = { showSettings = true }
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.hairdryer_section_history),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (state.entries.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.hairdryer_history_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(state.entries, key = { it.cd }) { entry ->
                    HistoryCard(
                        entry = entry,
                        expanded = expandedCd == entry.cd,
                        onToggle = { expandedCd = if (expandedCd == entry.cd) null else entry.cd },
                        onUse = { navBridge.navigate(entry.launchDestination()) },
                        onRename = { renameTarget = entry },
                        onToggleType = {
                            viewModel.setType(
                                entry.cd,
                                if (entry.type == HairdryerDeviceType.CLOUD) {
                                    HairdryerDeviceType.BLUETOOTH
                                } else {
                                    HairdryerDeviceType.CLOUD
                                }
                            )
                        },
                        onPin = { pinTarget = entry },
                        onDelete = {
                            expandedCd = null
                            viewModel.delete(entry.cd)
                        }
                    )
                }
            }

            item {
                OutlinedButton(
                    onClick = { navBridge.navigate(Destination.QrScan) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.hairdryer_action_scan))
                }
            }
        }
    }

    if (showSettings) {
        HairdryerSettingsDialog(
            settings = state.settings,
            onDismiss = { showSettings = false },
            onModeChange = viewModel::setMode,
            onNfcModeChange = viewModel::setNfcMode,
            onResetFormatVerdicts = viewModel::resetFormatVerdicts
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.alias.orEmpty(),
            onDismiss = { renameTarget = null },
            onConfirm = { alias ->
                viewModel.rename(target.cd, alias)
                renameTarget = null
            }
        )
    }

    pinTarget?.let { target ->
        IconPickerDialog(
            selectedIconId = target.iconId ?: HairdryerShortcuts.DEFAULT_ICON_ID,
            onDismiss = { pinTarget = null },
            onConfirm = { iconId ->
                viewModel.setIcon(target.cd, iconId)
                val label = target.displayTitle.take(12)
                val result = HairdryerShortcuts.addToHome(
                    context = context,
                    entry = target.copy(iconId = iconId),
                    label = label
                )
                viewModel.notify(
                    when (result) {
                        HairdryerShortcuts.PinResult.REQUESTED -> R.string.hairdryer_message_shortcut_requested
                        HairdryerShortcuts.PinResult.UPDATED -> R.string.hairdryer_message_shortcut_updated
                        HairdryerShortcuts.PinResult.ALREADY_PINNED -> R.string.hairdryer_message_shortcut_already
                        HairdryerShortcuts.PinResult.UNSUPPORTED -> R.string.hairdryer_message_shortcut_unsupported
                        HairdryerShortcuts.PinResult.FAILED -> R.string.hairdryer_message_shortcut_failed
                    }
                )
                pinTarget = null
            }
        )
    }
}

/** 记录里的「直接使用」：带上这条记录自己的类型与原文，落在过渡页里判型跳转。 */
private fun HairdryerHistoryEntry.launchDestination(): Destination = Destination.HairdryerLaunch(
    cd = cd,
    raw = raw,
    source = HairdryerLaunchSource.DIRECT.name
)

/** 顶部那张卡：现在是怎么判的 + 一句话说明 + 设置入口。 */
@Composable
private fun ModeCard(mode: HairdryerDetectionMode, onOpenSettings: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.hairdryer_mode_card_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = mode.displayName(),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = mode.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.hairdryer_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.hairdryer_action_settings))
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: HairdryerHistoryEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onUse: () -> Unit,
    onRename: () -> Unit,
    onToggleType: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = entry.displaySubtitle ?: entry.cd,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TypeChip(entry.type)
            }

            Text(
                text = stringResource(
                    R.string.hairdryer_history_use_count,
                    entry.useCount
                ) + " · " + entry.source.displayName(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onUse) { Text(stringResource(R.string.hairdryer_action_use)) }
                        TextButton(onClick = onRename) { Text(stringResource(R.string.hairdryer_action_rename)) }
                        TextButton(onClick = onPin) { Text(stringResource(R.string.hairdryer_action_pin_shortcut)) }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onToggleType) {
                            Text(
                                stringResource(
                                    if (entry.type == HairdryerDeviceType.CLOUD) {
                                        R.string.hairdryer_action_make_bluetooth
                                    } else {
                                        R.string.hairdryer_action_make_cloud
                                    }
                                )
                            )
                        }
                        TextButton(onClick = onDelete) {
                            Text(
                                text = stringResource(R.string.hairdryer_action_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 类型标签：蓝牙 / 4G —— 列表里一眼看出这台会跳哪儿。 */
@Composable
private fun TypeChip(type: HairdryerDeviceType) {
    val color = if (type == HairdryerDeviceType.CLOUD) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            text = stringResource(
                if (type == HairdryerDeviceType.CLOUD) {
                    R.string.hairdryer_type_cloud
                } else {
                    R.string.hairdryer_type_bluetooth
                }
            ),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hairdryer_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.hairdryer_rename_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** 选图标 → 添加到桌面（预设图标，每台机器挑一个花色就行）。 */
@Composable
private fun IconPickerDialog(
    selectedIconId: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var selected by remember { mutableStateOf(selectedIconId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hairdryer_icon_picker_title)) },
        text = {
            Column {
                // 一行 5 个：12 个预设图标正好排三行，对话框宽度不够时也不至于被裁掉
                HairdryerShortcuts.ICONS.chunked(5).forEach { row ->
                    Row(
                        modifier = Modifier.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { preset ->
                            val isSelected = preset.id == selected
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .clickable { selected = preset.id },
                                contentAlignment = Alignment.Center
                            ) {
                                // 用 Image 而不是 Icon：Icon 会按内容色给整张图染色，
                                // 这些预设图标是「彩色底 + 白色图形」，染色后只剩一坨纯色
                                Image(
                                    painter = painterResource(preset.resId),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.hairdryer_icon_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.hairdryer_action_pin_shortcut))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun HairdryerDetectionMode.displayName(): String = stringResource(
    when (this) {
        HairdryerDetectionMode.AUTO -> R.string.hairdryer_mode_auto
        HairdryerDetectionMode.AUTO_DEVICE -> R.string.hairdryer_mode_auto_device
        HairdryerDetectionMode.BLUETOOTH -> R.string.hairdryer_mode_bluetooth
        HairdryerDetectionMode.CLOUD -> R.string.hairdryer_mode_cloud
    }
)

@Composable
private fun HairdryerDetectionMode.description(): String = stringResource(
    when (this) {
        HairdryerDetectionMode.AUTO -> R.string.hairdryer_mode_auto_desc
        HairdryerDetectionMode.AUTO_DEVICE -> R.string.hairdryer_mode_auto_device_desc
        HairdryerDetectionMode.BLUETOOTH -> R.string.hairdryer_mode_bluetooth_desc
        HairdryerDetectionMode.CLOUD -> R.string.hairdryer_mode_cloud_desc
    }
)

@Composable
private fun HairdryerTypeSource.displayName(): String = stringResource(
    when (this) {
        HairdryerTypeSource.PROBE -> R.string.hairdryer_source_probe
        HairdryerTypeSource.FORMAT -> R.string.hairdryer_source_format
        HairdryerTypeSource.MANUAL -> R.string.hairdryer_source_manual
        HairdryerTypeSource.FALLBACK -> R.string.hairdryer_source_fallback
    }
)
