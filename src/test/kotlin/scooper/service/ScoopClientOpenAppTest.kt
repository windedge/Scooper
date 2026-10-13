package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.App
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

    // ---------------------------------------------------------------
    // Direct-install Open fallback: launch the manifest bin exe when
    // the Start Menu shortcut lookup fails (Discover openShortcut parity)
    // ---------------------------------------------------------------

    @Test
    fun `manifestBinExePaths reads top level string and nested pair bins`() {
        val json = """
            {"version":"1.0",
             "bin":"bin\\Tesseract.exe",
             "architecture":{
               "64bit":{"bin":[["bin\\x64\\App.exe","App"]]},
               "32bit":{"bin":["bin\\x86\\App32.exe"]}}}
        """.trimIndent()
        assertEquals(listOf(BIN, BIN_X64, BIN_X86), client.manifestBinExePaths(json))
    }

    @Test
    fun `manifestBinExePaths dedupes and ignores blank or invalid input`() {
        val json = """{"bin":["bin\\App.exe","bin\\App.exe"]}"""
        assertEquals(listOf(BIN_APP), client.manifestBinExePaths(json))
        assertTrue(client.manifestBinExePaths(null).isEmpty())
        assertTrue(client.manifestBinExePaths("  ").isEmpty())
        assertTrue(client.manifestBinExePaths("not json").isEmpty())
        assertTrue(client.manifestBinExePaths("""{"bin":"","architecture":{"64bit":{"bin":[""]}}}""").isEmpty())
    }

    @Test
    fun `openAppCommand skips non exe bins and points to the install dir`() {
        val installDir = File(tempDir.toFile(), "apps/demo/current")
        val cmd = client.openAppCommand(installDir, listOf(BIN, HELPER_JAR))
        assertEquals(listOf("cmd", "/c", "start", "", BIN), cmd?.args)
        assertEquals(installDir, cmd?.workDir)
    }

    @Test
    fun `openAppCommand returns null when no bin is an exe`() {
        val installDir = File(tempDir.toFile(), "apps/demo/current")
        assertNull(client.openAppCommand(installDir, listOf(HELPER_JAR, "script.cmd")))
    }

    // ---------------------------------------------------------------
    // manifestShortcutPairs (unified Open mechanism)
    // ---------------------------------------------------------------

    @Test
    fun `manifestShortcutPairs reads top level and per architecture pairs`() {
        val json = """
            {"version":"1.0",
             "shortcuts":[["bin\\App.exe","App"]],
             "architecture":{"64bit":{"shortcuts":[["bin\\x64\\Tool.exe","Tool X64"]]}}}
        """.trimIndent()
        assertEquals(
            listOf("bin\\App.exe" to "App", "bin\\x64\\Tool.exe" to "Tool X64"),
            client.manifestShortcutPairs(json),
        )
    }

    @Test
    fun `manifestShortcutPairs returns empty for blank invalid or shortcut-free input`() {
        assertTrue(client.manifestShortcutPairs(null).isEmpty())
        assertTrue(client.manifestShortcutPairs("  ").isEmpty())
        assertTrue(client.manifestShortcutPairs("not json").isEmpty())
        assertTrue(client.manifestShortcutPairs("""{"bin":"app.exe"}""").isEmpty())
        assertTrue(client.manifestShortcutPairs("""{"shortcuts":[["app.exe",""]]}""").isEmpty())
    }

    // ---------------------------------------------------------------
    // resolveLaunchTarget: unified launch priority
    // preferredShortcut -> first manifest shortcut -> first exe bin
    // -> Start Menu lnk -> null
    // ---------------------------------------------------------------

    private fun installDir(): File = File(tempDir.toFile(), "apps/demo/current")

    private fun expectedCommand(target: String, dir: File): LaunchTarget.StartCommand =
        LaunchTarget.StartCommand(listOf("cmd", "/c", "start", "", target), dir)

    private val shortcutsJson =
        """{"shortcuts":[["bin\\App.exe","App"],["bin\\Tool.exe","Tool"]],"bin":"bin\\App.exe"}"""

    @Test
    fun `resolveLaunchTarget matches the preferred shortcut by target path`() {
        val resolved = client.resolveLaunchTarget(
            name = "demo",
            installDir = installDir(),
            manifestJson = shortcutsJson,
            preferredShortcut = "bin\\Tool.exe",
            appDataDir = tempDir.toFile(),
        )
        assertEquals(expectedCommand("bin\\Tool.exe", installDir()), resolved)
    }

    @Test
    fun `resolveLaunchTarget matches the preferred shortcut by display name`() {
        val resolved = client.resolveLaunchTarget(
            name = "demo",
            installDir = installDir(),
            manifestJson = shortcutsJson,
            preferredShortcut = "Tool",
            appDataDir = tempDir.toFile(),
        )
        assertEquals(expectedCommand("bin\\Tool.exe", installDir()), resolved)
    }

    @Test
    fun `resolveLaunchTarget matches the preferred shortcut across path separators`() {
        val resolved = client.resolveLaunchTarget(
            name = "demo",
            installDir = installDir(),
            manifestJson = shortcutsJson,
            preferredShortcut = "bin/Tool.exe",
            appDataDir = tempDir.toFile(),
        )
        assertEquals(expectedCommand("bin\\Tool.exe", installDir()), resolved)
    }

    @Test
    fun `resolveLaunchTarget launches the first shortcut when nothing is preferred`() {
        val resolved = client.resolveLaunchTarget(
            name = "demo",
            installDir = installDir(),
            manifestJson = shortcutsJson,
            preferredShortcut = null,
            appDataDir = tempDir.toFile(),
        )
        assertEquals(expectedCommand("bin\\App.exe", installDir()), resolved)
    }

    @Test
    fun `resolveLaunchTarget falls back to the first exe bin without shortcuts`() {
        val resolved = client.resolveLaunchTarget(
            name = "demo",
            installDir = installDir(),
            manifestJson = """{"bin":"bin\\Tesseract.exe"}""",
            preferredShortcut = null,
            appDataDir = tempDir.toFile(),
        )
        assertEquals(expectedCommand(BIN, installDir()), resolved)
    }

    @Test
    fun `resolveLaunchTarget falls back to the start menu lnk`() {
        val lnk = createLnk("demo.lnk")
        val resolved = client.resolveLaunchTarget(
            name = "demo",
            installDir = installDir(),
            manifestJson = null,
            preferredShortcut = null,
            appDataDir = tempDir.toFile(),
        )
        assertEquals(LaunchTarget.Lnk(lnk), resolved)
    }

    @Test
    fun `resolveLaunchTarget returns null when nothing matches`() {
        val resolved = client.resolveLaunchTarget(
            name = "missing-app",
            installDir = installDir(),
            manifestJson = null,
            preferredShortcut = null,
            appDataDir = null,
        )
        assertNull(resolved)
    }

    // ---------------------------------------------------------------
    // openShortcut thin delegation (Discover entry point)
    // ---------------------------------------------------------------

    @Test
    fun `openShortcut is a silent no-op without shortcuts or with an out of range index`() {
        val app = App(name = "demo", latestVersion = "1.0", shortcuts = null)
        client.openShortcut(app, 0)
        client.openShortcut(app, 3)
    }

    companion object {
        private const val BIN = "bin\\Tesseract.exe"
        private const val BIN_APP = "bin\\App.exe"
        private const val BIN_X64 = "bin\\x64\\App.exe"
        private const val BIN_X86 = "bin\\x86\\App32.exe"
        private const val HELPER_JAR = "bin\\helper.jar"
    }
}
