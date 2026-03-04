package com.xingheyuzhuan.classflow.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.graphics.drawable.toDrawable
import kotlinx.coroutines.delay
import java.time.LocalTime

private val DarkColorScheme = darkColorScheme(
    primary = SakuraPrimaryDark,
    onPrimary = SakuraOnPrimaryDark,
    primaryContainer = SakuraPrimaryContainerDark,
    onPrimaryContainer = SakuraOnPrimaryContainerDark,
    secondary = SakuraSecondaryDark,
    onSecondary = SakuraOnSecondaryDark,
    secondaryContainer = SakuraSecondaryContainerDark,
    onSecondaryContainer = SakuraOnSecondaryContainerDark,
    tertiary = SakuraTertiaryDark,
    onTertiary = SakuraOnTertiaryDark,
    tertiaryContainer = SakuraTertiaryContainerDark,
    onTertiaryContainer = SakuraOnTertiaryContainerDark,
    error = SakuraErrorDark,
    onError = SakuraOnErrorDark,
    errorContainer = SakuraErrorContainerDark,
    onErrorContainer = SakuraOnErrorContainerDark,
    background = SakuraBackgroundDark,
    onBackground = SakuraOnBackgroundDark,
    surface = SakuraSurfaceDark,
    onSurface = SakuraOnSurfaceDark,
    surfaceVariant = SakuraSurfaceVariantDark,
    onSurfaceVariant = SakuraOnSurfaceVariantDark,
    outline = SakuraOutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = SakuraPrimary,
    onPrimary = SakuraOnPrimary,
    primaryContainer = SakuraPrimaryContainer,
    onPrimaryContainer = SakuraOnPrimaryContainer,
    secondary = SakuraSecondary,
    onSecondary = SakuraOnSecondary,
    secondaryContainer = SakuraSecondaryContainer,
    onSecondaryContainer = SakuraOnSecondaryContainer,
    tertiary = SakuraTertiary,
    onTertiary = SakuraOnTertiary,
    tertiaryContainer = SakuraTertiaryContainer,
    onTertiaryContainer = SakuraOnTertiaryContainer,
    error = SakuraError,
    onError = SakuraOnError,
    errorContainer = SakuraErrorContainer,
    onErrorContainer = SakuraOnErrorContainer,
    background = SakuraBackground,
    onBackground = SakuraOnBackground,
    surface = SakuraSurface,
    onSurface = SakuraOnSurface,
    surfaceVariant = SakuraSurfaceVariant,
    onSurfaceVariant = SakuraOnSurfaceVariant,
    outline = SakuraOutline
)

private val AfternoonLightColorScheme = lightColorScheme(
    primary = AfternoonPrimary,
    onPrimary = AfternoonOnPrimary,
    primaryContainer = AfternoonPrimaryContainer,
    onPrimaryContainer = AfternoonOnPrimaryContainer,
    secondary = AfternoonSecondary,
    onSecondary = AfternoonOnSecondary,
    secondaryContainer = AfternoonSecondaryContainer,
    onSecondaryContainer = AfternoonOnSecondaryContainer,
    tertiary = AfternoonTertiary,
    onTertiary = AfternoonOnTertiary,
    tertiaryContainer = AfternoonTertiaryContainer,
    onTertiaryContainer = AfternoonOnTertiaryContainer,
    error = AfternoonError,
    onError = AfternoonOnError,
    errorContainer = AfternoonErrorContainer,
    onErrorContainer = AfternoonOnErrorContainer,
    background = AfternoonBackground,
    onBackground = AfternoonOnBackground,
    surface = AfternoonSurface,
    onSurface = AfternoonOnSurface,
    surfaceVariant = AfternoonSurfaceVariant,
    onSurfaceVariant = AfternoonOnSurfaceVariant,
    outline = AfternoonOutline
)

private val AfternoonDarkColorScheme = darkColorScheme(
    primary = AfternoonPrimaryDark,
    onPrimary = AfternoonOnPrimaryDark,
    primaryContainer = AfternoonPrimaryContainerDark,
    onPrimaryContainer = AfternoonOnPrimaryContainerDark,
    secondary = AfternoonSecondaryDark,
    onSecondary = AfternoonOnSecondaryDark,
    secondaryContainer = AfternoonSecondaryContainerDark,
    onSecondaryContainer = AfternoonOnSecondaryContainerDark,
    tertiary = AfternoonTertiaryDark,
    onTertiary = AfternoonOnTertiaryDark,
    tertiaryContainer = AfternoonTertiaryContainerDark,
    onTertiaryContainer = AfternoonOnTertiaryContainerDark,
    error = AfternoonErrorDark,
    onError = AfternoonOnErrorDark,
    errorContainer = AfternoonErrorContainerDark,
    onErrorContainer = AfternoonOnErrorContainerDark,
    background = AfternoonBackgroundDark,
    onBackground = AfternoonOnBackgroundDark,
    surface = AfternoonSurfaceDark,
    onSurface = AfternoonOnSurfaceDark,
    surfaceVariant = AfternoonSurfaceVariantDark,
    onSurfaceVariant = AfternoonOnSurfaceVariantDark,
    outline = AfternoonOutlineDark
)

