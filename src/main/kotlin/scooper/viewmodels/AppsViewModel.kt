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

data class AppsFilter(
    val query: String = "",
    val selectBucket: String = "",
    val page: Int = 1,
    val scope: String = "all",
)

data class AppsState(
    val apps: List<App> = emptyList(),
    val buckets: List<Bucket> = emptyList(),
    val filter: AppsFilter = AppsFilter()
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
        getApps()
    }

    fun getApps(
        query: String? = null,
        bucket: String? = null,
        scope: String? = null,
        page: Int? = null
    ) = intent {
        postSideEffect(AppsSideEffect.Loading)
        reduce {
            val currentQuery = query ?: state.filter.query
            val currentBucket = bucket ?: state.filter.selectBucket
            val currentScope = scope ?: state.filter.scope
            val currentPage = page ?: state.filter.page

            val apps = AppsRepository.getApps(currentQuery, currentBucket, currentScope, limit = 1000)
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
        val buckets = AppsRepository.getBuckets()
        reduce { state.copy(buckets = buckets) }
    }

    fun reloadApps() = intent {
        AppsRepository.loadApps()
        getApps()
    }

    fun resetFilter() = intent {
        reduce { state.copy(filter = AppsFilter()) }
    }
}

