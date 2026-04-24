package scooper.ui.theme

import androidx.compose.material.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.io.File

val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
val Arial = FontFamily(androidx.compose.ui.text.platform.Font(File("$systemRoot\\Fonts\\arial.ttf")))

@Composable
fun typography(scale: Float = 1.0f) = Typography(
    defaultFontFamily = Arial,
    h5 = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = (20 * scale).sp,
    ),
    h6 = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = (18 * scale).sp,
    ),
    body1 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = (16 * scale).sp,
    ),
    body2 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = (16 * scale).sp,
    ),
    button = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = (15 * scale).sp,
        letterSpacing = (0.5 * scale).sp,
    ),
    caption = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = (14 * scale).sp,
    ),
    subtitle1 = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = (16 * scale).sp,
    ),
    subtitle2 = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = (14 * scale).sp,
    ),
    overline = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = (12 * scale).sp,
        letterSpacing = (1 * scale).sp,
    ),
)
