package scooper.viewmodels

import kotlinx.coroutines.GlobalScope
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import scooper.data.App
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
        getApps()
    }

    fun getApps(page: Int = 0) = intent {
        postSideEffect(AppsSideEffect.Loading)
        val apps = AppsRepository.getApps(limit = 1000)
        reduce { state.copy(apps = apps) }
        postSideEffect(AppsSideEffect.Done)
    }

    fun reloadApps() = intent {
        AppsRepository.loadApps()
        getApps()
    }
}

