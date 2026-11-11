package scooper.service

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.AppStatus
import scooper.data.Bucket
import java.io.File

/**
 * Cross-bucket duplicate manifests (same name in two buckets, e.g. cemu in
 * extras 2.0 and games 2.6): the bucket recorded in the install record must
 * decide which duplicate row is the installed one. Without it the
 * alphabetically first bucket won dedup and Updates showed "installed 2.6,
 * latest 2.0" from a wrong bucket pair.
 */
class ScoopClientCrossBucketTest {

    @TempDir
    lateinit var tempDir: File

    private val root by lazy { ScoopTempRoot(tempDir) }

    @AfterEach
    fun removeJunctions() = root.removeJunctions()

    // ---------------------------------------------------------------
    // buildAllApps: install record bucket decides the installed row
    // ---------------------------------------------------------------

    @Test
    fun `install record bucket wins over alphabetical bucket order for cross-bucket names`() {
        root.writeManifest("extras", "cemu", "2.0")
        root.writeManifest("games", "cemu", "2.6")
        root.markInstalled("cemu", version = "2.6", recordBucket = "games")
        val client = root.client()

        val cemu = client.buildAllApps(client.installedSnapshot()).first { it.name == "cemu" }

        assertEquals("games", cemu.bucket?.name, "the install record's bucket must win")
        assertEquals(AppStatus.INSTALLED, cemu.status)
        assertEquals("2.6", cemu.version, "version must be the games install, not the extras manifest")
        assertEquals("2.6", cemu.latestVersion, "latest must come from the games manifest")
        assertFalse(cemu.updatable, "no false update hint from the wrong bucket pair")
    }

    @Test
    fun `non-origin bucket manifest stays an uninstalled discover row`() {
        root.writeManifest("extras", "cemu", "2.0")
        root.writeManifest("games", "cemu", "2.6")
        root.markInstalled("cemu", version = "2.6", recordBucket = "games")
        val client = root.client()

        val extrasRows = client.buildAppsFromManifestNames(
            File(tempDir, "buckets/extras"),
            setOf("cemu.json"),
            Bucket(name = "extras", url = ""),
            client.installedSnapshot(),
        )

        val row = extrasRows.single()
        assertEquals(AppStatus.UNINSTALL, row.status, "the extras copy must stay discoverable, not vanish")
        assertEquals("2.0", row.latestVersion)
    }

    @Test
    fun `cross-bucket name without install record keeps rows uninstalled`() {
        root.writeManifest("extras", "cemu", "2.0")
        root.writeManifest("games", "cemu", "2.6")
        val client = root.client()

        val cemu = client.buildAllApps(client.installedSnapshot()).first { it.name == "cemu" }

        assertEquals(AppStatus.UNINSTALL, cemu.status)
        assertFalse(cemu.updatable)
    }

    @Test
    fun `unique-name apps need no install record read and stay installed`() {
        root.writeManifest("extras", "cemu", "2.0")
        root.writeManifest("games", "cemu", "2.6")
        root.writeManifest("main", "ripgrep", "14.0")
        root.markInstalled("ripgrep", version = "14.0", recordBucket = "main")
        val client = root.client()

        val rg = client.buildAllApps(client.installedSnapshot()).first { it.name == "ripgrep" }

        assertEquals("main", rg.bucket?.name)
        assertEquals(AppStatus.INSTALLED, rg.status)
        assertTrue(rg.installed)
    }

    // ---------------------------------------------------------------
    // assumeOrigin: save / post-install indexing skips strictness
    // ---------------------------------------------------------------

    @Test
    fun `assumeOrigin true keeps the row installed even in a non-origin bucket`() {
        root.writeManifest("extras", "cemu", "2.0")
        root.writeManifest("games", "cemu", "2.6")
        root.markInstalled("cemu", version = "2.6", recordBucket = "games")
        val client = root.client()

        val rows = client.buildAppsFromManifestNames(
            File(tempDir, "buckets/extras"),
            setOf("cemu.json"),
            Bucket(name = "extras", url = ""),
            client.installedSnapshot(),
            assumeOrigin = true,
        )

        val row = rows.single()
        assertEquals(AppStatus.INSTALLED, row.status, "just-saved or just-installed manifests stay attributed to their bucket")
        assertEquals("extras", row.bucket?.name)
        assertEquals("2.6", row.version, "the installed version is kept, not the manifest one")
    }

    // ---------------------------------------------------------------
    // updateArgs: the corrected row targets the origin bucket
    // ---------------------------------------------------------------

    @Test
    fun `update args target the origin bucket for the corrected row`() {
        root.writeManifest("extras", "cemu", "2.0")
        root.writeManifest("games", "cemu", "2.6")
        root.markInstalled("cemu", version = "2.6", recordBucket = "games")
        val client = root.client()

        val cemu = client.buildAllApps(client.installedSnapshot()).first { it.name == "cemu" }
        val args = client.updateArgs(cemu, global = false, force = false)

        assertEquals(listOf("scoop", "update", "games/cemu"), args)
    }
}
