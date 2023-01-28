package scooper.viewmodels

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.annotation.OrbitExperimental
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.syntax.simple.*
import scooper.data.App
import scooper.data.Bucket
import scooper.repository.AppsRepository
import scooper.ui.AppRoute
import scooper.util.Scoop
import scooper.util.logger


enum class OperationType {
    INSTALL_APP, UPDATE_APP, DOWNLOAD_APP, UNINSTALL_APP, ADD_BUCKET, REMOVE_BUCKET, REFRESH
}

data class Operation(
    val action: OperationType,
    val target: Any? = null,   // app or bucket
    val global: Boolean = false,
    val url: String? = null,
) {
    override fun toString(): String {
        val target = when (this.target) {
            is App -> this.target.name
            is String -> this.target
            else -> this.target?.toString()
        }
        return action.toString() + if (this.target != null) "-> $target" else ""
    }
}

data class AppsFilter(
    val query: String = "",
    val selectBucket: String = "",
    val page: Int = 1,
    val scope: String = "all",
)

data class AppsState(
    val apps: List<App>? = null,
    val buckets: List<Bucket> = emptyList(),
    val filter: AppsFilter = AppsFilter(),
    val processingApp: String? = null,
    val refreshing: Boolean = false,
    val waitingApps: Set<String> = emptySet(),
    val output: String = "",
)


sealed class AppsSideEffect {
    object Empty : AppsSideEffect()
    object Loading : AppsSideEffect()
    object Done : AppsSideEffect()
    data class Toast(val text: String) : AppsSideEffect()
    data class Log(val text: String) : AppsSideEffect()
    data class Route(val route: AppRoute) : AppsSideEffect()
}

@OptIn(ExperimentalCoroutinesApi::class, OrbitExperimental::class)
@Suppress("MemberVisibilityCanBePrivate")
class AppsViewModel : ContainerHost<AppsState, AppsSideEffect> {
    private val logger by logger()

    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private val channel = Channel<Operation>(1)

    override val container: Container<AppsState, AppsSideEffect> = coroutineScope.container(AppsState()) {
        launchOperationQueue()
        applyFilters()
        getBuckets()
        subscribeLogging()
    }

    fun launchOperationQueue() {
        CoroutineScope(Dispatchers.IO).launch {
            while (!channel.isClosedForReceive) {
                val operation = channel.receive()
                logger.info("operation = $operation ...")
                when (operation.action) {
                    OperationType.INSTALL_APP -> installApp(operation.target as App, operation.global)
                    OperationType.UPDATE_APP -> updateApp(operation.target as App)
                    OperationType.DOWNLOAD_APP -> downloadApp(operation.target as App)
                    OperationType.UNINSTALL_APP -> uninstallApp(operation.target as App)
                    OperationType.ADD_BUCKET -> addScoopBucket(operation.target as String, operation.url)
                    OperationType.REMOVE_BUCKET -> removeScoopBucket(operation.target as String)
                    OperationType.REFRESH -> refresh()
                }
                logger.info("operation = $operation done.")
            }
        }
    }

    fun subscribeLogging() {
        coroutineScope.launch(Dispatchers.IO) {
            Scoop.logStream.collect {
                intent {
                    postSideEffect(AppsSideEffect.Log(it))
                    val output = state.output + it + "\n"
                    reduce { state.copy(output = output) }
                }
            }
        }
    }

    fun applyFilters(
        query: String? = null, bucket: String? = null, scope: String? = null, page: Int? = null
    ) = blockingIntent {
        val currentQuery = query ?: state.filter.query
        val currentBucket = bucket ?: state.filter.selectBucket
        val currentScope = scope ?: state.filter.scope
        val currentPage = page ?: state.filter.page
        val apps = AppsRepository.getApps(currentQuery, currentBucket, currentScope, limit = 1000)
        reduce {
            state.copy(
                apps = apps, filter = state.filter.copy(
                    query = currentQuery,
                    selectBucket = currentBucket,
                    scope = currentScope,
                    page = currentPage
                )
            )
        }
    }

    fun onQueryChange(text: String) = blockingIntent {
        reduce { state.copy(filter = state.filter.copy(query = text)) }
    }

    fun getBuckets() = intent {
        AppsRepository.loadBuckets()
        val buckets = AppsRepository.getBuckets()
        reduce { state.copy(buckets = buckets) }
    }

    fun reloadApps() = blockingIntent {
        AppsRepository.loadApps()
        applyFilters()
    }

    fun resetFilter() = intent {
        reduce { state.copy(filter = AppsFilter()) }
    }

    fun refresh() = blockingIntent {
        reduce { state.copy(refreshing = true) }
        Scoop.refresh {
            reloadApps()
            reduce {
                state.copy(refreshing = false)
            }
        }
    }

    fun queuedUpdateApps() = intent {
        channel.send(Operation(OperationType.REFRESH))
    }

