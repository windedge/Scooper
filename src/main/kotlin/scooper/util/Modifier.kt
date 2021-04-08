package scooper.util

import androidx.compose.desktop.LocalAppWindow
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerMoveFilter
import java.awt.Cursor

fun Modifier.onHover(block: @Composable Modifier.(on: Boolean) -> Unit): Modifier = composed {
    var hover by remember { mutableStateOf(false) }
    block(hover)
    pointerMoveFilter(
        onEnter = { hover = true; false },
        onExit = { hover = false; false }
    )
}

fun Modifier.cursorOnHover(cursor: Cursor): Modifier = onHover { on ->
    if (on) {
        LocalAppWindow.current.window.cursor = cursor
    } else {
        LocalAppWindow.current.window.cursor = Cursor.getDefaultCursor()
    }
}

fun Modifier.cursorInput(): Modifier = composed {
    cursorOnHover(Cursor(Cursor.TEXT_CURSOR))
}

fun Modifier.cursorHand(): Modifier = composed {
    cursorOnHover(Cursor(Cursor.HAND_CURSOR))
}

