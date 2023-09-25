package scooper.ui


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.material.icons.twotone.KeyboardArrowUp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.koin.java.KoinJavaComponent.get
import scooper.ui.components.IconButton
import scooper.ui.components.Link
import scooper.ui.components.PrefRow
import scooper.ui.components.SettingContainer
import scooper.ui.components.Tooltip
import scooper.util.Scoop
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.util.readableSize
import scooper.viewmodels.CleanupState
import scooper.viewmodels.CleanupViewModel
import scooper.viewmodels.OldVersion
import java.awt.Desktop


@Composable
fun CleanupContainer(cleanupViewModel: CleanupViewModel = get(CleanupViewModel::class.java)) {
    val state by cleanupViewModel.container.stateFlow.collectAsState()
    LaunchedEffect(Unit) {
        if (state.cacheSize < 0 && !state.scanningCache) {
            cleanupViewModel.computeCacheSize()
        }
        if (state.totalOldSize < 0 && !state.scanningOldVersion) {
            cleanupViewModel.computeOldVersions()
        }
    }

    SettingContainer {
        Column {
            PrefRow(title = "Download Cache", nestedContent = {
                CacheSection(
                    state,
                    onClean = { cleanupViewModel.clearCache() },
                    onScan = { cleanupViewModel.computeCacheSize() },
                    onOpen = { Desktop.getDesktop().open(Scoop.cacheDir) }
                )
            }) {
                Tooltip("Rescan") {
                    Box(modifier = Modifier.clickable { cleanupViewModel.computeCacheSize() }) {
                        Icon(
                            // Icons.TwoTone.Refresh,
                            painterResource("search_for.svg"),
                            "",
                            tint = colors.primary,
                            modifier = Modifier.fillMaxHeight().padding(5.dp).width(25.dp).cursorLink()
                        )
                    }
                }
            }

            Divider()

            PrefRow(title = "Old Versions", nestedContent = {
                OldVersions(
                    state.oldVersions,
                    totalSize = state.totalOldSize,
                    scanning = state.scanningOldVersion,
                    cleaning = state.cleaningOldVersions,
                    onDelete = { cleanupViewModel.cleanup(*it.toTypedArray()) },
                )
            }) {
                if (!state.scanningOldVersion) {
                    Tooltip("Rescan") {
                        IconButton(
                            onClick = { cleanupViewModel.computeOldVersions() },
                            modifier = Modifier.cursorLink(),
                        ) {
                            Icon(
                                painterResource("search_for.svg"),
                                null,
                                tint = colors.primary,
                                modifier = Modifier.fillMaxHeight().padding(5.dp).width(25.dp).cursorLink()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CacheSection(state: CleanupState, onClean: () -> Unit, onScan: () -> Unit, onOpen: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Total Size: ${state.cacheSize.readableSize()}")
        Spacer(modifier = Modifier.height(5.dp))
        if (state.cleaningCache) {
            OutlinedButton(modifier = Modifier.height(35.dp), onClick = {}, enabled = false) {
                Text("Cleaning...")
            }
        } else if (state.scanningCache) {
            OutlinedButton(modifier = Modifier.height(35.dp), onClick = {}, enabled = false) {
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
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.TwoTone.Delete, contentDescription = "Clear Cache", tint = colors.primary,
                                modifier = Modifier.size(30.dp).padding(start = 5.dp, top = 5.dp, bottom = 5.dp)
                            )
                            Text("Clear Cache", modifier = Modifier.padding(end = 5.dp))
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxHeight().cursorHand().clickable { onScan() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painterResource("search_for.svg"), "", tint = colors.primary,
                                modifier = Modifier.size(30.dp).padding(start = 5.dp, top = 5.dp, bottom = 5.dp)
                            )
                            Text("Rescan")
                        }
                    }
                    Divider(modifier = Modifier.width(1.dp).height(20.dp).align(Alignment.CenterVertically))
                    Tooltip("Open Directory") {
                        Box(modifier = Modifier.clickable { onOpen() }) {
                            Icon(
//                                Icons.Outlined.Home,
                                painterResource("folder.svg"),
                                "",
                                tint = colors.primary,
                                modifier = Modifier.fillMaxHeight().padding(5.dp).width(25.dp).cursorLink()
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_ENTRIES = 3

@Composable
fun OldVersions(
    oldVersions: List<OldVersion>? = null,
    totalSize: Long = 0L,
    scanning: Boolean = false,
    cleaning: Boolean = false,
    onDelete: (List<OldVersion>) -> Unit = {}
) {
    if (oldVersions == null || scanning) {
        Box(modifier = Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(30.dp))
        }
        return
    }
    if (oldVersions.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth().height(40.dp).padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Great, old versions has been cleaned up.")
        }
        return
    }
    Box(modifier = Modifier.fillMaxWidth(), Alignment.TopCenter) {
        Column(
            modifier = Modifier.fillMaxWidth(0.6f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Total Size: ${totalSize.readableSize()}")
                if (cleaning) {
                    Box(
                        modifier = Modifier.height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    Button(
                        onClick = { onDelete(oldVersions) },
                        modifier = Modifier.cursorHand(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Text("Clean All")
                    }
                }
            }

            var showMore by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier.fillMaxWidth()
                    .border(0.8.dp, color = colors.onBackground, shape = MaterialTheme.shapes.medium)
                    .background(color = colors.background)
            ) {
                oldVersions.let { if (!showMore && it.size > MAX_ENTRIES) it.filterIndexed { index, _ -> index < MAX_ENTRIES } else it }
                    .forEachIndexed { idx, oldVersion ->
                        if (idx > 0) {
                            Divider()
                        }
                        OldVersion(oldVersion, onDelete = { onDelete(listOf(oldVersion)) })
                    }
            }

            if (oldVersions.size > MAX_ENTRIES) {
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
}

@Composable
fun OldVersion(oldVersion: OldVersion, onDelete: (OldVersion) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Link(
                oldVersion.app,
                onClicked = { Desktop.getDesktop().open(oldVersion.appDir) },
            )
            Spacer(modifier = Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("${oldVersion.paths.size} old versions")
                Text(oldVersion.size.readableSize())
                if (oldVersion.global) {
                    Text("*global*", color = colors.primary)
                }
            }
        }
        IconButton(onClick = { onDelete(oldVersion) }, modifier = Modifier.cursorHand()) {
            Icon(Icons.TwoTone.Delete, contentDescription = "Delete")
        }
    }
}