package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.blockingIntent
import org.orbitmvi.orbit.container
import scooper.data.App
import scooper.data.AppStatus
import scooper.data.Bucket
import scooper.repository.AppsRepository
import scooper.service.ScoopClient
import scooper.service.ScoopEvent
import scooper.service.ScoopSearchApp
import scooper.service.ScoopSearchService
import scooper.service.ScoopSearchSort
import scooper.service.ScoopService
import scooper.taskqueue.Task
import scooper.taskqueue.TaskQueue
import scooper.util.logger
import scooper.util.tr
import scooper.viewmodels.AppSideEffect
import scooper.viewmodels.ToastType
import scooper.viewmodels.taskToast

data class ScoopSearchState(
    val query: String = "",
    val results: List<ScoopSearchApp> = emptyList(),
    val totalCount: Int = 0,
    val currentPage: Int = 0,
    val pageSize: Int = 20,
    val searching: Boolean = false,
    val initialLoaded: Boolean = false,
    val sort: ScoopSearchSort = ScoopSearchSort.BestMatch,
    val officialOnly: Boolean = false,
    val distinctOnly: Boolean = true,
    val showBucketName: Boolean = true,
    val errorMessage: String? = null,
    val installingApps: Set<String> = emptySet(),
    val installedAppNames: Set<String> = emptySet(),
    val localBucketNames: Set<String> = emptySet(),
)

