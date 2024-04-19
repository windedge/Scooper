package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import scooper.data.Bucket
import scooper.repository.AppsRepository
import scooper.taskqueue.Task
import scooper.taskqueue.TaskQueue
import scooper.util.PAGE_SIZE
import scooper.util.Scoop
import scooper.util.logger

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
    val output: String = "",
)


@OptIn(OrbitExperimental::class)
@Suppress("MemberVisibilityCanBePrivate")
class AppsViewModel(private val taskQueue: TaskQueue) : ContainerHost<AppsState, SideEffect> {
    private val logger by logger()

    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    override val container: Container<AppsState, SideEffect> = coroutineScope.container(AppsState()) {
        applyFilters()
        getBuckets()
        subscribeLogging()
    }

    fun subscribeLogging() = intent {
        coroutineScope.launch(Dispatchers.IO) {
            Scoop.logStream.collect {
                postSideEffect(SideEffect.Log(it))
                val output = state.output + it + "\n"
                reduce { state.copy(output = output) }
            }
        }
    }

    fun applyFilters(query: String? = null, bucket: String? = null, scope: String? = null) = intent {
        val currentQuery = query ?: state.filter.query
        val currentBucket = bucket ?: state.filter.selectedBucket
        val currentScope = scope ?: state.filter.scope
        val result = AppsRepository.getApps(currentQuery, currentBucket, currentScope, limit = state.filter.pageSize)

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
            AppsRepository.getApps(
                filter.query,
                filter.selectedBucket,
                filter.scope,
                offset = offset,
                limit = pageSize
            )

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
        reduce { state.copy(filter = AppsFilter()) }
    }

    fun refresh() = blockingIntent {
        Scoop.refresh { reloadApps() }
    }

    fun installApp(app: App, global: Boolean = false) = blockingIntent {
        Scoop.install(app, global) { exitValue ->
            if (exitValue != 0) {
                postSideEffect(SideEffect.Toast("Install app, ${app.uniqueName} error!"))
                return@install
            }

            postSideEffect(SideEffect.Toast("Install app, ${app.uniqueName} successfully!"))
            AppsRepository.updateApp(app.copy(status = "installed"))
            applyFilters()
        }
    }

    fun uninstallApp(app: App) = blockingIntent {
        Scoop.uninstall(app, app.global) { exitValue ->
            if (exitValue != 0) {
                postSideEffect(SideEffect.Toast("Uninstall app, ${app.uniqueName} error!"))
                return@uninstall
            }

            postSideEffect(SideEffect.Toast("Uninstall app, ${app.uniqueName} successfully!"))
            AppsRepository.updateApp(app.copy(status = "uninstall", global = false))
            applyFilters()
        }
    }

    fun updateApp(app: App) = blockingIntent {
        Scoop.update(app, app.global) { exitValue ->
            if (exitValue != 0) {
                postSideEffect(SideEffect.Toast("Update app, ${app.uniqueName} error!"))
                return@update
            }

            postSideEffect(SideEffect.Toast("Update app, ${app.uniqueName} successfully!"))
            AppsRepository.updateApp(app.copy(version = app.latestVersion))
            applyFilters()
        }
    }

    fun downloadApp(app: App) = blockingIntent {
        Scoop.download(app) { exitValue ->
            if (exitValue != 0) {
                postSideEffect(SideEffect.Toast("Download app: ${app.uniqueName} error!"))
                return@download
            }

            postSideEffect(SideEffect.Toast("Download app: ${app.uniqueName} successfully!"))
            applyFilters()
        }
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

    fun cancelTask(app: App) = intent {
        taskQueue.getTask(app.uniqueName)?.run {
            taskQueue.cancelTask(app.uniqueName)
        }
    }

    fun cancel(app: App? = null) = intent {
        logger.info("cancelling")

        // cancelling pending operation
        if (app != null && taskQueue.containTask(app.uniqueName)) {
            logger.info("cancel task = ${app.name}")
            cancelTask(app)
        } else {
            logger.info("Stop scoop")
            Scoop.stop()
            if (app != null) {
                logger.info("cancelling app = ${app.uniqueName}")
                AppsRepository.updateApp(app.copy(status = "failed"))
                applyFilters()
            }
        }
    }

    fun queuedUpdateApps() = intent {
        taskQueue.addTask(Task.Refresh { refresh() })
    }

    fun queuedInstall(app: App, global: Boolean = false) = intent {
        taskQueue.addTask(Task.Install(app) { installApp(app, global) })
    }

    fun queuedUninstall(app: App) = intent {
        taskQueue.addTask(Task.Uninstall(app) { uninstallApp(app) })
    }

    fun queuedUpdate(app: App) = intent {
        taskQueue.addTask(Task.Update(app) { updateApp(app) })
    }

    fun queuedDownload(app: App) = intent {
        taskQueue.addTask(Task.Download(app) { downloadApp(app) })
    }

    fun queuedAddBucket(bucket: String, url: String? = null) = intent {
        taskQueue.addTask(Task.AddBucket(bucket) { addScoopBucket(bucket, url) })
    }

    fun queuedRemoveBucket(bucket: String) = intent {
        taskQueue.addTask(Task.RemoveBucket(bucket) { removeScoopBucket(bucket) })
    }

}
