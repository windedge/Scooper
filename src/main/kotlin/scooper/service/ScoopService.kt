package scooper.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import scooper.data.App
import scooper.data.AppStatus
import scooper.data.Bucket
import scooper.repository.AppsRepository

/**
 * Application layer for Scoop operations.
 * Executes CLI calls, updates database, and emits ScoopEvent changes
 * so that all ViewModels can stay in sync.
 *
 * ViewModels own the TaskQueue scheduling and Toast messages.
 * This service is called from within task lambdas.
 */
class ScoopService(
    private val scoopClient: ScoopClient,
    private val appsRepository: AppsRepository,
) {
    private val _events = MutableSharedFlow<ScoopEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ScoopEvent> = _events.asSharedFlow()

    private suspend fun emit(event: ScoopEvent) {
        _events.emit(event)
    }

    /** Install an app and update DB on success. Returns exit code. */
    suspend fun install(app: App, global: Boolean = false): Int {
        val result = scoopClient.install(app, global)
        if (result.exitCode == 0) {
            appsRepository.upsertApp(app.copy(status = AppStatus.INSTALLED))
            emit(ScoopEvent.AppInstalled(app.name))
        }
        return result.exitCode
    }

    /** Uninstall an app and update DB.
     *  If the app's bucket still exists locally, marks it as UNINSTALL.
     *  If the bucket was already removed, deletes the record entirely.
     *  Returns the actual CLI exit code for UI feedback. */
    suspend fun uninstall(app: App): Int {
        val result = scoopClient.uninstall(app, app.global)
        if (result.exitCode == 0) {
            val bucketExists = app.bucket?.name?.let { it in scoopClient.bucketNames } ?: false
            if (bucketExists) {
                appsRepository.upsertApp(app.copy(status = AppStatus.UNINSTALL, global = false))
            } else {
                appsRepository.deleteApp(app.name, app.bucket?.name)
            }
            emit(ScoopEvent.AppUninstalled(app.name))
        }
        return result.exitCode
    }

    /** Update an app and update DB on success. Returns exit code. */
    suspend fun updateApp(app: App, global: Boolean = false): Int {
        val result = scoopClient.update(app, global)
        if (result.exitCode == 0) {
            appsRepository.upsertApp(app.copy(version = app.latestVersion))
            emit(ScoopEvent.AppUpdated(app.name))
        }
        return result.exitCode
    }

    /** Download an app. Returns exit code. */
    suspend fun download(app: App): Int {
        val result = scoopClient.download(app)
        if (result.exitCode == 0) {
            emit(ScoopEvent.AppDownloaded(app.name))
        }
        return result.exitCode
    }

    /** Add a bucket and update DB on success. Returns exit code. */
    suspend fun addBucket(bucket: String, url: String? = null): Int {
        val result = scoopClient.addBucket(bucket, url)
        if (result.exitCode == 0) {
            appsRepository.loadBuckets()
            emit(ScoopEvent.BucketAdded(bucket))
        }
        return result.exitCode
    }

    /** Remove a bucket and update DB on success. Returns exit code. */
    suspend fun removeBucket(bucket: String): Int {
        val result = scoopClient.removeBucket(bucket)
        if (result.exitCode == 0) {
            appsRepository.loadBuckets()
            emit(ScoopEvent.BucketRemoved(bucket))
        }
        return result.exitCode
    }

    /** Full reload from filesystem. */
    suspend fun reloadAll() {
        appsRepository.loadAll()
        emit(ScoopEvent.Reloaded)
    }

    /** Incremental apps reload. */
    suspend fun reloadApps() {
        appsRepository.loadApps()
        emit(ScoopEvent.AppsReloaded)
    }

    /** Full refresh (scoop update + reloadApps). */
    suspend fun refresh() {
        scoopClient.refresh()
        appsRepository.loadApps()
        emit(ScoopEvent.Reloaded)
    }
}
