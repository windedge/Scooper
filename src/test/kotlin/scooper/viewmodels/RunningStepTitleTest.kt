package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// TDD for the running ASSIST step header: the title must show the live tool
// activity text (mirroring the line under the phase tag) and only fall back to
// the step's stored title when no activity is in flight.
class RunningStepTitleTest {

    // ---------------------------------------------------------------
    // fallback
    // ---------------------------------------------------------------

    @Test
    fun `null activity keeps the stored phase start title`() {
        val title = runningStepTitle(
            phase = "assist",
            currentActivity = null,
            fallbackTitle = "Working on your request...",
        )
        assertEquals("Working on your request...", title)
    }

    @Test
    fun `blank activity falls back to the stored phase start title`() {
        val title = runningStepTitle(
            phase = "assist",
            currentActivity = "   ",
            fallbackTitle = "Working on your request...",
        )
        assertEquals("Working on your request...", title)
    }

    @Test
    fun `non-assist phase keeps the stored phase start title`() {
        val title = runningStepTitle(
            phase = "research",
            currentActivity = "Fetching web page...",
            fallbackTitle = "Researching fd...",
        )
        assertEquals("Researching fd...", title)
    }

    // ---------------------------------------------------------------
    // live activity override
    // ---------------------------------------------------------------

    @Test
    fun `assist running step mirrors the current activity text`() {
        val title = runningStepTitle(
            phase = "assist",
            currentActivity = "Fetching web page...",
            fallbackTitle = "Working on your request...",
        )
        assertEquals("Fetching web page...", title)
    }

    @Test
    fun `activity text is trimmed before it becomes the title`() {
        val title = runningStepTitle("assist", "  Fetching web page...  ", "Working on your request...")
        assertEquals("Fetching web page...", title)
    }

    @Test
    fun `title is a single line with no newlines`() {
        val title = runningStepTitle("assist", "Fetching web page...", "Working on your request...")
        assertTrue('\n' !in title, "title must stay on one line")
    }
}
