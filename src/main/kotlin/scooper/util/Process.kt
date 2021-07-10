package scooper.util

fun Process.killTree() {
    this.descendants().forEach { it.destroy() }
    this.destroy()
}