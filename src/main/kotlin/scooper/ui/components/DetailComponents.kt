package scooper.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import scooper.ui.components.rememberPainterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import scooper.service.GitHubRelease
import scooper.ui.theme.*
import scooper.util.cursorHand
import scooper.util.safeBrowse
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ReleaseDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

@Composable
fun DetailMetadataRow(label: String, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.width(80.dp),
            style = typography.body2.copy(
                fontWeight = FontWeight.Medium,
                color = colors.textMuted,
            ),
        )
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
fun ReleaseNoteCard(release: GitHubRelease) {
    val colors = MaterialTheme.colors
    val tag = release.tag_name.ifBlank { release.name ?: "Unknown" }
    val date = release.published_at?.let { parseIsoDate(it) }?.format(ReleaseDateFormatter)

    Column(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, colors.borderDefault, RoundedCornerShape(8.dp))
            .background(colors.backgroundHover.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .border(
                        BorderStroke(1.dp, colors.primary.copy(alpha = 0.3f)),
                        RoundedCornerShape(4.dp),
                    )
                    .background(colors.primarySubtle, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    tag,
                    style = typography.overline.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                    ),
                )
            }
            Spacer(Modifier.width(8.dp))
            if (date != null) {
                Text(
                    date,
                    style = typography.caption.copy(color = colors.textMuted),
                )
            }
            Spacer(Modifier.weight(1f))
            if (release.prerelease) {
                Text(
                    "pre-release",
                    style = typography.overline.copy(color = colors.warningDefault),
                )
            }
            if (release.html_url.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Tooltip(release.html_url, position = TooltipPosition.Top) {
                    Icon(
                        rememberPainterResource("external_link_icon.xml"),
                        "View on GitHub",
                        modifier = Modifier.size(12.dp).cursorHand().clickable { safeBrowse(release.html_url) },
                        tint = colors.textMuted,
                    )
                }
            }
        }

        if (!release.body.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            val fullBody = release.body!!
            val collapsedLineCount = 5
            var expanded by remember { mutableStateOf(false) }
            var needsToggle by remember { mutableStateOf(false) }

            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    fullBody,
                    style = typography.body2.copy(
                        color = colors.textBody,
                        fontFamily = FontFamily.Monospace,
                    ),
                    maxLines = if (expanded) Int.MAX_VALUE else collapsedLineCount,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { result ->
                        if (!expanded) needsToggle = result.didOverflowHeight
                    },
                )
            }
            if (needsToggle || expanded) {
                Link(
                    text = if (expanded) "Show less" else "Show more",
                    painter = null,
                    onClicked = { expanded = !expanded },
                )
            }
        }
    }
}

private fun parseIsoDate(isoDate: String): LocalDateTime? {
    return try {
        LocalDateTime.ofInstant(Instant.parse(isoDate), ZoneId.systemDefault())
    } catch (_: Exception) {
        null
    }
}
