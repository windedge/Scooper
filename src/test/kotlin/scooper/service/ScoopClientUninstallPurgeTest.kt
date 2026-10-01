package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.App
import scooper.taskqueue.TaskQueue
import java.io.File

/**
 * TDD tests for uninstall command assembly and recursive delete:
 * - uninstall(purge = true) must assemble `scoop uninstall -p <name>` (and keep
 *   the global `sudo scoop uninstall -g -p <name>` shape).
 * - Recursive delete removes nested trees and never throws on failure.
 */
class ScoopClientUninstallPurgeTest {

    private val logStream = ScoopLogStream()
    private val client = ScoopClient(logStream, TaskQueue())

    @TempDir
    lateinit var tempDir: java.nio.file.Path

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
