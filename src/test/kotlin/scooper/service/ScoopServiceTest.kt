package scooper.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import scooper.taskqueue.TaskQueue

class ScoopServiceTest {

    private val logStream = ScoopLogStream()
    private val scoopClient = ScoopClient(logStream, TaskQueue())

    @Test
    fun `logStream is accessible`() {
        assertSame(logStream, scoopClient.logStream)
    }

    @Test
    fun `rootDir is not null`() {
        assertNotNull(scoopClient.rootDir)
    }

    @Test
    fun `globalRootDir is not null`() {
        assertNotNull(scoopClient.globalRootDir)
    }

    @Test
    fun `cacheDir is under rootDir`() {
        assertTrue(scoopClient.cacheDir.absolutePath.contains("scoop"))
        assertTrue(scoopClient.cacheDir.absolutePath.contains("cache"))
    }

    @Test
    fun `bucketNames returns list (may be empty on test machine)`() {
        val names = scoopClient.bucketNames
        assertNotNull(names)
        // On a machine with scoop installed, this may return buckets
    }

    @Test
    fun `computeCacheSize returns non-negative`() {
        val size = scoopClient.computeCacheSize()
        assertTrue(size >= 0)
    }
}
