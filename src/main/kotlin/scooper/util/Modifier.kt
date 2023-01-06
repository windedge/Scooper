package scooper.util

import androidx.compose.foundation.background
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import scooper.LocalWindow
import java.awt.Cursor

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.onHover(block: @Composable Modifier.(on: Boolean) -> Unit): Modifier = composed {
    var hover by remember { mutableStateOf(false) }
    block(hover)
    val modifier = onPointerEvent(PointerEventType.Enter) { hover = true }
            .onPointerEvent(PointerEventType.Exit) { hover = false }

    modifier
}

fun Modifier.cursorOnHover(cursor: Cursor): Modifier = onHover { on ->
    if (on) {
        // LocalAppWindow.current.window.cursor = cursor
        LocalWindow.current.cursor = cursor
    } else {
        // LocalAppWindow.current.window.cursor = Cursor.getDefaultCursor()
        LocalWindow.current.cursor = Cursor.getDefaultCursor()
    }
}

fun Modifier.cursorInput(): Modifier = composed {
    cursorOnHover(Cursor(Cursor.TEXT_CURSOR))
}

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.cursorLink(
    hoverModifier: Modifier = Modifier,
    onHover: @Composable (on: Boolean) -> Unit = {}
): Modifier = composed {
    var hover by remember { mutableStateOf(false) }
    onHover(hover)
    var default = onPointerEvent(PointerEventType.Enter) {
        hover = true
    }
        .onPointerEvent(PointerEventType.Exit) {
            hover = false
        }
    if (hover) {
        // LocalAppWindow.current.window.cursor = Cursor(Cursor.HAND_CURSOR)
        LocalWindow.current.cursor = Cursor(Cursor.HAND_CURSOR)
        default = default.background(color = MaterialTheme.colors.background).then(hoverModifier)
    } else {
        // LocalAppWindow.current.window.cursor = Cursor.getDefaultCursor()
        LocalWindow.current.cursor = Cursor.getDefaultCursor()
    }
    default
}

fun Modifier.cursorHand(): Modifier = cursorLink()
