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
 * Saving to a bucket writes a bucket row, a later direct install wrote a second orphan
 * row, so Discover showed the same app twice: the orphan upsert must converge instead.
 */
class SaveBucketThenInstallConvergenceTest {

    @TempDir
    lateinit var tempDir: File

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    private fun createRepository() =
        createInMemoryAppsRepository(
            ScoopClient(
                logStream = ScoopLogStream(),
                taskQueue = TaskQueue(),
                rootDirOverride = tempDir,
            ),
        )

    /** The record shape the save path writes (bucket row, not installed). */
    private fun bucketRow(name: String, bucket: String) = App(
        name = name,
        latestVersion = "1.2.3",
        version = "1.2.3",
        status = AppStatus.UNINSTALL,
        bucket = Bucket(bucket),
    )

    /** The record shape the direct-install path writes (orphan, INSTALLED). */
    private fun installedOrphan(name: String) = App(
        name = name,
        latestVersion = "1.2.3",
        version = "1.2.3",
        status = AppStatus.INSTALLED,
        bucket = null,
    )

    private fun rowsByName(repository: AppsRepository, name: String): List<App> =
        repository.getApps(query = name).value.filter { it.name == name }

    // ---------------------------------------------------------------
    // Save then direct install must keep a single row
    // ---------------------------------------------------------------

    @Test
    fun `orphan installed write converges into an existing same-name bucket row`() {
        val repository = createRepository()
        repository.ensureBucket("main")
        repository.upsertApps(listOf(bucketRow("testapp", "main")))

        repository.upsertApps(listOf(installedOrphan("testapp")))

        val rows = rowsByName(repository, "testapp")
        assertEquals(1, rows.size, "the bucket row and the orphan INSTALLED write must collapse into one row")
        assertEquals("main", rows[0].bucket?.name, "the surviving row must keep the bucket attribution")
        assertEquals(AppStatus.INSTALLED, rows[0].status, "the surviving row must show as installed")
    }

    @Test
    fun `orphan installed write still creates an orphan row when no same-name row exists`() {
        val repository = createRepository()

        repository.upsertApps(listOf(installedOrphan("testapp")))

        val rows = rowsByName(repository, "testapp")
        assertEquals(1, rows.size)
        assertNull(rows[0].bucket, "a direct install with no bucket row stays an orphan")
        assertEquals(AppStatus.INSTALLED, rows[0].status)
    }

    @Test
    fun `orphan installed write never touches a different-name bucket row`() {
        val repository = createRepository()
        repository.ensureBucket("main")
        repository.upsertApps(listOf(bucketRow("otherapp", "main")))

        repository.upsertApps(listOf(installedOrphan("testapp")))

        assertEquals(1, rowsByName(repository, "otherapp").size)
        assertEquals(AppStatus.UNINSTALL, rowsByName(repository, "otherapp")[0].status)
        assertEquals(1, rowsByName(repository, "testapp").size)
    }

    @Test
    fun `orphan installed write keeps a different-bucket row untouched`() {
        val repository = createRepository()
        repository.ensureBucket("main")
        repository.ensureBucket("extras")
        repository.upsertApps(listOf(bucketRow("testapp", "main")))

        repository.upsertApps(listOf(installedOrphan("testapp")))

        val mainRow = rowsByName(repository, "testapp").firstOrNull { it.bucket?.name == "main" }
        assertNotNull(mainRow)
    }

    @Test
    fun `orphan uninstalled write still converges into a same-name bucket row`() {
        val repository = createRepository()
        repository.ensureBucket("main")
        repository.upsertApps(listOf(bucketRow("testapp", "main")))

        repository.upsertApps(
            listOf(installedOrphan("testapp").copy(status = AppStatus.UNINSTALL)),
        )

        val rows = rowsByName(repository, "testapp")
        assertEquals(1, rows.size, "convergence must not depend on the status value")
        assertEquals("main", rows[0].bucket?.name)
    }
}
