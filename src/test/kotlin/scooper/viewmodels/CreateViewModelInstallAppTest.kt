package scooper.viewmodels

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.data.App
import scooper.data.Bucket
import scooper.service.CommandResult

/**
 * TDD tests for the AI `install_app` path: the [aiInstallApp] wrapper runs the
 * real scoop install (or short-circuits on the already-installed guard) and
 * maps the outcome to the JSON reply for the model plus the CreateState
 * updates (installedForUse and the toast payloads).
 */
class CreateViewModelInstallAppTest {

    private fun resultJsonField(json: String, field: String): String? {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(json)
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
        return (obj[field] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }

    // ---------------------------------------------------------------------
    // Success path
    // ---------------------------------------------------------------------

    @Test
    fun `successful install reports app and bucket and marks installedForUse`() = runBlocking {
        val installedApps = mutableListOf<App>()
        val result = aiInstallApp(
            bucket = "main",
            name = "fd",
            alreadyInstalled = false,
            install = { app ->
                installedApps.add(app)
                CommandResult(exitCode = 0)
            },
        )

        assertEquals(
            listOf(App(name = "fd", latestVersion = "0", bucket = Bucket("main"))),
            installedApps,
            "the host install must receive the App built from bucket and name",
        )
        assertTrue(result.installedForUse, "a successful install must set installedForUse")
        assertTrue(result.newlyInstalled, "a successful install is a new install in this session")
        assertNull(result.maskedError, "a successful install has no failure toast payload")

        assertEquals("true", resultJsonField(result.resultJson, "success"))
        assertEquals("fd", resultJsonField(result.resultJson, "app"))
        assertEquals("main", resultJsonField(result.resultJson, "bucket"))
        assertEquals("bucket", resultJsonField(result.resultJson, "mode"))
    }

    // ---------------------------------------------------------------------
    // Failure paths
    // ---------------------------------------------------------------------

    @Test
    fun `failed install keeps installedForUse false and surfaces the error`() = runBlocking {
        val result = aiInstallApp(
            bucket = "main",
            name = "fd",
            alreadyInstalled = false,
            install = { _ -> CommandResult(exitCode = 1, errorMessage = "download failed") },
        )

        assertFalse(result.installedForUse, "a failed install must not set installedForUse")
        assertFalse(result.newlyInstalled)
        assertEquals("download failed", result.maskedError)
        assertEquals("false", resultJsonField(result.resultJson, "success"))
        assertEquals("bucket", resultJsonField(result.resultJson, "mode"))
        assertEquals("download failed", resultJsonField(result.resultJson, "error"))
    }

    @Test
    fun `failed install without error message falls back to the exit code`() = runBlocking {
        val result = aiInstallApp(
            bucket = "main",
            name = "fd",
            alreadyInstalled = false,
            install = { _ -> CommandResult(exitCode = 3) },
        )

        assertEquals("exit code 3", result.maskedError)
        assertEquals("exit code 3", resultJsonField(result.resultJson, "error"))
    }

    @Test
    fun `failure error message is masked before it reaches the model or a toast`() = runBlocking {
        val result = aiInstallApp(
            bucket = "main",
            name = "fd",
            alreadyInstalled = false,
            install = { _ -> CommandResult(exitCode = 1, errorMessage = "401 for key sk-abcdefghijklmnop") },
        )

        assertEquals("401 for key sk-***", result.maskedError, "API keys must be masked in the failure payload")
        assertEquals("401 for key sk-***", resultJsonField(result.resultJson, "error"))
    }

    // ---------------------------------------------------------------------
    // Already-installed guard
    // ---------------------------------------------------------------------

    @Test
    fun `already installed guard skips the install and reports success with a message`() = runBlocking {
        var calls = 0
        val result = aiInstallApp(
            bucket = "main",
            name = "fd",
            alreadyInstalled = true,
            install = { _ ->
                calls++
                CommandResult(exitCode = 0)
            },
        )

        assertEquals(0, calls, "an app already installed in this session must not be installed again")
        assertTrue(result.installedForUse, "the app is installed for use after the guard")
        assertFalse(result.newlyInstalled, "the guard must not trigger the Installed toast again")
        assertNull(result.maskedError)

        assertEquals("true", resultJsonField(result.resultJson, "success"))
        assertEquals("bucket", resultJsonField(result.resultJson, "mode"))
        assertEquals("already installed in this session", resultJsonField(result.resultJson, "message"))
        assertNull(resultJsonField(result.resultJson, "app"), "the guard reply must not claim a fresh install")
    }
}
