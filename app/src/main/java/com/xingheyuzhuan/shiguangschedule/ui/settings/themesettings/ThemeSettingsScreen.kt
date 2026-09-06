package com.xingheyuzhuan.shiguangschedule.ui.settings.themesettings

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.AppThemeMode
import com.xingheyuzhuan.shiguangschedule.ui.components.AdvancedColorPicker
import com.xingheyuzhuan.shiguangschedule.ui.components.ColorPickerConfig
import com.xingheyuzhuan.shiguangschedule.ui.theme.LocalIsDarkTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    onBack: () -> Unit,
    viewModel: ThemeSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.appSettings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.theme_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 主题模式选择
            SectionHeader(stringResource(R.string.theme_mode_label))
            ThemeModeSelector(
                selectedMode = settings.themeMode,
                onModeSelected = { viewModel.onThemeModeChanged(it) }
            )

            // Sakura 时间色板开关（ClassFlow 特色 / 上游固定样式）
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SakuraTimeThemeToggle(
                enabled = settings.useSakuraTimeTheme,
                onEnabledChange = { viewModel.onUseSakuraTimeThemeChanged(it) }
            )

            // 上游样式选项（Sakura 关闭时生效：动态取色 + 自定义种子色）
            AnimatedVisibility(
                visible = !settings.useSakuraTimeTheme,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    // 动态取色 (Android 12+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        DynamicColorToggle(
                            enabled = settings.useDynamicColor,
                            onEnabledChange = { viewModel.onUseDynamicColorChanged(it) }
                        )
                    }

                    // 自定义主色调选择
                    val showColorPicker = !settings.useDynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S

                    AnimatedVisibility(
                        visible = showColorPicker,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SectionHeader(stringResource(R.string.custom_color_title))

                    val isDark = LocalIsDarkTheme.current

                    if (isDark) {
                        ColorPickerItem(
                            label = stringResource(R.string.dark_primary_color),
                            currentColor = Color(settings.customDarkPrimary),
                            onColorChanged = { viewModel.onCustomDarkPrimaryChanged(it) },
                            onReset = { viewModel.onCustomDarkPrimaryChanged() }
                        )
                    } else {
                        ColorPickerItem(
                            label = stringResource(R.string.light_primary_color),
                            currentColor = Color(settings.customLightPrimary),
                            onColorChanged = { viewModel.onCustomLightPrimaryChanged(it) },
                            onReset = { viewModel.onCustomLightPrimaryChanged() }
                        )
                    }

                    Text(
                        text = stringResource(R.string.theme_color_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorPickerItem(
    label: String,
    currentColor: Color,
    onColorChanged: (Color) -> Unit,
    onReset: () -> Unit
) {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Surface(
        onClick = { showSheet = true },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(currentColor)
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 40.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                    TextButton(onClick = onReset) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_reset))
                    }
                }
                AdvancedColorPicker(
                    initialColor = currentColor,
                    onColorChanged = onColorChanged,
                    config = ColorPickerConfig(
                        showAlpha = false,
                        showInputMode = true
                    )
                )
            }
        }
    }
}


@Composable
private fun ThemeModeSelector(
    selectedMode: AppThemeMode,
    onModeSelected: (AppThemeMode) -> Unit
) {
    val modes = listOf(
        AppThemeMode.FOLLOW_SYSTEM to stringResource(R.string.theme_follow_system),
        AppThemeMode.LIGHT to stringResource(R.string.theme_light),
        AppThemeMode.DARK to stringResource(R.string.theme_dark)
    )

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = selectedMode == mode,
                onClick = { onModeSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SakuraTimeThemeToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Surface(
        onClick = { onEnabledChange(!enabled) },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.item_sakura_time_theme), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.desc_sakura_time_theme), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

@Composable
private fun DynamicColorToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Surface(
        onClick = { onEnabledChange(!enabled) },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.dynamic_color_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.dynamic_color_desc), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}