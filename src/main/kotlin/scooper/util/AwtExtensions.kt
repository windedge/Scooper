package scooper.util

import java.awt.Desktop
import java.awt.Window

// ref: https://stackoverflow.com/questions/309023/how-to-bring-a-window-to-the-front
fun Window.bringToFront() {
    isVisible = true
    setAlwaysOnTop(true)
    toFront()
    requestFocus()
    setAlwaysOnTop(false)

    // see: https://github.com/JetBrains/compose-multiplatform/issues/4231
    if (Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
        Desktop.getDesktop().requestForeground(true)
    }
}