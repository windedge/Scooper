package scooper.repository

import scooper.service.ScoopService
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
    private val scoopService: ScoopService,
) {
    fun computeCacheSize(): Long = scoopService.computeCacheSize()

    val cacheDir: File
        get() = scoopService.cacheDir

    fun scanOldVersions(): List<OldVersion> {
        return (scoopService.localInstalledAppDirs + scoopService.globalInstalledAppDirs)
            .filter { dir -> dir.exists() && ((dir.listFiles()?.size ?: 0) > 2) }
            .map { dir ->
                val current = dir.resolve("current").toPath().toRealPath().name
                val oldDirs = dir.listFiles()?.filter { it.name != "current" && it.name != current } ?: listOf()
                val global = dir.absolutePath.contains(scoopService.globalRootDir.absolutePath)
                val appDir = if (global) {
                    scoopService.globalRootDir.resolve("apps").resolve(dir.name)
                } else {
                    scoopService.rootDir.resolve("apps").resolve(dir.name)
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

    suspend fun removeCache(onFinish: suspend (exitValue: Int) -> Unit) {
        scoopService.removeCache(onFinish = onFinish)
    }

    suspend fun cleanup(vararg apps: String, global: Boolean, onFinish: suspend (exitValue: Int) -> Unit) {
        scoopService.cleanup(*apps, global = global, onFinish = onFinish)
    }
}
