package scooper.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import scooper.util.bottomBorder
import scooper.util.cursorHand
import scooper.util.noRippleClickable
import scooper.util.onHover

@Composable
fun Link(
    text: String = "",
    modifier: Modifier = Modifier,
    painter: Painter? = painterResource("external_link_icon.xml"),
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
            fontSize = 16.sp,
            color = colors.primary,
            // textDecoration = if (hover) TextDecoration.Underline else TextDecoration.None
        )
        if (painter != null) {
            Icon(
                painter,
                contentDescription = "Open Link",
                tint = colors.primary
            )
        }
    }
}