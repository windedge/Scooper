package scooper.util

import java.io.File


fun Process.killTree() {
    this.descendants().forEach { it.destroy() }
    this.destroy()
}

fun findExecutable(name: String): String? {
    for (dirname in System.getenv("PATH").split(File.pathSeparator)) {
        val file = File(dirname, name)
        if (file.isFile && file.canExecute()) {
            return file.absolutePath
        }
    }
    // throw AssertionError("should have found the executable")
    return null
}