package scooper.ui.theme

import androidx.compose.material.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.io.File

val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
val Arial = FontFamily(androidx.compose.ui.text.platform.Font(File("$systemRoot\\Fonts\\arial.ttf")))

// Set of Material typography styles to start with
val typography = Typography(
    defaultFontFamily = Arial,
    h5 = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
    ),
    h6 = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
    ),
    body1 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
    ),
    body2 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
    ),
    button = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.5.sp,
    ),
    caption = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
    ),
    subtitle1 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
    ),
    subtitle2 = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
    ),
    overline = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
    ),
)
