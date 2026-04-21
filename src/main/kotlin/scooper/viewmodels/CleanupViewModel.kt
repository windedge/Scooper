package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.reduce
import scooper.repository.CleanupRepository
import scooper.repository.OldVersion
import scooper.util.logger

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


class CleanupViewModel(
    private val cleanupRepository: CleanupRepository,
) : ContainerHost<CleanupState, CleanupSideEffect>, AutoCloseable {
    private val logger by logger()

    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + supervisorJob)
    override val container: Container<CleanupState, CleanupSideEffect> = coroutineScope.container(CleanupState())

    fun computeCacheSize() = intent {
        reduce { state.copy(scanningCache = true) }
        val size = cleanupRepository.computeCacheSize()
        delay(500L)
        reduce { state.copy(cacheSize = size, scanningCache = false) }
    }

    fun computeOldVersions() = intent {
        reduce { state.copy(scanningOldVersion = true) }
        val oldVersions = cleanupRepository.scanOldVersions().sortedBy { it.size }.reversed()
        reduce { state.copy(oldVersions = oldVersions, scanningOldVersion = false) }
    }

    fun clearCache() = intent {
        reduce { state.copy(cleaningCache = true) }
        cleanupRepository.removeCache { result ->
            logger.info("Cache removed, result = $result")
            reduce { state.copy(cleaningCache = false) }
            this@CleanupViewModel.computeCacheSize()
        }
    }

    fun cleanup(vararg oldVersions: OldVersion) = intent {
        val cleanupApps: suspend (List<OldVersion>, Boolean) -> Unit = { versions, global ->
            if (versions.isNotEmpty()) {
                val apps = versions.map { it.app }.toTypedArray()
                cleanupRepository.cleanup(*apps, global = global) {
                    reduce { state.copy(oldVersions = state.oldVersions?.filter { it.app !in apps }) }
                }
            }
        }
        reduce { state.copy(cleaningOldVersions = true) }
        cleanupApps(oldVersions.filter { !it.global }, false)
        cleanupApps(oldVersions.filter { it.global }, true)
        reduce { state.copy(cleaningOldVersions = false) }
    }

    override fun close() {
        supervisorJob.cancel()
    }
}
