package me.desidubanime.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.darkColorScheme

private val Colors = darkColorScheme(
    primary = Primary,
    onPrimary = OnBg,
    secondary = Accent,
    onSecondary = OnBg,
    tertiary = Gold,
    onTertiary = Bg,
    background = Bg,
    onBackground = OnBg,
    surface = Surface,
    onSurface = OnBg,
    surfaceVariant = SurfaceHi,
    onSurfaceVariant = Muted,
    surfaceTint = Accent,
)

// Slightly rounder than the Material defaults: posters and panels read as
// cards on a television at ten feet, and a 14dp corner survives the heavy
// downscaling a 1080p -> 720p panel does.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun DesiDubTheme(content: @Composable () -> Unit) {
    // TV apps are always dark; ignore system setting but keep the param referenced.
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = Colors, shapes = AppShapes, content = content)
}
