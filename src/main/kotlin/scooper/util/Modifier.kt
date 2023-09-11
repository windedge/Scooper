@file:Suppress("unused")

package scooper.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp

inline fun Modifier.ifTrue(predicate: Boolean, builder: () -> Modifier) =
    then(if (predicate) builder() else Modifier)

fun Modifier.paddingIfHeight(padding: PaddingValues): Modifier = composed {
    val layoutDirection = LocalLayoutDirection.current

    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        var width = placeable.width
        var height = placeable.height
        var posX = 0
        var posY = 0

        if (placeable.height > 0) {
            val startPadding = padding.calculateLeftPadding(layoutDirection).roundToPx()
            val endPadding = padding.calculateRightPadding(layoutDirection).roundToPx()
            val topPadding = padding.calculateTopPadding().roundToPx()
            val bottomPadding = padding.calculateBottomPadding().roundToPx()

            width += (startPadding + endPadding)
            height += (topPadding + bottomPadding)
            posX += startPadding
            posY += topPadding
        }
        layout(width, height) {
            placeable.placeRelative(posX, posY)
        }
    }
}

fun Modifier.bottomBorder(strokeWidth: Dp, color: Color) = composed {
    val density = LocalDensity.current
    val strokeWidthPx = density.run { strokeWidth.toPx() }

    Modifier.drawBehind {
        val width = size.width
        val height = size.height - strokeWidthPx / 2

        drawLine(
            color = color,
            start = Offset(x = 0f, y = height),
            end = Offset(x = width, y = height),
            strokeWidth = strokeWidthPx
        )
    }
}

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
//     cursorOnHover(Cursor(Cursor.TEXT_CURSOR))
//    this.pointerHoverIcon(PointerIconDefaults.Text)
    this.pointerHoverIcon(PointerIcon.Text)
}

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.cursorHand(): Modifier = composed {
//    this.pointerHoverIcon(PointerIconDefaults.Hand)
    this.pointerHoverIcon(PointerIcon.Hand)
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
