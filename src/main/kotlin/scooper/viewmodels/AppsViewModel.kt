package scooper.viewmodels

import kotlinx.coroutines.GlobalScope
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import scooper.data.App
import scooper.data.Bucket
import scooper.repository.AppsRepository
import scooper.repository.Scoop
import java.util.*

data class AppsFilter(
    val query: String = "",
    val selectBucket: String = "",
    val page: Int = 1,
    val scope: String = "all",
)

data class AppsState(
    val apps: List<App> = emptyList(),
    val buckets: List<Bucket> = emptyList(),
    val filter: AppsFilter = AppsFilter(),
    val installingApp: String? = null,
    val updatingApps: Boolean = false
)

sealed class AppsSideEffect {
    object Empty : AppsSideEffect()
    object Loading : AppsSideEffect()
    object Done : AppsSideEffect()
    data class Toast(val text: String) : AppsSideEffect()
}

class AppsViewModel : ContainerHost<AppsState, AppsSideEffect> {

    override val container: Container<AppsState, AppsSideEffect> = GlobalScope.container(AppsState()) {
        getBuckets()
        applyFilters()
    }

    fun applyFilters(
        query: String? = null,
        bucket: String? = null,
        scope: String? = null,
        page: Int? = null
    ) = intent {
        postSideEffect(AppsSideEffect.Loading)
        val currentQuery = query ?: state.filter.query
        val currentBucket = bucket ?: state.filter.selectBucket
        val currentScope = scope ?: state.filter.scope
        val currentPage = page ?: state.filter.page
        val apps = AppsRepository.getApps(currentQuery, currentBucket, currentScope, limit = 1000)
        println("applyFilters: now = ${Date(System.currentTimeMillis())}")
        reduce {
            state.copy(
                apps = apps,
                filter = state.filter.copy(
                    query = currentQuery,
                    selectBucket = currentBucket,
                    scope = currentScope,
                    page = currentPage
                )
            )
        }
        postSideEffect(AppsSideEffect.Done)
    }

    fun getBuckets() = intent {
        AppsRepository.loadBuckets()
        val buckets = AppsRepository.getBuckets()
        reduce { state.copy(buckets = buckets) }
    }

    fun reloadApps() = intent {
        AppsRepository.loadApps()
        applyFilters()
    }

    fun resetFilter() = intent {
        reduce { state.copy(filter = AppsFilter()) }
    }

    fun updateApps() = intent {
        reduce { state.copy(updatingApps = true) }
        Scoop.update {
            println("before reloadApps: ${Date(System.currentTimeMillis())}")
            reloadApps()
            println("after reloadApps: ${Date(System.currentTimeMillis())}")
            reduce {
                state.copy(updatingApps = false)
            }
        }
    }

    fun install(app: App, global: Boolean = false) = intent {
        reduce {
            state.copy(installingApp = app.name)
        }

        Scoop.install(app, global) {
            reloadApps()
            reduce {
                state.copy(installingApp = null)
            }
        }
    }

    fun uninstall(app: App) = intent {
        reduce {
            state.copy(installingApp = app.name)
        }
        Scoop.uninstall(app, app.global) {
            reloadApps()
            reduce {
                state.copy(installingApp = null)
            }
        }
    }

    fun upgrade(app: App) = intent {
        reduce {
            state.copy(installingApp = app.name)
        }
        Scoop.upgrade(app, app.global) {
            reloadApps()
            reduce {
                state.copy(installingApp = null)
            }
        }
    }

    fun cancel() = intent {
        Scoop.stop()
        reduce {
            state.copy(installingApp = null)
        }
    }

    fun addBucket(bucket: String, url: String? = null) = intent {
        Scoop.addBucket(bucket, url, onFinish = { exitValue ->
            if (exitValue != 0) {
                postSideEffect(AppsSideEffect.Toast("add bucket error!"))
                return@addBucket
            } else {
                postSideEffect(AppsSideEffect.Toast("add bucket successfully!"))
            }
            getBuckets()
            reloadApps()
        })
    }

    fun deleteBucket(bucket: String) = intent {
        Scoop.removeBucket(bucket, onFinish = { exitValue ->
            if (exitValue != 0) {
                postSideEffect(AppsSideEffect.Toast("remove bucket error!"))
                return@removeBucket
            } else {
                postSideEffect(AppsSideEffect.Toast("remove bucket successfully!"))
            }
            getBuckets()
            reloadApps()
        })
    }
}

