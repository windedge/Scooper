package scooper.util

import java.lang.Process

fun Process.killTree() {
    this.descendants().forEach { it.destroy() }
    this.destroy()
}