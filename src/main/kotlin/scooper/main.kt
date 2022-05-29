package scooper

// import androidx.compose.desktop.Window

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import scooper.di.viewModelsModule
import scooper.framework.navigation.Router
import scooper.framework.navigation.core.BackStack
import scooper.repository.initDb
import scooper.ui.AppScreen
import scooper.ui.BucketsScreen
import scooper.ui.MenuBar
import scooper.ui.theme.ScooperTheme
import scooper.viewmodels.AppsSideEffect
import scooper.viewmodels.AppsViewModel

sealed class AppRoute {
    data class Apps(val scope: String) : AppRoute()
    object Splash : AppRoute()
    object Buckets : AppRoute()
    object Settings : AppRoute()
}

val LocalWindow = compositionLocalOf<ComposeWindow> { error("Undefined window") }

@OptIn(ExperimentalComposeUiApi::class)
fun main() = application {
    initDb()
    val koinApp = startKoin {
        modules(viewModelsModule)
    }
    val state = rememberWindowState(
        placement = WindowPlacement.Floating,
        size = WindowSize(960.dp, 650.dp),
        position = WindowPosition(Alignment.Center)
    )
    Window(
        onCloseRequest = ::exitApplication, state,
        title = "Scooper",
        icon = painterResource("logo.svg"),
    ) {
        val appsViewModel = koinApp.koin.get<AppsViewModel>()
        val scope = rememberCoroutineScope()
        val scaffoldState = rememberScaffoldState()
        scope.launch {
            appsViewModel.container.sideEffectFlow.collect { sideEffect ->
                when (sideEffect) {
                    AppsSideEffect.Empty -> {
                    }
                    is AppsSideEffect.Toast -> {
                        scope.launch {
                            scaffoldState.snackbarHostState.showSnackbar(sideEffect.text)
                        }
                    }
                    else -> {
                    }
                }
            }
        }

        CompositionLocalProvider(LocalWindow provides window) {
            ScooperTheme {
                Scaffold(scaffoldState = scaffoldState, snackbarHost = {
                    SnackbarHost(it) { snackbarData ->
                        val textStyle = MaterialTheme.typography.h2
                        CompositionLocalProvider(LocalTextStyle provides textStyle) {
                            Snackbar(
                                backgroundColor = colors.primary,
                                contentColor = colors.onPrimary
                            ) {
                                Text(snackbarData.message)
                            }
                        }
                    }
                }) {
                    Router<AppRoute>(start = AppRoute.Apps(scope = "")) { currentRoute ->
                        // Router<AppRoute>(start = AppRoute.Buckets) { currentRoute ->
                        Layout(this) {
                            when (val route = currentRoute.value) {
                                is AppRoute.Apps -> {
                                    appsViewModel.resetFilter()
                                    AppScreen(route.scope)
                                }
                                AppRoute.Buckets -> {
                                    appsViewModel.getBuckets()
                                    BucketsScreen()
                                }
                                AppRoute.Settings -> SettingScreen()
                                AppRoute.Splash -> TODO()
                            }
                        }
                    }
                }
            }
        }
    }

}

@OptIn(InternalCoroutinesApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Layout(navigator: BackStack<AppRoute>, content: @Composable () -> Unit) {
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
fun SettingScreen() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Text("Settings")
    }
}
