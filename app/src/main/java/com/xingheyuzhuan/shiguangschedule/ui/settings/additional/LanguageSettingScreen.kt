package com.xingheyuzhuan.shiguangschedule.ui.settings.additional

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import com.xingheyuzhuan.shiguangschedule.R

/**
 * 语言数据实体（仅限本文件内部解析使用）
 */
private data class LanguageItem(
    val name: String,
    val tag: String
)

/**
 * 独立语言设置页面（上游同步，Android 平台实现）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingScreen(
    onBack: () -> Unit
) {
    var currentTag by remember {
        mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }

    val tags = stringArrayResource(R.array.language_tags)
    val names = stringArrayResource(R.array.language_names)
    val followSystemText = stringResource(R.string.language_follow_system)

    val languageList = remember(tags, names, followSystemText) {
        buildList {
            add(LanguageItem(followSystemText, ""))
            addAll(names.zip(tags) { name, tag -> LanguageItem(name, tag) })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.item_language_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            languageList.forEach { item ->
                val isSelected = if (item.tag.isEmpty()) {
                    currentTag.isEmpty()
                } else {
                    currentTag.startsWith(item.tag)
                }

                ListItem(
                    modifier = Modifier.clickable {
                        if (!isSelected) {
                            currentTag = item.tag
                            AppCompatDelegate.setApplicationLocales(
                                if (item.tag.isEmpty()) {
                                    LocaleListCompat.getEmptyLocaleList()
                                } else {
                                    LocaleListCompat.forLanguageTags(item.tag)
                                }
                            )
                        }
                    },
                    headlineContent = { Text(text = item.name) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        RadioButton(
                            selected = isSelected,
                            onClick = null
                        )
                    }
                )
            }
        }
    }
}