    fun installApp(app: App, global: Boolean = false) = blockingIntent {
        reduce {
            state.copy(processingApp = app.name, waitingApps = state.waitingApps - setOf(app.uniqueName))
        }

        Scoop.install(app, global) { exitValue ->
            reduce { state.copy(processingApp = null) }
            if (exitValue != 0) {
                postSideEffect(AppsSideEffect.Toast("Install app, ${app.uniqueName} error!"))
                return@install
            }

            postSideEffect(AppsSideEffect.Toast("Install app, ${app.uniqueName} successfully!"))
            AppsRepository.updateApp(app.copy(status = "installed"))
            applyFilters()
        }
    }

    fun queuedInstall(app: App, global: Boolean = false) = intent {
        reduce {
            state.copy(waitingApps = state.waitingApps + setOf(app.uniqueName))
        }
        channel.send(Operation(OperationType.INSTALL_APP, app, global))
    }

    fun uninstallApp(app: App) = blockingIntent {
        reduce {
            state.copy(processingApp = app.name, waitingApps = state.waitingApps - setOf(app.uniqueName))
        }
        Scoop.uninstall(app, app.global) { exitValue ->
            reduce { state.copy(processingApp = null) }
            if (exitValue != 0) {
                postSideEffect(AppsSideEffect.Toast("Uninstall app, ${app.uniqueName} error!"))
                return@uninstall
            }

            postSideEffect(AppsSideEffect.Toast("Uninstall app, ${app.uniqueName} successfully!"))
            AppsRepository.updateApp(app.copy(status = "uninstall", global = false))
            applyFilters()
        }
    }

    fun queuedUninstall(app: App) = intent {
        reduce {
            state.copy(waitingApps = state.waitingApps + setOf(app.uniqueName))
        }
        channel.send(Operation(OperationType.UNINSTALL_APP, app))
    }

    fun updateApp(app: App) = blockingIntent {
        reduce {
            state.copy(processingApp = app.name, waitingApps = state.waitingApps - setOf(app.uniqueName))
        }
        Scoop.update(app, app.global) { exitValue ->
            reduce { state.copy(processingApp = null) }
            if (exitValue != 0) {
                postSideEffect(AppsSideEffect.Toast("Update app, ${app.uniqueName} error!"))
                return@update
            }

            postSideEffect(AppsSideEffect.Toast("Update app, ${app.uniqueName} successfully!"))
            AppsRepository.updateApp(app.copy(version = app.latestVersion))
            applyFilters()
        }
    }

    fun queuedUpdate(app: App) = intent {
        reduce {
            state.copy(waitingApps = state.waitingApps + setOf(app.uniqueName))
        }
        channel.send(Operation(OperationType.UPDATE_APP, app))
    }

    fun downloadApp(app: App) = blockingIntent {
        reduce {
            state.copy(processingApp = app.name, waitingApps = state.waitingApps - setOf(app.uniqueName))
        }
        Scoop.download(app) { exitValue ->
            reduce { state.copy(processingApp = null) }
            if (exitValue != 0) {
                postSideEffect(AppsSideEffect.Toast("Download app: ${app.uniqueName} error!"))
                return@download
            }

            postSideEffect(AppsSideEffect.Toast("Download app: ${app.uniqueName} successfully!"))
            applyFilters()
        }
    }

    fun queuedDownload(app: App) = intent {
        reduce {
            state.copy(waitingApps = state.waitingApps + setOf(app.uniqueName))
        }
        channel.send(Operation(OperationType.DOWNLOAD_APP, app))
    }


    fun addScoopBucket(bucket: String, url: String? = null) = blockingIntent {
        Scoop.addBucket(bucket, url) { exitValue ->
            if (exitValue != 0) {
                postSideEffect(AppsSideEffect.Toast("Add bucket: $bucket error!"))
                return@addBucket
            }

            postSideEffect(AppsSideEffect.Toast("Add bucket: $bucket successfully!"))
            getBuckets()
            reloadApps()
        }
    }

    fun queuedAddBucket(bucket: String, url: String? = null) = intent {
        channel.send(Operation(OperationType.ADD_BUCKET, bucket, url = url))
    }

    fun removeScoopBucket(bucket: String) = blockingIntent {
        Scoop.removeBucket(bucket) { exitValue ->
            if (exitValue != 0) {
                postSideEffect(AppsSideEffect.Toast("Remove bucket: $bucket error!"))
                return@removeBucket
            }

            postSideEffect(AppsSideEffect.Toast("Remove bucket: $bucket successfully!"))
            getBuckets()
            reloadApps()
        }
    }

    fun queuedRemoveBucket(bucket: String) = intent {
        channel.send(Operation(OperationType.REMOVE_BUCKET, bucket))
    }

    fun cancel(app: App? = null) = blockingIntent {
        logger.info("cancelling")
        Scoop.stop()

        reduce { state.copy(refreshing = false) }

        if (app != null) {
            reduce { state.copy(processingApp = null) }
            logger.info("cancelling app = ${app.uniqueName}")
            AppsRepository.updateApp(app.copy(status = "failed"))
            applyFilters()
        }
    }
}

