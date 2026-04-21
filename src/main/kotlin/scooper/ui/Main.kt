package scooper.ui

import androidx.compose.foundation.Image
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import scooper.ui.theme.ScooperTheme
import scooper.util.navigation.Router
import scooper.viewmodels.AppsSideEffect
import scooper.viewmodels.AppsViewModel
import scooper.viewmodels.CleanupSideEffect
import scooper.viewmodels.CleanupViewModel
import scooper.viewmodels.SettingsSideEffect
import scooper.viewmodels.SettingsViewModel
import java.awt.Dimension

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

    val scope = rememberCoroutineScope()
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
            window.minimumSize = Dimension(800.dp.roundToPx(), 500.dp.roundToPx())
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

        ScooperTheme(currentTheme = theme) {
            Router<AppRoute>(start = AppRoute.Apps(scope = "")) { currentRoute ->
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

@Composable
private fun SplashScreen(onClose: () -> Unit) {
    Window(
        onCloseRequest = onClose,
        state = rememberWindowState(
            width = 480.dp,
            height = 320.dp,
            placement = WindowPlacement.Floating,
            position = WindowPosition(Alignment.Center)
        ),
        title = "Scooper",
        icon = painterResource("logo.svg"),
        undecorated = true,
        resizable = false,
    ) {
        Box(
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Image(
                    painter = painterResource("logo.svg"),
                    contentDescription = "Scooper",
                    modifier = Modifier.size(64.dp)
                )
                Text("Scooper", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            }
        }
    }
}
