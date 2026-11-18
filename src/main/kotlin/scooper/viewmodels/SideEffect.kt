package scooper.viewmodels

import scooper.util.LocalizedText
import scooper.util.tr

enum class ToastType {
    SUCCESS,
    ERROR,
    INFO,
    /** The command succeeded but did nothing worth reporting as success (skipped work). */
    WARNING,
}

sealed class AppSideEffect {
    /** Structured toast copy, rendered through tr() at snackbar display time
     *  (Main.kt) so a language switch re-translates it. Dynamic / secret-masked
     *  payloads keep their already-resolved text as [LocalizedText.raw]. */
    data class Toast(val text: LocalizedText, val type: ToastType = ToastType.INFO) : AppSideEffect()
    data class Log(val text: String) : AppSideEffect()
}

/** Build a Toast side effect for a task that succeeded ([resultCode] == 0) or failed. */
fun taskToast(action: String, name: String, resultCode: Int): AppSideEffect.Toast {
    val success = resultCode == 0
    val type = if (success) ToastType.SUCCESS else ToastType.ERROR
    val text = when (action) {
        "Install" ->
            if (success) LocalizedText(
                msgid = "{{name}} has been installed.",
                args = listOf("name" to name),
            )
            else LocalizedText(
                msgid = "Failed to install {{name}}.",
                args = listOf("name" to name),
            )
        "Uninstall" ->
            if (success) LocalizedText(
                msgid = "{{name}} has been uninstalled.",
                args = listOf("name" to name),
            )
            else LocalizedText(
                msgid = "Failed to uninstall {{name}}.",
                args = listOf("name" to name),
            )
        "Update" ->
            if (success) LocalizedText(
                msgid = "{{name}} has been updated.",
                args = listOf("name" to name),
            )
            else LocalizedText(
                msgid = "Failed to update {{name}}.",
                args = listOf("name" to name),
            )
        "Download" ->
            if (success) LocalizedText(
                msgid = "{{name}} has been downloaded.",
                args = listOf("name" to name),
            )
            else LocalizedText(
                msgid = "Failed to download {{name}}.",
                args = listOf("name" to name),
            )
        "Remove bucket" ->
            if (success) LocalizedText(
                msgid = "{{name}} has been removed.",
                args = listOf("name" to name),
            )
            else LocalizedText(
                msgid = "Failed to remove bucket {{name}}.",
                args = listOf("name" to name),
            )
        else -> error("Unknown task action: $action")
    }
    return AppSideEffect.Toast(text, type)
}
