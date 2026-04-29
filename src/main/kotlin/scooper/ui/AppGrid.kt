package scooper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import scooper.data.App
import scooper.data.PaginationMode
import scooper.ui.components.ActionButton
import scooper.ui.theme.*
import scooper.util.cursorHand
import scooper.util.onHover
import scooper.util.safeBrowse
import scooper.viewmodels.AppsFilter

@Composable
fun AppGrid(
    apps: List<App>,
    filter: AppsFilter,
    processingApp: String? = null,
    waitingApps: Set<String> = setOf(),
    onInstall: (app: App, global: Boolean) -> Unit = { _, _ -> },
    onUpdate: (app: App) -> Unit = { },
    onDownload: (app: App) -> Unit = { },
    onUninstall: (app: App) -> Unit = { },
    onOpen: (app: App, shortcutIndex: Int) -> Unit = { _, _ -> },
    onCancel: (app: App?) -> Unit = { },
    onLoadMore: () -> Unit = { },
    onInstallVersion: (app: App) -> Unit = { },
) {
    val colors = MaterialTheme.colors
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        val gridState = rememberLazyGridState()
        // Waterfall mode: load more when near bottom
        if (filter.paginationMode == PaginationMode.Waterfall) {
            val shouldLoadMore = remember {
                derivedStateOf {
                    val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()
                    lastVisible != null && lastVisible.index >= gridState.layoutInfo.totalItemsCount - 3
                }
            }
            LaunchedEffect(shouldLoadMore) {
                snapshotFlow { shouldLoadMore.value }.collect { if (it) onLoadMore() }
            }
        }

        LaunchedEffect(filter.query, filter.scope, filter.selectedBucket, filter.paginationMode, filter.pageSize, filter.page) { gridState.scrollToItem(0) }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(end = 8.dp),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                items = apps,
                key = { _, app -> app.uniqueName }
            ) { _, app ->
                AppGridCard(
                    app,
                    installing = app.uniqueName == processingApp,
                    waiting = waitingApps.contains(app.uniqueName),
                    onInstall = onInstall,
                    onUpdate = onUpdate,
                    onDownload = onDownload,
                    onUninstall = onUninstall,
                    onOpen = onOpen,
                    onCancel = onCancel,
                    onInstallVersion = onInstallVersion,
                )
            }
        }

        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().background(color = colors.background),
            adapter = rememberScrollbarAdapter(gridState)
        )
    }
}

@Composable
fun AppGridCard(
    app: App,
    installing: Boolean = false,
    waiting: Boolean = false,
    onInstall: (app: App, global: Boolean) -> Unit = { _, _ -> },
    onUpdate: (app: App) -> Unit = { },
    onDownload: (app: App) -> Unit = { },
    onUninstall: (app: App) -> Unit = { },
    onOpen: (app: App, shortcutIndex: Int) -> Unit = { _, _ -> },
    onCancel: (app: App?) -> Unit = { },
    onInstallVersion: (app: App) -> Unit = { },
) {
    val colors = MaterialTheme.colors
    var isHover by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.borderDefault),
        elevation = if (isHover) 2.dp else 0.dp,
        modifier = Modifier.onHover { isHover = it },
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            // Header: name + bucket tag
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(app.name, style = AppNameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (app.homepage?.isNotEmpty() == true) {
                        Spacer(Modifier.width(4.dp))
                        val homepage = app.homepage!!
                        Icon(
                            painter = painterResource("external_link_icon.xml"),
                            homepage,
                            modifier = Modifier.size(12.dp).cursorHand().clickable { safeBrowse(homepage) },
                            tint = colors.textMuted
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .border(BorderStroke(1.dp, colors.borderDefault), RoundedCornerShape(3.dp))
                        .background(colors.backgroundHover, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(app.bucket?.name?.uppercase() ?: "", style = BucketTagStyle)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Description (2 lines)
            Text(
                app.description ?: "No description available.",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = DescStyle,
                modifier = Modifier.height(40.dp)
            )

            Spacer(Modifier.height(12.dp))

            // Bottom: version info + action
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    if (app.updatable) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(app.version ?: "", style = OldVersionStyle)
                            Spacer(Modifier.width(4.dp))
                            Icon(painterResource("arrow_right.xml"), "", modifier = Modifier.size(10.dp), tint = colors.textMuted)
                            Spacer(Modifier.width(4.dp))
                            Text(app.latestVersion, style = NewVersionStyle)
                        }
                    } else {
                        Text(app.version ?: "", style = CurrentVersionStyle)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(app.updateAt?.format(DateFormatter) ?: "", style = DateStyle)
                }

                ActionButton(app, installing, waiting, onInstall, onUpdate, onDownload, onUninstall, onOpen, onCancel, onInstallVersion)
            }
        }
    }
}
