package scooper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import scooper.ui.components.rememberPainterResource
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
import scooper.ui.components.CustomSnackbarHostState
import scooper.ui.components.SnackbarHost
import scooper.ui.theme.*
import scooper.util.bringToFront
import scooper.util.navigation.LocalBackStack
import scooper.util.navigation.core.BackStack
import scooper.util.navigation.Router
import scooper.util.ProvideI18n
import scooper.util.Strings
import scooper.viewmodels.AppSideEffect
import scooper.viewmodels.AppsViewModel
import scooper.viewmodels.CleanupViewModel
import scooper.ui.icons.*
import scooper.viewmodels.ScoopSearchViewModel
import scooper.viewmodels.SettingsViewModel
import java.awt.Desktop
import java.awt.Dimension
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.merge
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.geometry.Size
import scooper.util.TrayManager
import scooper.util.tr
import javax.swing.ImageIcon

val LocalShowFps = compositionLocalOf { mutableStateOf(false) }
val LocalFocusSearch = compositionLocalOf<() -> Unit> { {} }

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
    val scoopSearchViewModel: ScoopSearchViewModel = koinInject()

    // Window-level mutable state shared between Window onPreviewKeyEvent and content
    val focusSearchRequester = mutableStateOf(0)
    val navigatorRef = mutableStateOf<BackStack<AppRoute>?>(null)

    // Shared exit logic used by both window close and tray exit
    fun performExit() {
        val isMaximized = winState.placement == WindowPlacement.Maximized
        val size = winState.size
        val pos = winState.position
        logger.info("Saving window: x=${pos.x.value}, y=${pos.y.value}, w=${size.width.value}, h=${size.height.value}, maximized=$isMaximized")
        configRepository.setConfig(configRepository.getConfig().copy(
            windowX = pos.x.value.roundToInt(),
            windowY = pos.y.value.roundToInt(),
            windowWidth = size.width.value.roundToInt(),
            windowHeight = size.height.value.roundToInt(),
            isMaximized = isMaximized,
        ))
        appsViewModel.close()
        settingsViewModel.close()
        cleanupViewModel.close()
        scoopSearchViewModel.close()
        exitApplication()
    }

    // Track show-tray-icon setting reactively
    var showTrayIcon by remember { mutableStateOf(savedConfig.showTrayIcon) }
    var windowVisible by remember { mutableStateOf(true) }
    var bringToFront by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = {
            if (showTrayIcon) {
                windowVisible = false
            } else {
                performExit()
            }
        },
        state = winState,
        visible = windowVisible,
        title = "Scooper",
        icon = rememberPainterResource("logo.svg"),
        // Window-level key event interception — handled at AWT level, independent of Compose focus.
        onPreviewKeyEvent = { keyEvent ->
            handleWindowShortcut(
                keyEvent = keyEvent,
                navigator = navigatorRef.value,
                appsViewModel = appsViewModel,
                onFocusSearch = { focusSearchRequester.value++ },
            )
        }
    ) {
        window.minimumSize = Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT)

        // When the window becomes visible (e.g. restored from tray), bring it to the foreground
        LaunchedEffect(windowVisible) {
            if (windowVisible) {
                bringToFront = true
            }
        }
        // Bring window to foreground when requested (e.g. double-click tray icon)
        LaunchedEffect(bringToFront) {
            if (bringToFront) {
                window.bringToFront()
                bringToFront = false
            }
        }

        val settings by settingsViewModel.container.stateFlow.collectAsState()
        val uiConfig = settings.uiConfig
        val theme = uiConfig.theme.toSystemTheme()
        val currentLocale = name.kropp.kotlinx.gettext.Locale.forLanguageTag(uiConfig.locale)

        // Sync show-tray-icon setting from ViewModel state
        // If the setting is disabled while the window is hidden, show the window
        LaunchedEffect(uiConfig.showTrayIcon) {
            showTrayIcon = uiConfig.showTrayIcon
            if (!uiConfig.showTrayIcon) {
                windowVisible = true
                bringToFront = true
            }
        }

        // Auto-refresh: delegate to ViewModel
        LaunchedEffect(settings.uiConfig.periodicRefreshEnabled, settings.uiConfig.autoRefreshIntervalMinutes) {
            appsViewModel.setAutoRefresh(
                settings.uiConfig.periodicRefreshEnabled,
                settings.uiConfig.autoRefreshIntervalMinutes,
            )
        }

        val snackbarHostState = remember { CustomSnackbarHostState() }
        var statusText by remember { mutableStateOf("") }

        // Collect all side effects from all ViewModels
        LaunchedEffect(appsViewModel, settingsViewModel, cleanupViewModel, scoopSearchViewModel) {
            merge(
                appsViewModel.container.sideEffectFlow,
                settingsViewModel.container.sideEffectFlow,
                cleanupViewModel.container.sideEffectFlow,
                scoopSearchViewModel.container.sideEffectFlow,
            ).collect { sideEffect ->
                when (sideEffect) {
                    is AppSideEffect.Toast -> snackbarHostState.showSnackbar(sideEffect.text, sideEffect.type)
                    is AppSideEffect.Log -> statusText = sideEffect.text
                }
            }
        }

        val showFpsState = remember { mutableStateOf(false) }

        ProvideI18n(currentLocale) {
        ScooperTheme(
            currentTheme = theme,
            fontSizeScale = uiConfig.fontSizeScale,
            uiLanguage = Strings.current.locale.language,
            userFontFamilyName = uiConfig.fontFamily,
        ) {
            CompositionLocalProvider(LocalShowFps provides showFpsState) {
                // Snackbar overlay sits above Router so it survives route changes.
                Box(Modifier.fillMaxSize()) {
                    Router<AppRoute>(start = AppRoute.Apps(scope = "")) { currentRoute ->
                        @Suppress("UNCHECKED_CAST")
                        val navigator = LocalBackStack.current as BackStack<AppRoute>
                        // Expose navigator to Window-level onPreviewKeyEvent
                        navigatorRef.value = navigator
                        val provideFocusSearch: () -> Unit = { focusSearchRequester.value++ }

                        val showToolbar = when (currentRoute.value) {
                            is AppRoute.Settings, AppRoute.Output, AppRoute.Cleanup, AppRoute.ScoopSearch -> false
                            else -> true
                        }

                        val appsState by appsViewModel.container.stateFlow.collectAsState()

                        Column(Modifier.fillMaxSize()) {
                            CompositionLocalProvider(LocalFocusSearch provides provideFocusSearch) {
                                Row(Modifier.weight(1f)) {
                                    val isSettings = currentRoute.value is AppRoute.Settings
                                    val isOutput = currentRoute.value == AppRoute.Output
                                    if (!isSettings && !isOutput) {
                                        SidebarNav(updateCount = appsState.updateCount)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        if (currentRoute.value is AppRoute.Apps) {
                                            SearchBar(
                                                show = showToolbar,
                                                focusRequester = focusSearchRequester.value,
                                                onResetFocusRequester = { focusSearchRequester.value = 0 },
                                            )
                                        } else {
                                            val showToolbarRow = showToolbar && currentRoute.value !in listOf(
                                                AppRoute.Buckets, AppRoute.Cleanup, AppRoute.ScoopSearch
                                            )
                                            ToolbarRow(showToolbarRow)
                                        }
                                        Layout {
                                            val routeKey = when (val route = currentRoute.value) {
                                                AppRoute.Splash -> "splash"
                                                is AppRoute.Apps -> "apps:${route.scope}"
                                                AppRoute.Buckets -> "buckets"
                                                AppRoute.Cleanup -> "cleanup"
                                                AppRoute.ScoopSearch -> "scoopSearch"
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
                                                        AppRoute.ScoopSearch -> ScoopSearchScreen()
                                                        AppRoute.Output -> OutputScreen(onBack = { navigator.pop() })
                                                        is AppRoute.Settings -> SettingScreen()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } // CompositionLocalProvider LocalFocusSearch
                            StatusBar(statusText)
                        }
                    } // Router

                    // Snackbar overlay — outside Router so it is not recreated on route change.
                    Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 48.dp)) {
                        SnackbarHost(snackbarHostState)
                    }
                } // Box
            } // CompositionLocalProvider LocalShowFps
        } // ScooperTheme
        } // ProvideI18n
    }

    // System tray icon — shown when show-tray-icon is enabled
    if (showTrayIcon) {
        SystemTrayIcon(
            onShow = { windowVisible = true; bringToFront = true },
            onExit = { performExit() }
        )
    }
    }
}

