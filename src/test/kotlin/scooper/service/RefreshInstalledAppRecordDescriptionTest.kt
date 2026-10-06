package scooper.service

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.App
import scooper.data.AppStatus
import scooper.data.Bucket
import scooper.repository.AppsRepository
import scooper.taskqueue.TaskQueue
import scooper.viewmodels.refreshInstalledAppRecordCore
import java.io.File

/**
 * TDD tests for the bucket-install index refresh: the record upserted by
 * [refreshInstalledAppRecordCore] must carry the manifest fields (notably the
 * description) so Discover does not show "No description available." right
 * after a real install. Covers both first install (no DB row yet) and the
 * reinstall of an already-known app (sparse row exists).
 */
class RefreshInstalledAppRecordDescriptionTest {

    @TempDir
    lateinit var tempDir: File

    private val description = "Onyx - The context layer powered by all your apps"

    private fun createRepository(): AppsRepository =
        createInMemoryAppsRepository(
            ScoopClient(
                logStream = ScoopLogStream(),
                taskQueue = TaskQueue(),
                rootDirOverride = tempDir,
            ),
        )

    /** Bucket manifest plus the on-disk install layout scoop produces. */
    private fun setupInstalledApp(name: String) {
        val bucketDir = tempDir.resolve("buckets/main/bucket")
        bucketDir.mkdirs()
        bucketDir.resolve("$name.json").writeText(
            """{"version":"1.2.3","description":"$description","bin":["app.exe"]}""",
        )
        tempDir.resolve("apps/$name/current").mkdirs()
    }

    private fun findRow(repository: AppsRepository, name: String): App? =
        repository.getApps(query = name).value.firstOrNull { it.name == name }

    // ---------------------------------------------------------------

    @Test
    fun `refresh on first bucket install upserts the manifest description`() = runBlocking {
        val repository = createRepository()
        repository.ensureBucket("main")
        setupInstalledApp("testapp")

        refreshInstalledAppRecordCore("main", "testapp", repoScoopClient(), repository::upsertApp)

        val row = findRow(repository, "testapp")
        assertNotNull(row, "the app row must exist after the refresh")
        assertEquals(description, row?.description, "the description must come from the bucket manifest")
        assertEquals(AppStatus.INSTALLED, row?.status)
        assertEquals("main", row?.bucket?.name)
    }

    @Test
    fun `refresh fills the description of an existing sparse row`() = runBlocking {
        val repository = createRepository()
        repository.ensureBucket("main")
        setupInstalledApp("testapp")
        // Sparse row as a previous save/indexing pass might have created it.
        repository.upsertApp(
            App(
                name = "testapp",
                latestVersion = "1.2.3",
                version = "1.2.3",
                status = AppStatus.INSTALLED,
                bucket = Bucket("main"),
            ),
        )
        assertEquals(null, findRow(repository, "testapp")?.description)

        refreshInstalledAppRecordCore("main", "testapp", repoScoopClient(), repository::upsertApp)

        val row = findRow(repository, "testapp")
        assertEquals(description, row?.description, "the description must be filled from the bucket manifest")
        assertEquals(AppStatus.INSTALLED, row?.status)
    }

    /** A second ScoopClient over the same root: repository and refresh must see identical files. */
    private fun repoScoopClient(): ScoopClient = ScoopClient(
        logStream = ScoopLogStream(),
        taskQueue = TaskQueue(),
        rootDirOverride = tempDir,
    )
    // ---------------------------------------------------------------
    // installedAppRecord: Scoop now writes scoop-manifest.json
    // ---------------------------------------------------------------

    @Test
    fun `installedAppRecord reads the description from scoop-manifest json`() {
        val current = tempDir.resolve("apps/testapp/current")
        current.mkdirs()
        current.resolve("scoop-manifest.json").writeText(
            """{"version":"1.2.3","description":"$description"}""",
        )
        val client = repoScoopClient()

        val record = client.installedAppRecord("testapp")

        assertNotNull(record, "the record must be built from scoop-manifest.json")
        assertEquals(description, record?.description)
    }

    @Test
    fun `installedAppRecord still prefers the legacy manifest json`() {
        val current = tempDir.resolve("apps/testapp/current")
        current.mkdirs()
        current.resolve("manifest.json").writeText(
            """{"version":"1.2.3","description":"$description"}""",
        )
        val client = repoScoopClient()

        val record = client.installedAppRecord("testapp")

        assertNotNull(record)
        assertEquals(description, record?.description)
    }
}
