package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD tests for the openchamber-style execution summary line that replaces the
 * old summary capsule: completed runs show duration plus a tool count, running
 * ones mirror the live activity (generic "Working..." once the detail list is
 * expanded, so the live text never renders twice), and the whole-run detail
 * list renders behind a single fold (expanded shows everything, collapsed
 * shows nothing).
 *
 * Also covers the thought-line helpers: an 80-char collapsed preview for the
 * model reasoning text and the streaming delta mapping, ready for the engine
 * event that will carry reasoning (the current UI pipeline has no such event).
 */
class ExecutionSummaryLineTest {

    // ---------------------------------------------------------------
    // executionSummaryLineText: completed wording
    // ---------------------------------------------------------------

    @Test
    fun `completed summary shows Worked for with duration and tool count`() {
        val steps = listOf(
            ExecutionStep(phase = "research", title = "r", durationMs = 1000L, toolActivities = listOf("✔ a", "✔ b")),
            ExecutionStep(phase = "analyze", title = "a", durationMs = 400L, toolActivities = listOf("✔ c")),
            ExecutionStep(phase = "generate", title = "g", durationMs = 2000L, toolActivities = listOf("✔ d", "✔ e")),
        )
        assertEquals("✓ Worked for 3.4s · 5 tools", executionSummaryLineText(steps))
    }

    @Test
    fun `summary with zero tools uses the no-tools wording`() {
        val steps = listOf(ExecutionStep(phase = "research", title = "r", durationMs = 850L))
        assertEquals("✓ Worked for 850ms · no tools", executionSummaryLineText(steps))
    }

    @Test
    fun `summary with a single tool uses the singular wording`() {
        val steps = listOf(
            ExecutionStep(phase = "research", title = "r", durationMs = 850L, toolActivities = listOf("✔ a")),
        )
        assertEquals("✓ Worked for 850ms · 1 tool", executionSummaryLineText(steps))
    }

    @Test
    fun `long durations render in minutes and seconds`() {
        val steps = listOf(
            ExecutionStep(phase = "research", title = "r", durationMs = 60_000L),
            ExecutionStep(phase = "generate", title = "g", durationMs = 23_000L, toolActivities = listOf("✔ a", "✔ b", "✔ c", "✔ d")),
        )
        assertEquals("✓ Worked for 1m 23s · 4 tools", executionSummaryLineText(steps))
    }

    @Test
    fun `failed summary uses the Failed wording with the same counts`() {
        val steps = listOf(
            ExecutionStep(phase = "research", title = "r", durationMs = 1000L),
            ExecutionStep(phase = "generate", title = "g", durationMs = 2400L, toolActivities = listOf("✖ boom")),
        )
        assertEquals("✗ Failed after 3.4s · 1 tool", executionSummaryLineText(steps, failed = true))
    }

    @Test
    fun `empty steps render zero duration and no tools`() {
        assertEquals("✓ Worked for 0ms · no tools", executionSummaryLineText(emptyList()))
    }

    // ---------------------------------------------------------------
    // executionSummaryLineText: running wording
    // ---------------------------------------------------------------

    @Test
    fun `running summary mirrors the live activity text`() {
        assertEquals(
            "Fetching web page...",
            executionSummaryLineText(emptyList(), running = true, currentActivity = "Fetching web page..."),
        )
    }

    @Test
    fun `running summary with no live activity falls back to Working`() {
        assertEquals("Working...", executionSummaryLineText(emptyList(), running = true, currentActivity = null))
        assertEquals("Working...", executionSummaryLineText(emptyList(), running = true, currentActivity = "   "))
    }

    @Test
    fun `running summary keeps the fallback even when settled steps exist`() {
        val steps = listOf(ExecutionStep(phase = "research", title = "r", durationMs = 1000L))
        assertEquals("Working...", executionSummaryLineText(steps, running = true, currentActivity = null))
        assertEquals(
            "Fetching web page...",
            executionSummaryLineText(steps, running = true, currentActivity = "Fetching web page..."),
        )
    }

    @Test
    fun `completed summary never shows the running fallback wording`() {
        assertEquals(
            "✓ Worked for 0ms · no tools",
            executionSummaryLineText(emptyList(), running = false, currentActivity = null),
        )
    }

