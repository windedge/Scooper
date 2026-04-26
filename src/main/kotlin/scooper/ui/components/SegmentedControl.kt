package scooper.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import scooper.ui.theme.*
import scooper.util.cursorHand

@Composable
fun SegmentedControl(
    selected: Int,
    onUpdateCount: Int,
    onSelected: (Int) -> Unit,
) {
    val colors = MaterialTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(color = colors.surface)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pill container
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(color = colors.borderDefault.copy(alpha = 0.6f))
                .border(width = 1.dp, color = colors.borderDefault.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentedTab(
                text = "All Installed",
                selected = selected == 0,
                onClick = { onSelected(0) },
            )
            Spacer(Modifier.width(2.dp))
            SegmentedTab(
                text = "Updates",
                badge = if (onUpdateCount > 0) onUpdateCount else null,
                activeColor = colors.primaryHover,
                selected = selected == 1,
                onClick = { onSelected(1) },
            )
        }
    }
}

@Composable
fun SegmentedTab(
    text: String,
    modifier: Modifier = Modifier,
    badge: Int? = null,
    activeColor: Color = MaterialTheme.colors.textTitle,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colors
    val bgColor = if (selected) colors.surface else Color.Transparent
    val textColor = if (selected) activeColor else colors.unselectedTabText
    val selectedBorderColor = colors.borderDefault.copy(alpha = 0.5f)
    val badgeBg = if (selected) colors.primaryBadgeBg else colors.unselectedBadgeBg
    val badgeText = if (selected) colors.primaryHover else colors.sidebarTextMedium

    Row(
        modifier = modifier
            .then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(6.dp)) else Modifier)
            .clip(RoundedCornerShape(6.dp))
            .background(color = bgColor)
            .then(if (selected) Modifier.border(BorderStroke(1.dp, selectedBorderColor), RoundedCornerShape(6.dp)) else Modifier)
            .cursorHand()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = typography.body2.copy(
                fontWeight = FontWeight.Medium,
                color = textColor,
            ),
        )
        if (badge != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(color = badgeBg, shape = RoundedCornerShape(50))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    "$badge",
                    style = typography.caption.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = typography.caption.fontSize * (11f / 14f),
                        color = badgeText,
                    ),
                )
            }
        }
    }
}