class ScoopSearchViewModel(
    private val searchService: ScoopSearchService,
    private val scoopClient: ScoopClient,
    private val taskQueue: TaskQueue,
    private val appsRepository: AppsRepository,
    private val scoopService: ScoopService,
) : ContainerHost<ScoopSearchState, AppSideEffect>, AutoCloseable {
    private val logger by logger()
    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + supervisorJob)
    override val container: Container<ScoopSearchState, AppSideEffect> =
        coroutineScope.container(ScoopSearchState()) {
        refreshInstalledState()
        subscribeEvents()
    }

    private fun subscribeEvents() = intent {
        coroutineScope.launch {
            scoopService.events.collect { event ->
                when (event) {
                    is ScoopEvent.AppInstalled,
                    is ScoopEvent.AppUninstalled,
                    is ScoopEvent.BucketAdded,
                    is ScoopEvent.BucketRemoved,
                    ScoopEvent.Reloaded,
                    ScoopEvent.AppsReloaded,
                    ScoopEvent.BucketsReloaded -> refreshInstalledState()
                    else -> {}
                }
            }
        }
    }

    private fun refreshInstalledState() = intent {
        val installedNames = appsRepository.getApps(scope = "installed")
            .value.map { it.name.lowercase() }.toSet()
        val bucketNames = scoopClient.bucketNames.map { it.lowercase() }.toSet()
        reduce { state.copy(installedAppNames = installedNames, localBucketNames = bucketNames) }
    }

    fun onSearch(query: String) = intent {
        // Snapshot current options before any state change
        val options = SearchOptions(state.sort, state.officialOnly, state.distinctOnly)
        val pageSize = state.pageSize
        if (query.isBlank()) {
            reduce { state.copy(query = query, results = emptyList(), totalCount = 0, searching = false, initialLoaded = false, errorMessage = null) }
            return@intent
        }
        reduce { state.copy(query = query, searching = true, errorMessage = null) }
        doSearchFirstPage(query, pageSize, options)
    }

    fun setSort(sort: ScoopSearchSort) = intent {
        updateOptionsAndSearch(state.copy(sort = sort))
    }

    fun setOfficialOnly(officialOnly: Boolean) = intent {
        updateOptionsAndSearch(state.copy(officialOnly = officialOnly))
    }

    fun setDistinctOnly(distinctOnly: Boolean) = intent {
        updateOptionsAndSearch(state.copy(distinctOnly = distinctOnly))
    }

    fun setShowBucketName(showBucketName: Boolean) = intent {
        reduce { state.copy(showBucketName = showBucketName) }
    }

    fun loadMore() = intent {
        if (state.searching) return@intent
        if (state.results.size >= state.totalCount && state.initialLoaded) return@intent
        if (state.query.isBlank()) return@intent
        val nextPage = state.currentPage + 1
        val options = SearchOptions(state.sort, state.officialOnly, state.distinctOnly)
        reduce { state.copy(searching = true) }
        try {
            val result = searchService.search(state.query, nextPage, state.pageSize, options.sort, options.officialOnly, options.distinctOnly)
            reduce {
                state.copy(
                    results = state.results + result.value,
                    totalCount = result.count,
                    currentPage = nextPage,
                    searching = false,
                )
            }
        } catch (e: Exception) {
            logger.error("Search failed", e)
            reduce { state.copy(searching = false, errorMessage = formatError(e)) }
        }
    }

    fun installSearchApp(app: ScoopSearchApp, bucketName: String) = intent {
        val appKey = app.Name
        reduce { state.copy(installingApps = state.installingApps + appKey) }

        val bucketExists = scoopClient.bucketNames.any { it.equals(bucketName, ignoreCase = true) }
        if (!bucketExists) {
            taskQueue.addTask(Task.AddBucket(bucketName) { blockingIntent {
                val resultCode = scoopService.addBucket(bucketName, app.Metadata.Repository)
                if (resultCode != 0) {
                    postSideEffect(AppSideEffect.Toast(tr("Failed to add {{name}}.", "name" to bucketName), ToastType.ERROR))
                    reduce { state.copy(installingApps = state.installingApps - appKey) }
                }
            }})
        }

        val installApp = App(
            name = app.Name,
            latestVersion = app.Version,
            status = AppStatus.UNINSTALL,
            description = app.Description,
            homepage = app.Homepage,
            license = app.License,
            bucket = Bucket(name = bucketName, url = app.Metadata.Repository),
        )
        taskQueue.addTask(Task.Install(installApp) { blockingIntent {
            val resultCode = scoopService.install(installApp, global = false)
            reduce { state.copy(installingApps = state.installingApps - appKey) }
            postSideEffect(taskToast("Install", installApp.name, resultCode))
        }})
    }

    /**
     * Apply option change, then re-search first page if there's an active query.
     */
    private suspend fun org.orbitmvi.orbit.syntax.Syntax<ScoopSearchState, AppSideEffect>.updateOptionsAndSearch(
        partialState: ScoopSearchState,
    ) {
        val query = state.query
        val pageSize = state.pageSize
        val options = SearchOptions(partialState.sort, partialState.officialOnly, state.distinctOnly)
        reduce { partialState }
        if (query.isBlank()) return
        reduce { state.copy(searching = true, errorMessage = null) }
        doSearchFirstPage(query, pageSize, options)
    }

    private suspend fun org.orbitmvi.orbit.syntax.Syntax<ScoopSearchState, AppSideEffect>.doSearchFirstPage(
        query: String,
        pageSize: Int,
        options: SearchOptions,
    ) {
        try {
            val result = searchService.search(query, 0, pageSize, options.sort, options.officialOnly, options.distinctOnly)
            reduce {
                state.copy(
                    results = result.value,
                    totalCount = result.count,
                    currentPage = 0,
                    searching = false,
                    initialLoaded = true,
                )
            }
        } catch (e: Exception) {
            logger.error("Search failed", e)
            reduce {
                state.copy(
                    searching = false,
                    results = emptyList(),
                    totalCount = 0,
                    currentPage = 0,
                    initialLoaded = true,
                    errorMessage = formatError(e),
                )
            }
        }
    }

    override fun close() {
        supervisorJob.cancel()
        searchService.close()
    }

    private fun formatError(e: Exception): String = when (e) {
        is java.net.ConnectException -> tr("Unable to connect to search server. Please check your network.")
        is java.net.UnknownHostException -> tr("Unable to reach search server. Please check your network.")
        is java.net.SocketTimeoutException -> tr("Search server timed out. Please try again.")
        is javax.net.ssl.SSLException -> tr("Connection to search server failed. Please try again.")
        else -> e.message ?: tr("Search failed. Please try again.")
    }

    private data class SearchOptions(
        val sort: ScoopSearchSort,
        val officialOnly: Boolean,
        val distinctOnly: Boolean,
    )
}
