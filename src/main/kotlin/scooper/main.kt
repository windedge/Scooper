package scooper

import androidx.compose.desktop.Window
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme.colors
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get
import scooper.di.viewModelsModule
import scooper.framework.navigation.Router
import scooper.framework.navigation.core.BackStack
import scooper.repository.initDb
import scooper.ui.AppPage
import scooper.ui.MenuBar
import scooper.ui.theme.ScooperTheme
import scooper.viewmodels.AppsViewModel
import kotlin.time.ExperimentalTime


sealed class AppRoute {
    data class Apps(val scope: String) : AppRoute()
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
        Router<AppRoute>(start = AppRoute.Apps(scope = "")) { currentRoute ->
            Layout(this) {
                when (val route = currentRoute.value) {
                    is AppRoute.Apps -> {
                        appsViewModel.resetFilter()
                        AppPage(route.scope)
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
