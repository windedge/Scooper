package scooper.service

import com.scoopai.core.ToolActivity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolActivityFormatterTest {

    @Test
    fun `Started event formats known tools with human-readable description`() {
        assertEquals(
            "Querying repository info...",
            ToolActivityFormatter.format(ToolActivity.Started("fetchRepoInfo", """{"repo":"sharkdp/fd"}""")),
        )
        assertEquals(
            "Fetching latest release...",
            ToolActivityFormatter.format(ToolActivity.Started("fetchLatestRelease", """{"repo":"sharkdp/fd"}""")),
        )
        assertEquals(
            "Computing SHA256 hash...",
            ToolActivityFormatter.format(ToolActivity.Started("computeSha256FromUrl", """{"url":"https://..."}""")),
        )
        assertEquals(
            "Inspecting archive contents...",
            ToolActivityFormatter.format(ToolActivity.Started("listArchiveContents", """{"url":"https://..."}""")),
        )
        assertEquals(
            "Scanning executables...",
            ToolActivityFormatter.format(ToolActivity.Started("findExecutables", """{"url":"https://..."}""")),
        )
        assertEquals(
            "Listing release assets...",
            ToolActivityFormatter.format(ToolActivity.Started("listReleaseAssets", "{}")),
        )
        assertEquals(
            "Reviewing manifest...",
            ToolActivityFormatter.format(ToolActivity.Started("__review_manifest__", "{}")),
        )
    }

    @Test
    fun `Started event with unknown tool formats as Running toolName`() {
        assertEquals(
            "Running customTool...",
            ToolActivityFormatter.format(ToolActivity.Started("customTool", "{}")),
        )
    }

    @Test
    fun `Completed event formats cleanly in English`() {
        assertEquals(
            "Repository info fetched",
            ToolActivityFormatter.format(ToolActivity.Completed("fetchRepoInfo", """{"name":"fd"}""")),
        )
        assertEquals(
            "SHA256 hash computed",
            ToolActivityFormatter.format(ToolActivity.Completed("computeSha256FromUrl", "abc123")),
        )
        assertEquals(
            "Manifest reviewed",
            ToolActivityFormatter.format(ToolActivity.Completed("__review_manifest__", "ok")),
        )
        assertEquals(
            "Release assets listed",
            ToolActivityFormatter.format(ToolActivity.Completed("listReleaseAssets", "ok")),
        )
    }

    @Test
    fun `Completed event with unknown tool formats as Finished toolName`() {
        assertEquals(
            "Finished customTool",
            ToolActivityFormatter.format(ToolActivity.Completed("customTool", "{\"name\":\"fd\"}")),
        )
    }

    @Test
    fun `Completed event for verify_install formats the install test outcome`() {
        assertEquals(
            "Install test passed",
            ToolActivityFormatter.format(ToolActivity.Completed("verify_install", """{"success":true}""")),
        )
        assertEquals(
            "Install test failed: budget exhausted",
            ToolActivityFormatter.format(ToolActivity.Completed("verify_install", """{"success":false,"error":"budget exhausted"}""")),
        )
        assertEquals(
            "Install test failed",
            ToolActivityFormatter.format(ToolActivity.Completed("verify_install", """{"success":false}""")),
        )
    }

    // ---------------------------------------------------------------
    // save_to_bucket (P2)
    // ---------------------------------------------------------------

    @Test
    fun `Started event for save_to_bucket formats as saving to bucket`() {
        assertEquals(
            "Saving to bucket...",
            ToolActivityFormatter.format(ToolActivity.Started("save_to_bucket", """{"bucket":"main"}""")),
        )
    }

    @Test
    fun `Completed event for save_to_bucket formats the save outcome`() {
        assertEquals(
            "Saved to bucket",
            ToolActivityFormatter.format(
                ToolActivity.Completed("save_to_bucket", """{"success":true,"path":"C:/scoop/buckets/main/fd.json"}"""),
            ),
        )
        assertEquals(
            "Save to bucket failed: bucket 'nope' does not exist",
            ToolActivityFormatter.format(
                ToolActivity.Completed("save_to_bucket", """{"success":false,"error":"bucket 'nope' does not exist"}"""),
            ),
        )
        assertEquals(
            "Save to bucket failed",
            ToolActivityFormatter.format(ToolActivity.Completed("save_to_bucket", """{"success":false}""")),
        )
    }

    // ---------------------------------------------------------------
    // install_app (P3)
    // ---------------------------------------------------------------

    @Test
    fun `Started event for install_app formats as installing app`() {
        assertEquals(
            "Installing app...",
            ToolActivityFormatter.format(ToolActivity.Started("install_app", """{"bucket":"main","name":"fd"}""")),
        )
    }

    @Test
    fun `Completed event for install_app formats the install outcome`() {
        assertEquals(
            "App installed",
            ToolActivityFormatter.format(
                ToolActivity.Completed("install_app", """{"success":true,"app":"fd","bucket":"main"}"""),
            ),
        )
        assertEquals(
            "App installed",
            ToolActivityFormatter.format(
                ToolActivity.Completed("install_app", """{"success":true,"message":"already installed in this session"}"""),
            ),
        )
        assertEquals(
            "Install failed: download failed",
            ToolActivityFormatter.format(
                ToolActivity.Completed("install_app", """{"success":false,"error":"download failed"}"""),
            ),
        )
        assertEquals(
            "Install failed",
            ToolActivityFormatter.format(ToolActivity.Completed("install_app", """{"success":false}""")),
        )
    }

    @Test
    fun `parseVerifyInstallResult returns true for a successful install`() {
        assertEquals(true, parseVerifyInstallResult("""{"success":true}""").success)
    }

    @Test
    fun `parseVerifyInstallResult returns false for a failed install`() {
        assertEquals(false, parseVerifyInstallResult("""{"success":false}""").success)
        assertEquals(false, parseVerifyInstallResult("""{"success":false,"error":"budget exhausted"}""").success)
    }

    @Test
    fun `parseVerifyInstallResult returns null for non-JSON input`() {
        assertEquals(null, parseVerifyInstallResult("not json").success)
        assertEquals(null, parseVerifyInstallResult("12345").success)
    }

    @Test
    fun `parseVerifyInstallResult returns null for blank or null result`() {
        assertEquals(null, parseVerifyInstallResult("").success)
        assertEquals(null, parseVerifyInstallResult("   ").success)
        assertEquals(null, parseVerifyInstallResult(null).success)
    }

    @Test
    fun `parseVerifyInstallResult returns null when success is missing or not boolean`() {
        assertEquals(null, parseVerifyInstallResult("""{"error":"x"}""").success)
        assertEquals(null, parseVerifyInstallResult("""{"success":"yes"}""").success)
    }

    @Test
    fun `parseVerifyInstallResult extracts the recorded error text`() {
        assertEquals(null, parseVerifyInstallResult("""{"success":false}""").error)
        assertEquals("budget exhausted", parseVerifyInstallResult("""{"success":false,"error":"budget exhausted"}""").error)
        assertEquals(null, parseVerifyInstallResult("not json").error)
    }

    @Test
    fun `Failed event formats tool error`() {
        val formatted = ToolActivityFormatter.format(ToolActivity.Failed("fetchRepoInfo", "Network timeout"))
        assertTrue(formatted.contains("fetchRepoInfo"))
        assertTrue(formatted.contains("failed"))
    }
}
