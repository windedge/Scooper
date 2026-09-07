package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.taskqueue.TaskQueue
import java.io.File
import java.nio.file.Path

/**
 * TDD tests for the Open button logic layer: startMenuShortcutFile path building and
 * openApp failure handling when the Start Menu shortcut does not exist.
 */
class ScoopClientOpenAppTest {

    private val logStream = ScoopLogStream()
    private val client = ScoopClient(logStream, TaskQueue())

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `startMenuShortcutFile points to Scoop Apps lnk under the app data dir`() {
        val appData = tempDir.toFile()
        val lnk = client.startMenuShortcutFile("ripgrep", appData)
        assertEquals(
            File(appData, "Microsoft/Windows/Start Menu/Programs/Scoop Apps/ripgrep.lnk").absolutePath,
            lnk.absolutePath,
        )
    }

    @Test
    fun `openApp fails with the shortcut name when the lnk does not exist`() {
        // A name that cannot exist under the real Scoop Apps folder, so this is safe
        // against the real APPDATA environment.
        val missing = "scoop-ai-open-app-missing-${System.nanoTime()}"
        val result = client.openApp(missing)
        assertFalse(result.isSuccess)
        val message = result.errorMessage.orEmpty()
        assertTrue(message.contains(missing), "error message must mention the app name, got: $message")
    }
}
