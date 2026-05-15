package scooper.repository

import scooper.service.CommandResult
import scooper.service.ScoopClient
import scooper.util.dirSize
import java.io.File
import kotlin.io.path.name

data class OldVersion(
    val app: String,
    val global: Boolean = false,
    val size: Long = 0L,
    val paths: List<File> = listOf(),
    val appDir: File,
)

class CleanupRepository(
    private val scoopClient: ScoopClient,
) {
    fun computeCacheSize(): Long = scoopClient.computeCacheSize()

    val cacheDir: File
        get() = scoopClient.cacheDir

    fun scanOldVersions(): List<OldVersion> {
        return (scoopClient.localInstalledAppDirs + scoopClient.globalInstalledAppDirs)
            .filter { dir -> dir.exists() && ((dir.listFiles()?.size ?: 0) > 2) }
            .map { dir ->
                val current = dir.resolve("current").toPath().toRealPath().name
                val oldDirs = dir.listFiles()?.filter { it.name != "current" && it.name != current } ?: listOf()
                val global = dir.absolutePath.contains(scoopClient.globalRootDir.absolutePath)
                val appDir = if (global) {
                    scoopClient.globalRootDir.resolve("apps").resolve(dir.name)
                } else {
                    scoopClient.rootDir.resolve("apps").resolve(dir.name)
                }
                OldVersion(
                    app = dir.name,
                    size = oldDirs.sumOf { it.dirSize() },
                    global = global,
                    paths = oldDirs,
                    appDir = appDir,
                )
            }
    }

    suspend fun removeCache(vararg apps: String): CommandResult = scoopClient.removeCache(*apps)

    suspend fun cleanup(vararg apps: String, global: Boolean): CommandResult = scoopClient.cleanup(*apps, global = global)
}
