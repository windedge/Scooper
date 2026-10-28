package scooper.viewmodels

import scooper.service.AiErrorAction
import scooper.service.GithubUrlParser
import scooper.service.PhaseSummaryFormatter

/**
 * Decision for one composer send (S2 step 5). The view model keeps the intent
 * shell, the epoch, the reduce and the bridge calls; planning only classifies
 * the input and hands back the payload to reduce.
 */
internal sealed interface SendDecision {
    /** Nothing to send: blank composer without images, or a blank questionnaire answer. */
    data object Ignored : SendDecision

    /** A pending questionnaire owns the composer, so the text is its answer. */
    data class AnswerAskUser(val answer: String) : SendDecision

    /** A run, test install or install owns the UI; the view model toasts and drops the send. */
    data object Busy : SendDecision

    /** Assist round: instruction plus the images that go with it. */
    data class Assist(val instruction: String, val images: List<PendingImage>) : SendDecision

    /** Fresh generation from a GitHub url. */
    data class Generate(val url: String, val note: String, val images: List<PendingImage>) : SendDecision
}

/**
 * Classify a send. The text resolution order mirrors the composer: an explicit
 * argument (input, then note, then url) wins over the draft still in state.
 *
 * A questionnaire answer is checked before the busy guard: the engine is
 * legitimately running while it waits, so the answer must not be blocked.
 */
internal fun planSend(
    input: String?,
    note: String?,
    url: String?,
    composerText: String,
    askUserPending: Boolean,
    busy: Boolean,
    images: List<PendingImage>,
): SendDecision {
    val text = (input ?: note ?: url ?: composerText).trim()
    if (askUserPending) {
        return if (text.isBlank()) SendDecision.Ignored else SendDecision.AnswerAskUser(text)
    }
    if (busy) return SendDecision.Busy
    if (text.isBlank() && images.isEmpty()) return SendDecision.Ignored
    // Pure image send: a fixed instruction carries the screenshots into the session.
    if (text.isBlank()) return SendDecision.Assist(CreateViewModel.PURE_IMAGE_INSTRUCTION, images)
    if (isAssistRequest(text)) {
        return SendDecision.Assist(resolveAssistInstruction(text, text), images)
    }
    val (derivedUrl, derivedNote) = GithubUrlParser.extract(text)
    return SendDecision.Generate(url = derivedUrl.orEmpty(), note = derivedNote, images = images)
}

/** Payload of a fresh generation start, everything the view model needs to reduce. */
internal data class GenerateStart(
    val url: String,
    val userPrompt: String?,
    val appGuess: String,
    val initialPhase: String,
    val phaseTitle: String,
    val images: List<PendingImage>,
    /** App names the engine's conflict check must ignore (the name this session saved). */
    val ignoredNames: Set<String>,
)

/**
 * Plan a generation start, or null when the input holds no GitHub url (the
 * view model then reports the invalid-url hint). [resumeFrom] only decides the
 * phase the engine starts at; the checkpoint itself stays with the bridge.
 */
internal fun planGenerate(
    rawUrlInput: String,
    noteInput: String,
    savedAppName: String? = null,
    resumeFrom: AiErrorAction? = null,
    images: List<PendingImage> = emptyList(),
): GenerateStart? {
    val (url, extraFromField) = GithubUrlParser.extract(rawUrlInput)
    if (url == null) return null
    val userPrompt = listOfNotNull(
        extraFromField.ifBlank { null },
        noteInput.trim().ifBlank { null },
    ).joinToString(" ").ifBlank { null }
    val appGuess = GithubUrlParser.deriveAppName(url)
    val initialPhase = initialPhaseFor(resumeFrom)
    return GenerateStart(
        url = url,
        userPrompt = userPrompt,
        appGuess = appGuess,
        initialPhase = initialPhase,
        phaseTitle = PhaseSummaryFormatter.formatPhaseStart(initialPhase, appGuess, 0),
        images = images,
        ignoredNames = ignoredNamesForConflictCheck(savedAppName),
    )
}

/** Engine phase a retry action resumes from; anything else starts fresh research. */
internal fun initialPhaseFor(resumeFrom: AiErrorAction?): String = when (resumeFrom) {
    AiErrorAction.RETRY_FROM_ANALYZE -> "analyze"
    AiErrorAction.RETRY_FROM_GENERATE -> "generate"
    else -> "research"
}

/** Payload of an assist start, including the replay bookkeeping of this round. */
internal data class AssistStart(
    val instruction: String,
    /** Text of the user bubble this round adds (chip rounds show a label, not the prompt). */
    val userPrompt: String,
    val currentManifest: String,
    val history: List<String>,
    /** Images actually sent: fresh attachments, else the replayed screenshot. */
    val images: List<PendingImage>,
    /** Bubble whose images this round replays; the view model flags it after the send. */
    val replaySourceId: String?,
    val phaseTitle: String,
    val ignoredNames: Set<String>,
    val suggestedAppName: String?,
    /** True when any user bubble referenced @bucket/app, so the read tool stays registered. */
    val hasManifestRefs: Boolean,
)

/**
 * Plan an assist start, or null for a blank instruction (a defensive no-op
 * path, never an error). [attempt] is the round counter after the increment and
 * feeds the execution step title.
 */
internal fun planAssist(
    instruction: String,
    displayUserText: String?,
    manifestJson: String,
    messages: List<ChatMessage>,
    images: List<PendingImage>,
    suggestedAppName: String?,
    savedAppName: String?,
    attempt: Int,
): AssistStart? {
    val text = instruction.trim()
    if (text.isBlank()) return null
    val userPrompt = displayUserText?.trim().takeUnless { it.isNullOrBlank() } ?: text
    return AssistStart(
        instruction = text,
        userPrompt = userPrompt,
        currentManifest = manifestJson,
        history = extractUserHistory(messages),
        // A text-only follow-up must still reach the model with the screenshot that
        // triggered it; only freshly attached images are recorded on the new bubble.
        images = resolveAssistImages(images, messages),
        replaySourceId = if (images.isEmpty()) replaySourceIn(messages)?.id else null,
        phaseTitle = PhaseSummaryFormatter.formatPhaseStart(
            phase = "assist",
            appName = suggestedAppName,
            attempt = attempt,
        ),
        ignoredNames = ignoredNamesForConflictCheck(savedAppName),
        suggestedAppName = suggestedAppName,
        // This round's own message counts: a reference typed now keeps the tool registered.
        hasManifestRefs = userManifestRefs(messages + ChatMessage(role = ChatRole.User, text = userPrompt))
            .isNotEmpty(),
    )
}
