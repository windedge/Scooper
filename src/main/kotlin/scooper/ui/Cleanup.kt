package scooper.ui


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.material.icons.twotone.KeyboardArrowUp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import scooper.repository.CleanupRepository
import scooper.repository.OldVersion
import scooper.ui.components.IconButton
import scooper.ui.components.Link
import scooper.ui.components.Tooltip
import scooper.ui.theme.*
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.util.readableSize
import scooper.viewmodels.CleanupState
import scooper.viewmodels.CleanupViewModel
import java.awt.Desktop


@Composable
fun CleanupScreen(
    cleanupViewModel: CleanupViewModel = koinInject(),
    cleanupRepository: CleanupRepository = koinInject(),
) {
    val state by cleanupViewModel.container.stateFlow.collectAsState()
    LaunchedEffect(Unit) {
        if (state.cacheSize < 0 && !state.scanningCache) {
            cleanupViewModel.computeCacheSize()
        }
        if (state.totalOldSize < 0 && !state.scanningOldVersion) {
            cleanupViewModel.computeOldVersions()
        }
    }

    val scrollState = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 860.dp)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 48.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            // Page Title
            Text(
                "Cleanup",
                style = MaterialTheme.typography.h5.copy(
                    fontWeight = FontWeight.Bold,
                    color = colors.textTitle,
                )
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Free up disk space by removing old versions and downloaded cache files.",
                style = MaterialTheme.typography.body2.copy(color = colors.textBody)
            )

            Spacer(Modifier.height(32.dp))

            // Download Cache Card
            CleanupCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Download Cache",
                        style = MaterialTheme.typography.subtitle1.copy(
                            fontWeight = FontWeight.Medium,
                            color = colors.textTitle,
                        )
                    )
                    Tooltip("Rescan") {
                        IconButton(
                            onClick = { cleanupViewModel.computeCacheSize() },
                            modifier = Modifier.cursorLink(),
                        ) {
                            Icon(
                                painterResource("sync.svg"),
                                contentDescription = "Rescan",
                                tint = colors.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))

                // Cache content centered
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Total Size: ${state.cacheSize.readableSize()}",
                        style = MaterialTheme.typography.body1.copy(color = colors.textBody)
                    )
                    Spacer(Modifier.height(16.dp))
                    CacheActionButtons(
                        state = state,
                        onClean = { cleanupViewModel.clearCache() },
                        onScan = { cleanupViewModel.computeCacheSize() },
                        onOpen = { Desktop.getDesktop().open(cleanupRepository.cacheDir) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Old Versions Card
            CleanupCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Old Versions",
                        style = MaterialTheme.typography.subtitle1.copy(
                            fontWeight = FontWeight.Medium,
                            color = colors.textTitle,
                        )
                    )
                    if (!state.scanningOldVersion) {
                        Tooltip("Rescan") {
                            IconButton(
                                onClick = { cleanupViewModel.computeOldVersions() },
                                modifier = Modifier.cursorLink(),
                            ) {
                                Icon(
                                    painterResource("sync.svg"),
                                    contentDescription = "Rescan",
                                    tint = colors.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))

                OldVersions(
                    oldVersions = state.oldVersions,
                    totalSize = state.totalOldSize,
                    scanning = state.scanningOldVersion,
                    cleaning = state.cleaningOldVersions,
                    onDelete = { cleanupViewModel.cleanup(*it.toTypedArray()) },
                )
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}


@Composable
private fun CleanupCard(content: @Composable () -> Unit) {
    val cardBg = if (colors.isLight) colors.surface else Slate800
    Card(
        backgroundColor = cardBg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.borderDefault),
        elevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            content()
        }
    }
}


