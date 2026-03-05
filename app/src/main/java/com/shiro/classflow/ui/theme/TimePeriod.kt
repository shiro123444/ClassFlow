package com.shiro.classflow.ui.theme

/**
 * Time periods for dynamic theme switching.
 *
 * The app automatically switches between three color palettes based on the current hour:
 * - MORNING (6:00-12:00): Sakura pink theme
 * - AFTERNOON (12:00-18:00): Soft blue-gray theme
 * - EVENING (18:00-6:00): Lavender purple theme
 */
enum class TimePeriod {
    MORNING,
    AFTERNOON,
    EVENING;

    companion object {
        /**
         * Determines the time period based on the current hour (0-23).
         */
        fun fromHour(hour: Int): TimePeriod {
            return when (hour) {
                in 6..11 -> MORNING
                in 12..17 -> AFTERNOON
                else -> EVENING
            }
        }
    }
}

