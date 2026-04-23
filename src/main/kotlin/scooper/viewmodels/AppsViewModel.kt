package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.annotation.OrbitExperimental
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.syntax.simple.blockingIntent
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import scooper.data.App
import scooper.data.AppStatus
import scooper.data.Bucket
import scooper.data.PaginationMode
import scooper.data.ViewMode
import scooper.repository.AppsRepository
import scooper.service.ScoopCli
import scooper.service.ScoopLogStream
import scooper.service.ScoopService
import scooper.taskqueue.Task
import scooper.taskqueue.TaskQueue
import scooper.util.PAGE_SIZE
import scooper.util.logger

data class AppsFilter(
    val query: String = "",
    val selectedBucket: String = "",
    val page: Int = 1,
    val pageSize: Int = PAGE_SIZE,
    val scope: String = "all",
    val sort: String = "updated",
    val paginationMode: PaginationMode = PaginationMode.Waterfall,
)

data class AppsState(
    val apps: List<App>? = null,
    val totalCount: Long = 0L,
    val buckets: List<Bucket> = emptyList(),
    val filter: AppsFilter = AppsFilter(),
    val output: String = "",
    val updateCount: Long = 0L,
    val viewMode: ViewMode = ViewMode.List,
)


@OptIn(OrbitExperimental::class)
@Suppress("MemberVisibilityCanBePrivate")
class AppsViewModel(
    private val taskQueue: TaskQueue,
    private val appsRepository: AppsRepository,
    private val scoopLogStream: ScoopLogStream,
    private val scoopCli: ScoopCli,
    private val scoopService: ScoopService,
) : ContainerHost<AppsState, AppsSideEffect>, AutoCloseable {
    private val logger by logger()

    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + supervisorJob)
    override val container: Container<AppsState, AppsSideEffect> = coroutineScope.container(AppsState()) {
        applyFilters()
        getBuckets()
        subscribeLogging()
    }

    fun subscribeLogging() = intent {
        coroutineScope.launch(Dispatchers.IO) {
            scoopLogStream.logStream.collect {
                postSideEffect(AppsSideEffect.Log(it))
                val lines = (state.output + it + "\n").lines()
                val output = lines.takeLast(500).joinToString("\n")
                reduce { state.copy(output = output) }
            }
        }
    }

    fun applyFilters(
        query: String? = null,
        bucket: String? = null,
        scope: String? = null,
        sort: String? = null,
        paginationMode: PaginationMode? = null,
        pageSize: Int? = null,
    ) = intent {
        val currentQuery = query ?: state.filter.query
        val currentBucket = bucket ?: state.filter.selectedBucket
        val currentScope = scope ?: state.filter.scope
        val currentSort = sort ?: state.filter.sort
        val currentPaginationMode = paginationMode ?: state.filter.paginationMode
        val currentPageSize = pageSize ?: state.filter.pageSize

        if (currentPaginationMode == PaginationMode.Pagination) {
            val result = appsRepository.getApps(
                currentQuery, currentBucket, currentScope,
                offset = 0,
                limit = currentPageSize,
                sort = currentSort
            )
            val updateCount = appsRepository.getUpdateCount()
            reduce {
                state.copy(
                    apps = result.value,
                    totalCount = result.totalCount,
                    updateCount = updateCount,
                    filter = state.filter.copy(
                        query = currentQuery,
                        selectedBucket = currentBucket,
                        scope = currentScope,
                        sort = currentSort,
                        page = 1,
                        paginationMode = currentPaginationMode,
                        pageSize = currentPageSize,
                    )
                )
            }
        } else {
            val result = appsRepository.getApps(
                currentQuery, currentBucket, currentScope,
                limit = currentPageSize,
                sort = currentSort
            )
            val updateCount = appsRepository.getUpdateCount()
            reduce {
                state.copy(
                    apps = result.value,
                    totalCount = result.totalCount,
                    updateCount = updateCount,
                    filter = state.filter.copy(
                        query = currentQuery,
                        selectedBucket = currentBucket,
                        scope = currentScope,
                        sort = currentSort,
                        page = 1,
                        paginationMode = currentPaginationMode,
                        pageSize = currentPageSize,
                    )
                )
            }
        }
    }

    fun loadMore() = intent {
        if (state.filter.paginationMode != PaginationMode.Waterfall) return@intent
        val filter = state.filter
        val nextPage = filter.page + 1
        val offset = (nextPage - 1) * filter.pageSize.toLong()
        val pageSize = filter.pageSize
        val result =
            appsRepository.getApps(
                filter.query,
                filter.selectedBucket,
                filter.scope,
                offset = offset,
                limit = pageSize,
                sort = filter.sort
            )

        reduce {
            state.copy(
                apps = state.apps?.plus(result.value) ?: result.value,
                filter = state.filter.copy(page = nextPage)
            )
        }
    }

    fun goToPage(page: Int) = intent {
        if (state.filter.paginationMode != PaginationMode.Pagination) return@intent
        val filter = state.filter
        val offset = (page - 1) * filter.pageSize.toLong()
        val result = appsRepository.getApps(
            filter.query,
            filter.selectedBucket,
            filter.scope,
            offset = offset,
            limit = filter.pageSize,
            sort = filter.sort
        )
        reduce {
            state.copy(
                apps = result.value,
                totalCount = result.totalCount,
                filter = state.filter.copy(page = page)
            )
        }
    }

    fun setViewMode(viewMode: ViewMode) = intent {
        reduce { state.copy(viewMode = viewMode) }
    }

    fun getBuckets() = intent {
        appsRepository.loadBuckets()
        val buckets = appsRepository.getBuckets()
        reduce { state.copy(buckets = buckets) }
    }

    fun reloadApps() = blockingIntent {
        appsRepository.loadApps()
        applyFilters()
    }

    fun resetFilter() = intent {
        reduce { state.copy(filter = AppsFilter()) }
    }

    fun refresh() = blockingIntent {
        scoopCli.refresh { reloadApps() }
    }

    // ==================== Task Queue ====================

    fun scheduleReloadApps() = intent {
        taskQueue.addTask(Task.Refresh { reloadApps() })
    }

    fun scheduleUpdateApps() = intent {
        taskQueue.addTask(Task.Refresh { refresh() })
    }

    fun openApp(app: App, shortcutIndex: Int = 0) = intent {
        scoopService.openShortcut(app, shortcutIndex)
    }

    fun scheduleInstall(app: App, global: Boolean = false) = intent {
        taskQueue.addTask(Task.Install(app) { blockingIntent {
            scoopCli.install(app, global) { exitValue ->
                if (exitValue != 0) {
                    postSideEffect(AppsSideEffect.Toast("Install app, ${app.uniqueName} error!"))
                    return@install
                }
                postSideEffect(AppsSideEffect.Toast("Install app, ${app.uniqueName} successfully!"))
                appsRepository.updateApp(app.copy(status = AppStatus.INSTALLED))
                applyFilters()
            }
        }})
    }

    fun scheduleUninstall(app: App) = intent {
        taskQueue.addTask(Task.Uninstall(app) { blockingIntent {
            scoopCli.uninstall(app, app.global) { exitValue ->
                if (exitValue != 0) {
                    postSideEffect(AppsSideEffect.Toast("Uninstall app, ${app.uniqueName} error!"))
                    return@uninstall
                }
                postSideEffect(AppsSideEffect.Toast("Uninstall app, ${app.uniqueName} successfully!"))
                appsRepository.updateApp(app.copy(status = AppStatus.UNINSTALL, global = false))
                applyFilters()
            }
        }})
    }

    fun scheduleUpdate(app: App) = intent {
        taskQueue.addTask(Task.Update(app) { blockingIntent {
            scoopCli.update(app, app.global) { exitValue ->
                if (exitValue != 0) {
                    postSideEffect(AppsSideEffect.Toast("Update app, ${app.uniqueName} error!"))
                    return@update
                }
                postSideEffect(AppsSideEffect.Toast("Update app, ${app.uniqueName} successfully!"))
                appsRepository.updateApp(app.copy(version = app.latestVersion))
                applyFilters()
            }
        }})
    }

    fun scheduleDownload(app: App) = intent {
        taskQueue.addTask(Task.Download(app) { blockingIntent {
            scoopCli.download(app) { exitValue ->
                if (exitValue != 0) {
                    postSideEffect(AppsSideEffect.Toast("Download app: ${app.uniqueName} error!"))
                    return@download
                }
                postSideEffect(AppsSideEffect.Toast("Download app: ${app.uniqueName} successfully!"))
                applyFilters()
            }
        }})
    }

    fun scheduleAddBucket(bucket: String, url: String? = null) = intent {
        taskQueue.addTask(Task.AddBucket(bucket) { blockingIntent {
            scoopCli.addBucket(bucket, url) { exitValue ->
                if (exitValue != 0) {
                    postSideEffect(AppsSideEffect.Toast("Add bucket: $bucket error!"))
                    return@addBucket
                }
                postSideEffect(AppsSideEffect.Toast("Add bucket: $bucket successfully!"))
                getBuckets()
                reloadApps()
            }
        }})
    }

    fun scheduleRemoveBucket(bucket: String) = intent {
        taskQueue.addTask(Task.RemoveBucket(bucket) { blockingIntent {
            scoopCli.removeBucket(bucket) { exitValue ->
                if (exitValue != 0) {
                    postSideEffect(AppsSideEffect.Toast("Remove bucket: $bucket error!"))
                    return@removeBucket
                }
                postSideEffect(AppsSideEffect.Toast("Remove bucket: $bucket successfully!"))
                getBuckets()
                reloadApps()
            }
        }})
    }

    // ==================== Cancel ====================

    fun cancelTask(app: App) = intent {
        taskQueue.getTask(app.uniqueName)?.run {
            taskQueue.cancelTask(app.uniqueName)
        }
    }

    fun cancel(app: App? = null) = intent {
        logger.info("cancelling")
        if (app != null && taskQueue.containTask(app.uniqueName)) {
            logger.info("cancel task = ${app.name}")
            cancelTask(app)
        } else {
            logger.info("Stop scoop")
            scoopCli.stop()
            if (app != null) {
                logger.info("cancelling app = ${app.uniqueName}")
                appsRepository.updateApp(app.copy(status = AppStatus.FAILED))
                applyFilters()
            }
        }
    }

    override fun close() {
        supervisorJob.cancel()
    }
}
