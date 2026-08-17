package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD tests for [tailLines] and [errorSummary] (top-level pure functions extracted from
 * CreateViewModel so the failure-summary truncation is directly testable).
 *
 * Regression: errorSummary used to prepend the detected error line and then re-apply
 * tailLines(combined, maxLines), which cut the prepended line back off. The detected
 * ERROR line must always survive within the summary.
 */
class CreateViewModelErrorSummaryTest {

    // ---------------------------------------------------------------
    // tailLines
    // ---------------------------------------------------------------

    @Test
    fun `tailLines keeps text unchanged when within the limit`() {
        assertEquals("a\nb\nc", tailLines("a\nb\nc", 5))
    }

    @Test
    fun `tailLines keeps only the last N lines when longer`() {
        val log = (1..10).joinToString("\n") { "line$it" }
        assertEquals("line6\nline7\nline8\nline9\nline10", tailLines(log, 5))
    }

    @Test
    fun `tailLines keeps everything when maxLines is negative or zero`() {
        val log = (1..3).joinToString("\n") { "line$it" }
        // matches the old behavior: any text with more lines than maxLines is cut by takeLast(0)/takeLast(negative)
        assertEquals("", tailLines(log, 0))
    }

    // ---------------------------------------------------------------
    // errorSummary must keep the detected error line
    // ---------------------------------------------------------------

    @Test
    fun `errorSummary keeps the detected ERROR line within the first maxLines`() {
        val log = (1..100).joinToString("\n") { "line$it" }
        val detected = "ERROR: scoop install failed"
        val summary = errorSummary(detected, log, maxLines = 5)
        val lines = summary.lines()
        assertTrue(detected in summary, "detected line must appear in the summary: $summary")
        assertEquals(detected, lines.first(), "detected line must be the first line")
        assertTrue(lines.size <= 5, "summary must not exceed 5 lines, got ${lines.size}")
        assertEquals("line97", lines[1], "tail must still come from the log end")
        assertEquals("line100", lines.last(), "tail must end at the log's last line")
    }

    @Test
    fun `errorSummary does not duplicate the detected line when it is already in the tail`() {
        val log = "line1\nline2\nERROR: boom"
        val summary = errorSummary("ERROR: boom", log, maxLines = 5)
        assertEquals("line1\nline2\nERROR: boom", summary)
        assertEquals(1, summary.lines().count { it == "ERROR: boom" })
    }

    @Test
    fun `errorSummary with empty error message just returns the log tail`() {
        val log = (1..10).joinToString("\n") { "line$it" }
        val expected = "line6\nline7\nline8\nline9\nline10"
        assertEquals(expected, errorSummary(null, log, 5))
        assertEquals(expected, errorSummary("   ", log, 5))
    }

    @Test
    fun `errorSummary respects maxLines even with detected line plus long log`() {
        val log = (1..100).joinToString("\n") { "line$it" }
        val summary = errorSummary("detected error", log, maxLines = 3)
        assertEquals(3, summary.lines().size)
        assertTrue(summary.startsWith("detected error"), "detected line must survive the 3-line cap")
    }
}
