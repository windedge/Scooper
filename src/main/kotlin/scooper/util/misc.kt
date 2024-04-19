package scooper.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files

fun Double.readableSize() = when {
    this >= 1 shl 30 -> "%.1f GB".format(this / (1 shl 30))
    this >= 1 shl 20 -> "%.1f MB".format(this / (1 shl 20))
    this >= 1 shl 10 -> "%.0f kB".format(this / (1 shl 10))
    this == 0.0 -> "0 bytes"
    else -> "$this bytes"
}

fun Long.readableSize() = this.toDouble().readableSize()

fun File.dirSize(noFollowLink: Boolean = true): Long {
    var treeWalk = this.walkTopDown()
    if (noFollowLink) {
        treeWalk = treeWalk.onEnter {
            !Files.isSymbolicLink(it.toPath()) &&
                    it.toPath().parent.toRealPath() == it.toPath().toRealPath().parent.toRealPath()
        }
    }
    return treeWalk.filter { it.isFile }.map { it.length() }.sum()
}

fun JsonObject.getString(key: String): String {
    return getOrDefault(key, "").toString().removeSurrounding("\"")
}

@Suppress("unused")
fun removeAnsiColor(text: String): String {
    val ansiEscapeRegex = "\u001B\\[[0-9;]*[mK]".toRegex()
    return text.replace(ansiEscapeRegex, "")
}

fun parseAnsiColors(text: String): AnnotatedString {
    val regex = "\u001B\\[([0-9;]*)m".toRegex()
    var lastIndex = 0
    var currentColor = Color.Unspecified

    return buildAnnotatedString {
        regex.findAll(text).forEach { matchResult ->
            val index = matchResult.range.first
            // Append the text from the previous match to this match
            withStyle(SpanStyle(color = currentColor)) {
                append(text.substring(lastIndex, index))
            }

            // Parse the ANSI color code and update the current color
            val ansiCode = matchResult.groups[1]?.value
            currentColor = ansiCode?.let { ansiColorToComposeColor(it) } ?: Color.Unspecified

            // Update the last index to the end position of the current match
            lastIndex = matchResult.range.last + 1
        }
        // Append all text after the last ANSI sequence
        withStyle(SpanStyle(color = currentColor)) {
            append(text.substring(lastIndex))
        }
    }
}


fun ansiColorToComposeColor(ansiCode: String): Color {
    return when (ansiCode) {
        "30" -> Color.Black
        "31" -> Color.Red.copy(red = 0.8f, green = 0f, blue = 0f)
        "32" -> Color.Green.copy(red = 0f, green = 0.8f, blue = 0f)
        "33" -> Color.Yellow.copy(red = 0.8f, green = 0.8f, blue = 0f)
        "34" -> Color.Blue.copy(red = 0f, green = 0f, blue = 0.8f)
        "35" -> Color.Magenta.copy(red = 0.8f, green = 0f, blue = 0.8f)
        "36" -> Color.Cyan.copy(red = 0f, green = 0.8f, blue = 0.8f)
        "37" -> Color.White.copy(red = 0.9f, green = 0.9f, blue = 0.9f)
        "90" -> Color.DarkGray.copy(red = 0.3f, green = 0.3f, blue = 0.3f)
        "97" -> Color.LightGray.copy(red = 0.7f, green = 0.7f, blue = 0.7f)
        else -> Color.Unspecified
    }
}