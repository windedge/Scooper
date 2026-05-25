package scooper.viewmodels

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
    val pastAction = when (action) {
        "Install" -> "Installed"
        "Uninstall" -> "Uninstalled"
        "Update" -> "Updated"
        "Download" -> "Downloaded"
        "Remove bucket" -> "Removed"
        else -> action
    }
    val text = if (success) "$pastAction $name" else "Failed to $action $name"
    return AppSideEffect.Toast(text, type)
}
