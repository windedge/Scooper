package scooper.viewmodels

import com.scoopai.core.TokenUsage
import com.scoopai.core.ToolActivity
import kotlinx.coroutines.channels.Channel
import scooper.util.VersionComparator
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
 * Steps a card renders for the given whole-run fold state: collapsed keeps
 * only the trailing step's phase tag line (with its isRunning/TypingDots state);
 * every tool activity detail line, including the last one, hides behind the
 * fold. Expanded shows every step untouched. Pure function.
 */
internal fun visibleExecutionSteps(steps: List<ExecutionStep>, expanded: Boolean): List<ExecutionStep> {
    if (expanded) return steps
    val last = steps.lastOrNull() ?: return emptyList()
    return listOf(last.copy(toolActivities = emptyList()))
}

/**
 * Number of activity lines hidden by the collapsed whole-run fold (every detail
 * line across all phases, since collapsed renders no activity row at all);
 * 0 when there is nothing to fold. Pure function.
 */
internal fun earlierExecutionActivityCount(steps: List<ExecutionStep>): Int =
    steps.sumOf { it.toolActivities.size }

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
internal fun drainActivityEvents(channel: Channel<Pair<ToolActivity, Long>>) {
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
 * "✓ Completed in 3.4s · 3 steps" (total duration = sum of step durations);
 * error bubbles render "✗ Failed in ..." instead so the badge never claims
 * success for a failed run.
 * Token usage is intentionally absent here; it renders only in the bubble
 * footer below the body (see [formatTokenFooter]).
 * Pure function.
 */
internal fun executionSummaryCapsuleText(steps: List<ExecutionStep>, failed: Boolean = false): String {
    val totalMs = steps.fold(0L) { acc, step -> acc + step.durationMs.coerceAtLeast(0L) }
    val noun = if (steps.size == 1) "step" else "steps"
    val verb = if (failed) "Failed" else "Completed"
    val mark = if (failed) "✗" else "✓"
    return "$mark $verb in ${formatStepDuration(totalMs)} · ${steps.size} $noun"
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
 * The install highlight is dismissed once the manifest has been exercised or persisted:
 * a completed AI verify_install or a bucket save.
 */
internal fun manifestTestSucceeded(
    installVerified: Boolean,
    manifestSaved: Boolean,
): Boolean = installVerified || manifestSaved

/**
 * Save to Bucket is nudged as the primary action once the manifest is verified but
 * before it has actually been saved.
 */
internal fun saveToBucketPrimary(testSucceeded: Boolean, manifestSaved: Boolean): Boolean = testSucceeded && !manifestSaved

/** The install-verified / saved / installed-for-use / installed-shortcuts markers tracked on CreateState. */
internal data class VerificationFlags(
    val installVerified: Boolean,
    val manifestSaved: Boolean,
    val installedForUse: Boolean,
    val installedShortcuts: Boolean,
)

/**
 * A new manifest (different non-blank content) invalidates the previous verification,
 * save, and installed-for-use markers, so the new manifest starts fresh.
 */
internal fun newManifestResetsVerification(
    newManifest: String,
    currentManifest: String,
    installVerified: Boolean,
    manifestSaved: Boolean,
    installedForUse: Boolean,
    installedShortcuts: Boolean = false,
): VerificationFlags {
    val manifestChanged = newManifest.isNotBlank() && newManifest != currentManifest
    return if (manifestChanged) {
        VerificationFlags(false, false, false, false)
    } else {
        VerificationFlags(installVerified, manifestSaved, installedForUse, installedShortcuts)
    }
}

/** Install for use is the primary nudge once the manifest is saved and before it is installed.
 *  Superseded by deployment* ([deploymentAction]) for the deploy button. */
internal fun installActionPrimary(manifestSaved: Boolean, installedForUse: Boolean): Boolean =
    manifestSaved && !installedForUse

/**
 * Install for use is offered for any valid manifest, saved or not, with
 * nothing in flight. An unsaved manifest installs straight from the local
 * file; the save result is no longer part of the gate.
 * Superseded by deployment* ([deploymentAction]) for the deploy button.
 */
internal fun installActionEnabled(
    manifestJson: String,
    installedForUse: Boolean,
    running: Boolean,
    testing: Boolean,
    installing: Boolean,
): Boolean = isScoopManifestJson(manifestJson) && !installedForUse && !running && !testing && !installing

/** Install is hidden once the app is installed for use; Open replaces it.
 *  Superseded by deployment* ([deploymentLabel] / [deploymentAction]). */
internal fun installActionVisible(installedForUse: Boolean): Boolean = !installedForUse

/**
 * Label for the install action. When the app was installed outside this session
 * (system install), a saved manifest needs a reinstall to apply fixes, so the
 * label says Reinstall instead of Install.
 * Superseded by deployment* ([deploymentLabel]) for the deploy button.
 */
internal fun installActionLabel(systemInstalled: Boolean): String =
    if (systemInstalled) "Reinstall" else "Install"

/** True when the manifest declares Start Menu shortcuts (top level or any architecture block). */
internal fun manifestHasShortcuts(manifestJson: String): Boolean {
    if (manifestJson.isBlank()) return false
    return try {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(manifestJson)
            as? kotlinx.serialization.json.JsonObject ?: return false
        fun hasShortcuts(el: kotlinx.serialization.json.JsonElement?): Boolean =
            (el as? kotlinx.serialization.json.JsonObject)?.get("shortcuts")
                ?.let { it as? kotlinx.serialization.json.JsonArray }?.isNotEmpty() == true
        hasShortcuts(obj) || (obj["architecture"] as? kotlinx.serialization.json.JsonObject)
            ?.values?.any { hasShortcuts(it) } == true
    } catch (_: Exception) {
        false
    }
}

/** Open is offered once the app is installed for use and its manifest declares shortcuts. */
internal fun openAppActionVisible(installedForUse: Boolean, manifestShortcutsAvailable: Boolean): Boolean =
    installedForUse && manifestShortcutsAvailable

/**
 * How the manifest version relates to the installed one. Older means the manifest
 * is older than what is installed; Unknown covers non-comparable tags like "nightly".
 */
enum class VersionRelation { Older, Same, Newer, Unknown }

/**
 * Re-exported so viewmodel code keeps using the unqualified name; the constant itself
 * lives in data/ because the service layer reads install records too.
 */
typealias InstallOrigin = scooper.data.InstallOrigin

/**
 * Deployment state derived purely from manifest + install record inputs.
 * Consumed by [deploymentLabel] / [deploymentAction]; no UI wiring yet.
 */
sealed interface DeploymentState {
    data object NoManifest : DeploymentState
    data object InvalidManifest : DeploymentState
    data object UnresolvedName : DeploymentState
    data object NotInstalled : DeploymentState

    data class Installed(
        val installedVersion: String,
        val manifestVersion: String?,
        val relation: VersionRelation,
        val origin: InstallOrigin,
        val originBucket: String?,
        val inThisSession: Boolean,
    ) : DeploymentState
}

/** What the deploy button should do for a state; None renders disabled. */
enum class DeploymentAction {
    None, InstallDirect, InstallFromBucket, UpdateForce, UninstallThenInstall, OpenOnly
}

/** Inputs for [deriveDeploymentState]; strings instead of File so it stays pure. */
data class DeploymentInputs(
    val manifestJson: String?,
    val appName: String? = null,
    val saveBucket: String? = null,
    val installed: InstalledRecord? = null,
    val inThisSession: Boolean = false,
)

/** The installed copy as far as deployment decisions need to know. */
data class InstalledRecord(
    val version: String,
    val origin: InstallOrigin,
    val originBucket: String?,
)

/**
 * App name the deployment state is derived for, in resolution order: the name a
 * save actually used (a save may rename the app), the engine suggestion, then the
 * name a direct install used. Blank candidates are skipped. Pure function.
 */
internal fun deploymentAppName(
    saveResult: SaveResult?,
    suggestedAppName: String?,
    directInstallName: String?,
): String? = listOf(
    saveResult?.appName,
    suggestedAppName,
    directInstallName,
).firstOrNull { !it.isNullOrBlank() }

/**
 * Derive the deployment state. Version comparison: exact string equality means Same
 * (Scoop compares versions as strings, so 2024.01 vs 2024.1 are different); natural
 * order then decides Newer/Older, with compare==0-but-different-strings counted as
 * Newer so the button offers Update instead of a no-op reinstall. Versions that do
 * not start with a digit ("nightly") are not comparable: relation Unknown and a null
 * manifestVersion. Pure function.
 */
internal fun deriveDeploymentState(inputs: DeploymentInputs): DeploymentState {
    if (inputs.manifestJson.isNullOrBlank()) return DeploymentState.NoManifest
    val manifestVersion = manifestVersionOf(inputs.manifestJson)
        ?: return DeploymentState.InvalidManifest
    if (inputs.appName.isNullOrBlank()) return DeploymentState.UnresolvedName
    val installed = inputs.installed ?: return DeploymentState.NotInstalled
    val comparable = isComparableVersion(manifestVersion) && isComparableVersion(installed.version)
    val relation = when {
        !comparable -> VersionRelation.Unknown
        manifestVersion == installed.version -> VersionRelation.Same
        VersionComparator.compare(manifestVersion, installed.version) > 0 -> VersionRelation.Newer
        VersionComparator.compare(manifestVersion, installed.version) < 0 -> VersionRelation.Older
        else -> VersionRelation.Newer
    }
    return DeploymentState.Installed(
        installedVersion = installed.version,
        manifestVersion = if (comparable) manifestVersion else null,
        relation = relation,
        origin = installed.origin,
        originBucket = installed.originBucket,
        inThisSession = inputs.inThisSession,
    )
}

/**
 * Button label for a state: "Install" / "Update" / "Reinstall", or "" to hide the
 * button (nothing to deploy; the UI shows Open instead). Pure function.
 */
internal fun deploymentLabel(state: DeploymentState): String = when (state) {
    DeploymentState.NoManifest -> ""
    DeploymentState.InvalidManifest,
    DeploymentState.UnresolvedName,
    DeploymentState.NotInstalled,
    -> "Install"
    is DeploymentState.Installed -> when {
        state.relation == VersionRelation.Newer -> "Update"
        state.relation == VersionRelation.Same && state.inThisSession -> ""
        else -> "Reinstall"
    }
}

/**
 * Action behind the button. Installed + same version + this session opens only.
 * DirectFile installs always uninstall first (the leftover url cannot update).
 * A bucket install whose bucket matches the save bucket (or with no save bucket at
 * all: no new bucket exists, so force-updating the installed content is the
 * least destructive reinstall) uses UpdateForce; anything else uninstalls first.
 * Pure function.
 */
internal fun deploymentAction(state: DeploymentState, saveBucket: String?): DeploymentAction = when (state) {
    DeploymentState.NoManifest,
    DeploymentState.InvalidManifest,
    DeploymentState.UnresolvedName,
    -> DeploymentAction.None
    DeploymentState.NotInstalled ->
        if (saveBucket == null) DeploymentAction.InstallDirect else DeploymentAction.InstallFromBucket
    is DeploymentState.Installed -> when {
        state.relation == VersionRelation.Same && state.inThisSession -> DeploymentAction.OpenOnly
        state.origin == InstallOrigin.DirectFile -> DeploymentAction.UninstallThenInstall
        state.origin == InstallOrigin.Bucket &&
            (saveBucket == null || state.originBucket == saveBucket) -> DeploymentAction.UpdateForce
        else -> DeploymentAction.UninstallThenInstall
    }
}

/**
 * Bucket the deploy plan works from: the installed copy's recorded origin bucket
 * first, so an update still knows its bucket after the save result was cleared
 * (assist round, dismissed result card), then the save bucket. Pure function.
 */
internal fun deployBucket(state: DeploymentState, saveBucket: String?): String? =
    (state as? DeploymentState.Installed)?.originBucket ?: saveBucket

/**
 * Deploy button gate: a parseable manifest, a plan with work left, and nothing in
 * flight. The `installedForUse` latch is deliberately absent: an install in this
 * session must not disable a later version or bucket change that still has to be
 * deployed (and a vanished install must be retryable). Pure function.
 */
internal fun deployButtonEnabled(
    manifestJson: String,
    action: DeploymentAction,
    running: Boolean,
    testing: Boolean,
    installing: Boolean,
): Boolean = isScoopManifestJson(manifestJson) &&
    action != DeploymentAction.None &&
    !running && !testing && !installing

/** Blank versions and non-digit-leading tags ("nightly") cannot be order-compared. */
private fun isComparableVersion(version: String): Boolean = version.firstOrNull()?.isDigit() == true

/** Version field of a manifest JSON, or null when missing / blank / not an object. */
private fun manifestVersionOf(manifestJson: String): String? = try {
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(manifestJson)
        as? kotlinx.serialization.json.JsonObject ?: return null
    (obj["version"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.content?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}
