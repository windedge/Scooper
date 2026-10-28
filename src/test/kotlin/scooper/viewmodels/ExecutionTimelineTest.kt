package scooper.viewmodels

import com.scoopai.core.ToolActivity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD tests for [ExecutionTimeline], the activity pump plus repair-round
 * bookkeeping seam extracted from CreateViewModel. The formatter is injected so
 * formatting failures can be exercised without touching the real formatter.
 */
class ExecutionTimelineTest {

    private fun timeline(format: (ToolActivity) -> String = { it.toolName }) =
        ExecutionTimeline(formatToolActivity = format)

    private fun started(name: String): ToolActivity = ToolActivity.Started(toolName = name, toolArgs = "{}")

    /** Collects [count] activities from [timeline], then stops the collector. */
    private suspend fun collectSome(timeline: ExecutionTimeline, count: Int): List<Pair<String, String>> {
        val received = mutableListOf<Pair<String, String>>()
        coroutineScope {
            val collector = launch {
                timeline.collect { activity, text -> received += activity.toolName to text }
            }
            withTimeout(2_000) {
                while (received.size < count) delay(5)
            }
            collector.cancel()
        }
        return received
    }

    // ---------------------------------------------------------------
    // offer / collect: ordered activity pump
    // ---------------------------------------------------------------

    @Test
    fun `collect delivers offered activities in order with formatted text`() = runBlocking {
        val timeline = timeline { activity -> "formatted:${activity.toolName}" }

        assertTrue(timeline.offer(1L, started("first")))
        assertTrue(timeline.offer(1L, started("second")))

        assertEquals(
            listOf("first" to "formatted:first", "second" to "formatted:second"),
            collectSome(timeline, 2),
        )
    }

    @Test
    fun `offer rejects activities of an older epoch`() = runBlocking {
        val timeline = timeline()
        timeline.setCurrentEpoch(2L)

        assertTrue(timeline.offer(2L, started("new")))
        assertFalse(timeline.offer(1L, started("stale")), "events of a superseded run must be dropped")

        assertEquals(listOf("new" to "new"), collectSome(timeline, 1))
    }

    @Test
    fun `buffered events of a superseded run are dropped once a new epoch is set`() = runBlocking {
        val timeline = timeline()
        timeline.setCurrentEpoch(1L)
        assertTrue(timeline.offer(1L, started("stale")))

        // A new run starts; its first event has not been offered yet, so the
        // stale one is still buffered and must not reach the collector.
        timeline.setCurrentEpoch(2L)
        assertTrue(timeline.offer(2L, started("fresh")))

        assertEquals(listOf("fresh" to "fresh"), collectSome(timeline, 1))
    }

    @Test
    fun `events are collected unchanged while no epoch has been set`() = runBlocking {
        val timeline = timeline()

        assertTrue(timeline.offer(1L, started("first")))
        assertTrue(timeline.offer(7L, started("second")))

        assertEquals(
            listOf("first" to "first", "second" to "second"),
            collectSome(timeline, 2),
        )
    }

    @Test
    fun `collect falls back to the tool name when the formatter throws`() = runBlocking {
        val timeline = timeline { throw IllegalStateException("formatter exploded") }

        timeline.offer(1L, started("verify_install"))

        assertEquals(listOf("verify_install" to "verify_install"), collectSome(timeline, 1))
    }

    // ---------------------------------------------------------------
    // drain: late-event race defense
    // ---------------------------------------------------------------

    @Test
    fun `drain discards buffered activities before a run is finalized`() = runBlocking {
        val timeline = timeline()
        timeline.offer(1L, started("buffered"))
        timeline.offer(1L, ToolActivity.Completed(toolName = "buffered", toolResult = "ok"))

        timeline.drain()

        val received = mutableListOf<String>()
        coroutineScope {
            val collector = launch { timeline.collect { activity, _ -> received += activity.toolName } }
            delay(100)
            collector.cancel()
        }
        assertTrue(received.isEmpty(), "drained events must not leak into the next state, got: $received")
    }

    // ---------------------------------------------------------------
    // repair rounds: session-scoped attempt numbering
    // ---------------------------------------------------------------

    @Test
    fun `repairAttempt counts repair phases only`() {
        val timeline = timeline()

        assertEquals(0, timeline.repairAttempt("research"))
        assertEquals(1, timeline.repairAttempt("repair"))
        assertEquals(2, timeline.repairAttempt("REPAIR"), "phase matching is case-insensitive")
        assertEquals(3, timeline.repairAttempt("repair"))
        assertEquals(3, timeline.repairAttempt("verify"), "non-repair phases keep the current count")
    }

    @Test
    fun `resetRounds restarts repair numbering for a new session`() {
        val timeline = timeline()
        timeline.repairAttempt("repair")
        timeline.repairAttempt("repair")

        timeline.resetRounds()

        assertEquals(1, timeline.repairAttempt("repair"))
    }
}
