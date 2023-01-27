package scooper.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import org.koin.java.KoinJavaComponent.get
import org.slf4j.LoggerFactory
import scooper.data.App
import scooper.ui.components.Tooltip
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.viewmodels.AppsFilter
import scooper.viewmodels.AppsViewModel
import java.time.format.DateTimeFormatter

@Suppress("unused")
private val logger = LoggerFactory.getLogger("ui.App")

@Composable
fun AppScreen(scope: String, appsViewModel: AppsViewModel = get(AppsViewModel::class.java)) {
    val state by appsViewModel.container.stateFlow.collectAsState()
    val apps = state.apps
    val filter = state.filter
    val processingApp = state.processingApp
    val waitingApps = state.waitingApps
    LaunchedEffect(scope) {
        appsViewModel.applyFilters(scope = scope)
    }

    Surface(Modifier.fillMaxSize(), elevation = 1.dp, shape = shapes.large) {
        if (apps == null) return@Surface

        if (apps.isNotEmpty()) {
            AppList(
                apps,
                filter,
                processingApp = processingApp,
                waitingApps = waitingApps,
                onInstall = appsViewModel::queuedInstall,
                onUpdate = appsViewModel::queuedUpdate,
                onDownload = appsViewModel::queuedDownload,
                onUninstall = appsViewModel::queuedUninstall,
                onCancel = appsViewModel::cancel
            )
        } else {
            NoResults()
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
    onUpdate: (app: App) -> Unit = {},
    onDownload: (app: App) -> Unit = {},
    onUninstall: (app: App) -> Unit = {},
    onCancel: (app: App?) -> Unit = {}
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(2.dp)
    ) {

        val state = rememberLazyListState()
        LaunchedEffect(filter) { state.scrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), state) {
            itemsIndexed(items = apps) { idx, app ->
                AppCard(
                    app,
                    divider = idx > 0,
                    installing = app.name == processingApp,
                    waiting = waitingApps.contains(app.uniqueName),
                    onInstall = onInstall,
                    onUpdate = onUpdate,
                    onDownload = onDownload,
                    onUninstall = onUninstall,
                    onCancel = onCancel
                )
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
    divider: Boolean = false,
    installing: Boolean = false,
    waiting: Boolean = false,
    onInstall: (app: App, global: Boolean) -> Unit = { _, _ -> },
    onUpdate: (app: App) -> Unit = {},
    onDownload: (app: App) -> Unit = {},
    onUninstall: (app: App) -> Unit = {},
    onCancel: (app: App?) -> Unit = {}
) {
    Surface {
        Column {
            if (divider) {
                Divider(Modifier.height(1.dp).padding(start = 10.dp, end = 10.dp))
            }
            Box(
                Modifier.height(120.dp).padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        Modifier.fillMaxHeight().defaultMinSize(400.dp).fillMaxWidth(0.7f),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(app.name, style = typography.h6)
                            if (app.homepage != null && app.homepage!!.isNotBlank()) {
                                Icon(
                                    painter = painterResource("external_link_icon.xml"),
                                    // imageVector = loadXmlImageVector("external_link_icon.xml"),
                                    app.homepage,
                                    modifier = Modifier.cursorHand().clickable {
                                        java.awt.Desktop.getDesktop()
                                            .browse(java.net.URI.create(app.homepage!!))
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                if (app.global) "*global*" else "",
                                style = typography.button.copy(color = colors.primary)
                            )
                        }

                        Text(app.description ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(modifier = Modifier.fillMaxWidth()) {
                            val formatter = DateTimeFormatter.ofPattern("YYYY-MM-dd")
                            Text(app.updateAt.format(formatter), Modifier.widthIn(80.dp, 100.dp))
                            Spacer(Modifier.width(30.dp))
                            Text("[${app.bucket?.name ?: ""}]")
                        }
                    }

                    Column(
                        Modifier.fillMaxHeight().width(120.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            app.version ?: app.status,
                            maxLines = if (app.updatable) 1 else 3,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = true,
                            color = if (app.status == "failed") Color.Red else Color.Unspecified,
                        )
                        if (app.updatable) {
                            Text(
                                "⬇️".padStart(3, ' '),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = true
                            )
                            Text(
                                app.latestVersion,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = true
                            )
                        }

                        ActionButton(app, installing, waiting, onInstall, onUpdate, onDownload, onUninstall, onCancel)
                    }
                }

                Spacer(modifier = Modifier.height(1.dp))
            }
        }
    }
}


@Composable
fun ActionButton(
    app: App,
    installing: Boolean,
    waiting: Boolean,
    onInstall: (app: App, global: Boolean) -> Unit,
    onUpdate: (app: App) -> Unit,
    onDownload: (app: App) -> Unit,
    onUninstall: (app: App) -> Unit,
    onCancel: (app: App?) -> Unit
) {
    var expand by remember { mutableStateOf(false) }
    DropdownMenu(
        expand, onDismissRequest = { expand = false },
        modifier = Modifier.width(IntrinsicSize.Max).padding(vertical = 0.dp).cursorHand(),
        // offset = DpOffset(x = (-24).dp, y = 1.dp)
    ) {
        if (!app.installed) {
            if (app.status != "failed") {
                DropdownMenuItem(
                    onClick = { expand = false; onInstall(app, true) },
                    modifier = Modifier.sizeIn(maxHeight = 25.dp)
                ) {
                    MenuText("Install Globally")
                }
                DropdownMenuItem(
                    onClick = { expand = false; onDownload(app) },
                    modifier = Modifier.sizeIn(maxHeight = 25.dp)
                ) {
                    MenuText("Download Only")
                }
            }
            if (app.status == "failed") {
                DropdownMenuItem(
                    onClick = { expand = false; onUninstall(app) },
                    modifier = Modifier.sizeIn(maxHeight = 25.dp)
                ) {
                    MenuText("Uninstall")
                }
            }
        }
        if (app.installed) {
            DropdownMenuItem(
                onClick = { expand = false; onUninstall(app) },
                modifier = Modifier.sizeIn(maxHeight = 25.dp)
            ) {
                MenuText("Uninstall")
            }

            if (app.updatable) {
                DropdownMenuItem(
                    onClick = { expand = false; onDownload(app) },
                    modifier = Modifier.sizeIn(maxHeight = 25.dp)
                ) {
                    MenuText("Download Latest")
                }
            }
        }
    }

    Row(
        modifier = Modifier.height(30.dp).border(
            1.dp, color = colors.onBackground, shape = RoundedCornerShape(4.dp)
        )
    ) {
        var modifier = Modifier.fillMaxHeight().width(90.dp)
        val text: String
        var textColor: Color = Color.Unspecified
        when {
            installing -> {
                text = "Cancel"
                modifier = modifier.cursorLink().background(colors.error).clickable { onCancel(app) }
                textColor = colors.onError
            }

            waiting -> {
                text = "Waiting"
                textColor = colors.onSecondary
            }

            app.status == "installed" && app.updatable -> {
                text = "Update"
                modifier = modifier.cursorLink().clickable { onUpdate(app) }
            }

            app.status == "installed" -> {
                text = "Installed"
                textColor = colors.onSecondary
            }

            else -> {
                text = "Install"
                modifier = modifier.cursorLink().clickable { onInstall(app, false) }
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier,
        ) {
            Text(text, modifier = Modifier.padding(5.5.dp), fontWeight = FontWeight.Medium, color = textColor)
        }

        Box(
            Modifier.height(30.dp).width(1.dp).padding(vertical = 5.dp)
                .background(color = colors.onBackground)
        )

        if (installing || waiting) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxHeight().width(25.dp)
            ) {
                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
            }
        } else {
            Icon(
                Icons.TwoTone.KeyboardArrowDown,
                "",
                tint = colors.onBackground,
                modifier = Modifier.fillMaxHeight().width(25.dp).cursorLink().clickable { expand = true }
            )
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
