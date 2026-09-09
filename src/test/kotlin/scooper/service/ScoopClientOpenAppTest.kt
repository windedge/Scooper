package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    // ---------------------------------------------------------------
    // shortcutNamesFromManifest
    // ---------------------------------------------------------------

    @Test
    fun `shortcutNamesFromManifest reads top level display names`() {
        val json = """{"version":"1.0","shortcuts":[["app.exe","Sanad Agent"],["other.exe","Other"]]}"""
        assertEquals(listOf("Sanad Agent", "Other"), client.shortcutNamesFromManifest(json))
    }

    @Test
    fun `shortcutNamesFromManifest reads per architecture display names`() {
        val json = """{"version":"1.0","architecture":{"64bit":{"shortcuts":[["app.exe","API Dash"]]}}}"""
        assertEquals(listOf("API Dash"), client.shortcutNamesFromManifest(json))
    }

    @Test
    fun `shortcutNamesFromManifest returns empty for blank invalid or shortcut-free input`() {
        assertTrue(client.shortcutNamesFromManifest(null).isEmpty())
        assertTrue(client.shortcutNamesFromManifest("  ").isEmpty())
        assertTrue(client.shortcutNamesFromManifest("not json").isEmpty())
        assertTrue(client.shortcutNamesFromManifest("""{"version":"1.0","bin":"app.exe"}""").isEmpty())
    }

    // ---------------------------------------------------------------
    // resolveStartMenuShortcut
    // ---------------------------------------------------------------

    private fun createLnk(fileName: String): File {
        val lnk = client.startMenuShortcutFile(fileName.removeSuffix(".lnk"), tempDir.toFile())
        lnk.parentFile.mkdirs()
        lnk.writeText("")
        return lnk
    }

    @Test
    fun `resolveStartMenuShortcut prefers the manifest display name`() {
        val expected = createLnk("Sanad Agent.lnk")
        val resolved = client.resolveStartMenuShortcut(
            name = "sanad-agent",
            shortcutNames = listOf("Sanad Agent"),
            appDataDir = tempDir.toFile(),
        )
        assertEquals(expected.absolutePath, resolved?.absolutePath)
    }

    @Test
    fun `resolveStartMenuShortcut falls back to the app name`() {
        val expected = createLnk("ripgrep.lnk")
        val resolved = client.resolveStartMenuShortcut(
            name = "ripgrep",
            shortcutNames = emptyList(),
            appDataDir = tempDir.toFile(),
        )
        assertEquals(expected.absolutePath, resolved?.absolutePath)
    }

    @Test
    fun `resolveStartMenuShortcut matches normalized display names without a manifest`() {
        val expected = createLnk("API Dash.lnk")
        val resolved = client.resolveStartMenuShortcut(
            name = "apidash",
            shortcutNames = emptyList(),
            appDataDir = tempDir.toFile(),
        )
        assertEquals(expected.absolutePath, resolved?.absolutePath)
    }

    @Test
    fun `resolveStartMenuShortcut returns null when nothing matches`() {
        createLnk("Other App.lnk")
        val resolved = client.resolveStartMenuShortcut(
            name = "missing-app",
            shortcutNames = listOf("Missing App"),
            appDataDir = tempDir.toFile(),
        )
        assertNull(resolved)
    }
}
