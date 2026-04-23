package scooper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.shapes
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
import scooper.data.App
import scooper.data.AppStatus
import scooper.taskqueue.Task
import scooper.taskqueue.TaskQueue
import scooper.ui.components.IconButton
import scooper.ui.components.OnBottomReached
import scooper.ui.components.Tooltip
import scooper.ui.components.TooltipPosition
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.util.onHover
import scooper.util.safeBrowse
import scooper.viewmodels.AppsFilter
import scooper.viewmodels.AppsViewModel
import scooper.ui.theme.*
import java.time.format.DateTimeFormatter

@Suppress("unused")
private val logger = LoggerFactory.getLogger("ui.App")

@Composable
fun AppScreen(scope: String, appsViewModel: AppsViewModel = koinInject()) {
    val taskQueue: TaskQueue = koinInject()
    val state by appsViewModel.container.stateFlow.collectAsState()
    val apps = state.apps
    val filter = state.filter
    val tasks by taskQueue.pendingTasksFlow.collectAsState(listOf())
    val waitingApps = tasks.map { it.name }.toSet()
    val runningTask by taskQueue.runningTaskFlow.collectAsState(null)
    val processingApp = when (runningTask) {
        is Task.Install, is Task.Update, is Task.Uninstall, is Task.Download -> runningTask!!.name
        else -> null
    }

    val isInstalledScope = scope == "installed" || scope == "updates"
    var showOnlyUpdates by remember { mutableStateOf(scope == "updates") }
    val effectiveScope = when {
        isInstalledScope && showOnlyUpdates -> "updates"
        isInstalledScope -> "installed"
        else -> scope
    }

    LaunchedEffect(effectiveScope) {
        appsViewModel.applyFilters(scope = effectiveScope)
    }

    Surface(Modifier.fillMaxSize(), elevation = 0.dp, shape = shapes.large) {
        if (apps == null) return@Surface

        Column {
            if (isInstalledScope) {
                SegmentedControl(
                    selected = if (showOnlyUpdates) 1 else 0,
                    onUpdateCount = state.updateCount.toInt(),
                    onSelected = { showOnlyUpdates = it == 1 },
                )
            }

            if (apps.isNotEmpty()) {
                AppList(
                    apps,
                    filter,
                    processingApp = processingApp,
                    waitingApps = waitingApps,
                    onInstall = appsViewModel::scheduleInstall,
                    onUpdate = appsViewModel::scheduleUpdate,
                    onDownload = appsViewModel::scheduleDownload,
                    onUninstall = appsViewModel::scheduleUninstall,
                    onOpen = appsViewModel::openApp,
                    onCancel = appsViewModel::cancel,
                    onLoadMore = appsViewModel::loadMore,
                )
            } else {
                NoResults()
            }
        }
    }
}

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
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(2.dp)
    ) {
        val state = rememberLazyListState()
        state.OnBottomReached(2, onLoadMore = onLoadMore)

        LaunchedEffect(filter.query, filter.scope, filter.selectedBucket) { state.scrollToItem(0) }
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
                    onCancel = onCancel
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
    onCancel: (app: App?) -> Unit = { }
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
                        ActionButton(app, installing, waiting, onInstall, onUpdate, onDownload, onUninstall, onOpen, onCancel)
                    }
                }
            }
            Divider(Modifier.padding(horizontal = 32.dp), color = colors.divider)
        }
    }
}
private val DateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

