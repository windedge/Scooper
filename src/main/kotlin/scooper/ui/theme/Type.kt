package scooper.ui.theme

import androidx.compose.material.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.io.File

val Arial = FontFamily(androidx.compose.ui.text.platform.Font(File("c:\\Windows\\Fonts\\arial.ttf")))

// Set of Material typography styles to start with
val typography = Typography(
    defaultFontFamily = Arial,
    body1 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    body2 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    ),
    button = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 1.sp,
    )
    /* Other default text styles to override
    button = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.W500,
    fontSize = 14.sp
    ),
    caption = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp
    )
    */
)