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
    fun `Failed event formats tool error`() {
        val formatted = ToolActivityFormatter.format(ToolActivity.Failed("fetchRepoInfo", "Network timeout"))
        assertTrue(formatted.contains("fetchRepoInfo"))
        assertTrue(formatted.contains("failed"))
    }
}
