package scooper.viewmodels

import com.scoopai.core.TokenUsage
import com.scoopai.core.ToolActivity
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.service.PhaseSummaryFormatter

/**
 * TDD tests for the transient-execution aggregation pure functions extracted from
 * CreateViewModel (architecture proposal 5.1: ActiveExecution vs finalized ChatMessage).
 *
 * The functions aggregate engine events (onPhase / onToolActivity / onPhaseOutput) into
 * [ActiveExecution] steps and fold the transient state into a finalized message with
 * collapsed/expanded execution steps (success collapses, failure expands).
 */
class CreateViewModelExecutionTest {

    // ---------------------------------------------------------------
    // appendPhaseStep: phase start aggregation
    // ---------------------------------------------------------------

    @Test
    fun `appendPhaseStep on null active creates a first running step`() {
        val active = appendPhaseStep(
            active = null,
            phase = "research",
            title = "Researching repository metadata...",
            nowMs = 1000L,
        )
        assertEquals(1, active.steps.size)
        val step = active.steps.first()
        assertEquals("research", step.phase)
        assertEquals("Researching repository metadata...", step.title)
        assertTrue(step.isRunning)
        assertEquals(0L, step.durationMs)
        assertEquals(1000L, active.currentStepStartedAtMs)
        assertNull(active.currentActivity)
    }

    @Test
    fun `appendPhaseStep closes the previous running step with durationMs from nowMs`() {
        val first = appendPhaseStep(null, "research", "Researching...", nowMs = 1000L)
        val second = appendPhaseStep(first, "analyze", "Analyzing...", nowMs = 3500L)

        assertEquals(2, second.steps.size)
        val research = second.steps[0]
        assertFalse(research.isRunning, "previous step must be closed")
        assertEquals(2500L, research.durationMs, "durationMs = nowMs - currentStepStartedAtMs")

        val analyze = second.steps[1]
        assertTrue(analyze.isRunning)
        assertEquals(0L, analyze.durationMs)
        assertEquals(3500L, second.currentStepStartedAtMs)
        assertNull(second.currentActivity, "phase switch resets the live activity line")
    }

    @Test
    fun `appendPhaseStep with same phase re-entry keeps a single running step`() {
        // startGeneration pre-creates the initial phase step; the engine then fires
        // onPhase for the same phase. No duplicate step must be created.
        val first = appendPhaseStep(null, "research", "Researching...", nowMs = 1000L)
        val again = appendPhaseStep(first, "research", "Researching...", nowMs = 1200L)

        assertEquals(1, again.steps.size)
        assertTrue(again.steps.first().isRunning)
        assertEquals("Researching...", again.steps.first().title)
    }

    // ---------------------------------------------------------------
    // RepairRoundCounter: session-scoped repair attempt tracking
    // ---------------------------------------------------------------

    @Test
    fun `repair counter increments per round and resets on a new session`() {
        val counter = RepairRoundCounter()
        assertEquals(1, counter.onPhase("repair"), "first repair round must show attempt 1")
        assertEquals(2, counter.onPhase("repair"), "second repair round must show attempt 2")
        assertEquals(3, counter.onPhase("repair"), "third repair round must show attempt 3")

        counter.reset()
        assertEquals(1, counter.onPhase("repair"), "new session must reset the counter back to 1")
    }

    @Test
    fun `repair counter ignores non-repair phases`() {
        val counter = RepairRoundCounter()
        assertEquals(0, counter.onPhase("research"), "counter starts at zero before any repair phase")
        assertEquals(0, counter.onPhase("analyze"), "non-repair phases must not advance the counter")
        assertEquals(1, counter.onPhase("repair"), "first repair phase after other phases still shows attempt 1")
    }

    @Test
    fun `repair counter renders the attempt label through formatPhaseStart`() {
        val counter = RepairRoundCounter()
        assertEquals(
            "Repairing manifest (attempt 1)...",
            PhaseSummaryFormatter.formatPhaseStart("repair", "fd", attempt = counter.onPhase("repair")),
        )
        assertEquals(
            "Repairing manifest (attempt 2)...",
            PhaseSummaryFormatter.formatPhaseStart("repair", "fd", attempt = counter.onPhase("repair")),
        )
    }

    // ---------------------------------------------------------------
    // attachToolActivity: tool activity ownership and dedup
    // ---------------------------------------------------------------

    @Test
    fun `attachToolActivity Started updates currentActivity without touching steps`() {
        val active = appendPhaseStep(null, "research", "Researching...", nowMs = 1000L)
        val updated = attachToolActivity(
            active,
            ToolActivity.Started(toolName = "fetchRepoInfo", toolArgs = "{}"),
            "Querying repository info...",
        )
        assertEquals("Querying repository info...", updated?.currentActivity)
        assertEquals(active.steps, updated?.steps)
    }

    @Test
    fun `attachToolActivity Completed appends check-prefixed line to the last step`() {
        val active = appendPhaseStep(null, "research", "Researching...", nowMs = 1000L)
        val updated = attachToolActivity(
            active,
            ToolActivity.Completed(toolName = "fetchRepoInfo", toolResult = "ok"),
            "Repository info fetched",
        )
        assertEquals(listOf("✔ Repository info fetched"), updated?.steps?.last()?.toolActivities)
        assertNull(updated?.currentActivity)
    }

    @Test
    fun `attachToolActivity Failed appends cross-prefixed line to the last step`() {
        val active = appendPhaseStep(null, "research", "Researching...", nowMs = 1000L)
        val updated = attachToolActivity(
            active,
            ToolActivity.Failed(toolName = "fetchLatestRelease", error = "404"),
            "Tool fetchLatestRelease failed: 404",
        )
        assertEquals(listOf("✖ Tool fetchLatestRelease failed: 404"), updated?.steps?.last()?.toolActivities)
    }

