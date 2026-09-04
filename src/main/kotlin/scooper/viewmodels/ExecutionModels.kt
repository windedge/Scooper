package scooper.viewmodels

import com.scoopai.core.TokenUsage
import com.scoopai.core.ToolActivity
import kotlinx.coroutines.channels.Channel
import java.util.Locale

/**
 * Single execution step of a generation round (research / analyze / generate /
 * assist / repair / verify). Running steps accumulate tool activity; a finished step keeps
 * its duration and optional phase-output summary (architecture proposal 5.1).
 */
data class ExecutionStep(
    val phase: String,
    val title: String,
    val toolActivities: List<String> = emptyList(),
    val durationMs: Long = 0,
    val isRunning: Boolean = false,
    val summary: String? = null,
)

/**
 * Transient per-round execution progress held in [CreateState.activeExecution] while
 * a generation/assist session runs. It never enters the message history directly;
 * on finish it is folded into a finalized [ChatMessage] via [collapseActiveExecution].
 */
data class ActiveExecution(
    val steps: List<ExecutionStep> = emptyList(),
    /** Live tool activity line shown for the running step. */
    val currentActivity: String? = null,
    /** Wall-clock ms when the current running step started; drives durationMs. */
    val currentStepStartedAtMs: Long = 0L,
)

/**
 * UI-side summary written for a finished verify step. The engine emits onPhase for
 * verify but never onPhaseOutput, so without this marker the step would keep an
 * empty summary (architecture: verify is the only phase without an output event).
 */
internal const val VERIFY_STEP_SUMMARY = "Manifest validated"

/**
 * Summary a closing/settled step keeps. The synthetic [VERIFY_STEP_SUMMARY] marker is
 * only applied to a verify step (case-insensitive) that has neither its own summary
 * nor a failed tool activity line; everything else keeps whatever it already had.
 * Pure function.
 */
internal fun closingStepSummary(step: ExecutionStep): String? {
    if (!step.phase.equals("verify", ignoreCase = true)) return step.summary
    if (!step.summary.isNullOrBlank()) return step.summary
    if (step.toolActivities.any { it.contains("✖") }) return step.summary
    return VERIFY_STEP_SUMMARY
}

/**
 * Append a new running step for [phase], closing the previous running step with
 * [durationMs] computed from its start timestamp. A re-entry for the same phase
 * (e.g. the pre-created initial step followed by the engine's onPhase event) keeps
 * the existing running step instead of duplicating it.
 *
 * Pure function; inject the wall clock through [nowMs].
 */
internal fun appendPhaseStep(
    active: ActiveExecution?,
    phase: String,
    title: String,
    nowMs: Long,
): ActiveExecution {
    val current = active ?: ActiveExecution()
    val steps = current.steps.toMutableList()
    val last = steps.lastOrNull()
    if (last != null && last.isRunning && last.phase.equals(phase, ignoreCase = true)) {
        // Same phase re-entry: keep the running step as-is, reset the live line only.
        return current.copy(currentActivity = null)
    }
    if (last != null) {
        steps.removeAt(steps.size - 1)
        steps.add(
            if (last.isRunning) {
                last.copy(
                    isRunning = false,
                    durationMs = (nowMs - current.currentStepStartedAtMs).coerceAtLeast(0L),
                    summary = closingStepSummary(last),
                )
            } else {
                last
            },
        )
    }
    steps.add(ExecutionStep(phase = phase, title = title, isRunning = true))
    return current.copy(steps = steps, currentActivity = null, currentStepStartedAtMs = nowMs)
}

/**
 * Session-scoped repair round counter. The engine emits `onPhase("repair")` once per
 * internal repair pass, and several passes can run inside one user session; the count
 * labels each with its real round number (1, 2, ...) instead of a stale assist count.
 * [reset] starts a fresh count for a new generate/assist session.
 */
internal class RepairRoundCounter {
    private var count = 0

    /** Increments on repair phases only; returns the current attempt number. */
    fun onPhase(phase: String): Int {
        if (phase.equals("repair", ignoreCase = true)) {
            count++
        }
        return count
    }

    /** Reset so the first repair round of a new session shows attempt 1. */
    fun reset() {
        count = 0
    }
}

/**
 * Attribute a tool activity event to the most recent step.
 *
 * Completed/Failed events are appended to the step's toolActivities with a check /
 * cross prefix (deduplicated); Started (and any other event) refreshes the live
 * [ActiveExecution.currentActivity] line. Returns null when [active] is null (no run
 * in progress); events arriving outside a run are dropped by the caller.
 *
 * Pure function.
 */
