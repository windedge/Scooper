package scooper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import scooper.util.cursorHand
import scooper.util.onHover

@Composable
fun MenuItem(
    text: String = "",
    modifier: Modifier = Modifier,
    selectItem: MutableState<String>,
    indent: Int = 30,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    var hover by remember { mutableStateOf(false) }
    val highlight = hover || selectItem.value == text
    var default = Modifier
        .fillMaxWidth()
        .padding(2.dp)
        .height(35.dp)
        .background(color = if (highlight) colors.primary else Color.Unspecified)
        .cursorHand()
    if (onClick != null) {
        default = default.onHover { hover = it }.clickable {
            onClick.invoke()
            selectItem.value = text
        }
    }

    val combined = modifier.then(default)
    Row(
        combined,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(indent.dp))

        val color = if (highlight) colors.onPrimary else colors.onSurface
        if (icon != null) {
            Icon(
                icon,
                "",
                modifier = Modifier.size(20.dp),
                tint = if (highlight) colors.onPrimary else colors.onSecondary
            )
        }
        Text(text, color = color)
    }
}

