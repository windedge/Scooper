package scooper.ui.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

private val darkColorPalette = darkColors(
    primary = Blue500,
    primaryVariant = Blue600,
    onPrimary = Color.White,
    secondary = Slate400,
    onSecondary = Color.White,
    surface = Slate800,
    onSurface = Slate50,
    background = Slate900,
    onBackground = Slate50,
    error = Red500,
    onError = Color.White,
)

private val lightColorPalette = lightColors(
    primary = Blue600,
    primaryVariant = Blue700,
    onPrimary = Color.White,
    secondary = Slate500,
    onSecondary = Color.White,
    surface = Color.White,
    onSurface = Slate900,
    background = Slate50,
    onBackground = Slate900,
    error = Red500,
    onError = Color.White,
)

@Composable
fun ScooperTheme(currentTheme: SystemTheme = currentSystemTheme, fontSizeScale: Float = 1.0f, content: @Composable () -> Unit) {
    val colors = if (currentTheme == SystemTheme.DARK) darkColorPalette else lightColorPalette

    MaterialTheme(
        colors = colors,
        typography = typography(fontSizeScale),
        shapes = shapes,
        content = content
    )
}