    @Test
    fun `attachToolActivity dedupes repeated completed lines`() {
        val active = appendPhaseStep(null, "research", "Researching...", nowMs = 1000L)
        val activity = ToolActivity.Completed(toolName = "fetchRepoInfo", toolResult = "ok")
        val once = attachToolActivity(active, activity, "Repository info fetched")
        val twice = attachToolActivity(once, activity, "Repository info fetched")
        assertEquals(1, twice?.steps?.last()?.toolActivities?.size, "duplicate lines must be dropped")
    }

    @Test
    fun `attachToolActivity appends to the most recent step`() {
        val research = appendPhaseStep(null, "research", "Researching...", nowMs = 1000L)
        val analyze = appendPhaseStep(research, "analyze", "Analyzing...", nowMs = 2000L)
        val updated = attachToolActivity(
            analyze,
            ToolActivity.Completed(toolName = "computeSha256FromUrl", toolResult = "abc"),
            "SHA256 hash computed",
        )
        assertEquals(0, updated?.steps?.first()?.toolActivities?.size)
        assertEquals(listOf("✔ SHA256 hash computed"), updated?.steps?.last()?.toolActivities)
    }

    @Test
    fun `attachToolActivity on null active returns null`() {
        assertNull(
            attachToolActivity(
                null,
                ToolActivity.Started(toolName = "fetchRepoInfo", toolArgs = ""),
                "Querying repository info...",
            ),
        )
    }

    @Test
    fun `attachToolActivity Completed clears the live line left by a Started event`() {
        val active = appendPhaseStep(null, "research", "Researching...", nowMs = 1000L)
        val started = attachToolActivity(
            active,
            ToolActivity.Started(toolName = "fetchRepoInfo", toolArgs = "{}"),
            "Querying repository info...",
        )
        assertEquals("Querying repository info...", started?.currentActivity)
        val completed = attachToolActivity(
            started,
            ToolActivity.Completed(toolName = "fetchRepoInfo", toolResult = "ok"),
            "Repository info fetched",
        )
        assertNull(
            completed?.currentActivity,
            "Completed replaces the live line with the durable check line; the stale text must not linger",
        )
        assertEquals(listOf("✔ Repository info fetched"), completed?.steps?.last()?.toolActivities)
    }

    @Test
    fun `attachToolActivity Failed clears the live line left by a Started event`() {
        val active = appendPhaseStep(null, "generate", "Generating...", nowMs = 1000L)
        val started = attachToolActivity(
            active,
            ToolActivity.Started(toolName = "writeManifest", toolArgs = "{}"),
            "Writing manifest...",
        )
        val failed = attachToolActivity(
            started,
            ToolActivity.Failed(toolName = "writeManifest", error = "disk full"),
            "Tool writeManifest failed: disk full",
        )
        assertNull(
            failed?.currentActivity,
            "Failed replaces the live line with the durable cross line; the stale text must not linger",
        )
    }

    // ---------------------------------------------------------------
    // setPhaseSummary: phase output ownership
    // ---------------------------------------------------------------

    @Test
    fun `setPhaseSummary writes summary onto the last step of the matching phase`() {
        var active = appendPhaseStep(null, "verify", "Validating manifest...", nowMs = 1000L)
        active = appendPhaseStep(active, "assist", "Refining...", nowMs = 2000L)
        active = appendPhaseStep(active, "verify", "Validating manifest...", nowMs = 3000L)

        val updated = setPhaseSummary(active, "verify", "Generated manifest v1.0.0 (64bit)")
        assertEquals("Generated manifest v1.0.0 (64bit)", updated?.steps?.last()?.summary)
        // The earlier verify step was closed on the phase switch, so it already
        // carries the synthetic closing summary; setPhaseSummary must not touch it.
        assertEquals(VERIFY_STEP_SUMMARY, updated?.steps?.first()?.summary)
    }

    @Test
    fun `setPhaseSummary with unknown phase leaves steps unchanged`() {
        val active = appendPhaseStep(null, "research", "Researching...", nowMs = 1000L)
        val updated = setPhaseSummary(active, "generate", "Generated manifest")
        assertEquals(active, updated)
    }

    @Test
    fun `setPhaseSummary on null active returns null`() {
        assertNull(setPhaseSummary(null, "generate", "Generated manifest"))
    }

    // ---------------------------------------------------------------
    // markAnsweredSummary: direct-answer finish summary clear
    // ---------------------------------------------------------------

    @Test
    fun `markAnsweredSummary clears the last assist step summary`() {
        val steps = listOf(
            ExecutionStep(phase = "research", title = "Researching...", summary = "Found metadata"),
            ExecutionStep(
                phase = "assist",
                title = "Refining...",
                summary = "Updated manifest v0.30.0 (64bit, shortcuts configured)",
            ),
        )

        val updated = markAnsweredSummary(steps)

        assertNull(updated[1].summary)
        assertEquals("Found metadata", updated[0].summary, "non-assist steps keep their summary")
    }

    @Test
    fun `markAnsweredSummary leaves steps without an assist step unchanged`() {
        val steps = listOf(
            ExecutionStep(phase = "verify", title = "Validating...", summary = "Manifest validated"),
        )

        assertEquals(steps, markAnsweredSummary(steps))
    }

    @Test
    fun `markAnsweredSummary on an empty list returns it unchanged`() {
        assertEquals(emptyList<ExecutionStep>(), markAnsweredSummary(emptyList()))
    }

