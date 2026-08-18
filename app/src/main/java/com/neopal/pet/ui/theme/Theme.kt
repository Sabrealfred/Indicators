package com.neopal.pet.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkScheme = darkColorScheme(
    primary = NeoColors.NeonCyan,
    onPrimary = NeoColors.ChassisBlack,
    secondary = NeoColors.NeonRed,
    onSecondary = NeoColors.OnDark,
    tertiary = NeoColors.NeonPurple,
    background = NeoColors.ChassisBlack,
    onBackground = NeoColors.OnDark,
    surface = NeoColors.SurfaceDark,
    onSurface = NeoColors.OnDark,
    surfaceVariant = NeoColors.SurfaceCard,
    onSurfaceVariant = NeoColors.OnDarkMuted,
    outline = NeoColors.ChassisLight,
)

private val LightScheme = lightColorScheme(
    primary = NeoColors.NeonCyanDim,
    onPrimary = NeoColors.SurfaceLightCard,
    secondary = NeoColors.NeonRedDim,
    onSecondary = NeoColors.SurfaceLightCard,
    tertiary = NeoColors.NeonPurple,
    background = NeoColors.SurfaceLight,
    onBackground = NeoColors.OnLight,
    surface = NeoColors.SurfaceLightCard,
    onSurface = NeoColors.OnLight,
    surfaceVariant = NeoColors.SurfaceLight,
    onSurfaceVariant = NeoColors.OnLightMuted,
    outline = androidx.compose.ui.graphics.Color(0xFFD3D7DE),
)

@Composable
fun NeoPalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = NeoTypography,
        content = content,
    )
}