internal fun attachToolActivity(
    active: ActiveExecution?,
    activity: ToolActivity,
    formattedText: String,
): ActiveExecution? {
    if (active == null) return null
    val logLine = when (activity) {
        is ToolActivity.Completed -> "✔ $formattedText"
        is ToolActivity.Failed -> "✖ $formattedText"
        else -> null
    }
    val steps = active.steps.toMutableList()
    val last = steps.lastOrNull()
    if (logLine == null || last == null) {
        // Started event (or no step yet): keep it as the live activity line only.
        return active.copy(currentActivity = formattedText)
    }
    steps.removeAt(steps.size - 1)
    steps.add(
        if (logLine in last.toolActivities) {
            last
        } else {
            last.copy(toolActivities = last.toolActivities + logLine)
        },
    )
    // Completed/Failed close the live line: the durable check/cross line now lives
    // in the step's toolActivities, so the stale live text must not linger beside it.
    return active.copy(steps = steps, currentActivity = null)
}

/**
 * Write a phase-output summary onto the most recent step of [phase]. Unknown phases
 * leave the state unchanged. Pure function.
 */
internal fun setPhaseSummary(
    active: ActiveExecution?,
    phase: String,
    summary: String,
): ActiveExecution? {
    if (active == null) return null
    val index = active.steps.indexOfLast { it.phase.equals(phase, ignoreCase = true) }
    if (index == -1) return active
    val steps = active.steps.toMutableList()
    steps[index] = steps[index].copy(summary = summary)
    return active.copy(steps = steps)
}

/**
 * Clear the summary of the last assist step when the engine answered the user's
 * question directly (ManifestResult.answered=true). The manifest is guaranteed
 * unchanged in that case, so the deterministic "Updated manifest ..." line derived
 * from the engine's snapshot must not survive into the finalized execution card;
 * the UI renders no summary line at all for a cleared step.
 */
internal fun markAnsweredSummary(steps: List<ExecutionStep>): List<ExecutionStep> {
    val index = steps.indexOfLast { it.phase.equals("assist", ignoreCase = true) }
    if (index == -1) return steps
    val updated = steps.toMutableList()
    updated[index] = updated[index].copy(summary = null)
    return updated
}

/**
 * Fold the transient [active] execution into the finalized message history by
 * attaching the (closed) steps to the last agent message. [expanded] marks the
 * message's execution card auto-expanded (failure/exhausted); success passes false.
 * [nowMs] settles the durationMs of any still-running step (usually the final
 * verify step, which never receives a closing appendPhaseStep event); steps that
 * are already settled are left untouched. Without an active execution the messages
 * are returned unchanged. Pure function.
 */
internal fun collapseActiveExecution(
    messages: List<ChatMessage>,
    active: ActiveExecution?,
    expanded: Boolean,
    nowMs: Long,
): List<ChatMessage> {
    if (active == null || active.steps.isEmpty() || messages.isEmpty()) return messages
    val index = messages.indexOfLast { it.role == ChatRole.Agent }
    if (index == -1) return messages
    val steps = active.steps.map { step ->
        if (!step.isRunning) {
            step
        } else {
            step.copy(
                isRunning = false,
                durationMs = (nowMs - active.currentStepStartedAtMs).coerceAtLeast(0L),
                summary = closingStepSummary(step),
            )
        }
    }
    return messages.toMutableList().apply {
        this[index] = this[index].copy(executionSteps = steps, isExecutionExpanded = expanded)
    }
}

/**
 * Drain residual buffered activity events (late-event race defense, proposal 5.1):
 * everything still buffered when the run finishes must not leak into the next run
 * or mutate a finalized state. After this loop the channel is empty.
 */
internal fun drainActivityEvents(channel: Channel<ToolActivity>) {
    while (true) {
        val result = channel.tryReceive()
        if (result.isFailure) break
    }
}

/**
 * Human-readable duration for a finished execution step:
 * "850ms", "3.4s", "1m 5s". Non-positive input renders as "0ms".
 */
internal fun formatStepDuration(durationMs: Long): String {
    val ms = durationMs.coerceAtLeast(0L)
    return when {
        ms < 1000L -> "${ms}ms"
        ms < 60_000L -> String.format(Locale.ROOT, "%.1fs", ms / 1000.0)
        else -> "${ms / 60_000L}m ${(ms % 60_000L) / 1000L}s"
    }
}

