package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.reduce
import scooper.util.Scoop
import scooper.util.dirSize
import scooper.util.logger
import java.io.File
import kotlin.io.path.name

data class OldVersion(
    val app: String,
    val global: Boolean = false,
    val size: Long = 0L,
    val paths: List<File> = listOf(),
) {
    val appDir: File = if (global) {
        Scoop.globalRootDir.resolve("apps").resolve(app)
    } else {
        Scoop.rootDir.resolve("apps").resolve(app)
    }
}

data class CleanupState(
    val cleaningCache: Boolean = false,
    val scanningCache: Boolean = false,
    val cacheSize: Long = -1,

    val scanningOldVersion: Boolean = false,
    val cleaningOldVersions: Boolean = false,
    val oldVersions: List<OldVersion>? = null,
) {
    val totalOldSize: Long = oldVersions?.sumOf { it.size } ?: -1L
}


class CleanupViewModel : ContainerHost<CleanupState, SideEffect> {
    private val logger by logger()

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    override val container: Container<CleanupState, SideEffect> = coroutineScope.container(CleanupState())

    fun computeCacheSize() = intent {
        reduce { state.copy(scanningCache = true) }
        val size = Scoop.computeCacheSize()
        delay(500L)
        reduce { state.copy(cacheSize = size, scanningCache = false) }
    }

    fun computeOldVersions() = intent {
        reduce { state.copy(scanningOldVersion = true) }
        val oldVersions = scanOldVersions().sortedBy { it.size }.reversed()
        reduce { state.copy(oldVersions = oldVersions, scanningOldVersion = false) }
    }

    fun clearCache() = intent {
        reduce { state.copy(cleaningCache = true) }
        Scoop.removeCache() { result ->
            logger.info("Cache removed, result = $result")
            reduce { state.copy(cleaningCache = false) }
            this@CleanupViewModel.computeCacheSize()
        }
    }

    fun cleanup(vararg oldVersions: OldVersion) = intent {
        val cleanupApps = { versions: List<OldVersion>, global: Boolean ->
            if (versions.isNotEmpty()) {
                val apps = versions.map { it.app }.toTypedArray()
                Scoop.cleanup(*apps, global = global) {
                    reduce { state.copy(oldVersions = state.oldVersions?.filter { it.app !in apps }) }
                }
            }
        }
        reduce { state.copy(cleaningOldVersions = true) }
        cleanupApps(oldVersions.filter { !it.global }, false)
        cleanupApps(oldVersions.filter { it.global }, true)
        reduce { state.copy(cleaningOldVersions = false) }
    }


    internal fun scanOldVersions(): List<OldVersion> {
        val oldVersions = (Scoop.localInstalledAppDirs + Scoop.globalInstalledAppDirs).filter { dir ->
            dir.exists() && (dir.listFiles()?.size!! > 2)
        }.map { dir ->
            val current = dir.resolve("current").toPath().toRealPath().name
            val oldDirs = dir.listFiles()?.filter { it.name != "current" && it.name != current } ?: listOf()
            val global = dir.absolutePath.contains(Scoop.globalRootDir.absolutePath)
            OldVersion(
                app = dir.name,
                size = oldDirs.sumOf { it.dirSize() },
                global = global,
                paths = oldDirs
            )
        }
        return oldVersions
    }
}