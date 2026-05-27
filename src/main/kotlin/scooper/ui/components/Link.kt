package scooper.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import scooper.ui.icons.*
import scooper.util.cursorHand
import scooper.util.noRippleClickable
import scooper.util.onHover

private data class LineMetrics(val top: Float, val bottom: Float, val right: Float)

@Composable
fun Link(
    text: String = "",
    modifier: Modifier = Modifier,
    painter: Painter? = rememberVectorPainter(Lucide.ExternalLink),
    onClicked: () -> Unit
) {
    var hover by remember { mutableStateOf(false) }
    val linkColor = colors.primary
    val iconSize = 14.dp
    val iconSpacing = 4.dp
    val density = LocalDensity.current
    val iconSizePx = with(density) { iconSize.toPx() }
    val iconSpacingPx = with(density) { iconSpacing.toPx() }
    val strokeWidthPx = with(density) { 1.dp.toPx() }

    var lines by remember { mutableStateOf<List<LineMetrics>>(emptyList()) }
    val hasIcon = painter != null
    val hasLines = lines.isNotEmpty()

    Box(
        modifier = modifier.then(
            Modifier.cursorHand().noRippleClickable(onClicked).onHover { hover = it }
                .then(if (hasIcon) Modifier.fillMaxWidth() else Modifier)
                .drawBehind {
                    if (!hover) return@drawBehind
                    lines.forEachIndexed { index, line ->
                        val endX = if (hasIcon && index == lines.lastIndex)
                            line.right + iconSpacingPx + iconSizePx
                        else
                            line.right
                        drawLine(
                            color = linkColor,
                            start = Offset(0f, line.bottom - strokeWidthPx / 2),
                            end = Offset(endX, line.bottom - strokeWidthPx / 2),
                            strokeWidth = strokeWidthPx,
                        )
                    }
                }
        ),
    ) {
        Text(
            text,
            style = typography.body1.copy(color = linkColor),
            modifier = if (hasIcon) Modifier.padding(end = iconSize + iconSpacing) else Modifier,
            onTextLayout = { result: TextLayoutResult ->
                lines = (0 until result.lineCount).map { lineIdx ->
                    LineMetrics(
                        top = result.getLineTop(lineIdx),
                        bottom = result.getLineBottom(lineIdx),
                        right = result.getLineRight(lineIdx),
                    )
                }
            },
        )

        if (hasIcon && hasLines) {
            val last = lines.last()
            val iconX = last.right + iconSpacingPx
            val iconY = (last.top + last.bottom) / 2 - iconSizePx / 2

            Icon(
                painter!!,
                contentDescription = "Open Link",
                modifier = Modifier
                    .size(iconSize)
                    .offset { IntOffset(iconX.toInt(), iconY.toInt()) },
                tint = linkColor,
            )
        }
    }
}
