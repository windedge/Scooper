package scooper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import scooper.ui.components.FpsCounter
import scooper.ui.components.FpsLabel
import scooper.ui.components.Tooltip
import scooper.ui.components.TooltipPosition
import scooper.ui.theme.*
import scooper.util.cursorHand
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack
import scooper.util.noRippleClickable
import scooper.util.removeAnsiColor

@Suppress("UNCHECKED_CAST")
@Composable
fun StatusBar(statusText: String) {
    val colors = MaterialTheme.colors
    val navigator: BackStack<AppRoute> = LocalBackStack.current as BackStack<AppRoute>
    val showFpsState = LocalShowFps.current
    val showFps by showFpsState
    var fps by remember { mutableStateOf(0) }

    Surface(
        modifier = Modifier.fillMaxWidth().height(28.dp),
        color = colors.statusBarBg,
        border = BorderStroke(1.dp, colors.borderDefault)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: FPS display
            if (showFps) {
                FpsCounter(onFps = { fps = it })
                FpsLabel(fps)
            }

            Spacer(Modifier.weight(1f))

            // Right: Logs toggle & Clock
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Console Toggle
                Tooltip("Show Console Logs", position = TooltipPosition.Top) {
                    Box(
                        modifier = Modifier
                            .height(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.borderDefault, RoundedCornerShape(4.dp))
                            .cursorHand()
                            .noRippleClickable {
                                if (navigator.current.value != AppRoute.Output) {
                                    navigator.push(AppRoute.Output)
                                } else {
                                    navigator.pop()
                                }
                            }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val annotatedString = removeAnsiColor(statusText)
                        Text(
                            annotatedString.ifBlank { "Console" },
                            style = MaterialTheme.typography.caption.copy(color = colors.sidebarTextMedium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
