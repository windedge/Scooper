package scooper

import androidx.compose.desktop.Window
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get
import scooper.data.App
import scooper.di.viewModelsModule
import scooper.framework.navigation.Router
import scooper.framework.navigation.core.BackStack
import scooper.repository.initDb
import scooper.ui.MenuItem
import scooper.ui.SearchBox
import scooper.ui.theme.ScooperTheme
import scooper.viewmodels.AppsViewModel
import java.time.format.DateTimeFormatter
import kotlin.time.ExperimentalTime


sealed class AppRoute {
    data class Apps(val filter: String) : AppRoute()
    object Splash : AppRoute()
    object Buckets : AppRoute()
    object Settings : AppRoute()
}

@ExperimentalTime
fun main() = Window(size = IntSize(960, 650), title = "Scooper") {
    initDb()
    val koinApp = startKoin {
        modules(viewModelsModule)
    }

    val appsViewModel = koinApp.koin.get<AppsViewModel>()
    ScooperTheme {
        Router<AppRoute>(start = AppRoute.Apps(filter = "")) { currentRoute ->
            Layout(this) {
                when (val route = currentRoute.value) {
                    is AppRoute.Apps -> {
                        appsViewModel.resetFilter()
                        AppView(route.filter)
                    }
                    AppRoute.Buckets -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text("Buckets")
                    }
                    AppRoute.Settings -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text("Settings")
                    }
                    AppRoute.Splash -> TODO()
                }
            }
        }
    }
}

@Composable
fun Layout(navigator: BackStack<AppRoute>, content: @Composable () -> Unit) {
    val appsViewModel = get<AppsViewModel>(AppsViewModel::class.java)

    /*
    val sideEffect by appsViewModel.container.sideEffectFlow.collectAsState(AppsSideEffect.Empty)
    println("sideEffect = ${sideEffect::class.java}")
    */

    val layoutScope = rememberCoroutineScope()
    layoutScope.launch {
        appsViewModel.container.sideEffectFlow.collect {
            println("sideEffect = ${it}")
        }
    }

    Surface(color = colors.background) {
        Box {
            Row(
                Modifier.defaultMinSize(minWidth = 800.dp, minHeight = 500.dp).fillMaxSize()
                    .padding(top = 2.dp, start = 1.dp, end = 1.dp, bottom = 1.dp)
            ) {
                MenuBar(navigator)
                Spacer(Modifier.width(4.dp))
                content()
            }
        }
    }

}

@Composable
fun MenuBar(navigator: BackStack<AppRoute>) {
    Surface(
        Modifier.fillMaxHeight().width(180.dp),
        elevation = 3.dp,
        shape = shapes.large,
    ) {
        Column(
            Modifier.defaultMinSize(10.dp).padding(vertical = 2.dp)
        ) {
            val selectedItem = remember { mutableStateOf("Apps") }
            val route = navigator.current.value
            MenuItem(
                "Apps",
                icon = Icons.TwoTone.Home,
                selectItem = selectedItem,
                selected = route is AppRoute.Apps && route.filter == "",
                onClick = { navigator.replace(AppRoute.Apps(filter = "")) }
            )
            MenuItem(
                "Installed",
                indent = 40,
                icon = Icons.TwoTone.KeyboardArrowRight,
                selectItem = selectedItem,
                selected = route is AppRoute.Apps && route.filter == "installed",
                onClick = { navigator.replace(AppRoute.Apps(filter = "installed")) },
            )
            MenuItem(
                "Updates",
                indent = 40,
                icon = Icons.TwoTone.KeyboardArrowRight,
                selectItem = selectedItem,
                selected = route is AppRoute.Apps && route.filter == "updates",
                onClick = { navigator.replace(AppRoute.Apps(filter = "updates")) },
            )
            Divider(Modifier.height(1.dp))
            MenuItem(
                "Buckets",
                icon = Icons.TwoTone.List,
                selectItem = selectedItem,
                selected = route == AppRoute.Buckets,
                onClick = { navigator.replace(AppRoute.Buckets) }
            )
            Divider(Modifier.height(1.dp))
            MenuItem(
                "Settings",
                icon = Icons.TwoTone.Settings,
                selectItem = selectedItem,
                selected = route == AppRoute.Settings,
                onClick = { navigator.replace(AppRoute.Settings) }
            )
            Divider(Modifier.height(1.dp))
        }
    }
}

@Composable
fun AppView(filter: String, appsViewModel: AppsViewModel = get(AppsViewModel::class.java)) {
    appsViewModel.getApps(scope = filter)
    val state = appsViewModel.container.stateFlow.collectAsState()
    val apps = state.value.apps
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
                            val formatter = DateTimeFormatter.ofPattern("YYYY-MM-dd")
                            Text(app.updateAt.format(formatter), Modifier.widthIn(80.dp, 100.dp))
                            Spacer(Modifier.width(30.dp))
                            Text("[${app.bucket.name}]")
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
                        Text(
                            app.version,
                            Modifier.width(100.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            modifier = Modifier.height(30.dp).border(
                                1.dp, color = colors.onBackground, shape = RoundedCornerShape(4.dp)
                            )
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxHeight().width(90.dp).clickable { },
                            ) {
                                Text(
                                    if (app.updatable) "Update" else if (app.installed) "Installed" else "Install",
                                    modifier = Modifier.padding(5.5.dp)
                                )
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