    @Test
    fun `expanded running summary stays on Working instead of mirroring the live activity`() {
        // Expanded detail already renders the live activity inside the step
        // block; a mirrored summary line would print the same text twice.
        assertEquals(
            "Working...",
            executionSummaryLineText(
                emptyList(),
                running = true,
                expanded = true,
                currentActivity = "Fetching web page...",
            ),
        )
        assertEquals(
            "Working...",
            executionSummaryLineText(emptyList(), running = true, expanded = true, currentActivity = null),
        )
    }

    @Test
    fun `collapsed running summary keeps mirroring the live activity text`() {
        assertEquals(
            "Fetching web page...",
            executionSummaryLineText(
                emptyList(),
                running = true,
                expanded = false,
                currentActivity = "Fetching web page...",
            ),
        )
        // Default (collapsed) keeps the pre-existing behavior untouched.
        assertEquals(
            "Fetching web page...",
            executionSummaryLineText(emptyList(), running = true, currentActivity = "Fetching web page..."),
        )
    }

    // ---------------------------------------------------------------
    // visibleExecutionSteps: whole-run fold semantics
    // ---------------------------------------------------------------

    private val foldSteps = listOf(
        ExecutionStep(phase = "research", title = "r", toolActivities = listOf("✔ a")),
        ExecutionStep(phase = "verify", title = "v", isRunning = true),
    )

    @Test
    fun `collapsed fold hides every step`() {
        assertTrue(visibleExecutionSteps(foldSteps, expanded = false).isEmpty())
    }

    @Test
    fun `expanded fold shows every step untouched`() {
        assertEquals(foldSteps, visibleExecutionSteps(foldSteps, expanded = true))
    }

    @Test
    fun `collapsed fold on empty steps stays empty`() {
        assertTrue(visibleExecutionSteps(emptyList(), expanded = false).isEmpty())
    }

    // ---------------------------------------------------------------
    // thoughtSummary: 80-char collapsed preview
    // ---------------------------------------------------------------

    @Test
    fun `thought summary keeps short text unchanged`() {
        assertEquals("Let me check the repo", thoughtSummary("Let me check the repo"))
    }

    @Test
    fun `thought summary keeps text of exactly 80 chars unchanged`() {
        val text = "x".repeat(80)
        assertEquals(text, thoughtSummary(text))
    }

    @Test
    fun `thought summary truncates longer text to 80 chars with an ellipsis`() {
        val text = "y".repeat(120)
        assertEquals("y".repeat(80) + "...", thoughtSummary(text))
        assertEquals(83, thoughtSummary(text)?.length)
    }

    @Test
    fun `thought summary collapses newlines and trims whitespace`() {
        assertEquals("a b c", thoughtSummary("  a\nb\n  c  "))
    }

    @Test
    fun `thought summary is null for blank or null input`() {
        assertNull(thoughtSummary(null))
        assertNull(thoughtSummary("   "))
    }

    // ---------------------------------------------------------------
    // thoughtLineFromText: streaming delta mapping
    // ---------------------------------------------------------------

    @Test
    fun `thought line starts with the first delta`() {
        val line = thoughtLineFromText(null, "Thinking")
        assertEquals("Thinking", line.text)
        assertTrue(line.hasText)
    }

    @Test
    fun `thought line appends later deltas`() {
        val line = thoughtLineFromText(ThoughtLine(text = "Thinking", hasText = true), " hard")
        assertEquals("Thinking hard", line.text)
        assertTrue(line.hasText)
    }

    @Test
    fun `thought line ignores blank deltas`() {
        val kept = thoughtLineFromText(ThoughtLine(text = "Thinking", hasText = true), "  ")
        assertEquals("Thinking", kept.text)
        assertTrue(kept.hasText)
        val untouched = thoughtLineFromText(null, "")
        assertEquals("", untouched.text)
        assertFalse(untouched.hasText)
    }

    @Test
    fun `thought line reports whether the full text exists`() {
        assertTrue(thoughtLineFromText(ThoughtLine(text = "abc", hasText = true), null).hasText)
        assertFalse(thoughtLineFromText(null, null).hasText)
    }
}
