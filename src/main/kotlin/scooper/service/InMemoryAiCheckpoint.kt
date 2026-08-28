package scooper.service

import com.scoopai.core.artifacts.CheckpointResume.ResumeStage
import com.scoopai.core.artifacts.ResumeData

/**
 * In-memory snapshot of the phase outputs produced during the most recent AI
 * generation run, kept so a mid-phase failure can be resumed from a later
 * stage (skip research / skip analyze) instead of always restarting from
 * scratch.
 *
 * The raw, un-masked phase JSON (research / analyze) is captured via the
 * scoop-ai `onPhaseOutput` listener. It is only held in memory; it is not
 * persisted or masked.
 */
data class InMemoryAiCheckpoint(
    val url: String,
    val userPrompt: String,
    val researchJson: String? = null,
    val analyzeJson: String? = null,
)

/**
 * Which [AiErrorAction] resume stages are currently available for [cp].
 *
 * Returns at most one latest available resume stage:
 * - null / no research output -> nothing to resume (full run only).
 * - research only -> can skip research, resume from analyze.
 * - research + analyze -> can skip both research & analyze, resume from generate.
 */
internal fun resumableStagesOf(cp: InMemoryAiCheckpoint?): Set<AiErrorAction> {
    val research = cp?.researchJson
    if (research == null || research.isBlank()) return emptySet()
    if (cp.analyzeJson.isNullOrBlank()) {
        return setOf(AiErrorAction.RETRY_FROM_ANALYZE)
    }
    return setOf(AiErrorAction.RETRY_FROM_GENERATE)
}

/**
 * Fold a raw phase output (phase name + JSON) into [this] checkpoint.
 *
 * `research` / `analyze` outputs are stored; `generate` / `refine` outputs are
 * not part of the resume signature and are ignored (this instance is returned).
 */
internal fun InMemoryAiCheckpoint.withPhaseOutput(phase: String, output: String): InMemoryAiCheckpoint =
    when (phase.lowercase()) {
        "research" -> copy(researchJson = output)
        "analyze" -> copy(analyzeJson = output)
        else -> this
    }

/**
 * Build [ResumeData] to resume from stage [from] using the phase outputs in [cp].
 *
 * Returns null when the requested resume cannot be satisfied (missing or
 * corrupt required JSON), in which case the caller should fall back to a full
 * regeneration.
 */
internal fun buildResumeData(
    cp: InMemoryAiCheckpoint?,
    from: AiErrorAction,
    runId: String,
): ResumeData? {
    if (cp == null) return null
    return when (from) {
        AiErrorAction.RETRY_FROM_ANALYZE ->
            ResumeData.fromPhaseOutputs(ResumeStage.ANALYZE, runId, cp.researchJson, cp.analyzeJson)
        AiErrorAction.RETRY_FROM_GENERATE ->
            ResumeData.fromPhaseOutputs(ResumeStage.GENERATE, runId, cp.researchJson, cp.analyzeJson)
        else -> null
    }
}

/**
 * Filter a translated error action list so that resume actions that are not
 * currently resumable (no checkpoint data for that stage) are dropped.
 * Non-resume actions (e.g. RETRY_FULL, OPEN_SETTINGS) always pass through.
 */
internal fun resolveErrorActions(
    actions: List<AiErrorAction>,
    resumableStages: Set<AiErrorAction>,
): List<AiErrorAction> = actions.filter { action ->
    action !in setOf(AiErrorAction.RETRY_FROM_ANALYZE, AiErrorAction.RETRY_FROM_GENERATE) ||
        action in resumableStages
}

/**
 * Stateful holder for the current [InMemoryAiCheckpoint], shared as a Koin
 * singleton between [ScoopAiBridge] (writer) and the UI (reader).
 *
 * [beginRun] starts a fresh checkpoint for a new run; [update] then folds each
 * phase output into it as the run progresses.
 */
class InMemoryAiCheckpointService {

    @Volatile
    private var checkpoint: InMemoryAiCheckpoint? = null

    /** Start a fresh checkpoint for a new generation run of [url]/[userPrompt]. */
    fun beginRun(url: String, userPrompt: String) {
        checkpoint = InMemoryAiCheckpoint(url = url, userPrompt = userPrompt)
    }

    /** Fold a phase output into the current checkpoint; no-op before [beginRun]. */
    fun update(phase: String, output: String) {
        checkpoint = checkpoint?.withPhaseOutput(phase, output)
    }

    fun snapshot(): InMemoryAiCheckpoint? = checkpoint

    fun reset() {
        checkpoint = null
    }
}