    @Test
    fun `answered finish collapses the cleared assist summary into the finalized message`() {
        // Mirrors the handleFinished answered branch order: clear the assist step
        // summary first, then fold the active execution into the finalized message.
        var active = appendPhaseStep(null, "assist", "Refining...", nowMs = 1000L)
        active = setPhaseSummary(active, "assist", "Updated manifest v0.30.0 (64bit, shortcuts configured)")!!
        active = active.copy(steps = markAnsweredSummary(active.steps))

        val messages = listOf(ChatMessage(role = ChatRole.Agent, text = "Answered the question."))
        val collapsed = collapseActiveExecution(messages, active, expanded = false, nowMs = 2000L)

        val steps = collapsed.single().executionSteps
        assertEquals(1, steps.size)
        assertNull(steps.single().summary)
    }

    // ---------------------------------------------------------------
    // collapseActiveExecution: transient state folding into a message
    // ---------------------------------------------------------------

    private fun sampleActive(): ActiveExecution {
        val research = appendPhaseStep(null, "research", "Researching...", nowMs = 1000L)
        val analyze = appendPhaseStep(research, "analyze", "Analyzing...", nowMs = 2000L)
        return analyze.copy(
            steps = analyze.steps.map { it.copy(isRunning = it.phase == "analyze") },
        )
    }

