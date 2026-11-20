package me.desidubanime.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val Colors = darkColorScheme(
    primary = Primary,
    onPrimary = OnBg,
    background = Bg,
    onBackground = OnBg,
    surface = Surface,
    onSurface = OnBg,
    surfaceVariant = SurfaceHi,
)

@Composable
fun DesiDubTheme(content: @Composable () -> Unit) {
    // TV apps are always dark; ignore system setting but keep the param referenced.
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = Colors, content = content)
}
