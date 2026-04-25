package scooper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.runBlocking
import org.koin.compose.koinInject
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.slf4j.LoggerFactory
import scooper.data.MIN_WINDOW_HEIGHT
import scooper.data.MIN_WINDOW_WIDTH
import scooper.data.toSystemTheme
import scooper.di.system
import scooper.di.viewModels
import scooper.repository.AppsRepository
import scooper.repository.ConfigRepository
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
import java.io.File
import kotlin.math.roundToInt

val LocalShowFps = compositionLocalOf { mutableStateOf(false) }

@Suppress("unused")
private val logger by lazy { LoggerFactory.getLogger("Main") }

fun main() {
    startKoin { modules(system, viewModels) }

    val dbFile = File(System.getenv("USERPROFILE")).resolve(".scooper.db")
    val needsDbInit = !dbFile.exists()

    // For existing databases, init synchronously (very fast) to avoid showing any splash
    if (!needsDbInit) {
        val appsRepository: AppsRepository = GlobalContext.get().get()
        runBlocking { initDb(appsRepository) }
    }

    application {
        var dbReady by remember { mutableStateOf(!needsDbInit) }
        var initProgress by remember { mutableStateOf(0f) }
        if (!dbReady) {
            val appsRepository: AppsRepository = koinInject()
            LaunchedEffect(Unit) {
                initDb(appsRepository) { progress -> initProgress = progress }
                dbReady = true
            }
            SplashScreen(onClose = ::exitApplication, progress = initProgress)
            return@application
        }

        val configRepository: ConfigRepository = koinInject()
    val savedConfig = remember { configRepository.getConfig() }

    val winState = remember {
        logger.info("Restoring window: x=${savedConfig.windowX}, y=${savedConfig.windowY}, w=${savedConfig.windowWidth}, h=${savedConfig.windowHeight}, maximized=${savedConfig.isMaximized}")
        WindowState(
            width = savedConfig.windowWidth.dp,
            height = savedConfig.windowHeight.dp,
            placement = if (savedConfig.isMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = if (savedConfig.windowX != null && savedConfig.windowY != null) {
                WindowPosition(
                    x = savedConfig.windowX.dp,
                    y = savedConfig.windowY.dp,
                )
            } else {
                WindowPosition(Alignment.Center)
            }
        )
    }

    val appsViewModel: AppsViewModel = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
    val cleanupViewModel: CleanupViewModel = koinInject()

    Window(
        onCloseRequest = {
            val isMaximized = winState.placement == WindowPlacement.Maximized
            val size = winState.size
            val pos = winState.position
            logger.info("Saving window: x=${pos.x.value}, y=${pos.y.value}, w=${size.width.value}, h=${size.height.value}, maximized=$isMaximized")
            configRepository.setConfig(savedConfig.copy(
                windowX = pos.x.value.roundToInt(),
                windowY = pos.y.value.roundToInt(),
                windowWidth = size.width.value.roundToInt(),
                windowHeight = size.height.value.roundToInt(),
                isMaximized = isMaximized,
            ))
            appsViewModel.close()
            settingsViewModel.close()
            cleanupViewModel.close()
            exitApplication()
        },
        winState,
        title = "Scooper",
        icon = painterResource("logo.svg"),
    ) {
        window.minimumSize = Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT)

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
                    AppRoute.Cleanup -> false
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
                            ToolbarRow(showToolbar && currentRoute.value != AppRoute.Buckets && currentRoute.value != AppRoute.Cleanup)
                            Layout {
                                val routeKey = when (val route = currentRoute.value) {
                                    AppRoute.Splash -> "splash"
                                    is AppRoute.Apps -> "apps:${route.scope}"
                                    AppRoute.Buckets -> "buckets"
                                    AppRoute.Cleanup -> "cleanup"
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
                                            AppRoute.Cleanup -> CleanupScreen()
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
}

@Composable
fun SplashScreen(onClose: () -> Unit, progress: Float = 0f) {
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

                Text(
                    "Scooper",
                    style = typography().h5,
                    color = Slate900
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    "Scoop Package Manager GUI",
                    style = typography().caption,
                    color = Slate400
                )

                Spacer(Modifier.height(28.dp))

                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.width(180.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Blue600,
                    backgroundColor = Slate200,
                )
            }
        }
    }
}
