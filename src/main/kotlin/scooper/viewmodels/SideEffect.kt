package scooper.viewmodels

import scooper.util.tr

enum class ToastType {
    SUCCESS,
    ERROR,
    INFO,
}

sealed class AppSideEffect {
    data class Toast(val text: String, val type: ToastType = ToastType.INFO) : AppSideEffect()
    data class Log(val text: String) : AppSideEffect()
}

/** Build a Toast side effect for a task that succeeded ([resultCode] == 0) or failed. */
fun taskToast(action: String, name: String, resultCode: Int): AppSideEffect.Toast {
    val success = resultCode == 0
    val type = if (success) ToastType.SUCCESS else ToastType.ERROR
    val (past, lower) = when (action) {
        "Install" -> "installed" to "install"
        "Uninstall" -> "uninstalled" to "uninstall"
        "Update" -> "updated" to "update"
        "Download" -> "downloaded" to "download"
        "Remove bucket" -> "removed" to "remove bucket"
        else -> action to action
    }
    val text = if (success) tr("{{name}} has been {{past}}.", "name" to name, "past" to past) else tr("Failed to {{lower}} {{name}}.", "lower" to lower, "name" to name)
    return AppSideEffect.Toast(text, type)
}
