package scooper.service

import scooper.data.App
import java.io.File

/**
 * Interface for Scoop CLI operations, decoupled from implementation to support test mocking.
 */
interface ScoopCli {
    suspend fun refresh(onFinish: suspend (exitValue: Int) -> Unit)
    suspend fun install(app: App, global: Boolean, onFinish: suspend (exitValue: Int) -> Unit)
    suspend fun uninstall(app: App, global: Boolean, onFinish: suspend (exitValue: Int) -> Unit)
    suspend fun update(app: App, global: Boolean, onFinish: suspend (exitValue: Int) -> Unit)
    suspend fun download(app: App, onFinish: suspend (exitValue: Int) -> Unit)
    suspend fun addBucket(bucket: String, url: String?, onFinish: suspend (exitValue: Int) -> Unit)
    suspend fun removeBucket(bucket: String, onFinish: suspend (exitValue: Int) -> Unit)
    suspend fun cleanup(vararg apps: String, global: Boolean, onFinish: suspend (exitValue: Int) -> Unit)
    suspend fun removeCache(vararg apps: String, onFinish: suspend (exitValue: Int) -> Unit)
    suspend fun installVersion(app: App, manifestFile: File, global: Boolean, onFinish: suspend (exitValue: Int) -> Unit)
    fun stop()
}
