package scooper.viewmodels

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.annotation.OrbitExperimental
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.syntax.simple.*
import scooper.data.App
import scooper.data.Bucket
import scooper.repository.AppsRepository
import scooper.util.PAGE_SIZE
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
        return action.toString() + (target?.let { " -> $it" } ?: "")
    }
}

data class AppsFilter(
    val query: String = "",
    val selectedBucket: String = "",
    val page: Int = 1,
    val pageSize: Int = PAGE_SIZE,
    val scope: String = "all",
)

data class AppsState(
    val apps: List<App>? = null,
    val totalCount: Long = 0L,
    val buckets: List<Bucket> = emptyList(),
    val filter: AppsFilter = AppsFilter(),
    val processingApp: String? = null,
    val refreshing: Boolean = false,
    val waitingApps: Set<String> = emptySet(),
    val output: String = "",
)


@OptIn(ExperimentalCoroutinesApi::class, OrbitExperimental::class)
@Suppress("MemberVisibilityCanBePrivate")
class AppsViewModel : ContainerHost<AppsState, SideEffect> {
    private val logger by logger()

    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    override val container: Container<AppsState, SideEffect> = coroutineScope.container(AppsState()) {
        launchOperationQueue()
        applyFilters()
        getBuckets()
        subscribeLogging()
        subscribeQuery()
    }

    private val channel = Channel<Operation>(1)
    private val _queryText = MutableStateFlow<String>("")
    val queryText = _queryText.asStateFlow()

    @OptIn(FlowPreview::class)
    fun subscribeQuery() {
        coroutineScope.launch {
            _queryText.debounce(400L).collect {
                this@AppsViewModel.applyFilters(query = it)
            }
        }
    }

    fun onQueryChange(text: String) {
        _queryText.value = text
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
                    postSideEffect(SideEffect.Log(it))
                    val output = state.output + it + "\n"
                    reduce { state.copy(output = output) }
                }
            }
        }
    }

    fun applyFilters(
        query: String? = null, bucket: String? = null, scope: String? = null, page: Int? = null
    ) = intent {
        val currentQuery = query ?: state.filter.query
        val currentBucket = bucket ?: state.filter.selectedBucket
        val currentScope = scope ?: state.filter.scope
        val result =
            AppsRepository.getApps(currentQuery, currentBucket, currentScope, limit = state.filter.pageSize)

        reduce {
            state.copy(
                apps = result.value,
                totalCount = result.totalCount,
                filter = state.filter.copy(
                    query = currentQuery,
                    selectedBucket = currentBucket,
                    scope = currentScope,
                    page = 1
                )
            )
        }
    }

    fun loadMore() = intent {
        val filter = state.filter
        val nextPage = filter.page + 1
        val offset = (nextPage - 1) * filter.pageSize.toLong()
        val pageSize = filter.pageSize
        val result =
            AppsRepository.getApps(filter.query, filter.selectedBucket, filter.scope, offset = offset, limit = pageSize)

        reduce {
            state.copy(
                apps = state.apps?.plus(result.value) ?: result.value,
                filter = state.filter.copy(page = nextPage)
            )
        }
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
        _queryText.value = ""
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
                postSideEffect(SideEffect.Toast("Install app, ${app.uniqueName} error!"))
                return@install
            }

            postSideEffect(SideEffect.Toast("Install app, ${app.uniqueName} successfully!"))
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
                postSideEffect(SideEffect.Toast("Uninstall app, ${app.uniqueName} error!"))
                return@uninstall
            }

            postSideEffect(SideEffect.Toast("Uninstall app, ${app.uniqueName} successfully!"))
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
                postSideEffect(SideEffect.Toast("Update app, ${app.uniqueName} error!"))
                return@update
            }

            postSideEffect(SideEffect.Toast("Update app, ${app.uniqueName} successfully!"))
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
                postSideEffect(SideEffect.Toast("Download app: ${app.uniqueName} error!"))
                return@download
            }

            postSideEffect(SideEffect.Toast("Download app: ${app.uniqueName} successfully!"))
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
                postSideEffect(SideEffect.Toast("Add bucket: $bucket error!"))
                return@addBucket
            }

            postSideEffect(SideEffect.Toast("Add bucket: $bucket successfully!"))
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
                postSideEffect(SideEffect.Toast("Remove bucket: $bucket error!"))
                return@removeBucket
            }

            postSideEffect(SideEffect.Toast("Remove bucket: $bucket successfully!"))
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

