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
import scooper.ui.components.TooltipPostion
import scooper.util.cursorHand
import scooper.util.noRippleClickable

@Composable
fun StatusBar(statusText: String, onClick: () -> Unit) {
    ProvideTextStyle(MaterialTheme.typography.caption.copy(color = MaterialTheme.colors.onSecondary)) {
        Column {
            // Divider(color = colors.onBackground)
            Tooltip("Show Console Logs", position = TooltipPostion.Top) {
                Box(modifier = Modifier.fillMaxWidth().cursorHand()
                    .noRippleClickable { onClick() })
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