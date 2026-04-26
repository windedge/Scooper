package scooper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import scooper.data.App
import scooper.data.AppStatus
import scooper.data.PaginationMode
import scooper.ui.components.ActionButton
import scooper.ui.components.OnBottomReached
import scooper.ui.components.Tooltip
import scooper.ui.components.TooltipPosition
import scooper.ui.theme.*
import scooper.util.cursorHand
import scooper.util.onHover
import scooper.util.safeBrowse
import scooper.viewmodels.AppsFilter
import java.time.format.DateTimeFormatter

// Shared text styles used by both list and grid card views
internal val AppNameStyle @Composable get() = typography.h6.copy(color = colors.onSurface)
internal val DescStyle @Composable get() = typography.body2.copy(color = colors.textBody)
internal val BucketTagStyle @Composable get() = typography.overline.copy(fontWeight = FontWeight.Bold, color = colors.textBody)
internal val OldVersionStyle @Composable get() = typography.body2.copy(color = colors.textMuted, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
internal val NewVersionStyle @Composable get() = typography.body2.copy(fontWeight = FontWeight.Medium, color = colors.updateDefault)
internal val CurrentVersionStyle @Composable get() = typography.body2.copy(fontWeight = FontWeight.Medium, color = Slate700)
internal val DateStyle @Composable get() = typography.caption.copy(color = colors.textMuted)
internal val DateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

@Composable
fun AppList(
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
    Box(
        modifier = Modifier.fillMaxSize().padding(2.dp)
    ) {
        val state = rememberLazyListState()
        state.OnBottomReached(2, onLoadMore = onLoadMore)

        val scrollResetPage = if (filter.paginationMode == PaginationMode.Pagination) filter.page else 1
        LaunchedEffect(
            filter.query,
            filter.scope,
            filter.selectedBucket,
            filter.sort,
            filter.sortOrder,
            filter.paginationMode,
            filter.pageSize,
            scrollResetPage,
        ) { state.scrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), state) {
            items(
                count = apps.size,
                key = { apps[it].uniqueName },
                contentType = { "app" }
            ) { index ->
                val app = apps[index]
                AppCard(
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
            item {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().background(color = colors.background),
            adapter = rememberScrollbarAdapter(scrollState = state)
        )
    }
}

@Composable
fun AppCard(
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
    Box(
        modifier = Modifier.fillMaxWidth()
            .onHover { isHover = it }
            .background(if (isHover) colors.backgroundHover else Color.Transparent)
    ) {
        Column {
            Row(
                Modifier.padding(horizontal = 32.dp, vertical = 22.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left Content
                Column(
                    Modifier.weight(1f).padding(end = 24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                        Text(
                            app.name,
                            style = AppNameStyle
                        )
                        if (app.homepage?.isNotEmpty() == true) {
                            Spacer(Modifier.width(6.dp))
                            val homepage = app.homepage!!
                            Tooltip(homepage, position = TooltipPosition.Top) {
                                Icon(
                                    painter = painterResource("external_link_icon.xml"),
                                    homepage,
                                    modifier = Modifier.size(14.dp).cursorHand().clickable {
                                        safeBrowse(homepage)
                                    },
                                    tint = colors.textMuted
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        // Bucket Tag
                        Box(
                            modifier = Modifier
                                .border(BorderStroke(1.dp, colors.borderDefault), RoundedCornerShape(4.dp))
                                .background(colors.backgroundHover, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                app.bucket?.name?.uppercase() ?: "",
                                style = BucketTagStyle
                            )
                        }
                    }

                    Text(
                        app.description ?: "No description available.",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = DescStyle
                    )
                }

                // Right Version & Actions
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Version info
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (app.updatable) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    app.version ?: "",
                                    style = OldVersionStyle
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    painterResource("arrow_right.xml"), "",
                                    modifier = Modifier.size(12.dp),
                                    tint = colors.textMuted
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    app.latestVersion,
                                    style = NewVersionStyle
                                )
                            }
                        } else {
                            Text(
                                app.version ?: "",
                                style = CurrentVersionStyle
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            app.updateAt?.format(DateFormatter) ?: "",
                            style = DateStyle
                        )
                    }

                    // Action Button
                    Box(modifier = Modifier.width(120.dp)) {
                        ActionButton(app, installing, waiting, onInstall, onUpdate, onDownload, onUninstall, onOpen, onCancel, onInstallVersion)
                    }
                }
            }
            Divider(Modifier.padding(horizontal = 32.dp), color = colors.divider)
        }
    }
}
