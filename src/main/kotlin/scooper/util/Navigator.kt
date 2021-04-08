package scooper.scooper.util

import moe.tlaster.precompose.navigation.NavOptions
import moe.tlaster.precompose.navigation.Navigator

fun Navigator.replaceCurrent(route: String, options: NavOptions? = null) {
    if (canGoBack) {
        goBack()
    }
    navigate(route, options)
}

val Navigator.currentRoute: String
    get() {
        return this::class.java.getDeclaredField("stackManager").let {
            it.isAccessible = true
            val stackManager = it.get(this)
            if (stackManager != null) {
                println("stackManager::class.qualifiedName = ${stackManager::class.qualifiedName}")
            }
            return@let ""
        }
    }