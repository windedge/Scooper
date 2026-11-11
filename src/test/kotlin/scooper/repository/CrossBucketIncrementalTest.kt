package scooper.repository

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.AppStatus
import scooper.data.Bucket
import scooper.service.ScoopClient
import scooper.service.ScoopTempRoot
import scooper.service.createInMemoryAppsRepository
import java.io.File

/**
 * Incremental indexing must not resurrect a wrong-bucket state for
 * cross-bucket duplicate names: changing extras/cemu.json may not turn the
 * games-installed row into an extras row (that was the original corruption
 * path), and a change to the origin bucket keeps the row correct.
 */
class CrossBucketIncrementalTest {

    @TempDir
    lateinit var tempDir: File

    private val root by lazy { ScoopTempRoot(tempDir) }

    @AfterEach
    fun removeJunctions() = root.removeJunctions()

    private fun createRepository(): AppsRepository = createInMemoryAppsRepository(root.client())

    /** The call shape of computeBucketChanges for one changed bucket. */
    private fun incrementalBuild(client: ScoopClient, bucket: String): List<scooper.data.App> =
        client.buildAppsFromManifestNames(
            File(tempDir, "buckets/$bucket"),
            setOf("cemu.json"),
            Bucket(name = bucket, url = ""),
            client.installedSnapshot(),
        )

    private fun cemuRow(repository: AppsRepository): scooper.data.App =
        repository.getApps(query = "cemu").value.single { it.name == "cemu" }

    // ---------------------------------------------------------------
    // Incremental change to the non-origin bucket must not steal the row
    // ---------------------------------------------------------------

    @Test
    fun `change to a non-origin bucket manifest does not steal the installed row`() {
        root.writeManifest("extras", "cemu", "2.0")
        root.writeManifest("games", "cemu", "2.6")
        root.markInstalled("cemu", version = "2.6", recordBucket = "games")
        val repository = createRepository()

        // Full load seeds the corrected row (games, INSTALLED).
        repository.loadAll()

        // Incremental: extras/cemu.json changed, so computeBucketChanges builds
        // the extras manifest and upserts it.
        val client = root.client()
        repository.upsertApps(incrementalBuild(client, "extras"))

        val row = cemuRow(repository)
        assertEquals("games", row.bucket?.name, "the extras change must keep the games attribution")
        assertEquals(AppStatus.INSTALLED, row.status, "the installed row must not flip to uninstalled")
        assertEquals("2.6", row.version)
        assertFalse(row.updatable)
    }

    @Test
    fun `change to the origin bucket manifest keeps the row correct`() {
        root.writeManifest("extras", "cemu", "2.0")
        root.writeManifest("games", "cemu", "2.6")
        root.markInstalled("cemu", version = "2.6", recordBucket = "games")
        val repository = createRepository()

        repository.loadAll()

        // Incremental: games/cemu.json changed (same content here).
        val client = root.client()
        repository.upsertApps(incrementalBuild(client, "games"))

        val row = cemuRow(repository)
        assertEquals("games", row.bucket?.name)
        assertEquals(AppStatus.INSTALLED, row.status)
        assertEquals("2.6", row.version)
        assertEquals("2.6", row.latestVersion)
        assertFalse(row.updatable)
    }
}
