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
    val text = when (action) {
        "Install" ->
            if (success) tr("{{name}} has been installed.", "name" to name)
            else tr("Failed to install {{name}}.", "name" to name)
        "Uninstall" ->
            if (success) tr("{{name}} has been uninstalled.", "name" to name)
            else tr("Failed to uninstall {{name}}.", "name" to name)
        "Update" ->
            if (success) tr("{{name}} has been updated.", "name" to name)
            else tr("Failed to update {{name}}.", "name" to name)
        "Download" ->
            if (success) tr("{{name}} has been downloaded.", "name" to name)
            else tr("Failed to download {{name}}.", "name" to name)
        "Remove bucket" ->
            if (success) tr("{{name}} has been removed.", "name" to name)
            else tr("Failed to remove bucket {{name}}.", "name" to name)
        else -> error("Unknown task action: $action")
    }
    return AppSideEffect.Toast(text, type)
}
