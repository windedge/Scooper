package scooper

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.MaterialTheme.shapes
import androidx.compose.material.MaterialTheme.typography
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import moe.tlaster.precompose.PreComposeWindow
import moe.tlaster.precompose.navigation.NavHost
import moe.tlaster.precompose.navigation.Navigator
import moe.tlaster.precompose.navigation.rememberNavigator
import moe.tlaster.precompose.navigation.transition.NavTransition
import moe.tlaster.precompose.ui.observeAsState
import moe.tlaster.precompose.ui.viewModel
import moe.tlaster.precompose.viewmodel.ViewModel
import scooper.repository.App
import scooper.repository.AppsRepository
import scooper.scooper.util.replaceCurrent
import scooper.ui.MenuItem
import scooper.ui.SearchBox
import scooper.ui.theme.ScooperTheme

fun main() = PreComposeWindow(size = IntSize(960, 650), title = "Scooper") {
    val navigator = rememberNavigator()
    ScooperTheme {
        Layout(navigator) {
            val navTransition = NavTransition()
            NavHost(navigator = navigator, navTransition = navTransition, initialRoute = "/apps") {
                scene("/apps") {
                    AppView()
                }
                scene("/installed") {
                    AppView()
                }
                scene("/updates") {
                    AppView()
                }
                scene("/buckets") {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text("Buckets")
                    }
                }
                scene("/settings") {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text("Settings")
                    }
                }
            }
        }
    }
}


@Composable
fun Layout(
    navigator: Navigator,
    menuBar: @Composable () -> Unit? = { MenuBar(navigator) },
    mainContent: @Composable () -> Unit,
) {
    Surface(color = colors.background) {
        Row(
            Modifier.defaultMinSize(minWidth = 800.dp, minHeight = 500.dp).fillMaxSize()
                .padding(top = 2.dp, start = 1.dp, end = 1.dp, bottom = 1.dp)
        ) {
            menuBar()
            Spacer(Modifier.width(4.dp))
            mainContent()
        }
    }
}

@Composable
fun MenuBar(navigator: Navigator) {
    Surface(
        Modifier.fillMaxHeight().width(180.dp),
        elevation = 3.dp,
        shape = shapes.large,
    ) {
        Column(
            Modifier.defaultMinSize(10.dp).padding(vertical = 2.dp)
        ) {
            val selectedItem = remember { mutableStateOf("Apps") }
            MenuItem(
                "Apps",
                icon = Icons.TwoTone.Home,
                selectItem = selectedItem,
                onClick = { navigator.replaceCurrent("/apps") }
            )
            MenuItem(
                "Installed",
                indent = 40,
                icon = Icons.TwoTone.KeyboardArrowRight,
                selectItem = selectedItem,
                onClick = { navigator.replaceCurrent("/installed") },
            )
            MenuItem(
                "Updates",
                indent = 40,
                icon = Icons.TwoTone.KeyboardArrowRight,
                selectItem = selectedItem,
                onClick = { navigator.replaceCurrent("/updates") },
            )
            Divider(Modifier.height(1.dp))
            MenuItem(
                "Buckets",
                icon = Icons.TwoTone.List,
                selectItem = selectedItem,
                onClick = { navigator.replaceCurrent("/buckets") })
            Divider(Modifier.height(1.dp))
            MenuItem(
                "Settings",
                icon = Icons.TwoTone.Settings,
                selectItem = selectedItem,
                onClick = { navigator.replaceCurrent("/settings") }
            )
            Divider(Modifier.height(1.dp))
        }
    }
}

class AppsViewModel : ViewModel() {
    val apps by lazy {
        AppsRepository.getApps()
    }
}

@Composable
fun AppView() {
    val viewModel = viewModel {
        AppsViewModel()
    }
    val apps by viewModel.apps.observeAsState()
    Column(Modifier.fillMaxSize()) {
        SearchBox()
        Spacer(Modifier.defaultMinSize(minHeight = 30.dp).fillMaxHeight(0.07f))
        AppList(apps)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppList(apps: List<App>) {
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
                    AppCard(app, divider = idx > 0)
                }
            }
            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    .background(color = colors.background),
                adapter = rememberScrollbarAdapter(
                    scrollState = state,
                    itemCount = apps.size,
                    averageItemSize = 121.dp // TextBox height + Spacer height
                )
            )
        }
    }
}

@Composable
fun AppCard(app: App, divider: Boolean = false) {
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
                        Text(app.name, style = typography.h6)
                        Text(app.description ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("2020-02-02", Modifier.widthIn(80.dp, 100.dp))
                            Spacer(Modifier.width(30.dp))
                            // Text("[${app.bucket.name}]")
                            Text("[extra]")
                        }
                    }
                    Column(
                        Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (app.global) "*global*" else "",
                            style = typography.button.copy(color = colors.secondary)
                        )
                        Text(app.version)
                        Row(
                            modifier = Modifier.height(30.dp).border(
                                1.dp, color = colors.onBackground, shape = RoundedCornerShape(4.dp)
                            )
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxHeight().width(90.dp).clickable { },
                            ) {
                                Text("Installed", modifier = Modifier.padding(5.5.dp))
                            }
                            Box(
                                Modifier.height(30.dp).width(1.dp).padding(vertical = 5.dp)
                                    .background(color = colors.onBackground)
                            )
                            Icon(
                                Icons.TwoTone.KeyboardArrowDown,
                                "",
                                tint = colors.onBackground,
                                modifier = Modifier.fillMaxHeight().width(25.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(1.dp))
            }
        }
    }
}

