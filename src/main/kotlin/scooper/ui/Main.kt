package scooper.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.slf4j.LoggerFactory
import scooper.di.viewModelsModule
import scooper.repository.initDb
import scooper.ui.components.SnackbarHost
import scooper.ui.theme.ScooperTheme
import scooper.util.navigation.Router
import scooper.viewmodels.AppsSideEffect
import scooper.viewmodels.AppsViewModel
import java.awt.Dimension

@Suppress("unused")
private val logger by lazy { LoggerFactory.getLogger("Main") }

sealed class AppRoute {
    data class Apps(val scope: String) : AppRoute()
    object Splash : AppRoute()
    object Buckets : AppRoute()
    object Settings : AppRoute()
    object Output : AppRoute()
}

// val LocalWindow = compositionLocalOf<ComposeWindow> { error("Undefined window") }

fun main() = application {
    initDb()
    val koinApp = startKoin { modules(viewModelsModule) }
    val winState = rememberWindowState(
        width = 960.dp,
        height = 600.dp,
        placement = WindowPlacement.Floating,
        position = WindowPosition(Alignment.Center)
    )
    Window(
        onCloseRequest = ::exitApplication, winState,
        title = "Scooper",
        icon = painterResource("logo.svg"),
    ) {
        val density = LocalDensity.current.density
        window.minimumSize = Dimension((800 * density).toInt(), (500 * density).toInt())

        val appsViewModel = koinApp.koin.get<AppsViewModel>()
        val state by appsViewModel.container.stateFlow.collectAsState()

        val scope = rememberCoroutineScope()
        val scaffoldState = rememberScaffoldState()
        var statusText by remember { mutableStateOf("") }
        val output = state.output

        scope.launch {
            appsViewModel.container.sideEffectFlow.collect { sideEffect ->
                when (sideEffect) {
                    AppsSideEffect.Empty -> {}
                    is AppsSideEffect.Toast -> {
                        scaffoldState.snackbarHostState.showSnackbar(sideEffect.text)
                    }

                    is AppsSideEffect.Log -> {
                        statusText = sideEffect.text
                    }

                    is AppsSideEffect.Route -> TODO()
                    AppsSideEffect.Loading -> TODO()
                    AppsSideEffect.Done -> TODO()
                    // else -> TODO()
                }
            }
        }

        ScooperTheme {
            Router<AppRoute>(start = AppRoute.Apps(scope = "")) { currentRoute ->
                Scaffold(
                    modifier = Modifier.requiredSizeIn(minWidth = 780.dp, minHeight = 450.dp),
                    scaffoldState = scaffoldState,
                    snackbarHost = { SnackbarHost(it) },
                    bottomBar = {
                        StatusBar(statusText, onClick = {
                            if (currentRoute.value != AppRoute.Output) {
                                this.push(AppRoute.Output)
                            } else {
                                this.pop()
                            }
                        })
                    }
                ) {
                    Layout(this, modifier = Modifier.padding(it) /*statusBar = { this.statusBar(statusText) }*/) {
                        when (val route = currentRoute.value) {
                            is AppRoute.Apps -> AppScreen(route.scope)
                            AppRoute.Buckets -> BucketsScreen()
                            AppRoute.Settings -> SettingScreen()
                            AppRoute.Output -> OutputScreen(output, onBack = { this@Router.pop() })
                            AppRoute.Splash -> TODO()
                        }
                    }
                }
            }
        }
    }
}

