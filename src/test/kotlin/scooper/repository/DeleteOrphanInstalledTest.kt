package scooper.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.App
import scooper.data.AppStatus
import scooper.data.Bucket
import scooper.service.ScoopClient
import scooper.service.ScoopLogStream
import scooper.service.createInMemoryAppsRepository
import scooper.taskqueue.TaskQueue
import java.io.File

/**
 * TDD tests for [AppsRepository.deleteOrphanInstalled]: a direct install
 * writes an orphan INSTALLED row (bucketId null), and saving the same app to
 * a bucket must delete that orphan row so the bucket row takes over without
 * leaving a duplicate Discover entry.
 */
class DeleteOrphanInstalledTest {

    @TempDir
    lateinit var tempDir: File

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    private fun createRepository(): AppsRepository =
        createInMemoryAppsRepository(
            ScoopClient(
                logStream = ScoopLogStream(),
                taskQueue = TaskQueue(),
                rootDirOverride = tempDir,
            ),
        )

    /** The record shape the direct-install path writes via upsertApps. */
    private fun installedOrphan(name: String) = App(
        name = name,
        latestVersion = "1.0.0",
        version = "1.0.0",
        status = AppStatus.INSTALLED,
        bucket = null,
    )

    private fun bucketRow(name: String, bucket: String) = App(
        name = name,
        latestVersion = "1.2.3",
        version = "1.2.3",
        status = AppStatus.UNINSTALL,
        bucket = Bucket(bucket),
    )

    private fun findRow(repository: AppsRepository, name: String): App? =
        repository.getApps(query = name).value.firstOrNull { it.name == name }

    // ---------------------------------------------------------------
    // deleteOrphanInstalled
    // ---------------------------------------------------------------

    @Test
    fun `deleteOrphanInstalled removes the orphan INSTALLED row for the app name`() {
        val repository = createRepository()
        repository.upsertApps(listOf(installedOrphan("testapp")))

        repository.deleteOrphanInstalled("testapp")

        assertNull(
            findRow(repository, "testapp"),
            "the orphan INSTALLED row must be deleted",
        )
    }

    @Test
    fun `deleteOrphanInstalled keeps the same-name bucket row`() {
        val repository = createRepository()
        repository.ensureBucket("main")
        repository.upsertApps(listOf(installedOrphan("testapp"), bucketRow("testapp", "main")))

        repository.deleteOrphanInstalled("testapp")

        val row = findRow(repository, "testapp")
        assertNotNull(row, "the bucket row must survive the orphan cleanup")
        assertEquals("main", row?.bucket?.name, "the surviving row must be the bucket row")
        assertEquals(AppStatus.UNINSTALL, row?.status)
    }

    @Test
    fun `deleteOrphanInstalled keeps other names' orphan rows`() {
        val repository = createRepository()
        repository.upsertApps(listOf(installedOrphan("testapp"), installedOrphan("otherapp")))

        repository.deleteOrphanInstalled("testapp")

        val other = findRow(repository, "otherapp")
        assertNotNull(other, "an unrelated orphan row must survive")
        assertEquals(AppStatus.INSTALLED, other?.status)
        assertNull(other?.bucket, "the unrelated row must stay an orphan")
    }

    @Test
    fun `deleteOrphanInstalled keeps a same-name orphan that is not INSTALLED`() {
        val repository = createRepository()
        repository.upsertApps(
            listOf(
                App(
                    name = "testapp",
                    latestVersion = "1.0.0",
                    version = "1.0.0",
                    status = AppStatus.UNINSTALL,
                    bucket = null,
                ),
            ),
        )

        repository.deleteOrphanInstalled("testapp")

        val row = findRow(repository, "testapp")
        assertNotNull(row, "a non-INSTALLED orphan row must survive")
        assertEquals(AppStatus.UNINSTALL, row?.status)
    }
}
