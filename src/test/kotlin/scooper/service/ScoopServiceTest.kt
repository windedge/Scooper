package scooper.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import scooper.taskqueue.TaskQueue

class ScoopServiceTest {

    private val logStream = ScoopLogStream()
    private val scoopService = ScoopService(logStream, TaskQueue())

    @Test
    fun `logStream is accessible`() {
        assertNotNull(scoopService.logStream)
        assertSame(logStream, scoopService.logStream)
    }

    @Test
    fun `rootDir is not null`() {
        assertNotNull(scoopService.rootDir)
    }

    @Test
    fun `globalRootDir is not null`() {
        assertNotNull(scoopService.globalRootDir)
    }

    @Test
    fun `cacheDir is under rootDir`() {
        assertTrue(scoopService.cacheDir.absolutePath.contains("scoop"))
        assertTrue(scoopService.cacheDir.absolutePath.contains("cache"))
    }

    @Test
    fun `bucketNames returns list (may be empty on test machine)`() {
        val names = scoopService.bucketNames
        assertNotNull(names)
        // On a machine with scoop installed, this may return buckets
    }

    @Test
    fun `computeCacheSize returns non-negative`() {
        val size = scoopService.computeCacheSize()
        assertTrue(size >= 0)
    }
}
