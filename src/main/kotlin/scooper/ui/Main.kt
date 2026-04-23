package scooper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import org.slf4j.LoggerFactory
import scooper.data.toSystemTheme
import scooper.di.system
import scooper.di.viewModels
import scooper.repository.AppsRepository
import scooper.repository.initDb
import scooper.ui.components.EnterAnimation
import scooper.ui.components.SnackbarHost
import scooper.ui.theme.*
import scooper.util.navigation.Router
import scooper.viewmodels.AppsSideEffect
import scooper.viewmodels.AppsViewModel
import scooper.viewmodels.CleanupSideEffect
import scooper.viewmodels.CleanupViewModel
import scooper.viewmodels.SettingsSideEffect

import scooper.viewmodels.SettingsViewModel
import java.awt.Dimension

val LocalShowFps = compositionLocalOf { mutableStateOf(false) }

@Suppress("unused")
private val logger by lazy { LoggerFactory.getLogger("Main") }

fun main() = application {
    remember { startKoin { modules(system, viewModels) } }

    var dbReady by remember { mutableStateOf(false) }
    val appsRepository: AppsRepository = koinInject()
    LaunchedEffect(Unit) {
        initDb(appsRepository)
        dbReady = true
    }

    if (!dbReady) {
        SplashScreen(onClose = ::exitApplication)
        return@application
    }

    val winState = rememberWindowState(
        width = 960.dp,
        height = 600.dp,
        placement = WindowPlacement.Floating,
        position = WindowPosition(Alignment.Center)
    )

    val appsViewModel: AppsViewModel = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
    val cleanupViewModel: CleanupViewModel = koinInject()

    Window(
        onCloseRequest = {
            appsViewModel.close()
            settingsViewModel.close()
            cleanupViewModel.close()
            exitApplication()
        },
        winState,
        title = "Scooper",
        icon = painterResource("logo.svg"),
    ) {
        with(LocalDensity.current) {
            window.minimumSize = Dimension(960.dp.roundToPx(), 560.dp.roundToPx())
        }

        val settings by settingsViewModel.container.stateFlow.collectAsState()
        val uiConfig = settings.uiConfig
        val theme = uiConfig.theme.toSystemTheme()

        LaunchedEffect(Unit) {
            if (uiConfig.refreshOnStartup) {
                appsViewModel.scheduleUpdateApps()
            }
        }

        val scaffoldState = rememberScaffoldState()
        var statusText by remember { mutableStateOf("") }

        // Collect Apps side effects
        LaunchedEffect(appsViewModel) {
            appsViewModel.container.sideEffectFlow.collect { sideEffect ->
                when (sideEffect) {
                    is AppsSideEffect.Toast -> scaffoldState.snackbarHostState.showSnackbar(sideEffect.text)
                    is AppsSideEffect.Log -> statusText = sideEffect.text
                }
            }
        }

        // Collect Settings side effects
        LaunchedEffect(settingsViewModel) {
            settingsViewModel.container.sideEffectFlow.collect { sideEffect ->
                when (sideEffect) {
                    is SettingsSideEffect.Toast -> scaffoldState.snackbarHostState.showSnackbar(sideEffect.text)
                }
            }
        }

        // Collect Cleanup side effects
        LaunchedEffect(cleanupViewModel) {
            cleanupViewModel.container.sideEffectFlow.collect { sideEffect ->
                when (sideEffect) {
                    is CleanupSideEffect.Toast -> scaffoldState.snackbarHostState.showSnackbar(sideEffect.text)
                }
            }
        }

        val showFpsState = remember { mutableStateOf(false) }

        ScooperTheme(currentTheme = theme, fontSizeScale = uiConfig.fontSizeScale) {
            CompositionLocalProvider(LocalShowFps provides showFpsState) {
                Router<AppRoute>(start = AppRoute.Apps(scope = "")) { currentRoute ->
                val showToolbar = when (currentRoute.value) {
                    is AppRoute.Settings -> false
                    AppRoute.Output -> false
                    else -> true
                }

                val appsState by appsViewModel.container.stateFlow.collectAsState()

                Scaffold(
                    scaffoldState = scaffoldState,
                    snackbarHost = { hostState -> SnackbarHost(hostState) },
                    bottomBar = { StatusBar(statusText) }
                ) { paddingValues ->
                    Row(modifier = Modifier.padding(paddingValues)) {
                        val isSettings = currentRoute.value is AppRoute.Settings
                        val isOutput = currentRoute.value == AppRoute.Output
                        if (!isSettings && !isOutput) {
                            SidebarNav(updateCount = appsState.updateCount)
                        }
                        Column(Modifier.weight(1f)) {
                            ToolbarRow(showToolbar && currentRoute.value != AppRoute.Buckets)
                            Layout {
                                val routeKey = when (val route = currentRoute.value) {
                                    AppRoute.Splash -> "splash"
                                    is AppRoute.Apps -> "apps:${route.scope}"
                                    AppRoute.Buckets -> "buckets"
                                    AppRoute.Output -> "output"
                                    is AppRoute.Settings -> "settings:${route.menuText}"
                                }
                                val previousRoute = this@Router.snapshot.dropLast(1).lastOrNull()?.value
                                val bothSettings = currentRoute.value is AppRoute.Settings && previousRoute is AppRoute.Settings
                                val animateContent = !bothSettings
                                key(routeKey) {
                                    EnterAnimation(animateContent) {
                                        when (val route = currentRoute.value) {
                                            AppRoute.Splash -> {}
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
            }
        }
    }
}

@Composable
fun SplashScreen(onClose: () -> Unit) {
    Window(
        onCloseRequest = onClose,
        state = rememberWindowState(
            width = 520.dp,
            height = 360.dp,
            placement = WindowPlacement.Floating,
            position = WindowPosition(Alignment.Center)
        ),
        title = "Scooper",
        icon = painterResource("logo.svg"),
        undecorated = true,
        resizable = false,
        transparent = true,
    ) {
        Box(
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).fillMaxSize()
                .background(Slate50)
                .border(1.dp, Slate200, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Logo with subtle shadow container
                Box(
                    modifier = Modifier.size(88.dp)
                        .background(Slate50, RoundedCornerShape(20.dp))
                        .border(1.dp, Slate200, RoundedCornerShape(20.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource("logo.svg"),
                        contentDescription = "Scooper",
                        modifier = Modifier.size(56.dp)
                    )
                }

                Spacer(Modifier.height(20.dp))

                // App name
                Text(
                    "Scooper",
                    style = typography().h5,
                    color = Slate900
                )

                Spacer(Modifier.height(4.dp))

                // Tagline
                Text(
                    "Scoop Package Manager GUI",
                    style = typography().caption,
                    color = Slate400
                )

                Spacer(Modifier.height(28.dp))

                // Loading indicator with brand color
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color = Blue600
                )
            }
        }
    }
}
