package scooper.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.ProvideTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import scooper.ui.components.Tooltip
import scooper.ui.components.TooltipPosition
import scooper.util.cursorHand
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack
import scooper.util.noRippleClickable

@Suppress("UNCHECKED_CAST")
@Composable
fun StatusBar(statusText: String) {
    val navigator: BackStack<AppRoute> = LocalBackStack.current as BackStack<AppRoute>
    ProvideTextStyle(MaterialTheme.typography.caption.copy(color = MaterialTheme.colors.onSecondary)) {
        Column {
            // Divider(color = colors.onBackground)
            Tooltip("Show Console Logs", position = TooltipPosition.Top) {
                Box(modifier = Modifier.fillMaxWidth().cursorHand()
                    .noRippleClickable {
                        if (navigator.current.value != AppRoute.Output) {
                            navigator.push(AppRoute.Output)
                        } else {
                            navigator.pop()
                        }
                    })
                {
                    Text(
                        statusText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}