@Composable
private fun CacheActionButtons(
    state: CleanupState,
    onClean: () -> Unit,
    onScan: () -> Unit,
    onOpen: () -> Unit,
) {
    if (state.cleaningCache) {
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.height(35.dp),
        ) {
            Text("Cleaning...")
        }
    } else if (state.scanningCache) {
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.height(35.dp),
        ) {
            Text("Scanning...")
        }
    } else {
        ProvideTextStyle(MaterialTheme.typography.button.copy(color = colors.primary)) {
            Row(
                modifier = Modifier
                    .border(border = ButtonDefaults.outlinedBorder, shape = RoundedCornerShape(4.dp))
                    .height(35.dp)
            ) {
                if (state.cacheSize > 0L) {
                    Row(
                        modifier = Modifier.fillMaxHeight().cursorHand().clickable { onClean() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.TwoTone.Delete,
                            contentDescription = "Clear Cache",
                            tint = colors.primary,
                            modifier = Modifier.size(30.dp).padding(start = 5.dp, top = 5.dp, bottom = 5.dp),
                        )
                        Text("Clear Cache", modifier = Modifier.padding(end = 5.dp))
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxHeight().cursorHand().clickable { onScan() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painterResource("sync.svg"),
                            "",
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp).padding(start = 5.dp),
                        )
                        Text("Rescan", modifier = Modifier.padding(horizontal = 4.dp))
                        Icon(
                            painterResource("box.xml"),
                            "",
                            tint = colors.primary,
                            modifier = Modifier.size(16.dp).padding(end = 5.dp),
                        )
                    }
                }
                Divider(modifier = Modifier.width(1.dp).height(20.dp).align(Alignment.CenterVertically))
                Tooltip("Open Directory") {
                    Box(modifier = Modifier.clickable { onOpen() }) {
                        Icon(
                            painterResource("folder.svg"),
                            "",
                            tint = colors.primary,
                            modifier = Modifier.fillMaxHeight().padding(5.dp).width(25.dp).cursorLink(),
                        )
                    }
                }
            }
        }
    }
}


private const val MAX_ENTRIES = 3

@Composable
private fun OldVersions(
    oldVersions: List<OldVersion>? = null,
    totalSize: Long = 0L,
    scanning: Boolean = false,
    cleaning: Boolean = false,
    onDelete: (List<OldVersion>) -> Unit = {},
) {
    if (oldVersions == null || scanning) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(30.dp))
        }
        return
    }

    if (oldVersions.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth().height(40.dp).padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Great, old versions have been cleaned up.", color = colors.textBody)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Summary row: total size + clean all button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Total Size: ${totalSize.readableSize()}", color = colors.textBody)
            if (cleaning) {
                Box(
                    modifier = Modifier.height(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                Button(
                    onClick = { onDelete(oldVersions) },
                    modifier = Modifier.cursorHand(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text("Clean All")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Versions list
        var showMore by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(0.8.dp, color = colors.borderDefault, shape = MaterialTheme.shapes.medium)
                .clip(MaterialTheme.shapes.medium)
                .background(color = colors.background),
        ) {
            oldVersions
                .let { if (!showMore && it.size > MAX_ENTRIES) it.filterIndexed { index, _ -> index < MAX_ENTRIES } else it }
                .forEachIndexed { idx, oldVersion ->
                    if (idx > 0) {
                        Divider()
                    }
                    OldVersionRow(oldVersion, onDelete = { onDelete(listOf(oldVersion)) })
                }
        }

        if (oldVersions.size > MAX_ENTRIES) {
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                val text = if (showMore) "Show less" else "Show more"
                val down = rememberVectorPainter(Icons.TwoTone.KeyboardArrowDown)
                val up = rememberVectorPainter(Icons.TwoTone.KeyboardArrowUp)
                val icon = if (showMore) up else down
                Link(text, painter = icon, onClicked = { showMore = !showMore })
            }
        }
    }
}


@Composable
private fun OldVersionRow(oldVersion: OldVersion, onDelete: (OldVersion) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Link(
                    oldVersion.app,
                    onClicked = { Desktop.getDesktop().open(oldVersion.appDir) },
                )
                Icon(
                    painterResource("external_link_icon.xml"),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = colors.primary.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "${oldVersion.paths.size} old versions",
                    style = MaterialTheme.typography.body2.copy(color = colors.textBody),
                )
                Text(
                    oldVersion.size.readableSize(),
                    style = MaterialTheme.typography.body2.copy(color = colors.textBody),
                )
                if (oldVersion.global) {
                    Text(
                        "*global*",
                        style = MaterialTheme.typography.body2.copy(color = colors.primary),
                    )
                }
            }
        }
        IconButton(
            onClick = { onDelete(oldVersion) },
            modifier = Modifier.cursorHand(),
        ) {
            Icon(
                Icons.TwoTone.Delete,
                contentDescription = "Delete",
                tint = colors.textMuted,
            )
        }
    }
}
