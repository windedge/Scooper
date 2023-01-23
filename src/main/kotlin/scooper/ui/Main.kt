package scooper.ui

import androidx.compose.animation.*
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.*
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

@OptIn(ExperimentalAnimationApi::class)
fun main() = application {
    initDb()
    val koinApp = remember { startKoin { modules(viewModelsModule) } }
    val scope = rememberCoroutineScope()

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
        with(LocalDensity.current) {
            window.minimumSize = Dimension(800.dp.roundToPx(), 500.dp.roundToPx())
        }
        val scaffoldState = rememberScaffoldState()
        val appsViewModel = koinApp.koin.get<AppsViewModel>()
        var statusText by remember { mutableStateOf("") }

        ScooperTheme {
            Router<AppRoute>(start = AppRoute.Apps(scope = "")) { currentRoute ->
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


                val showTopBar = when (currentRoute.value) {
                    AppRoute.Settings -> false
                    AppRoute.Output -> false
                    else -> true
                }
                Scaffold(
                    scaffoldState = scaffoldState,
                    snackbarHost = { hostState -> SnackbarHost(hostState) },
                    topBar = { NavHeader(showTopBar) },
                    bottomBar = { StatusBar(statusText) }
                ) { paddingValues ->
                    Layout(modifier = Modifier.padding(paddingValues)) {
                        EnterAnimation {
                            when (val route = currentRoute.value) {
                                is AppRoute.Apps -> AppScreen(route.scope)
                                AppRoute.Buckets -> BucketsScreen()
                                AppRoute.Settings -> SettingScreen()
                                AppRoute.Output -> OutputScreen(onBack = { this@Router.pop() })
                                AppRoute.Splash -> TODO()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EnterAnimation(content: @Composable AnimatedVisibilityScope.() -> Unit) {
    AnimatedVisibility(
        visibleState = remember { MutableTransitionState(false) }
            .apply { targetState = true },
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = 300,
                delayMillis = 0,
                easing = FastOutSlowInEasing
            )
        ) + slideInVertically(
            animationSpec = spring(),
            initialOffsetY = { height -> height / 10 }
        ),
        exit = ExitTransition.None,
    ) {
        this.content()
    }
}