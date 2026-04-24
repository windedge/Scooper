package scooper.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

enum class TooltipPosition {
    Top, Bottom
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun Tooltip(
    text: String = "...",
    position: TooltipPosition = TooltipPosition.Bottom,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val edgeThresholdPx = with(density) { 96.dp.toPx() }
    var anchorTop by remember { mutableStateOf(0f) }
    var anchorBottom by remember { mutableStateOf(0f) }

    val spaceAbove = anchorTop
    val spaceBelow = windowInfo.containerSize.height - anchorBottom
    val resolvedPosition = when {
        position == TooltipPosition.Top && spaceAbove < edgeThresholdPx && spaceBelow > spaceAbove -> TooltipPosition.Bottom
        position == TooltipPosition.Bottom && spaceBelow < edgeThresholdPx && spaceAbove > spaceBelow -> TooltipPosition.Top
        else -> position
    }

    val tooltipPlacement = when (resolvedPosition) {
        TooltipPosition.Top -> {
            TooltipPlacement.ComponentRect(
                anchor = Alignment.TopCenter,
                alignment = Alignment.BottomCenter,
                offset = DpOffset(0.dp, (-36).dp)
            )
        }

        TooltipPosition.Bottom -> {
            TooltipPlacement.ComponentRect(
                anchor = Alignment.BottomCenter,
                alignment = Alignment.TopCenter,
                offset = DpOffset(0.dp, 48.dp)
            )
        }
    }
    TooltipArea(
        tooltip = {
            Surface(
                modifier = Modifier.shadow(4.dp),
                color = MaterialTheme.colors.surface.copy(alpha = 0.95f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(10.dp),
                    color = MaterialTheme.colors.onSurface,
                    style = MaterialTheme.typography.caption,
                )
            }
        },
        delayMillis = 600, // in millisecond
        tooltipPlacement = tooltipPlacement
    ) {
        Box(
            modifier = Modifier.onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                anchorTop = bounds.top
                anchorBottom = bounds.bottom
            }
        ) {
            content()
        }
    }
}