/**
 * Toggle the execution review accordion of the finalized message with [messageId].
 * Messages without a matching id are left untouched (state stays unchanged when the
 * id cannot be found). Pure function (stage 3 reducer for onToggleExecutionExpanded).
 */
internal fun toggleExecutionExpanded(
    messages: List<ChatMessage>,
    messageId: String,
): List<ChatMessage> {
    val index = messages.indexOfFirst { it.id == messageId }
    if (index == -1) return messages
    return messages.toMutableList().apply {
        this[index] = this[index].copy(isExecutionExpanded = !this[index].isExecutionExpanded)
    }
}

/**
 * Whether the finalized message with [messageId] still renders the full expandable
 * execution accordion. Only the most recent [recentCount] agent messages that carry
 * execution steps get the full interaction; older rounds are fixed to a single-line
 * summary capsule to keep the LazyColumn light (proposal 5.1 history rule).
 * Pure function.
 */
internal fun shouldRenderFullExecutionSteps(
    messages: List<ChatMessage>,
    messageId: String,
    recentCount: Int = 3,
): Boolean {
    val recentIds = messages.asSequence()
        .filter { it.role == ChatRole.Agent && it.executionSteps.isNotEmpty() }
        .toList()
        .takeLast(recentCount)
        .mapTo(mutableSetOf()) { it.id }
    return messageId in recentIds
}

/**
 * Whether an execution step renders with the failure highlight: its own tool
 * activities contain a cross line, or the message it belongs to carries error
 * actions (an error bubble keeps its steps red-tinged for locating the failure).
 * Pure function.
 */
internal fun isFailedExecutionStep(step: ExecutionStep, messageHasActions: Boolean): Boolean {
    if (messageHasActions) return true
    return step.toolActivities.any { it.contains("✖") }
}

/**
 * Summary capsule text for a collapsed finalized execution card, e.g.
 * "✓ Completed in 3.4s · 3 steps" (total duration = sum of step durations).
 * Token usage is intentionally absent here; it renders only in the bubble
 * footer below the body (see [formatTokenFooter]).
 * Pure function.
 */
internal fun executionSummaryCapsuleText(steps: List<ExecutionStep>): String {
    val totalMs = steps.fold(0L) { acc, step -> acc + step.durationMs.coerceAtLeast(0L) }
    val noun = if (steps.size == 1) "step" else "steps"
    return "✓ Completed in ${formatStepDuration(totalMs)} · ${steps.size} $noun"
}

/**
 * Compact token count: "980" below 1000, "1.8k" above, "1.2M" above one million.
 */
internal fun formatTokenCount(total: Long): String = when {
    total >= 1_000_000L -> String.format(Locale.US, "%.1fM", total / 1_000_000.0)
    total >= 1_000L -> String.format(Locale.US, "%.1fk", total / 1_000.0)
    else -> total.toString()
}

/**
 * Bubble footer text, e.g. "1.2k in · 567 out tokens". Null when the run did not
 * report any tokens (provider without usage data), so no footer renders.
 */
internal fun formatTokenFooter(usage: TokenUsage?): String? {
    if (usage == null || usage.totalTokens <= 0L) return null
    val input = formatTokenCount(usage.totalInputTokens)
    val output = formatTokenCount(usage.totalOutputTokens)
    return "$input in · $output out tokens"
}

/**
 * Test Install's primary highlight is dismissed when the manifest has been exercised or
 * persisted: a passing test install, a completed AI verify_install, or a bucket save.
 */
internal fun manifestTestSucceeded(
    testResult: TestResult?,
    installVerified: Boolean,
    manifestSaved: Boolean,
): Boolean = testResult?.success == true || installVerified || manifestSaved

/**
 * Save to Bucket is nudged as the primary action only after a passing test install and
 * before the manifest has actually been saved.
 */
internal fun saveToBucketPrimary(testSucceeded: Boolean, manifestSaved: Boolean): Boolean = testSucceeded && !manifestSaved

/**
 * A new manifest (different non-blank content) invalidates the previous verification and
 * save markers, so the new manifest starts unverified.
 */
internal fun newManifestResetsVerification(
    newManifest: String,
    currentManifest: String,
    installVerified: Boolean,
    manifestSaved: Boolean,
): Pair<Boolean, Boolean> {
    val manifestChanged = newManifest.isNotBlank() && newManifest != currentManifest
    return if (manifestChanged) false to false else installVerified to manifestSaved
}
