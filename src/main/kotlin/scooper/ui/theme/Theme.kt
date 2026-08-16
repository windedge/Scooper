package scooper.ui.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
fun ScooperTheme(
    currentTheme: SystemTheme = currentSystemTheme,
    fontSizeScale: Float = 1.0f,
    uiLanguage: String = "en",
    userFontFamilyName: String? = null,
    content: @Composable () -> Unit
) {
    val colors = if (currentTheme == SystemTheme.DARK) darkColorPalette else lightColorPalette

    // A user-selected "Interface Font" overrides the locale-mapped family;
    // blank or unresolvable names fall back to the locale default (en -> Arial,
    // CJK -> the language's UI font). A locale change is a snapshot-state read
    // upstream (Strings.current), so this recomposes automatically and the
    // typography rebuilds with the new family.
    val fontFamily = remember(uiLanguage, userFontFamilyName) {
        userFontFamilyName?.let { fontFamilyOverride(it) } ?: fontFamilyFor(uiLanguage)
    }

    MaterialTheme(
        colors = colors,
        typography = typography(fontSizeScale, fontFamily),
        shapes = shapes,
        content = content
    )
}
