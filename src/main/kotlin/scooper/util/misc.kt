package scooper.util

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

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