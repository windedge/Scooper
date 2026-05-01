package scooper.service

import scooper.data.App
import java.io.File

/**
 * Interface for Scoop CLI operations, decoupled from implementation to support test mocking.
 */
interface ScoopCli {
    suspend fun refresh(): CommandResult
    suspend fun install(app: App, global: Boolean): CommandResult
    suspend fun uninstall(app: App, global: Boolean): CommandResult
    suspend fun update(app: App, global: Boolean): CommandResult
    suspend fun download(app: App): CommandResult
    suspend fun addBucket(bucket: String, url: String?): CommandResult
    suspend fun removeBucket(bucket: String): CommandResult
    suspend fun cleanup(vararg apps: String, global: Boolean): CommandResult
    suspend fun removeCache(vararg apps: String): CommandResult
    suspend fun installVersion(app: App, manifestFile: File, global: Boolean): CommandResult
    fun stop()
}
