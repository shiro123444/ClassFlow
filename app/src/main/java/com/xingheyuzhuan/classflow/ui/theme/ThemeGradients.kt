package com.xingheyuzhuan.classflow.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush

/**
 * Reusable gradient helpers for consistent theme-aware backgrounds.
 *
 * These composables automatically adapt to the current MaterialTheme color scheme,
 * ensuring all screens use consistent gradients that match the time-based theme.
 */
object ThemeGradients {
    /**
     * Standard background gradient: background -> surface -> background
     *
     * Used by TodayScheduleScreen, SettingsScreen, and other screens that need
     * a subtle gradient background.
     */
    @Composable
    fun backgroundGradient(): Brush {
        val bg = MaterialTheme.colorScheme.background
        val surf = MaterialTheme.colorScheme.surface
        return Brush.linearGradient(listOf(bg, surf, bg))
    }

    /**
     * Weekly schedule gradient: theme-aware replacement for hard-coded blue.
     *
     * Adapts to current time period and dark mode, using surfaceVariant for
     * a slightly more prominent gradient effect suitable for the schedule grid.
     */
    @Composable
    fun weeklyScheduleGradient(): Brush {
        val colorScheme = MaterialTheme.colorScheme
        return Brush.linearGradient(
            listOf(
                colorScheme.surfaceVariant,
                colorScheme.surface,
                colorScheme.surfaceVariant
            )
        )
    }
}

