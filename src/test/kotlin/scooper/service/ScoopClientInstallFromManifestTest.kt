package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.data.App
import scooper.taskqueue.TaskQueue
import java.io.File
import kotlin.reflect.KParameter

/**
 * TDD tests for direct install support:
 * - installFromManifestArgs assembles the local-manifest install command
 *   (plain file path, no -s because cache prewarm already ran).
 * - The local-manifest repair branch of update() reinstalls via
 *   localManifestRepairArgs and must never pass --force to scoop install
 *   (scoop-install.ps1 getopt only accepts giksua; --force exits 1).
 * - Scope detection shared by installVersion / installFromManifest.
 */
class ScoopClientInstallFromManifestTest {

    private val client = ScoopClient(ScoopLogStream(), TaskQueue())

    private fun app() = App(name = "myapp-scooper-test", latestVersion = "0")

    // ---------------------------------------------------------------
    // Command assembly: installFromManifestArgs
    // ---------------------------------------------------------------

    @Test
    fun `installFromManifestArgs installs the manifest file directly without -s`() {
        val args = client.installFromManifestArgs("C:\\tmp\\myapp.json", global = false)
        assertEquals(listOf("scoop", "install", "C:\\tmp\\myapp.json"), args)
        assertFalse("-s" in args)
    }

    @Test
    fun `installFromManifestArgs keeps sudo and -g shape for global install`() {
        val args = client.installFromManifestArgs("C:\\tmp\\myapp.json", global = true)
        assertEquals(listOf("sudo", "scoop", "install", "-g", "C:\\tmp\\myapp.json"), args)
    }

    // ---------------------------------------------------------------
    // B1 regression: local-manifest repair reinstall without --force
    // ---------------------------------------------------------------

    @Test
    fun `local manifest repair args reinstall from bucket without --force`() {
        val args = client.localManifestRepairArgs("main", "myapp-scooper-test", global = false)
        assertEquals(listOf("scoop", "install", "main/myapp-scooper-test"), args)
        assertFalse("--force" in args)
    }

    @Test
    fun `local manifest repair args keep sudo and -g shape for global`() {
        val args = client.localManifestRepairArgs("main", "myapp-scooper-test", global = true)
        assertEquals(listOf("sudo", "scoop", "install", "-g", "main/myapp-scooper-test"), args)
        assertFalse("--force" in args)
    }

    // ---------------------------------------------------------------
    // Interface contract: installFromManifest signature
    // ---------------------------------------------------------------

    @Test
    fun `scoop cli exposes installFromManifest with reinstallGlobal scope`() {
        val valueParams = ScoopCli::installFromManifest.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
        assertEquals(
            listOf("app", "manifestFile", "global", "reinstallGlobal"),
            valueParams.map { it.name },
        )
    }

    // ---------------------------------------------------------------
    // Scope detection shared by installVersion / installFromManifest
    // ---------------------------------------------------------------

    @Test
    fun `isInstalledInScope detects local install dir case-insensitively`() {
        val root = createTempRoot()
        try {
            root.resolve("apps/myapp-scooper-test").mkdirs()
            val scoped = ScoopClient(ScoopLogStream(), TaskQueue(), rootDirOverride = root)
            assertTrue(scoped.isInstalledInScope("MyApp-Scooper-Test", global = false))
            assertFalse(scoped.isInstalledInScope("other-app", global = false))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createTempRoot(): File =
        File.createTempFile("scooper-direct-install-", "").apply {
            delete()
            mkdirs()
        }
}
