package scooper.ui.theme

import androidx.compose.material.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * App typography with no explicit [defaultFontFamily].
 *
 * Deliberately leaves [Typography.defaultFontFamily] unset so that Skia
 * delegates fallback to the Windows system font manager. This is the same
 * strategy used by OmniPrint: Skia picks Segoe UI for Latin text and
 * resolves CJK characters through the system's own fallback chain
 * (typically Microsoft YaHei).
 *
 * Loading a font file explicitly (e.g. Arial from C:\Windows\Fonts)
 * bypasses the system font manager and often causes Skia to pick
 * inconsistent CJK fallback fonts across different [FontWeight] values.
 */
@Composable
fun typography(scale: Float = 1.0f) = Typography(
    h5 = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = (19 * scale).sp,
    ),
    h6 = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = (17 * scale).sp,
    ),
    body1 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = (15 * scale).sp,
    ),
    body2 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = (15 * scale).sp,
    ),
    button = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = (14 * scale).sp,
        letterSpacing = (0.5 * scale).sp,
    ),
    caption = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = (13 * scale).sp,
    ),
    subtitle1 = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = (15 * scale).sp,
    ),
    subtitle2 = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = (13 * scale).sp,
    ),
    overline = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = (11 * scale).sp,
        letterSpacing = (1 * scale).sp,
    ),
)