@Composable
private fun SystemTrayIcon(
    onShow: () -> Unit,
    onExit: () -> Unit
) {
    val trayIconPainter = rememberPainterResource("logo.svg")
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val iconSize = Size(16f, 16f)
    
    val awtIcon = remember(trayIconPainter, density, layoutDirection) {
        trayIconPainter.toAwtImage(density, layoutDirection, iconSize)
    }
    val showIcon = remember(awtIcon) { ImageIcon(awtIcon) }
    
    // Convert Lucide.Power (Compose ImageVector) to Swing Icon for the Exit menu item
    val exitPainter = rememberVectorPainter(Lucide.Power)
    val exitAwtImage = remember(exitPainter, density, layoutDirection) {
        exitPainter.toAwtImage(density, layoutDirection, iconSize)
    }
    val exitIcon = remember(exitAwtImage) { ImageIcon(exitAwtImage) }
    
    val trayManager = remember {
        TrayManager(
            icon = awtIcon,
            showIcon = showIcon,
            exitIcon = exitIcon,
            onShow = onShow,
            onExit = onExit
        )
    }

    // Rebuild the tray popup labels whenever the UI language changes.
    // Reading Strings.current here subscribes this composable to locale switches.
    LaunchedEffect(Strings.current) {
        trayManager.refreshMenu()
    }

    DisposableEffect(trayManager) {
        trayManager.install()
        onDispose { trayManager.remove() }
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
        icon = rememberPainterResource("logo.svg"),
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
                        painter = rememberPainterResource("logo.svg"),
                        contentDescription = "Scooper",
                        modifier = Modifier.size(56.dp)
                    )
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    tr("Scooper"),
                    style = typography().h5,
                    color = Slate900
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    tr("Scoop Package Manager GUI"),
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