    @Test
    fun `collapseActiveExecution attaches steps to the last agent message with running flags cleared`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "https://github.com/sharkdp/fd"),
            ChatMessage(role = ChatRole.Agent, text = "Done. Manifest ready."),
        )
        val collapsed = collapseActiveExecution(messages, sampleActive(), expanded = false, nowMs = 2500L)
        val agentMessage = collapsed.last()
        assertEquals(2, agentMessage.executionSteps.size)
        assertTrue(agentMessage.executionSteps.none { it.isRunning }, "all steps must be closed on collapse")
        assertFalse(agentMessage.isExecutionExpanded, "success collapses the execution card")
        // user message untouched
        assertEquals(0, collapsed.first().executionSteps.size)
    }

    @Test
    fun `collapseActiveExecution with expanded true marks the message expanded for failures`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.Agent, text = "Generation failed."),
        )
        val collapsed = collapseActiveExecution(messages, sampleActive(), expanded = true, nowMs = 2500L)
        assertTrue(collapsed.last().isExecutionExpanded, "failure/exhausted auto-expands the execution card")
    }

    @Test
    fun `collapseActiveExecution without active execution returns messages unchanged`() {
        val messages = listOf(ChatMessage(role = ChatRole.Agent, text = "hello"))
        assertEquals(messages, collapseActiveExecution(messages, null, expanded = false, nowMs = 0L))
        assertEquals(messages, collapseActiveExecution(messages, ActiveExecution(), expanded = false, nowMs = 0L))
    }

    @Test
    fun `collapseActiveExecution settles the last running step durationMs from nowMs`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "url"),
            ChatMessage(role = ChatRole.Agent, text = "Done."),
        )
        // sampleActive() leaves the analyze step running; its step started at 2000ms.
        val collapsed = collapseActiveExecution(messages, sampleActive(), expanded = false, nowMs = 5500L)
        val analyze = collapsed.last().executionSteps.last()
        assertFalse(analyze.isRunning)
        assertEquals(
            3500L,
            analyze.durationMs,
            "the last running step (usually verify) must settle durationMs = nowMs - currentStepStartedAtMs",
        )
    }

    @Test
    fun `collapseActiveExecution leaves settled durations untouched when no step is running`() {
        val messages = listOf(ChatMessage(role = ChatRole.Agent, text = "Done."))
        val research = appendPhaseStep(null, "research", "Researching...", nowMs = 1000L)
        // All steps already closed and settled: collapse must not re-settle durations.
        val closed = research.copy(
            steps = research.steps.map { it.copy(isRunning = false, durationMs = 777L) },
        )
        val collapsed = collapseActiveExecution(messages, closed, expanded = false, nowMs = 9999L)
        assertEquals(
            777L,
            collapsed.single().executionSteps.single().durationMs,
            "without a running step the settled durationMs must stay untouched",
        )
    }

    // ---------------------------------------------------------------
    // verify step summary: the engine emits onPhase for verify but never
    // onPhaseOutput, so the UI aggregation layer fills the summary itself.
    // ---------------------------------------------------------------

    @Test
    fun `appendPhaseStep closing a running verify step writes the validated summary`() {
        val verify = appendPhaseStep(null, "verify", "Validating manifest...", nowMs = 1000L)
        val assist = appendPhaseStep(verify, "assist", "Refining...", nowMs = 2000L)

        assertEquals("Manifest validated", assist.steps.first().summary)
    }

    @Test
    fun `appendPhaseStep matches the verify phase case-insensitively`() {
        val verify = appendPhaseStep(null, "VERIFY", "Validating manifest...", nowMs = 1000L)
        val assist = appendPhaseStep(verify, "assist", "Refining...", nowMs = 2000L)

        assertEquals("Manifest validated", assist.steps.first().summary)
    }

    @Test
    fun `collapseActiveExecution settles the final running verify step with the validated summary`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "url"),
            ChatMessage(role = ChatRole.Agent, text = "Done. Manifest ready."),
        )
        val active = appendPhaseStep(null, "verify", "Validating manifest...", nowMs = 1000L)

        val collapsed = collapseActiveExecution(messages, active, expanded = false, nowMs = 1500L)

        assertEquals(
            "Manifest validated",
            collapsed.last().executionSteps.single().summary,
            "verify is usually the last phase and is only closed by collapseActiveExecution",
        )
    }

    @Test
    fun `an existing verify summary is never overwritten by the validated marker`() {
        val verify = appendPhaseStep(null, "verify", "Validating manifest...", nowMs = 1000L)
        val withSummary = setPhaseSummary(verify, "verify", "Validation finished with issues")

        val assist = appendPhaseStep(withSummary, "assist", "Refining...", nowMs = 2000L)

        assertEquals("Validation finished with issues", assist.steps.first().summary)
    }

    @Test
    fun `non verify steps are closed without a synthetic summary`() {
        val research = appendPhaseStep(null, "research", "Researching...", nowMs = 1000L)
        val analyze = appendPhaseStep(research, "analyze", "Analyzing...", nowMs = 2000L)

        assertNull(analyze.steps.first().summary, "only verify gets the synthetic summary")
    }

    @Test
    fun `a verify step with a failed tool activity gets no validated summary`() {
        val verify = appendPhaseStep(null, "verify", "Validating manifest...", nowMs = 1000L)
        val failed = attachToolActivity(
            verify,
            ToolActivity.Failed(toolName = "validateManifest", error = "schema"),
            "Tool validateManifest failed: schema",
        )

        val assist = appendPhaseStep(failed, "assist", "Refining...", nowMs = 2000L)
        val collapsed = collapseActiveExecution(
            listOf(ChatMessage(role = ChatRole.Agent, text = "failed")),
            assist,
            expanded = true,
            nowMs = 2500L,
        )

        assertNull(
            collapsed.single().executionSteps.first().summary,
            "a verify step carrying a cross line must not claim the manifest validated",
        )
        assertNull(collapsed.single().executionSteps.last().summary)
    }

    // ---------------------------------------------------------------
    // drainActivityEvents: late-event race defense
    // ---------------------------------------------------------------

    @Test
    fun `drainActivityEvents discards residual buffered events`() {
        val channel = Channel<Pair<ToolActivity, Long>>(Channel.UNLIMITED)
        channel.trySend(ToolActivity.Started("fetchRepoInfo", "{}") to 1L)
        channel.trySend(ToolActivity.Completed("fetchRepoInfo", "ok") to 1L)
        channel.trySend(ToolActivity.Started("writeManifest", "{}") to 1L)

        drainActivityEvents(channel)

        assertTrue(channel.tryReceive().isFailure, "channel must be empty after drain")
        channel.close()
    }

    @Test
    fun `drainActivityEvents on an empty channel returns immediately`() {
        val channel = Channel<Pair<ToolActivity, Long>>(Channel.UNLIMITED)
        drainActivityEvents(channel)
        assertTrue(channel.tryReceive().isFailure)
        channel.close()
    }

    // ---------------------------------------------------------------
    // formatStepDuration: step duration rendering
    // ---------------------------------------------------------------

    @Test
    fun `formatStepDuration renders milliseconds below one second`() {
        assertEquals("0ms", formatStepDuration(0L))
        assertEquals("850ms", formatStepDuration(850L))
    }

    @Test
    fun `formatStepDuration renders seconds with one decimal`() {
        assertEquals("3.4s", formatStepDuration(3400L))
        assertEquals("59.9s", formatStepDuration(59900L))
    }

    @Test
    fun `formatStepDuration renders minutes and seconds beyond one minute`() {
        assertEquals("1m 5s", formatStepDuration(65000L))
        assertEquals("2m 0s", formatStepDuration(120000L))
    }

    // ---------------------------------------------------------------
    // Stage 3: toggleExecutionExpanded (review accordion state)
    // ---------------------------------------------------------------

    @Test
    fun `toggleExecutionExpanded flips the matching message flag`() {
        val target = ChatMessage(role = ChatRole.Agent, text = "done", isExecutionExpanded = false)
        val other = ChatMessage(role = ChatRole.Agent, text = "earlier")

        val updated = toggleExecutionExpanded(listOf(other, target), target.id)

        assertTrue(updated[1].isExecutionExpanded)
        assertFalse(updated[0].isExecutionExpanded, "other messages stay untouched")
    }

    @Test
    fun `toggleExecutionExpanded collapses an expanded message on second toggle`() {
        val target = ChatMessage(role = ChatRole.Agent, text = "failed", isExecutionExpanded = true)

        val updated = toggleExecutionExpanded(listOf(target), target.id)

        assertFalse(updated.single().isExecutionExpanded)
    }

    @Test
    fun `toggleExecutionExpanded with unknown id returns messages unchanged`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "url"),
            ChatMessage(role = ChatRole.Agent, text = "reply"),
        )
        assertEquals(messages, toggleExecutionExpanded(messages, "no-such-id"))
    }

    @Test
    fun `toggleExecutionExpanded keeps the message instance untouched for others`() {
        val target = ChatMessage(role = ChatRole.Agent, text = "a")
        val other = ChatMessage(role = ChatRole.Agent, text = "b")

        val updated = toggleExecutionExpanded(listOf(target, other), target.id)

        assertEquals(other, updated[1], "non-target instances must be preserved as-is")
    }

    // ---------------------------------------------------------------
    // Stage 3: shouldRenderFullExecutionSteps (recent rounds only)
    // ---------------------------------------------------------------

    private fun agentWithSteps(text: String): ChatMessage = ChatMessage(
        role = ChatRole.Agent,
        text = text,
        executionSteps = listOf(ExecutionStep(phase = "research", title = "Researching...")),
    )

    @Test
    fun `shouldRenderFullExecutionSteps allows the recent three stepped agent messages`() {
        val m1 = agentWithSteps("one")
        val m2 = agentWithSteps("two")
        val m3 = agentWithSteps("three")
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "q1"), m1,
            ChatMessage(role = ChatRole.User, text = "q2"), m2,
            ChatMessage(role = ChatRole.User, text = "q3"), m3,
        )

        assertTrue(shouldRenderFullExecutionSteps(messages, m1.id))
        assertTrue(shouldRenderFullExecutionSteps(messages, m2.id))
        assertTrue(shouldRenderFullExecutionSteps(messages, m3.id))
    }

    @Test
    fun `shouldRenderFullExecutionSteps rejects rounds older than the recent window`() {
        val old1 = agentWithSteps("old1")
        val old2 = agentWithSteps("old2")
        val m3 = agentWithSteps("m3")
        val m4 = agentWithSteps("m4")
        val m5 = agentWithSteps("m5")
        val messages = listOf(old1, old2, m3, m4, m5)

        assertFalse(shouldRenderFullExecutionSteps(messages, old1.id))
        assertFalse(shouldRenderFullExecutionSteps(messages, old2.id))
        assertTrue(shouldRenderFullExecutionSteps(messages, m3.id))
        assertTrue(shouldRenderFullExecutionSteps(messages, m4.id))
        assertTrue(shouldRenderFullExecutionSteps(messages, m5.id))
    }

    @Test
    fun `shouldRenderFullExecutionSteps honors a custom recentCount`() {
        val m1 = agentWithSteps("one")
        val m2 = agentWithSteps("two")
        val messages = listOf(m1, m2)

        assertFalse(shouldRenderFullExecutionSteps(messages, m1.id, recentCount = 1))
        assertTrue(shouldRenderFullExecutionSteps(messages, m2.id, recentCount = 1))
    }

    @Test
    fun `shouldRenderFullExecutionSteps ignores messages without execution steps`() {
        val plain = ChatMessage(role = ChatRole.Agent, text = "no steps")
        val stepped = agentWithSteps("steps")
        val messages = listOf(plain, stepped)

        assertFalse(shouldRenderFullExecutionSteps(messages, plain.id))
        assertTrue(shouldRenderFullExecutionSteps(messages, stepped.id))
    }

    @Test
    fun `shouldRenderFullExecutionSteps with unknown id returns false`() {
        val messages = listOf(agentWithSteps("one"))
        assertFalse(shouldRenderFullExecutionSteps(messages, "no-such-id"))
    }

    // ---------------------------------------------------------------
    // Stage 3: isFailedExecutionStep (failure highlight predicate)
    // ---------------------------------------------------------------

    @Test
    fun `isFailedExecutionStep detects a cross-prefixed tool activity line`() {
        val step = ExecutionStep(
            phase = "generate",
            title = "Generating...",
            toolActivities = listOf("✔ ok", "✖ Tool fetchLatestRelease failed: 404"),
        )
        assertTrue(isFailedExecutionStep(step, messageHasActions = false))
    }

    @Test
    fun `isFailedExecutionStep is false for clean steps without message actions`() {
        val step = ExecutionStep(
            phase = "research",
            title = "Researching...",
            toolActivities = listOf("✔ Repository info fetched"),
        )
        assertFalse(isFailedExecutionStep(step, messageHasActions = false))
    }

    @Test
    fun `isFailedExecutionStep is true for any step of an error message`() {
        val step = ExecutionStep(phase = "verify", title = "Validating...")
        assertTrue(isFailedExecutionStep(step, messageHasActions = true))
    }

    @Test
    fun `executionSummaryCapsuleText sums step durations and pluralizes steps`() {
        val steps = listOf(
            ExecutionStep(phase = "research", title = "r", durationMs = 1000L),
            ExecutionStep(phase = "analyze", title = "a", durationMs = 400L),
            ExecutionStep(phase = "generate", title = "g", durationMs = 2000L),
        )
        assertEquals("✓ Completed in 3.4s · 3 steps", executionSummaryCapsuleText(steps))
    }

    @Test
    fun `executionSummaryCapsuleText uses singular step for a single entry`() {
        val steps = listOf(ExecutionStep(phase = "assist", title = "r", durationMs = 850L))
        assertEquals("✓ Completed in 850ms · 1 step", executionSummaryCapsuleText(steps))
    }

    @Test
    fun `executionSummaryCapsuleText renders zero duration for empty steps`() {
        assertEquals("✓ Completed in 0ms · 0 steps", executionSummaryCapsuleText(emptyList()))
    }

    @Test
    fun `executionSummaryCapsuleText with failed true uses cross and Failed wording`() {
        val steps = listOf(
            ExecutionStep(phase = "research", title = "r", durationMs = 60_000L),
            ExecutionStep(phase = "generate", title = "g", durationMs = 19_000L),
        )
        assertEquals("✗ Failed in 1m 19s · 2 steps", executionSummaryCapsuleText(steps, failed = true))
    }

    @Test
    fun `executionSummaryCapsuleText failed defaults to completed wording`() {
        val steps = listOf(ExecutionStep(phase = "verify", title = "v", durationMs = 1_200L))
        assertEquals("✓ Completed in 1.2s · 1 step", executionSummaryCapsuleText(steps))
        assertEquals("✗ Failed in 1.2s · 1 step", executionSummaryCapsuleText(steps, failed = true))
    }

    // ---------------------------------------------------------------
    // Token usage display: compact count and bubble footer
    // ---------------------------------------------------------------

    @Test
    fun `formatTokenCount renders values below one thousand as-is`() {
        assertEquals("980", formatTokenCount(980L))
        assertEquals("0", formatTokenCount(0L))
    }

    @Test
    fun `formatTokenCount renders thousands with one decimal`() {
        assertEquals("1.0k", formatTokenCount(1000L))
        assertEquals("1.8k", formatTokenCount(1846L))
    }

    @Test
    fun `formatTokenCount renders millions with one decimal`() {
        assertEquals("1.2M", formatTokenCount(1234567L))
    }

    @Test
    fun `formatTokenFooter renders input and output with compact counts`() {
        assertEquals("1.2k in · 567 out tokens", formatTokenFooter(TokenUsage(1234, 567)))
    }

    @Test
    fun `formatTokenFooter returns null for zero totals or null usage`() {
        assertNull(formatTokenFooter(TokenUsage(0, 0)))
        assertNull(formatTokenFooter(null))
    }

    // ---------------------------------------------------------------
    // agentReplyMessage: final agent bubble carries the run tokenUsage
    // ---------------------------------------------------------------

    @Test
    fun `agentReplyMessage attaches non-zero token usage to the bubble`() {
        val message = agentReplyMessage("Done.", TokenUsage(1234, 567))
        assertEquals(ChatRole.Agent, message.role)
        assertEquals("Done.", message.text)
        assertEquals(TokenUsage(1234, 567), message.tokenUsage)
    }

    @Test
    fun `agentReplyMessage drops zero and null token usage`() {
        assertNull(agentReplyMessage("Done.", TokenUsage(0, 0)).tokenUsage)
        assertNull(agentReplyMessage("Done.", null).tokenUsage)
    }

    // ---------------------------------------------------------------
    // AI verify highlight dismissal: manifestTestSucceeded
    // ---------------------------------------------------------------

    @Test
    fun `manifestTestSucceeded is true after an AI verify install succeeded`() {
        assertTrue(manifestTestSucceeded(installVerified = true, manifestSaved = false))
    }

    @Test
    fun `manifestTestSucceeded is true once the manifest is saved`() {
        assertTrue(manifestTestSucceeded(installVerified = false, manifestSaved = true))
    }

    @Test
    fun `manifestTestSucceeded is false before any verification or save`() {
        assertFalse(manifestTestSucceeded(false, false))
    }

    // ---------------------------------------------------------------
    // Save to Bucket primary nudge: saveToBucketPrimary
    // ---------------------------------------------------------------

    @Test
    fun `saveToBucketPrimary is true after a passing test install before save`() {
        assertTrue(saveToBucketPrimary(testSucceeded = true, manifestSaved = false))
    }

    @Test
    fun `saveToBucketPrimary is false once the manifest is saved`() {
        assertFalse(saveToBucketPrimary(testSucceeded = true, manifestSaved = true))
    }

    @Test
    fun `saveToBucketPrimary is false before any test install`() {
        assertFalse(saveToBucketPrimary(testSucceeded = false, manifestSaved = false))
    }

    // ---------------------------------------------------------------
    // AI verify_install state transitions: applyInstallVerifyActivity
    // ---------------------------------------------------------------

    @Test
    fun `applyInstallVerifyActivity Started enables testing loading`() {
        val updated = applyInstallVerifyActivity(CreateState(), ToolActivity.Started("verify_install", "{}"))
        assertTrue(updated.testing)
    }

    @Test
    fun `applyInstallVerifyActivity Started for other tools leaves testing unchanged`() {
        val updated = applyInstallVerifyActivity(CreateState(testing = false), ToolActivity.Started("writeManifest", "{}"))
        assertFalse(updated.testing)
    }

    @Test
    fun `applyInstallVerifyActivity Completed success clears loading and marks verified`() {
        val updated = applyInstallVerifyActivity(
            CreateState(testing = true, installVerified = false),
            ToolActivity.Completed("verify_install", """{"success":true}"""),
        )
        assertFalse(updated.testing)
        assertTrue(updated.installVerified)
    }

    @Test
    fun `applyInstallVerifyActivity Completed failure clears loading but keeps verification unchanged`() {
        val updated = applyInstallVerifyActivity(
            CreateState(testing = true, installVerified = false),
            ToolActivity.Completed("verify_install", """{"success":false,"error":"x"}"""),
        )
        assertFalse(updated.testing)
        assertFalse(updated.installVerified, "a failed verify must not mark the manifest verified")
    }

    @Test
    fun `applyInstallVerifyActivity Completed with ambiguous result never clears verification`() {
        val updated = applyInstallVerifyActivity(
            CreateState(testing = true, installVerified = true),
            ToolActivity.Completed("verify_install", "not json"),
        )
        assertFalse(updated.testing)
        assertTrue(updated.installVerified, "ambiguous success must preserve the verification flag")
    }

    @Test
    fun `applyInstallVerifyActivity Failed clears testing`() {
        val updated = applyInstallVerifyActivity(CreateState(testing = true), ToolActivity.Failed("verify_install", "boom"))
        assertFalse(updated.testing)
    }

    @Test
    fun `applyInstallVerifyActivity ignores non verify_install completed events`() {
        val updated = applyInstallVerifyActivity(
            CreateState(testing = true, installVerified = false),
            ToolActivity.Completed("fetchRepoInfo", "ok"),
        )
        assertTrue(updated.testing)
        assertFalse(updated.installVerified)
    }

    // ---------------------------------------------------------------
    // New manifest resets verification/save/install markers: finishGeneration
    // ---------------------------------------------------------------

    @Test
    fun `new manifest resets all markers when the content changes`() {
        val (verified, saved, installed) = newManifestResetsVerification(
            "{}", "{old}",
            installVerified = true,
            manifestSaved = true,
            installedForUse = true,
        )
        assertFalse(verified)
        assertFalse(saved)
        assertFalse(installed, "a new manifest must reset the installed-for-use marker too")
    }

    @Test
    fun `same manifest preserves all markers`() {
        val (verified, saved, installed) = newManifestResetsVerification(
            "{old}", "{old}",
            installVerified = true,
            manifestSaved = true,
            installedForUse = true,
        )
        assertTrue(verified)
        assertTrue(saved)
        assertTrue(installed)
    }

    @Test
    fun `blank new manifest preserves all markers`() {
        val (verified, saved, installed) = newManifestResetsVerification(
            "", "{old}",
            installVerified = true,
            manifestSaved = true,
            installedForUse = true,
        )
        assertTrue(verified)
        assertTrue(saved)
        assertTrue(installed)
    }

    // ---------------------------------------------------------------
    // Install action bar (three-step wizard final state): installAction*
    // ---------------------------------------------------------------

    // installActionVisible: the Install button is hidden once the manifest is installed

    @Test
    fun `installActionVisible shows Install before the install succeeded`() {
        assertTrue(installActionVisible(installedForUse = false))
    }

    @Test
    fun `installActionVisible hides Install once the install succeeded`() {
        assertFalse(installActionVisible(installedForUse = true))
    }

    // installActionPrimary: Install is the primary nudge after Save, until installed

    @Test
    fun `installActionPrimary is true once saved and not yet installed`() {
        assertTrue(installActionPrimary(manifestSaved = true, installedForUse = false))
    }

    @Test
    fun `installActionPrimary is false before the manifest is saved`() {
        assertFalse(installActionPrimary(manifestSaved = false, installedForUse = false))
    }

    @Test
    fun `installActionPrimary is false once installed for use`() {
        assertFalse(installActionPrimary(manifestSaved = true, installedForUse = true))
    }

    // installActionEnabled: valid manifest, not installed, and no operation in flight

    @Test
    fun `installActionEnabled is true for a valid unsaved idle manifest`() {
        assertTrue(
            installActionEnabled(
                manifestJson = """{"version":"1.2.3"}""",
                installedForUse = false,
                running = false,
                testing = false,
                installing = false,
            ),
        )
    }

    @Test
    fun `installActionEnabled is false for a blank or invalid manifest`() {
        assertFalse(
            installActionEnabled(
                manifestJson = "   ",
                installedForUse = false,
                running = false,
                testing = false,
                installing = false,
            ),
            "blank manifest must disable the install action",
        )
        assertFalse(
            installActionEnabled(
                manifestJson = "not json",
                installedForUse = false,
                running = false,
                testing = false,
                installing = false,
            ),
            "invalid manifest JSON must disable the install action",
        )
    }

    @Test
    fun `installActionEnabled is false while any operation is in flight`() {
        // each in-flight flag disables the action independently
        for (busy in listOf("running", "testing", "installing")) {
            var running = false
            var testing = false
            var installing = false
            when (busy) {
                "running" -> running = true
                "testing" -> testing = true
                "installing" -> installing = true
            }
            assertFalse(
                installActionEnabled(manifestJson = """{"version":"1.2.3"}""", installedForUse = false, running = running, testing = testing, installing = installing),
                "$busy in flight must disable the install action",
            )
        }
    }

    @Test
    fun `installActionEnabled is false once installed for use`() {
        assertFalse(
            installActionEnabled(manifestJson = """{"version":"1.2.3"}""", installedForUse = true, running = false, testing = false, installing = false),
        )
    }

    // ---------------------------------------------------------------
    // Open button logic: manifestHasShortcuts / openAppActionVisible /
    // installActionVisible
    // ---------------------------------------------------------------

    @Test
    fun `manifestHasShortcuts detects top level shortcuts`() {
        assertTrue(manifestHasShortcuts("""{"version":"1.0","shortcuts":[["App","app.exe"]]}"""))
    }

    @Test
    fun `manifestHasShortcuts detects shortcuts inside an architecture block`() {
        assertTrue(
            manifestHasShortcuts(
                """{"version":"1.0","architecture":{"64bit":{"shortcuts":[["App","app.exe"]]}}}""",
            ),
        )
    }

    @Test
    fun `manifestHasShortcuts is false when the field is absent`() {
        assertFalse(manifestHasShortcuts("""{"version":"1.0","url":"https://example.com"}"""))
    }

    @Test
    fun `manifestHasShortcuts is false for an empty shortcuts array`() {
        assertFalse(manifestHasShortcuts("""{"version":"1.0","shortcuts":[]}"""))
    }

    @Test
    fun `manifestHasShortcuts is false for invalid JSON`() {
        assertFalse(manifestHasShortcuts("{not json"))
        assertFalse(manifestHasShortcuts(""))
    }

    @Test
    fun `openAppActionVisible requires installed for use and available shortcuts`() {
        assertTrue(openAppActionVisible(installedForUse = true, manifestShortcutsAvailable = true))
        assertFalse(openAppActionVisible(installedForUse = false, manifestShortcutsAvailable = true))
        assertFalse(openAppActionVisible(installedForUse = true, manifestShortcutsAvailable = false))
    }

    @Test
    fun `installActionVisible is the negation of installed for use`() {
        assertTrue(installActionVisible(installedForUse = false))
        assertFalse(installActionVisible(installedForUse = true))
    }

    @Test
    fun `installActionLabel is Install when the app is not installed system wide`() {
        assertEquals("Install", installActionLabel(systemInstalled = false))
    }

    @Test
    fun `installActionLabel is Reinstall when the app is already installed system wide`() {
        assertEquals("Reinstall", installActionLabel(systemInstalled = true))
    }

    // ---------------------------------------------------------------
    // newManifestResetsVerification: installedShortcuts is the 4th marker
    // ---------------------------------------------------------------

    @Test
    fun `new manifest resets all four markers including installed shortcuts`() {
        val (verified, saved, installed, shortcuts) = newManifestResetsVerification(
            "{new}", "{old}",
            installVerified = true,
            manifestSaved = true,
            installedForUse = true,
            installedShortcuts = true,
        )
        assertFalse(verified)
        assertFalse(saved)
        assertFalse(installed)
        assertFalse(shortcuts, "a new manifest must reset the installed-shortcuts flag too")
    }

    @Test
    fun `same manifest preserves all four markers including installed shortcuts`() {
        val (verified, saved, installed, shortcuts) = newManifestResetsVerification(
            "{old}", "{old}",
            installVerified = true,
            manifestSaved = true,
            installedForUse = true,
            installedShortcuts = true,
        )
        assertTrue(verified)
        assertTrue(saved)
        assertTrue(installed)
        assertTrue(shortcuts)
    }

    // ---------------------------------------------------------------
    // Whole-run tool activity fold: collapsed keeps only the trailing
    // phase tag line (plus its running state); every activity detail
    // line, including the last one, hides behind the fold toggle.
    // ---------------------------------------------------------------

    @Test
    fun `visibleExecutionSteps collapsed keeps only the last step with empty toolActivities`() {
        val steps = listOf(
            ExecutionStep("research", "Researching", toolActivities = listOf("✔ a", "✔ b")),
            ExecutionStep("analyze", "Analyzing", toolActivities = listOf("✔ c")),
        )
        val visible = visibleExecutionSteps(steps, expanded = false)
        assertEquals(1, visible.size)
        assertEquals("analyze", visible[0].phase)
        assertTrue(visible[0].toolActivities.isEmpty())
    }

    @Test
    fun `visibleExecutionSteps collapsed keeps the trailing running phase tag and hides its activities`() {
        val steps = listOf(
            ExecutionStep("research", "Researching", toolActivities = listOf("✔ a", "✔ b")),
            ExecutionStep("analyze", "Analyzing", toolActivities = listOf("✔ c"), isRunning = true),
        )
        val visible = visibleExecutionSteps(steps, expanded = false)
        // Collapsed is just the current phase tag line (TypingDots/liveActivity
        // render from isRunning/currentActivity outside this function).
        assertEquals(1, visible.size)
        assertEquals("analyze", visible[0].phase)
        assertTrue(visible[0].isRunning)
        assertTrue(visible[0].toolActivities.isEmpty())
    }

    @Test
    fun `visibleExecutionSteps collapsed drops even the finished activity lines of the last step`() {
        val steps = listOf(
            ExecutionStep(
                "analyze",
                "Analyzing package...",
                toolActivities = listOf("✔ flat archive", "✔ flat archive arm64"),
                summary = "Detected strategy 'portable_zip'",
            ),
            ExecutionStep("verify", "Validating manifest...", isRunning = true),
        )
        val visible = visibleExecutionSteps(steps, expanded = false)
        assertEquals(listOf("verify"), visible.map { it.phase })
        assertTrue(visible[0].toolActivities.isEmpty())
    }

    @Test
    fun `visibleExecutionSteps collapsed keeps the last finished step summary without its activity lines`() {
        val steps = listOf(
            ExecutionStep("analyze", "Analyzing", toolActivities = listOf("✔ a", "✔ b")),
            ExecutionStep("verify", "Validating", summary = "Manifest validated"),
        )
        val visible = visibleExecutionSteps(steps, expanded = false)
        assertEquals(listOf("verify"), visible.map { it.phase })
        assertEquals("Manifest validated", visible[0].summary)
        assertTrue(visible[0].toolActivities.isEmpty())
    }

    @Test
    fun `visibleExecutionSteps expanded shows every step untouched`() {
        val steps = listOf(
            ExecutionStep("research", "Researching", toolActivities = listOf("✔ a", "✔ b")),
            ExecutionStep("analyze", "Analyzing", toolActivities = listOf("✔ c")),
        )
        assertEquals(steps, visibleExecutionSteps(steps, expanded = true))
    }

    @Test
    fun `visibleExecutionSteps collapsed folds a single activity into the toggle as well`() {
        val steps = listOf(
            ExecutionStep("research", "Researching", toolActivities = listOf("✔ only")),
        )
        val visible = visibleExecutionSteps(steps, expanded = false)
        assertEquals(1, visible.size)
        assertEquals("research", visible[0].phase)
        assertTrue(visible[0].toolActivities.isEmpty())
    }

    @Test
    fun `visibleExecutionSteps with no activities keeps the last phase placeholder`() {
        val steps = listOf(
            ExecutionStep("research", "Researching", toolActivities = emptyList()),
            ExecutionStep("analyze", "Analyzing", toolActivities = emptyList()),
        )
        val visible = visibleExecutionSteps(steps, expanded = false)
        assertEquals(1, visible.size)
        assertEquals("analyze", visible[0].phase)
        assertTrue(visible[0].toolActivities.isEmpty())
    }

    @Test
    fun `visibleExecutionSteps collapsed on empty steps returns nothing`() {
        assertTrue(visibleExecutionSteps(emptyList(), expanded = false).isEmpty())
    }

    @Test
    fun `earlierExecutionActivityCount counts every activity line across all phases`() {
        val steps = listOf(
            ExecutionStep("research", "Researching", toolActivities = listOf("✔ a", "✔ b")),
            ExecutionStep("analyze", "Analyzing", toolActivities = listOf("✔ c", "✔ d")),
        )
        assertEquals(4, earlierExecutionActivityCount(steps))
    }

    @Test
    fun `earlierExecutionActivityCount counts a single activity line so the toggle shows`() {
        assertEquals(
            1,
            earlierExecutionActivityCount(
                listOf(ExecutionStep("research", "Researching", toolActivities = listOf("✔ a"))),
            ),
        )
    }

    @Test
    fun `earlierExecutionActivityCount with no activities is zero so the toggle stays hidden`() {
        assertEquals(
            0,
            earlierExecutionActivityCount(
                listOf(
                    ExecutionStep("research", "Researching", toolActivities = emptyList()),
                    ExecutionStep("analyze", "Analyzing", toolActivities = emptyList(), isRunning = true),
                ),
            ),
        )
        assertEquals(0, earlierExecutionActivityCount(emptyList()))
    }
}
