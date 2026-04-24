package scooper.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import scooper.data.App
import scooper.data.AppVersion
import scooper.data.AppVersionSource
import scooper.ui.theme.*
import scooper.util.cursorHand
import scooper.util.onHover
import java.time.format.DateTimeFormatter

private val DateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
fun VersionPickerDialog(
    app: App,
    versions: List<AppVersion>,
    loading: Boolean,
    error: String?,
    onInstall: (AppVersion, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties()
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderDefault),
            elevation = 4.dp,
            modifier = Modifier.width(480.dp).heightIn(max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Title
                Text(
                    "Select Version — ${app.name}",
                    style = typography.h6.copy(color = colors.onSurface),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${versions.size} version(s) available",
                    style = typography.caption.copy(color = colors.textMuted),
                )
                Spacer(Modifier.height(12.dp))

                // Divider
                Divider(color = colors.divider)

                when {
                    loading -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    error != null -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(error, style = typography.body2.copy(color = colors.dangerDefault))
                        }
                    }
                    versions.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("No versions available", style = typography.body2.copy(color = colors.textMuted))
                        }
                    }
                    else -> {
                        val listState = rememberLazyListState()
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().padding(end = 8.dp),
                            ) {
                                items(versions) { version ->
                                    VersionRow(
                                        version = version,
                                        isCurrentVersion = version.version == app.version,
                                        onInstall = { global -> onInstall(version, global) },
                                    )
                                }
                            }
                            VerticalScrollbar(
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().background(colors.surface),
                                adapter = rememberScrollbarAdapter(listState),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.cursorHand()) {
                        Text("Close", style = typography.body2.copy(color = colors.textMuted))
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionRow(
    version: AppVersion,
    isCurrentVersion: Boolean,
    onInstall: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colors
    var isHover by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onHover { isHover = it }
                .background(if (isHover) colors.backgroundHover else colors.Transparent)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Version text
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        version.version,
                        style = typography.body2.copy(
                            fontWeight = if (isCurrentVersion) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrentVersion) colors.primary else colors.onSurface,
                        ),
                    )
                    if (isCurrentVersion) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "(installed)",
                            style = typography.caption.copy(color = colors.primary),
                        )
                    }
                }
                if (version.source == AppVersionSource.Git || version.commitTime != null || version.message != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        if (version.source == AppVersionSource.Git) {
                            val sourceColor = colors.primary
                            Box(
                                modifier = Modifier
                                    .border(
                                        androidx.compose.foundation.BorderStroke(1.dp, sourceColor.copy(alpha = 0.3f)),
                                        RoundedCornerShape(3.dp),
                                    )
                                    .background(sourceColor.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Text("Git", style = typography.overline.copy(color = sourceColor, fontWeight = FontWeight.Medium))
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        version.commitTime?.let {
                            Text(
                                it.format(DateFormatter),
                                style = typography.caption.copy(color = colors.textMuted),
                            )
                        }
                        if (version.message != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                version.message!!.take(40),
                                style = typography.caption.copy(color = colors.textMuted),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // Install buttons
            if (!isCurrentVersion) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { onInstall(false) },
                        modifier = Modifier.cursorHand(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("Install", style = typography.caption.copy(color = colors.primary))
                    }
                    TextButton(
                        onClick = { onInstall(true) },
                        modifier = Modifier.cursorHand(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("Global", style = typography.caption.copy(color = colors.textMuted))
                    }
                }
            }
        }
        Divider(color = colors.divider)
    }
}

private val Colors.Transparent get() = androidx.compose.ui.graphics.Color.Transparent
