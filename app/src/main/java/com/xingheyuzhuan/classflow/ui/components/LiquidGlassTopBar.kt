package com.xingheyuzhuan.classflow.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * Collapsing top bar that wraps Material3 LargeTopAppBar.
 *
 * Expanded state shows a large bold title; on scroll the bar smoothly
 * collapses to a compact height with the title scaling down — all handled
 * natively by LargeTopAppBar + exitUntilCollapsedScrollBehavior.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidGlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
    navigationIcon: @Composable () -> Unit = {}
) {
    LargeTopAppBar(
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold
            )
        },
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.largeTopAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberLiquidGlassScrollBehavior(): TopAppBarScrollBehavior {
    return TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
}

