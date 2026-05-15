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
import scooper.data.AppVersion
import scooper.data.AppVersionSource
import scooper.data.Bucket
import scooper.data.PaginationMode
import scooper.data.ViewMode
import scooper.repository.AppsRepository
import scooper.repository.ConfigRepository
import scooper.repository.ScoopDbRepository
import scooper.service.GitHistoryService
import scooper.service.ScoopCli
import scooper.service.ScoopLogStream
import scooper.service.ScoopService
import scooper.service.ScoopEvent
import scooper.service.ScoopClient
import scooper.taskqueue.Task
import scooper.taskqueue.TaskQueue
import scooper.util.PAGE_SIZE
import scooper.util.logger
import scooper.util.VersionComparator
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class AppsFilter(
    val query: String = "",
    val selectedBucket: String = "",
    val page: Int = 1,
    val pageSize: Int = PAGE_SIZE,
    val scope: String = "all",
    val sort: String = "updated",
    val sortOrder: String = "desc",
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
    val versionPickerApp: App? = null,
    val versionPickerVersions: List<AppVersion> = emptyList(),
    val versionPickerLoading: Boolean = false,
    val versionPickerError: String? = null,
)


@OptIn(OrbitExperimental::class)
@Suppress("MemberVisibilityCanBePrivate")
class AppsViewModel(
    private val taskQueue: TaskQueue,
    private val appsRepository: AppsRepository,
    private val configRepository: ConfigRepository,
    private val scoopLogStream: ScoopLogStream,
    private val scoopCli: ScoopCli,
    private val scoopClient: ScoopClient,
    private val scoopService: ScoopService,
    private val gitHistoryService: GitHistoryService,
) : ContainerHost<AppsState, AppsSideEffect>, AutoCloseable {
    private val logger by logger()

    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + supervisorJob)
    private val gitHistoryIndexing = AtomicBoolean(false)
    private val initialState = run {
        val config = configRepository.getConfig()
        AppsState(viewMode = config.viewMode, filter = AppsFilter(paginationMode = config.paginationMode, pageSize = config.pageSize))
    }
    override val container: Container<AppsState, AppsSideEffect> = coroutineScope.container(initialState) {
        applyFilters()
        getBuckets()
        subscribeLogging()
        subscribeEvents()
        scheduleIndexGitHistoryIfNeeded()
    }

    fun subscribeEvents() = intent {
        coroutineScope.launch {
            scoopService.events.collect { event ->
                when (event) {
                    is ScoopEvent.AppInstalled,
                    is ScoopEvent.AppUninstalled,
                    is ScoopEvent.AppUpdated,
                    is ScoopEvent.AppDownloaded,
                    is ScoopEvent.AppsReloaded,
                    ScoopEvent.Reloaded -> {
                        applyFilters()
                    }
                    is ScoopEvent.BucketAdded,
                    is ScoopEvent.BucketRemoved,
                    ScoopEvent.BucketsReloaded -> {
                        getBuckets()
                        applyFilters()
                    }
                    else -> {}
                }
            }
        }
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
        sortOrder: String? = null,
        paginationMode: PaginationMode? = null,
        pageSize: Int? = null,
    ) = intent {
        val currentQuery = query ?: state.filter.query
        val currentBucket = bucket ?: state.filter.selectedBucket
        val currentScope = scope ?: state.filter.scope
        val currentSort = sort ?: state.filter.sort
        val currentSortOrder = sortOrder ?: state.filter.sortOrder
        val currentPaginationMode = paginationMode ?: state.filter.paginationMode
        val currentPageSize = pageSize ?: state.filter.pageSize

        val paginationModeChanged = paginationMode != null && paginationMode != state.filter.paginationMode
        val pageSizeChanged = pageSize != null && pageSize != state.filter.pageSize

        if (currentPaginationMode == PaginationMode.Pagination) {
            val result = appsRepository.getApps(
                currentQuery, currentBucket, currentScope,
                offset = 0,
                limit = currentPageSize,
                sort = currentSort,
                sortOrder = currentSortOrder
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
                        sortOrder = currentSortOrder,
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
                sort = currentSort,
                sortOrder = currentSortOrder
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
                        sortOrder = currentSortOrder,
                        page = 1,
                        paginationMode = currentPaginationMode,
                        pageSize = currentPageSize,
                    )
                )
            }
        }

        // Persist pagination settings when changed
        if (paginationModeChanged || pageSizeChanged) {
            configRepository.setConfig(configRepository.getConfig().copy(
                paginationMode = currentPaginationMode,
                pageSize = currentPageSize,
            ))
        }
    }

    fun loadMore() = intent {
        if (state.filter.paginationMode != PaginationMode.Waterfall) return@intent
        val filter = state.filter
        // No more data to load
        val currentApps = state.apps ?: emptyList()
        if (currentApps.size >= state.totalCount) return@intent
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
                sort = filter.sort,
                sortOrder = filter.sortOrder
            )

        if (result.value.isEmpty()) return@intent

        reduce {
            state.copy(
                apps = currentApps.plus(result.value),
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
            sort = filter.sort,
            sortOrder = filter.sortOrder
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
        configRepository.setConfig(configRepository.getConfig().copy(viewMode = viewMode))
    }

    fun getBuckets() = intent {
        appsRepository.loadBuckets()
        val buckets = appsRepository.getBuckets()
        reduce { state.copy(buckets = buckets) }
    }

    fun reloadApps() = blockingIntent {
        appsRepository.loadApps()
        applyFilters()
        scheduleIndexGitHistoryIfNeeded()
    }

    fun resetFilter() = intent {
        reduce { state.copy(filter = AppsFilter()) }
    }

    fun reloadAll() = blockingIntent {
        appsRepository.loadAll()
        applyFilters()
        scheduleIndexGitHistoryIfNeeded()
    }

    fun refresh() = blockingIntent {
        scoopCli.refresh()
        reloadApps()
    }

    // ==================== Task Queue ====================

    fun scheduleReloadApps() = intent {
        taskQueue.addTask(Task.Refresh {
            scoopService.reloadApps()
        })
    }

    fun scheduleReloadAll() = intent {
        taskQueue.addTask(Task.Refresh {
            scoopService.reloadAll()
        })
    }

    fun scheduleUpdateApps() = intent {
        taskQueue.addTask(Task.Refresh {
            scoopService.refresh()
        })
    }

    fun openApp(app: App, shortcutIndex: Int = 0) = intent {
        scoopClient.openShortcut(app, shortcutIndex)
    }

    fun scheduleInstall(app: App, global: Boolean = false) = intent {
        taskQueue.addTask(Task.Install(app) { blockingIntent {
            val resultCode = scoopService.install(app, global)
            if (resultCode != 0) {
                postSideEffect(AppsSideEffect.Toast("Install app: ${app.uniqueName} error!"))
            } else {
                postSideEffect(AppsSideEffect.Toast("Install app: ${app.uniqueName} successfully!"))
            }
        }})
    }

    fun scheduleUninstall(app: App) = intent {
        taskQueue.addTask(Task.Uninstall(app) { blockingIntent {
            val resultCode = scoopService.uninstall(app)
            if (resultCode != 0) {
                postSideEffect(AppsSideEffect.Toast("Uninstall app: ${app.uniqueName} error!"))
            } else {
                postSideEffect(AppsSideEffect.Toast("Uninstall app: ${app.uniqueName} successfully!"))
            }
        }})
    }

    fun scheduleUpdate(app: App) = intent {
        taskQueue.addTask(Task.Update(app) { blockingIntent {
            val resultCode = scoopService.updateApp(app, app.global)
            if (resultCode != 0) {
                postSideEffect(AppsSideEffect.Toast("Update app: ${app.uniqueName} error!"))
            } else {
                postSideEffect(AppsSideEffect.Toast("Update app: ${app.uniqueName} successfully!"))
            }
        }})
    }

    fun scheduleDownload(app: App) = intent {
        taskQueue.addTask(Task.Download(app) { blockingIntent {
            val resultCode = scoopService.download(app)
            if (resultCode != 0) {
                postSideEffect(AppsSideEffect.Toast("Download app: ${app.uniqueName} error!"))
            } else {
                postSideEffect(AppsSideEffect.Toast("Download app: ${app.uniqueName} successfully!"))
            }
        }})
    }

    fun scheduleAddBucket(bucket: String, url: String? = null) = intent {
        taskQueue.addTask(Task.AddBucket(bucket) { blockingIntent {
            val resultCode = scoopService.addBucket(bucket, url)
            if (resultCode != 0) {
                postSideEffect(AppsSideEffect.Toast("Add bucket: $bucket error!"))
            } else {
                postSideEffect(AppsSideEffect.Toast("Add bucket: $bucket successfully!"))
                getBuckets()
                reloadApps()
            }
        }})
    }

    fun scheduleRemoveBucket(bucket: String) = intent {
        taskQueue.addTask(Task.RemoveBucket(bucket) { blockingIntent {
            val resultCode = scoopService.removeBucket(bucket)
            if (resultCode != 0) {
                postSideEffect(AppsSideEffect.Toast("Remove bucket: $bucket error!"))
            } else {
                postSideEffect(AppsSideEffect.Toast("Remove bucket: $bucket successfully!"))
            }
        }})
    }

    // ==================== Cancel ==================== 

    fun cancelTask(app: App) = intent {
        taskQueue.getTask(app.uniqueName)?.run {
            taskQueue.cancelTask(app.uniqueName)
        }
    }

    fun clearOutput() = intent {
        reduce { state.copy(output = "") }
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

    // ==================== Version History (P2) ====================

    private val scoopDbRepository by lazy {
        ScoopDbRepository(scoopClient.rootDir.resolve("scoop.db"))
    }

    fun showVersionPicker(app: App) = intent {
        reduce { state.copy(versionPickerApp = app, versionPickerLoading = true, versionPickerError = null, versionPickerVersions = emptyList()) }
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val versions = loadVersions(app)
                intent {
                    reduce { state.copy(versionPickerVersions = versions, versionPickerLoading = false) }
                }
            } catch (e: Exception) {
                intent {
                    reduce { state.copy(versionPickerLoading = false, versionPickerError = e.message) }
                }
            }
        }
    }

    fun dismissVersionPicker() = intent {
        reduce { state.copy(versionPickerApp = null, versionPickerVersions = emptyList(), versionPickerLoading = false, versionPickerError = null) }
    }

    fun loadVersions(app: App): List<AppVersion> {
        val versions = mutableListOf<AppVersion>()

        // Priority 1: Scoop DB. It has the least IO but no date field.
        if (scoopDbRepository.isAvailable()) {
            versions.addAll(scoopDbRepository.getVersions(app))
        }

        // Priority 2: Git fallback only when Scoop DB has no data.
        if (versions.isEmpty() && app.bucket != null) {
            val bucketDir = scoopClient.bucketsBaseDir.resolve(app.bucket!!.name)
            val manifestPath = "bucket/${app.name}.json"
            val gitVersions = gitHistoryService.loadManifestVersions(bucketDir, manifestPath)
            val seenVersions = mutableSetOf<String>()
            for (gv in gitVersions) {
                val content = gv.commit?.let {
                    gitHistoryService.readManifestAtCommit(bucketDir, it, manifestPath)
                }
                val version = content?.let { parseVersionFromManifest(it) } ?: continue
                if (seenVersions.add(version)) {
                    versions.add(gv.copy(version = version))
                }
            }
        }

        return sortVersions(versions, app.latestVersion)
    }

    /** Sort versions: latestVersion pinned at top, rest by natural descending order. */
    private fun sortVersions(versions: List<AppVersion>, latestVersion: String): List<AppVersion> {
        val pinned = versions.filter { it.version == latestVersion }
        val rest = versions.filter { it.version != latestVersion }
            .sortedWith { a, b -> VersionComparator.compare(b.version, a.version) }
        return pinned + rest
    }

    private fun parseVersionFromManifest(manifestText: String): String? {
        val regex = """"version"\s*:\s*"([^"]+)"""".toRegex()
        return regex.find(manifestText)?.groupValues?.get(1)
    }

    fun scheduleInstallVersion(app: App, version: AppVersion, global: Boolean = false) = intent {
        taskQueue.addTask(Task.InstallVersion(app, version.version) { blockingIntent {
            // Write manifest to temp file
            val manifestText = when (version.source) {
                AppVersionSource.ScoopDb -> scoopDbRepository.getManifest(app, version.version)
                AppVersionSource.Git -> {
                    val bucketDir = app.bucket?.name?.let { scoopClient.bucketsBaseDir.resolve(it) }
                    val commit = version.commit ?: return@blockingIntent
                    bucketDir?.let {
                        gitHistoryService.readManifestAtCommit(it, commit, "bucket/${app.name}.json")
                    }
                }
            }

            if (manifestText == null) {
                postSideEffect(AppsSideEffect.Toast("Failed to get manifest for ${app.name}@${version.version}"))
                return@blockingIntent
            }

            val tempDir = File(System.getenv("TEMP"), "scooper-manifests/${app.name}/${version.version}")
            tempDir.mkdirs()
            val tempFile = File(tempDir, "${app.name}.json")
            tempFile.writeText(manifestText)

            val result = scoopCli.installVersion(app, tempFile, global)
            tempFile.delete()
            tempDir.deleteRecursively()
            if (result.exitCode != 0) {
                val msg = result.errorMessage ?: "Install version: ${app.name}@${version.version} error!"
                postSideEffect(AppsSideEffect.Toast(msg))
            } else {
                postSideEffect(AppsSideEffect.Toast("Installed version: ${app.name}@${version.version} successfully!"))
                appsRepository.updateApp(app.copy(status = AppStatus.INSTALLED, version = version.version))
                applyFilters()
            }
        }})
    }

    // ==================== Git History Indexing ====================

    fun scheduleIndexGitHistoryIfNeeded() = intent {
        if (!gitHistoryIndexing.compareAndSet(false, true)) {
            logger.info("Git history indexing is already running, skip scheduling")
            return@intent
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val bucketStates = appsRepository.getBucketIndexStates()
                for (bucketState in bucketStates) {
                    val bucketDir = scoopClient.bucketsBaseDir.resolve(bucketState.name)
                    if (!gitHistoryService.isGitBucket(bucketDir)) continue

                    val headCommit = gitHistoryService.getHeadCommit(bucketDir) ?: continue
                    if (GitHistoryService.isCurrentIndexState(bucketState.lastIndexedCommit, headCommit)) continue

                    // Collect known manifest names for this bucket
                    val knownNames = scoopClient.bucketDirManifestNames(bucketDir)
                    val lastIndexedCommit = if (bucketState.lastIndexedCommit?.startsWith("v") == true) {
                        GitHistoryService.commitFromIndexState(bucketState.lastIndexedCommit)
                    } else {
                        // Legacy index state did not include parser/index version; force full rebuild once.
                        null
                    }

                    val result = gitHistoryService.indexBucketManifestTimes(
                        bucketDir = bucketDir,
                        knownManifestNames = knownNames,
                        lastIndexedCommit = lastIndexedCommit,
                    ) ?: continue

                    appsRepository.updateManifestTimes(
                        bucketName = bucketState.name,
                        manifestTimes = result.manifestTimes,
                        headCommit = GitHistoryService.indexStateFor(result.headCommit),
                    )

                    logger.info("Indexed ${bucketState.name}: ${result.manifestTimes.size} manifests, full=${result.fullIndex}")
                }

                // Refresh UI after indexing completes
                applyFilters()
            } catch (e: Exception) {
                logger.error("Git history indexing failed: ${e.message}", e)
            } finally {
                gitHistoryIndexing.set(false)
            }
        }
    }

    override fun close() {
        supervisorJob.cancel()
    }
}
