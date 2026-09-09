package scooper.service

import scooper.data.App
import java.io.File

/**
 * Interface for Scoop CLI operations, decoupled from implementation to support test mocking.
 */
interface ScoopCli {
    suspend fun refresh(): CommandResult
    suspend fun install(app: App, global: Boolean): CommandResult

    /**
     * Uninstall [app]. When [purge] is true, pass `-p` so scoop also removes the
     * app's persist directory. Only test-install cleanup should purge; real app
     * uninstalls must keep the default (persist data preserved).
     */
    suspend fun uninstall(app: App, global: Boolean, purge: Boolean = false): CommandResult

    /**
     * Update [app]. When [force] is true, pass `--force` so scoop re-applies the
     * bucket manifest even if the installed version already matches; without it
     * a same-version update is a no-op exit 0 and a fixed manifest never lands.
     */
    suspend fun update(app: App, global: Boolean, force: Boolean = false): CommandResult
    suspend fun download(app: App): CommandResult
    suspend fun addBucket(bucket: String, url: String?): CommandResult
    suspend fun removeBucket(bucket: String): CommandResult
    suspend fun cleanup(vararg apps: String, global: Boolean): CommandResult
    suspend fun removeCache(vararg apps: String): CommandResult
    suspend fun installVersion(app: App, manifestFile: File, global: Boolean): CommandResult
    /**
     * Test-install a local manifest under a dedicated temporary app name.
     * Does NOT uninstall any real/official app - only the given [testAppName].
     */
    suspend fun testInstallManifest(testAppName: String, manifestFile: File): TestInstallResult
    fun stop()
}