// Pre-computed text styles to avoid copy() on every recomposition
private val AppNameStyle @Composable get() = typography.body1.copy(fontWeight = FontWeight.SemiBold, color = colors.onSurface)
private val DescStyle @Composable get() = typography.body2.copy(color = colors.textBody)
private val BucketTagStyle @Composable get() = typography.overline.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.textBody)
private val OldVersionStyle @Composable get() = typography.body2.copy(color = colors.textMuted, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
private val NewVersionStyle @Composable get() = typography.body2.copy(fontWeight = FontWeight.Medium, color = colors.updateDefault)
private val CurrentVersionStyle @Composable get() = typography.body2.copy(fontWeight = FontWeight.Medium, color = Slate700)
private val DateStyle @Composable get() = typography.caption.copy(fontSize = 13.sp, color = colors.textMuted)

private val UpdateGreen @Composable get() = colors.updateDefault
private val UninstallRed @Composable get() = colors.dangerDefault
private val InstallBlue @Composable get() = colors.primary

@Composable
fun ActionButton(
    app: App,
    installing: Boolean,
    waiting: Boolean,
    onInstall: (app: App, global: Boolean) -> Unit,
    onUpdate: (app: App) -> Unit,
    onDownload: (app: App) -> Unit,
    onUninstall: (app: App) -> Unit,
    onOpen: (app: App, shortcutIndex: Int) -> Unit,
    onCancel: (app: App?) -> Unit
) {
    val colors = MaterialTheme.colors
    var expand by remember { mutableStateOf(false) }
    if (expand) {
        DropdownMenu(
            expand, onDismissRequest = { expand = false },
            modifier = Modifier.width(IntrinsicSize.Max).padding(vertical = 0.dp).cursorHand(),
        ) {
            if (!app.installed) {
                if (app.status != AppStatus.FAILED) {
                    DropdownMenuItem(
                        onClick = { expand = false; onInstall(app, true) },
                        modifier = Modifier.sizeIn(maxHeight = 28.dp)
                    ) {
                        MenuText("Install Globally")
                    }
                    DropdownMenuItem(
                        onClick = { expand = false; onDownload(app) },
                        modifier = Modifier.sizeIn(maxHeight = 28.dp)
                    ) {
                        MenuText("Download Only")
                    }
                }
                if (app.status == AppStatus.FAILED) {
                    DropdownMenuItem(
                        onClick = { expand = false; onUninstall(app) },
                        modifier = Modifier.sizeIn(maxHeight = 28.dp)
                    ) {
                        MenuText("Uninstall")
                    }
                }
            }
            if (app.installed) {
                // Updatable + hasShortcuts: show shortcuts in dropdown with Open label
                if (app.updatable && app.hasShortcuts) {
                    app.shortcuts!!.forEachIndexed { index, shortcut ->
                        DropdownMenuItem(
                            onClick = { expand = false; onOpen(app, index) },
                            modifier = Modifier.sizeIn(maxHeight = 28.dp)
                        ) {
                            MenuText("Open ${shortcut.path}")
                        }
                    }
                    Divider()
                }

                DropdownMenuItem(
                    onClick = { expand = false; onUninstall(app) },
                    modifier = Modifier.sizeIn(maxHeight = 28.dp)
                ) {
                    MenuText("Uninstall")
                }

                if (app.updatable) {
                    DropdownMenuItem(
                        onClick = { expand = false; onDownload(app) },
                        modifier = Modifier.sizeIn(maxHeight = 28.dp)
                    ) {
                        MenuText("Download Only")
                    }
                }
            }
        }
    }

    // Unified split button using a single Box with rounded corners
    val buttonHeight = 34.dp
    val shape = RoundedCornerShape(6.dp)

    when {
        installing || waiting -> {
            var hovered by remember { mutableStateOf(false) }
            val showingCancel = installing || hovered
            Button(
                onClick = { onCancel(app) },
                modifier = Modifier.height(buttonHeight).width(120.dp).cursorLink().onHover { hovered = it },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (showingCancel) colors.dangerHover else UninstallRed
                ),
                shape = shape,
                elevation = ButtonDefaults.elevation(defaultElevation = 1.dp),
            ) {
                if (installing) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (showingCancel) "Cancel" else "Waiting",
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    style = typography.body2,
                )
            }
        }

        app.updatable -> {
            // Green split: [Update | ▼]
            var mainHovered by remember { mutableStateOf(false) }
            var arrowHovered by remember { mutableStateOf(false) }
            Surface(
                shape = shape,
                color = UpdateGreen,
                elevation = 1.dp,
                modifier = Modifier.height(buttonHeight).width(120.dp)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .onHover { mainHovered = it }
                            .background(if (mainHovered) colors.updateHover else Color.Transparent)
                            .cursorLink()
                            .clickable { onUpdate(app) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Update", color = Color.White, fontWeight = FontWeight.Medium, style = typography.body2)
                    }
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.2f)))
                    Box(
                        modifier = Modifier.width(28.dp).fillMaxHeight()
                            .onHover { arrowHovered = it }
                            .background(if (arrowHovered) colors.updatePressed else colors.updateHover)
                            .cursorLink()
                            .clickable { expand = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.TwoTone.KeyboardArrowDown, "", modifier = Modifier.size(16.dp), tint = Color.White)
                    }
                }
            }
        }

        app.installed && app.hasShortcuts && !app.updatable -> {
            // Outlined split: [Open | ▼]
            var mainHovered by remember { mutableStateOf(false) }
            var arrowHovered by remember { mutableStateOf(false) }
            Surface(
                shape = shape,
                color = Color.White,
                border = BorderStroke(1.dp, colors.borderDefault),
                elevation = 1.dp,
                modifier = Modifier.height(buttonHeight).width(120.dp)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .onHover { mainHovered = it }
                            .background(if (mainHovered) colors.backgroundHover else Color.Transparent)
                            .cursorLink()
                            .clickable { onOpen(app, 0) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Open", color = colors.primary, fontWeight = FontWeight.Medium, style = typography.body2)
                    }
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(colors.borderDefault))
                    Box(
                        modifier = Modifier.width(28.dp).fillMaxHeight()
                            .onHover { arrowHovered = it }
                            .background(if (arrowHovered) colors.divider else colors.backgroundHover)
                            .cursorLink()
                            .clickable { expand = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.TwoTone.KeyboardArrowDown, "", modifier = Modifier.size(16.dp), tint = colors.textMuted)
                    }
                }
            }
        }

        app.installed -> {
            // Outlined split: [Uninstall | ▼]
            var mainHovered by remember { mutableStateOf(false) }
            var arrowHovered by remember { mutableStateOf(false) }
            Surface(
                shape = shape,
                color = Color.White,
                border = BorderStroke(1.dp, colors.borderDefault),
                elevation = 1.dp,
                modifier = Modifier.height(buttonHeight).width(120.dp)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .onHover { mainHovered = it }
                            .background(if (mainHovered) colors.backgroundHover else Color.Transparent)
                            .cursorLink()
                            .clickable { onUninstall(app) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Uninstall", color = colors.sidebarTextMedium, fontWeight = FontWeight.Medium, style = typography.body2)
                    }
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(colors.borderDefault))
                    Box(
                        modifier = Modifier.width(28.dp).fillMaxHeight()
                            .onHover { arrowHovered = it }
                            .background(if (arrowHovered) colors.divider else colors.backgroundHover)
                            .cursorLink()
                            .clickable { expand = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.TwoTone.KeyboardArrowDown, "", modifier = Modifier.size(16.dp), tint = colors.textMuted)
                    }
                }
            }
        }

        else -> {
            // Blue split: [Install | ▼]
            var mainHovered by remember { mutableStateOf(false) }
            var arrowHovered by remember { mutableStateOf(false) }
            Surface(
                shape = shape,
                color = InstallBlue,
                elevation = 1.dp,
                modifier = Modifier.height(buttonHeight).width(120.dp)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .onHover { mainHovered = it }
                            .background(if (mainHovered) colors.primaryHover else Color.Transparent)
                            .cursorLink()
                            .clickable { onInstall(app, false) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Install", color = Color.White, fontWeight = FontWeight.Medium, style = typography.body2)
                    }
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.2f)))
                    Box(
                        modifier = Modifier.width(28.dp).fillMaxHeight()
                            .onHover { arrowHovered = it }
                            .background(if (arrowHovered) colors.primaryPressed else colors.primaryHover)
                            .cursorLink()
                            .clickable { expand = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.TwoTone.KeyboardArrowDown, "", modifier = Modifier.size(16.dp), tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun MenuText(text: String) {
    ProvideTextStyle(typography.subtitle2) {
        Text(
            text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


@Composable
fun NoResults() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painterResource("no-results.svg"),
            contentDescription = "No Results",
            modifier = Modifier.size(60.dp), tint = colors.primary
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text("No Results", style = typography.h6, color = colors.primary)
    }
}

@Composable
private fun SegmentedControl(
    selected: Int,
    onUpdateCount: Int,
    onSelected: (Int) -> Unit,
) {
    val colors = MaterialTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pill container
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.borderDefault.copy(alpha = 0.6f))
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
private fun SegmentedTab(
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
            .background(bgColor)
            .then(if (selected) Modifier.border(1.dp, selectedBorderColor, RoundedCornerShape(6.dp)) else Modifier)
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
                    .background(badgeBg)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    "$badge",
                    style = typography.caption.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = badgeText,
                    ),
                )
            }
        }
    }
}
