package scooper.ui.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

private val DarkColorPalette = darkColors(
    primary = purple200,
    primaryVariant = purple700,
    secondary = teal200
)

private val LightColorPalette = lightColors(
    primary = LightColors.primaryColor,
    primaryVariant = LightColors.primaryLightColor,
    onPrimary = LightColors.primaryTextColor,
    secondary = LightColors.secondaryColor,
    secondaryVariant = LightColors.secondaryDarkColor,
    onSecondary = LightColors.secondaryTextColor,
    surface = LightColors.surfaceColor,
    onSurface = LightColors.surfaceTextColor,
    background = LightColors.backgroupColor,
    onBackground = LightColors.backgroundTextColor,
    error = LightColors.errorColor

    /* Other default colors to override
background = Color.White,
surface = Color.White,
onPrimary = Color.White,
onSecondary = Color.Black,
onBackground = Color.Black,
*/
)

@Composable
fun ScooperTheme(currentTheme: SystemTheme = currentSystemTheme, content: @Composable() () -> Unit) {
    val colors = if (currentTheme == SystemTheme.DARK) {
        DarkColorPalette
    } else {
        LightColorPalette
    }

    MaterialTheme(
        colors = colors,
        typography = typography,
        shapes = shapes,
        content = content
    )
}