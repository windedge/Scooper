package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.App
import scooper.taskqueue.TaskQueue
import java.io.File
import java.nio.file.Path

/**
 * TDD tests for test-install persist cleanup:
 * - uninstall(purge = true) must assemble `scoop uninstall -p <name>` (and keep
 *   the global `sudo scoop uninstall -g -p <name>` shape).
 * - persistCleanupTarget / cleanupPersistDir locate `<root>/persist/<name>` only
 *   when it exists, scoped to an injected root (isolated-root pattern).
 * - Recursive delete removes nested trees and never throws on failure.
 */
class ScoopClientUninstallPurgeTest {

    private val logStream = ScoopLogStream()
    private val client = ScoopClient(logStream, TaskQueue())

    @TempDir
    lateinit var tempDir: Path

    private fun newClient(root: File): ScoopClient = ScoopClient(
        logStream = ScoopLogStream(),
        taskQueue = TaskQueue(),
        rootDirOverride = root,
    )

    // ---------------------------------------------------------------
    // Command assembly: purge flag
    // ---------------------------------------------------------------

    @Test
    fun `uninstall args contain -p when purge is true`() {
        val app = App(name = "myapp-scooper-test", latestVersion = "0")
        val args = client.uninstallArgs(app, global = false, purge = true)
        assertEquals(listOf("scoop", "uninstall", "-p", "myapp-scooper-test"), args)
    }

    @Test
    fun `uninstall args omit -p when purge is false`() {
        val app = App(name = "myapp", latestVersion = "0")
        val args = client.uninstallArgs(app, global = false, purge = false)
        assertEquals(listOf("scoop", "uninstall", "myapp"), args)
        assertFalse("-p" in args)
    }

    @Test
    fun `uninstall args keep sudo and -g shape for global purge`() {
        val app = App(name = "myapp", latestVersion = "0")
        val args = client.uninstallArgs(app, global = true, purge = true)
        assertEquals(listOf("sudo", "scoop", "uninstall", "-g", "-p", "myapp"), args)
    }

    // ---------------------------------------------------------------
    // persistCleanupTarget: exists -> path, missing -> null
    // ---------------------------------------------------------------

    @Test
    fun `persistCleanupTarget returns the persist dir when it exists`() {
        val root = tempDir.resolve("root-a").toFile().also { it.mkdirs() }
        val expected = root.resolve("persist/myapp-scooper-test").also { it.mkdirs() }
        expected.resolve("data.txt").writeText("persisted")

        val target = client.persistCleanupTarget(root, "myapp-scooper-test")

        assertEquals(expected.absolutePath, target?.absolutePath)
        assertTrue(target?.isDirectory == true)
    }

    @Test
    fun `persistCleanupTarget returns null when persist dir is missing`() {
        val root = tempDir.resolve("root-b").toFile().also { it.mkdirs() }

        val target = client.persistCleanupTarget(root, "myapp-scooper-test")

        assertNull(target)
    }

    @Test
    fun `persistCleanupTarget does not leak into other apps persist dirs`() {
        val root = tempDir.resolve("root-c").toFile().also { it.mkdirs() }
        // only the REAL app has persist data; the test name does not
        root.resolve("persist/myapp").also { it.mkdirs() }

        val target = client.persistCleanupTarget(root, "myapp-scooper-test")

        assertNull(target, "must not return a different app's persist dir")
    }

    // ---------------------------------------------------------------
    // cleanupPersistDir: injected-root entry point used by cleanupTestInstall
    // ---------------------------------------------------------------

    @Test
    fun `cleanupPersistDir removes leftover persist dir under injected root`() {
        val root = tempDir.resolve("root-d").toFile().also { it.mkdirs() }
        val persist = root.resolve("persist/old-scooper-test").also { it.mkdirs() }
        persist.resolve("config.json").writeText("{}")

        val isolated = newClient(root)
        val removed = isolated.cleanupPersistDir("old-scooper-test", root)

        assertTrue(removed, "leftover persist dir must be removed")
        assertFalse(persist.exists())
    }

    @Test
    fun `cleanupPersistDir returns false when nothing to clean`() {
        val root = tempDir.resolve("root-e").toFile().also { it.mkdirs() }

        val removed = newClient(root).cleanupPersistDir("absent-scooper-test", root)

        assertFalse(removed)
    }

    @Test
    fun `cleanupPersistDir uses rootDirOverride when root param omitted`() {
        val root = tempDir.resolve("root-f").toFile().also { it.mkdirs() }
        val persist = root.resolve("persist/legacy-scooper-test").also { it.mkdirs() }
        persist.resolve("settings.dat").writeText("x")

        val isolated = newClient(root)
        val removed = isolated.cleanupPersistDir("legacy-scooper-test")

        assertTrue(removed)
        assertFalse(persist.exists(), "must resolve against the isolated rootDirOverride")
    }

    // ---------------------------------------------------------------
    // Recursive delete
    // ---------------------------------------------------------------

    @Test
    fun `deleteRecursivelyQuietly removes nested directory tree`() {
        val target = tempDir.resolve("root-g/persist/deep-scooper-test").toFile().also { it.mkdirs() }
        target.resolve("a/b/c").mkdirs()
        target.resolve("a/b/c/file.txt").writeText("deep")
        target.resolve("a/top.txt").writeText("top")
        target.resolve("sibling.bin").writeText("bin")

        val removed = client.deleteRecursivelyQuietly(target)

        assertTrue(removed)
        assertFalse(target.exists(), "nested tree must be fully deleted")
    }

    @Test
    fun `deleteRecursivelyQuietly is a no-op for missing path`() {
        val missing = tempDir.resolve("root-h/persist/ghost").toFile()

        val removed = client.deleteRecursivelyQuietly(missing)

        assertTrue(removed, "deleting an absent path counts as cleaned")
    }

    @Test
    fun `deleteRecursivelyQuietly does not throw when deletion fails`() {
        assumeTrue(
            System.getProperty("os.name").lowercase().contains("win"),
            "open-handle deletion failure is Windows-specific",
        )
        val target = tempDir.resolve("root-i/persist/locked-scooper-test").toFile().also { it.mkdirs() }
        val locked = target.resolve("locked.dat")
        locked.writeText("locked")
        // Hold an open handle without FILE_SHARE_DELETE so Windows refuses deletion
        val channel = java.io.RandomAccessFile(locked, "rw").channel
        try {
            val removed = client.deleteRecursivelyQuietly(target)

            assertFalse(removed, "locked file must make deletion report failure")
            assertTrue(locked.exists(), "locked file must survive the failed cleanup")
            assertTrue(target.exists(), "parent dir must survive when a child cannot be deleted")
        } finally {
            channel.close()
        }
    }
}
