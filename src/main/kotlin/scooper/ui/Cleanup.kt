package scooper.ui


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.koin.java.KoinJavaComponent.get
import scooper.ui.components.*
import scooper.ui.components.IconButton
import scooper.util.*
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
            PrefRow(title = "Cache", nestedContent = {
                CacheSection(state, onClean = {
                    cleanupViewModel.clearCache()
                }, onScan = {
                    cleanupViewModel.computeCacheSize()
                })
            }) {
                Link("Open Directory", onClicked = { Desktop.getDesktop().open(Scoop.cacheDir) })
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
                                // Icons.TwoTone.,
                                null,
                                modifier = Modifier.height(20.dp),
                                tint = colors.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CacheSection(state: CleanupState, onClean: () -> Unit, onScan: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Total Size: ${state.cacheSize.readableSize()}")
        Spacer(modifier = Modifier.height(5.dp))
        if (state.cleaningCache) {
            OutlinedButton(onClick = {}, enabled = false) {
                Text("Cleaning...")
            }
        } else if (state.cacheSize == 0L) {
            OutlinedButton(
                onClick = { onScan() },
                modifier = Modifier.cursorHand()
            ) {
                Text("Rescan")
            }
        } else {
            ProvideTextStyle(MaterialTheme.typography.button.copy(color = colors.primary)) {
                Row(
                    modifier = Modifier.height(35.dp)
                        .border(
                            // 1.dp, color = colors.primary,
                            border = ButtonDefaults.outlinedBorder,
                            shape = RoundedCornerShape(4.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier.fillMaxHeight().cursorHand().clickable { onClean() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.TwoTone.Delete,
                            contentDescription = "Clear Cache",
                            tint = colors.primary,
                            modifier = Modifier.size(30.dp).padding(start = 5.dp, top = 5.dp, bottom = 5.dp)
                        )
                        Text("Clear Cache", modifier = Modifier.padding(end = 5.dp))
                    }
                    Divider(modifier = Modifier.width(1.dp).height(20.dp).align(Alignment.CenterVertically))
                    Tooltip("Rescan") {
                        Box(modifier = Modifier.clickable { onScan() }
                        ) {
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
            }
        }
    }
}

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
                oldVersions.let { if (!showMore) it.filterIndexed { index, _ -> index < 3 } else it }
                    .forEachIndexed { idx, oldVersion ->
                        if (idx > 0) {
                            Divider()
                        }
                        OldVersion(oldVersion, onDelete = { onDelete(listOf(oldVersion)) })
                    }
            }
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