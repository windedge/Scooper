package scooper.viewmodels

import kotlinx.coroutines.GlobalScope
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import scooper.repository.App
import scooper.repository.AppsRepository

data class AppsState(
    val apps: List<App> = emptyList(),
    val page: Int = 1,
)

sealed class AppsSideEffect {
    object Empty : AppsSideEffect()
    object Loading : AppsSideEffect()
    object Done : AppsSideEffect()
    data class Toast(val text: String) : AppsSideEffect()
}

class AppsViewModel : ContainerHost<AppsState, AppsSideEffect> {
    override val container: Container<AppsState, AppsSideEffect> = GlobalScope.container(AppsState()) {
        loadApps()
    }

    fun loadApps(page: Int = 0) = intent {
        postSideEffect(AppsSideEffect.Loading)
        val apps = AppsRepository.getApps()
        reduce { state.copy(apps = apps) }
        postSideEffect(AppsSideEffect.Done)
    }
}