private val EveningLightColorScheme = lightColorScheme(
    primary = EveningPrimary,
    onPrimary = EveningOnPrimary,
    primaryContainer = EveningPrimaryContainer,
    onPrimaryContainer = EveningOnPrimaryContainer,
    secondary = EveningSecondary,
    onSecondary = EveningOnSecondary,
    secondaryContainer = EveningSecondaryContainer,
    onSecondaryContainer = EveningOnSecondaryContainer,
    tertiary = EveningTertiary,
    onTertiary = EveningOnTertiary,
    tertiaryContainer = EveningTertiaryContainer,
    onTertiaryContainer = EveningOnTertiaryContainer,
    error = EveningError,
    onError = EveningOnError,
    errorContainer = EveningErrorContainer,
    onErrorContainer = EveningOnErrorContainer,
    background = EveningBackground,
    onBackground = EveningOnBackground,
    surface = EveningSurface,
    onSurface = EveningOnSurface,
    surfaceVariant = EveningSurfaceVariant,
    onSurfaceVariant = EveningOnSurfaceVariant,
    outline = EveningOutline
)

private val EveningDarkColorScheme = darkColorScheme(
    primary = EveningPrimaryDark,
    onPrimary = EveningOnPrimaryDark,
    primaryContainer = EveningPrimaryContainerDark,
    onPrimaryContainer = EveningOnPrimaryContainerDark,
    secondary = EveningSecondaryDark,
    onSecondary = EveningOnSecondaryDark,
    secondaryContainer = EveningSecondaryContainerDark,
    onSecondaryContainer = EveningOnSecondaryContainerDark,
    tertiary = EveningTertiaryDark,
    onTertiary = EveningOnTertiaryDark,
    tertiaryContainer = EveningTertiaryContainerDark,
    onTertiaryContainer = EveningOnTertiaryContainerDark,
    error = EveningErrorDark,
    onError = EveningOnErrorDark,
    errorContainer = EveningErrorContainerDark,
    onErrorContainer = EveningOnErrorContainerDark,
    background = EveningBackgroundDark,
    onBackground = EveningOnBackgroundDark,
    surface = EveningSurfaceDark,
    onSurface = EveningOnSurfaceDark,
    surfaceVariant = EveningSurfaceVariantDark,
    onSurfaceVariant = EveningOnSurfaceVariantDark,
    outline = EveningOutlineDark
)

@Composable
fun ClassFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    timeBasedTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    // Track current time period with state
    var currentTimePeriod by remember {
        mutableStateOf(TimePeriod.fromHour(LocalTime.now().hour))
    }

    // Update time period when crossing boundaries
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalTime.now()
            val newPeriod = TimePeriod.fromHour(now.hour)
            if (newPeriod != currentTimePeriod) {
                currentTimePeriod = newPeriod
            }

            // Smart delay: Check every minute near boundaries, every 15 minutes otherwise
            val minutesUntilNextHour = 60 - now.minute
            val delayMinutes = if (minutesUntilNextHour <= 5) 1 else 15
            delay(delayMinutes * 60 * 1000L)
        }
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        !timeBasedTheme -> {
            // Use original Sakura theme when time-based theme is disabled
            if (darkTheme) DarkColorScheme else LightColorScheme
        }
        darkTheme -> {
            when (currentTimePeriod) {
                TimePeriod.MORNING -> DarkColorScheme
                TimePeriod.AFTERNOON -> AfternoonDarkColorScheme
                TimePeriod.EVENING -> EveningDarkColorScheme
            }
        }
        else -> {
            when (currentTimePeriod) {
                TimePeriod.MORNING -> LightColorScheme
                TimePeriod.AFTERNOON -> AfternoonLightColorScheme
                TimePeriod.EVENING -> EveningLightColorScheme
            }
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            // Fully transparent window background for edge-to-edge glass effects
            window.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())

            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

