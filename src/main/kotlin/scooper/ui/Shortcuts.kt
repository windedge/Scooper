package scooper.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import scooper.util.navigation.core.BackStack
import scooper.viewmodels.AppsViewModel

/** All global shortcut definitions. */
enum class ShortcutAction(val label: String, val keys: String) {
    FocusSearch("Focus Search", "Ctrl+F"),
    RefreshScoop("Refresh Scoop", "Ctrl+R / F5"),
    ReloadApps("Reload Local Apps", "Ctrl+Shift+R"),
    NavDiscover("Navigate to Discover", "Ctrl+1"),
    NavInstalled("Navigate to Installed", "Ctrl+2"),
    NavBuckets("Navigate to Buckets", "Ctrl+3"),
    NavCleanup("Navigate to Cleanup", "Ctrl+4"),
    OpenSettings("Open Settings", "Ctrl+,"),
    OpenOutput("View Logs / Output", "Ctrl+L / Ctrl+`"),
    GoBack("Go Back", "Esc"),
}

/**
 * Match a [KeyEvent] to a [ShortcutAction], or null if no shortcut matches.
 * Delegates to [matchShortcutKey] with extracted key and modifiers.
 */
fun matchShortcut(event: KeyEvent): ShortcutAction? =
    matchShortcutKey(event.key, event.isCtrlPressed, event.isShiftPressed)

/**
 * Pure-function shortcut matching. Uses [Key] directly so it is testable
 * without a real Compose [KeyEvent] (which requires a native backend).
 */
fun matchShortcutKey(key: Key, ctrl: Boolean = false, shift: Boolean = false): ShortcutAction? {
    // Esc — always handle (back / dismiss)
    if (key == Key.Escape) return ShortcutAction.GoBack

    // Ctrl+Shift+R — reload local apps
    if (ctrl && shift && key == Key.R) return ShortcutAction.ReloadApps

    // Ctrl+R — refresh scoop
    if (ctrl && !shift && key == Key.R) return ShortcutAction.RefreshScoop

    // F5 — refresh scoop
    if (key == Key.F5) return ShortcutAction.RefreshScoop

    // Ctrl+F — focus search
    if (ctrl && key == Key.F) return ShortcutAction.FocusSearch

    // Ctrl+1..4 — navigation
    if (ctrl && key == Key.One) return ShortcutAction.NavDiscover
    if (ctrl && key == Key.Two) return ShortcutAction.NavInstalled
    if (ctrl && key == Key.Three) return ShortcutAction.NavBuckets
    if (ctrl && key == Key.Four) return ShortcutAction.NavCleanup

    // Ctrl+, — settings
    if (ctrl && key == Key.Comma) return ShortcutAction.OpenSettings

    // Ctrl+L or Ctrl+` — output / logs
    if (ctrl && key == Key.L) return ShortcutAction.OpenOutput
    if (ctrl && key == Key.Grave) return ShortcutAction.OpenOutput

    return null
}

/** Handle a Window-level key event, dispatching to the appropriate action. */
fun handleWindowShortcut(
    keyEvent: KeyEvent,
    navigator: BackStack<AppRoute>?,
    appsViewModel: AppsViewModel,
    onFocusSearch: () -> Unit,
): Boolean {
    val nav = navigator ?: return false
    val action = matchShortcut(keyEvent)
    if (action == null || keyEvent.type != KeyEventType.KeyDown) return false

    return when (action) {
        ShortcutAction.FocusSearch -> {
            val current = nav.current.value
            if (current is AppRoute.Apps) {
                onFocusSearch()
                true
            } else false
        }
        ShortcutAction.RefreshScoop -> {
            appsViewModel.scheduleUpdateApps()
            true
        }
        ShortcutAction.ReloadApps -> {
            appsViewModel.scheduleReloadApps()
            true
        }
        ShortcutAction.NavDiscover -> {
            val current = nav.current.value
            if (current !is AppRoute.Apps || current.scope.isNotEmpty()) {
                nav.popupAllAndPush(AppRoute.Apps(scope = ""))
            }
            true
        }
        ShortcutAction.NavInstalled -> {
            val current = nav.current.value
            if (current !is AppRoute.Apps || (current.scope != "installed" && current.scope != "updates")) {
                nav.popupAllAndPush(AppRoute.Apps(scope = "installed"))
            }
            true
        }
        ShortcutAction.NavBuckets -> {
            if (nav.current.value !is AppRoute.Buckets) {
                nav.popupAllAndPush(AppRoute.Buckets)
            }
            true
        }
        ShortcutAction.NavCleanup -> {
            if (nav.current.value !is AppRoute.Cleanup) {
                nav.popupAllAndPush(AppRoute.Cleanup)
            }
            true
        }
        ShortcutAction.OpenSettings -> {
            if (nav.current.value !is AppRoute.Settings) {
                nav.push(AppRoute.Settings.General)
            }
            true
        }
        ShortcutAction.OpenOutput -> {
            if (nav.current.value != AppRoute.Output) {
                nav.push(AppRoute.Output)
            }
            true
        }
        ShortcutAction.GoBack -> nav.pop()
    }
}
