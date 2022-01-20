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
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.koin.java.KoinJavaComponent.get
import scooper.data.App
import scooper.util.cursorHand
import scooper.util.cursorLink
import scooper.viewmodels.AppsViewModel
import java.time.format.DateTimeFormatter
import org.xml.sax.InputSource
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.Density


@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AppScreen(scope: String, appsViewModel: AppsViewModel = get(AppsViewModel::class.java)) {
    appsViewModel.applyFilters(scope = scope)
    val state = appsViewModel.container.stateFlow.collectAsState()
    val apps = state.value.apps
    val installingApp = state.value.installingApp
    Column(Modifier.fillMaxSize()) {
        SearchBox()
        Box(
            Modifier.defaultMinSize(minHeight = 30.dp).fillMaxHeight(0.07f).fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd
        ) {

            if (state.value.updatingApps) {
                Box(Modifier.fillMaxHeight().width(60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                }
            } else {
                BoxWithTooltip(
                    tooltip = {
                        Surface(
                            modifier = Modifier.shadow(4.dp),
                            color = Color(255, 255, 210),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "Update Scoop",
                                modifier = Modifier.padding(5.dp),
                                color = Color.Gray
                            )
                        }
                    },
                    delay = 600, // in milliseconds
                    tooltipPlacement = TooltipPlacement.CursorPoint(
                        offset = DpOffset((-16).dp, 0.dp),
                    ),
                ) {
                    Button(
                        onClick = { appsViewModel.updateApps() },
                        Modifier.height(25.dp).cursorLink(),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Icon(Icons.TwoTone.Refresh, contentDescription = null)
                    }
                }

            }
        }
        AppList(
            apps,
            installingApp = installingApp,
            onInstall = appsViewModel::install,
            onUpdate = appsViewModel::upgrade,
            onUninstall = appsViewModel::uninstall,
            onCancel = appsViewModel::cancel
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppList(
    apps: List<App>,
    installingApp: String? = null,
    onInstall: (app: App, global: Boolean) -> Unit = { _, _ -> },
    onUpdate: (app: App) -> Unit = {},
    onUninstall: (app: App) -> Unit = {},
    onCancel: () -> Unit = {}
) {
    Surface(
        Modifier.fillMaxSize(),
        elevation = 1.dp,
        shape = shapes.large
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(2.dp)
        ) {

            val state = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), state) {
                itemsIndexed(items = apps) { idx, app ->
                    AppCard(
                        app,
                        divider = idx > 0,
                        installing = app.name == installingApp,
                        onInstall = onInstall,
                        onUpdate = onUpdate,
                        onUninstall = onUninstall,
                        onCancel = onCancel
                    )
                }
            }
            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    .background(color = colors.background),
                adapter = rememberScrollbarAdapter(
                    scrollState = state // TextBox height + Spacer height
                )
            )
        }
    }
}

@Composable
fun AppCard(
    app: App,
    divider: Boolean = false,
    installing: Boolean = false,
    onInstall: (app: App, global: Boolean) -> Unit = { _, _ -> },
    onUpdate: (app: App) -> Unit = {},
    onUninstall: (app: App) -> Unit = {},
    onCancel: () -> Unit = {}
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
                                    imageVector = loadXmlImageVector("external_link_icon.xml"),
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
                                style = typography.button.copy(color = colors.secondary)
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

                        ActionButton(app, installing, onInstall, onUpdate, onUninstall, onCancel)
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
    onInstall: (app: App, global: Boolean) -> Unit,
    onUpdate: (app: App) -> Unit,
    onUninstall: (app: App) -> Unit,
    onCancel: () -> Unit
) {
    var expand by remember { mutableStateOf(false) }
    DropdownMenu(
        expand, onDismissRequest = { expand = false },
        modifier = Modifier.width(140.dp).padding(vertical = 0.dp).cursorHand(),
        offset = DpOffset(x = (-24).dp, y = 1.dp)
    ) {

        // var hover by remember { mutableStateOf(false) }
        if (!app.installed) {
            DropdownMenuItem(
                onClick = {
                    expand = false
                    onInstall(app, true)
                },
                modifier = Modifier.sizeIn(maxHeight = 25.dp)
            ) {
                Text(
                    "Install Globally",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (app.status == "failed") {
                DropdownMenuItem(
                    onClick = {
                        expand = false
                        onUninstall(app)
                    },
                    modifier = Modifier.sizeIn(maxHeight = 25.dp)
                ) {
                    Text(
                        "Uninstall",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (app.installed) {
            DropdownMenuItem(
                onClick = {
                    expand = false
                    onUninstall(app)
                },
                modifier = Modifier.sizeIn(maxHeight = 25.dp)
            ) {
                Text(
                    "Uninstall",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    Row(
        modifier = Modifier.height(30.dp).border(
            1.dp, color = MaterialTheme.colors.onBackground, shape = RoundedCornerShape(4.dp)
        )
    ) {
        var modifier = Modifier.fillMaxHeight().width(90.dp)
        val text: String
        var textColor: Color = Color.Unspecified
        when {
            installing -> {
                text = "Cancel"
                modifier =
                    modifier.cursorLink().background(MaterialTheme.colors.error).clickable { onCancel() }
                textColor = MaterialTheme.colors.onError
            }
            app.status == "installed" && app.updatable -> {
                text = "Update"
                modifier = modifier.cursorLink().clickable { onUpdate(app) }
            }
            app.status == "installed" -> {
                text = "Installed"
                textColor = MaterialTheme.colors.onSecondary
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
                .background(color = MaterialTheme.colors.onBackground)
        )

        if (installing) {
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
                tint = MaterialTheme.colors.onBackground,
                modifier = Modifier.fillMaxHeight().width(25.dp).cursorLink().clickable { expand = true }
            )
        }
    }
}

// fun loadXmlImageVector(file: File, density: Density): ImageVector =
//     file.inputStream().buffered().use { loadXmlImageVector(InputSource(it), density) }
fun loadXmlImageVector(path: String, density: Density = Density(1f)): ImageVector =
    useResource(path) { stream -> stream.buffered().use { loadXmlImageVector(InputSource(it), density) } }