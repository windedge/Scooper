package scooper.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.Scaffold
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.slf4j.LoggerFactory
import scooper.data.toSystemTheme
import scooper.di.system
import scooper.di.viewModels
import scooper.repository.initDb
import scooper.ui.components.EnterAnimation
import scooper.ui.components.SnackbarHost
import scooper.ui.theme.ScooperTheme
import scooper.util.navigation.Router
import scooper.viewmodels.AppsViewModel
import scooper.viewmodels.CleanupViewModel
import scooper.viewmodels.SettingsViewModel
import scooper.viewmodels.SideEffect
import java.awt.Dimension

@Suppress("unused")
private val logger by lazy { LoggerFactory.getLogger("Main") }

fun main() = application {
    initDb()
    val koinApp = remember { startKoin { modules(system, viewModels) } }
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
        val appsViewModel = koinApp.koin.get<AppsViewModel>()
        val settingsViewModel = koinApp.koin.get<SettingsViewModel>()
        val cleanupViewModel = koinApp.koin.get<CleanupViewModel>()

        val settings by settingsViewModel.container.stateFlow.collectAsState()
        val uiConfig = settings.uiConfig
        val theme = uiConfig.theme.toSystemTheme()
        LaunchedEffect(Unit) {
            if (uiConfig.refreshOnStartup) {
                appsViewModel.queuedUpdateApps()
            }
        }

        val scaffoldState = rememberScaffoldState()
        var statusText by remember { mutableStateOf("") }

        ScooperTheme(currentTheme = theme) {
            Router<AppRoute>(start = AppRoute.Apps(scope = "")) { currentRoute ->
                scope.launch {
                    val sideEffectFlow = merge(
                        appsViewModel.container.sideEffectFlow,
                        settingsViewModel.container.sideEffectFlow,
                        cleanupViewModel.container.sideEffectFlow,
                    )
                    sideEffectFlow.collect { sideEffect ->
                        when (sideEffect) {
                            SideEffect.Empty -> {}
                            is SideEffect.Toast -> scaffoldState.snackbarHostState.showSnackbar(sideEffect.text)
                            is SideEffect.Log -> statusText = sideEffect.text
                            SideEffect.Loading -> {
                                // this@Router.push(AppRoute.Settings.UI)
                            }

                            SideEffect.Done -> TODO()
                            is SideEffect.Route -> TODO()
                            // else -> TODO()
                        }
                    }
                }

                val showTopBar = when (currentRoute.value) {
                    is AppRoute.Settings -> false
                    AppRoute.Output -> false
                    else -> true
                }
                val enableAnimation = when (currentRoute.value) {
                    !is AppRoute.Settings -> true
                    else -> this.snapshot.size > 1 && this.previous.value !is AppRoute.Settings
                }

                Scaffold(
                    scaffoldState = scaffoldState,
                    snackbarHost = { hostState -> SnackbarHost(hostState) },
                    topBar = { NavHeader(showTopBar) },
                    bottomBar = { StatusBar(statusText) }
                ) { paddingValues ->
                    Layout(modifier = Modifier.padding(paddingValues)) {
                        EnterAnimation(enableAnimation) {
                            when (val route = currentRoute.value) {
                                AppRoute.Splash -> TODO()

                                is AppRoute.Apps -> AppScreen(route.scope)
                                AppRoute.Buckets -> BucketsScreen()
                                AppRoute.Output -> OutputScreen(onBack = { this@Router.pop() })

                                is AppRoute.Settings -> SettingScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}