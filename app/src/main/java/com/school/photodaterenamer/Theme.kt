package com.school.photodaterenamer

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val MatchDayDarkColorScheme = darkColorScheme(
    primary = PosterOrangeRed,
    onPrimary = PosterOffWhite,
    primaryContainer = PosterOrangeTint,
    onPrimaryContainer = PosterOffWhite,
    secondary = PosterGreen,
    onSecondary = PosterInk,
    secondaryContainer = PosterCharcoal,
    onSecondaryContainer = PosterOffWhite,
    tertiary = PosterWarningAmber,
    onTertiary = PosterInk,
    background = PosterInk,
    onBackground = PosterOffWhite,
    surface = PosterCharcoal,
    onSurface = PosterOffWhite,
    surfaceVariant = PosterCharcoal,
    onSurfaceVariant = PosterGray,
    outline = PosterDarkOutline
)

private val MatchDayLightColorScheme = lightColorScheme(
    primary = PosterOrangeRed,
    onPrimary = PosterPureWhite,
    primaryContainer = PosterInk,
    onPrimaryContainer = PosterOffWhite,
    secondary = PosterGreen,
    onSecondary = PosterPureWhite,
    secondaryContainer = PosterLightSurfaceVariant,
    onSecondaryContainer = PosterInk,
    tertiary = PosterWarningAmber,
    onTertiary = PosterInk,
    background = PosterOffWhite,
    onBackground = PosterInk,
    surface = PosterPureWhite,
    onSurface = PosterInk,
    surfaceVariant = PosterLightSurfaceVariant,
    onSurfaceVariant = PosterCharcoal,
    outline = PosterLightOutline
)

@Composable
fun NqdVolleyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) MatchDayDarkColorScheme else MatchDayLightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Header is always PosterInk (#111111), so light status bar icons look crisp in both modes
            window.statusBarColor = PosterInk.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
