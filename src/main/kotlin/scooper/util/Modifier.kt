package scooper.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.*

fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = composed {
    clickable(indication = null,
        interactionSource = remember { MutableInteractionSource() }) {
        onClick()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.onHover(block: @Composable Modifier.(on: Boolean) -> Unit): Modifier = composed {
    var hover by remember { mutableStateOf(false) }
    block(hover)
    val modifier = onPointerEvent(PointerEventType.Enter) { hover = true }
        .onPointerEvent(PointerEventType.Exit) { hover = false }

    modifier
}

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.cursorInput(): Modifier = composed {
    // cursorOnHover(Cursor(Cursor.TEXT_CURSOR))
    this.pointerHoverIcon(PointerIconDefaults.Text)
}

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.cursorHand(): Modifier = composed {
    this.pointerHoverIcon(PointerIconDefaults.Hand)
}

fun Modifier.cursorLink(): Modifier = cursorHand()

/*
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.cursorLink(
    hoverModifier: Modifier = Modifier,
    onHover: @Composable (on: Boolean) -> Unit = {}
): Modifier = composed {
    var hover by remember { mutableStateOf(false) }
    onHover(hover)
    val default = onPointerEvent(PointerEventType.Enter) {
        hover = true
    }.onPointerEvent(PointerEventType.Exit) {
        hover = false
    }
    if (hover) {
        default.pointerHoverIcon(PointerIconDefaults.Hand).background(color = MaterialTheme.colors.background)
            .then(hoverModifier)
    } else {
        default.pointerHoverIcon(PointerIconDefaults.Default)
    }
}
*/
