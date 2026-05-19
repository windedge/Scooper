package scooper.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography

import scooper.ui.icons.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import scooper.util.bottomBorder
import scooper.util.cursorHand
import scooper.util.noRippleClickable
import scooper.util.onHover

@Composable
fun Link(
    text: String = "",
    modifier: Modifier = Modifier,
    painter: Painter? = rememberVectorPainter(Lucide.ExternalLink),
    onClicked: () -> Unit
) {
    var hover by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.then(
            Modifier.cursorHand().noRippleClickable(onClicked).onHover { hover = it }
                .let { if (hover) it.bottomBorder(1.dp, color = colors.primary) else it }
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = typography.body1.copy(color = colors.primary),
            // textDecoration = if (hover) TextDecoration.Underline else TextDecoration.None
        )
        if (painter != null) {
            Icon(
                painter,
                contentDescription = "Open Link",
                modifier = Modifier.size(14.dp),
                tint = colors.primary
            )
        }
    }
}