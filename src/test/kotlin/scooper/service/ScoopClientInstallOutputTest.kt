package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.taskqueue.TaskQueue

/**
 * TDD tests for ScoopClient.evaluateInstallOutput.
 * Scoop exits 0 even when shortcut/icon creation fails, so "failed: Couldn't find"
 * lines must force exitCode 1 while normal success output stays exitCode 0.
 */
class ScoopClientInstallOutputTest {

    private val client = ScoopClient(ScoopLogStream(), TaskQueue())

    // ---------------------------------------------------------------
    // Test cases
    // ---------------------------------------------------------------

    @Test
    fun `shortcut creation failure forces exitCode 1 despite scoop exit 0`() {
        val output = listOf(
            "Installing 'pomodooist-scooper-test' (1.0.3) [64bit]...",
            "Linking ~\\scoop\\apps\\pomodooist-scooper-test\\current => ~\\scoop\\apps\\pomodooist-scooper-test",
            "Creating shortcut for Pomodooist (pomodooist.exe) failed: Couldn't find C:\\Users\\xujl\\scoop\\apps\\pomodooist-scooper-test\\current\\pomodooist.exe",
            "'pomodooist-scooper-test' (1.0.3) was installed successfully!",
        )

        val result = client.evaluateInstallOutput(0, output)

        assertEquals(1, result.exitCode, "shortcut failure must fail the install despite exit 0")
        assertFalse(result.isSuccess)
        assertTrue(
            result.errorMessage!!.contains("Couldn't find"),
            "errorMessage must carry the Couldn't find detail, got: ${result.errorMessage}",
        )
        assertEquals(output.joinToString("\n"), result.output)
    }

    @Test
    fun `icon creation failure forces exitCode 1 despite scoop exit 0`() {
        val output = listOf(
            "Installing 'iconapp-scooper-test' (2.1.0) [64bit]...",
            "failed: Couldn't find icon C:\\x\\icon.exe",
            "'iconapp-scooper-test' (2.1.0) was installed successfully!",
        )

        val result = client.evaluateInstallOutput(0, output)

        assertEquals(1, result.exitCode)
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Couldn't find icon"))
    }

    @Test
    fun `normal successful output keeps exitCode 0 and no error message`() {
        val output = listOf(
            "Installing 'ripgrep-scooper-test' (14.1.0) [64bit]...",
            "Creating shim for 'rg.exe'.",
            "Linking ~\\scoop\\apps\\ripgrep-scooper-test\\current => ~\\scoop\\apps\\ripgrep-scooper-test",
            "'ripgrep-scooper-test' (14.1.0) was installed successfully!",
        )

        val result = client.evaluateInstallOutput(0, output)

        assertEquals(0, result.exitCode)
        assertTrue(result.isSuccess)
        assertNull(result.errorMessage)
        assertNull(result.skippedMessage, "a real install must not be reported as skipped")
        assertEquals(output.joinToString("\n"), result.output)
    }

    @Test
    fun `nonzero exit with no error line gets process exit fallback message`() {
        val output = listOf(
            "Installing...",
            "Some neutral output",
        )

        val result = client.evaluateInstallOutput(3, output)

        assertEquals(3, result.exitCode, "fallback message must not mask the real exit code")
        assertFalse(result.isSuccess)
        assertTrue(
            result.errorMessage!!.contains("exited with code 3"),
            "errorMessage must carry an explicit conclusion, got: ${result.errorMessage}",
        )
    }

    @Test
    fun `nonzero exit with matched ERROR line keeps original message and real exit code`() {
        val output = listOf("ERROR line here")

        val result = client.evaluateInstallOutput(2, output)

        assertEquals(2, result.exitCode)
        assertFalse(result.isSuccess)
        assertEquals("ERROR line here", result.errorMessage)
    }

    // ---------------------------------------------------------------
    // Silent skips: scoop exits 0 after doing nothing
    // ---------------------------------------------------------------

    @Test
    fun `held app output is reported as skipped with held to version reason`() {
        val output = listOf(
            "WARN  'pinned-scooper-test' is held to version 1.0.0",
        )

        val result = client.evaluateInstallOutput(0, output)

        assertEquals(0, result.exitCode, "a held app is not a failure, scoop exits 0")
        assertTrue(result.isSuccess)
        assertNull(result.errorMessage)
        assertTrue(
            result.skippedMessage!!.contains("held to version"),
            "skippedMessage must explain the hold, got: ${result.skippedMessage}",
        )
    }

    @Test
    fun `running process skip with ERROR line fails the update with the still running message`() {
        val output = listOf(
            "Updating 'blocked-scooper-test' (1.0.0 -> 2.0.0)",
            "ERROR The following instances of \"blocked-scooper-test\" are still running. Close them and try again.",
            "Running process detected, skip updating.",
        )

        val result = client.evaluateInstallOutput(0, output)

        assertEquals(1, result.exitCode, "the ERROR line precedes the skip hint and must fail the update")
        assertFalse(result.isSuccess)
        assertTrue(
            result.errorMessage!!.contains("still running"),
            "errorMessage must carry the running-process detail, got: ${result.errorMessage}",
        )
        assertNull(result.skippedMessage, "an error must not also be reported as a skip")
    }

    @Test
    fun `running process skip hint alone is reported as skipped`() {
        val output = listOf(
            "Updating 'blocked-scooper-test' (1.0.0 -> 2.0.0)",
            "Running process detected, skip updating.",
        )

        val result = client.evaluateInstallOutput(0, output)

        assertEquals(0, result.exitCode)
        assertNull(result.errorMessage)
        assertTrue(
            result.skippedMessage!!.contains("still running"),
            "skippedMessage must explain the running process, got: ${result.skippedMessage}",
        )
    }

    @Test
    fun `no manifest available ERROR line still fails the update`() {
        val output = listOf("ERROR No manifest available for 'ghost-scooper-test'.")

        val result = client.evaluateInstallOutput(0, output)

        assertEquals(1, result.exitCode)
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("No manifest available"))
        assertNull(result.skippedMessage)
    }
}
