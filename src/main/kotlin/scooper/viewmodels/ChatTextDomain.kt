package scooper.viewmodels

import com.scoopai.core.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import scooper.service.GithubUrlParser

/**
 * Whether [text] is a valid Scoop manifest, using the same bar as the CLI
 * ([com.scoopai.tools.manifest.FileManifestIo.validate]): it must parse with kotlinx
 * Json and the root element must be a [JsonObject]. Blank text counts as invalid.
 */
internal fun isScoopManifestJson(text: String): Boolean {
    if (text.isBlank()) return false
    return try {
        Json.parseToJsonElement(text) is JsonObject
    } catch (_: Exception) {
        false
    }
}

/** Classification of a finished generation, splitting assist exhaustion from invalid JSON. */
data class FinishClassification(
    /** True when the manifest is not valid Scoop manifest JSON. */
    val invalidJson: Boolean,
    /** True when the run ended with issues (assist exhausted and/or invalid JSON). Drives badge/toast. */
    val finishedWithIssues: Boolean,
)

internal fun isNameConflictIssue(issue: String): Boolean {
    val text = issue.lowercase()
    return text.contains("conflicts with an existing manifest") ||
        text.contains("conflicts with an existing") ||
        text.contains("already exists in") ||
        text.contains("already used")
}

internal fun isNameConflictOnly(issues: List<String>, invalidJson: Boolean): Boolean {
    if (invalidJson || issues.isEmpty()) return false
    return issues.all { isNameConflictIssue(it) }
}

/**
 * Classify a finished generation result.
 *
 * The exhausted flag alone must NOT disable Save: a valid JSON manifest
 * with unresolved assist issues can still be saved (the UI keeps the button
 * enabled and only [invalidJson] gates it).
 */
internal fun classifyManifestFinish(manifest: String, exhausted: Boolean): FinishClassification {
    val invalidJson = !isScoopManifestJson(manifest)
    return FinishClassification(
        invalidJson = invalidJson,
        finishedWithIssues = exhausted || invalidJson,
    )
}

private val IGNORED_HISTORY_TEXTS = setOf(
    "Download confirmed",
    "Download skipped",
    "Let AI decide",
)

private const val HISTORY_MAX_ENTRIES = 12

private const val HISTORY_LINE_MAX = 400

/**
 * How many recent conversation entries [resolveAssistImages] scans backwards when
 * looking for an image to replay. Four entries cover "screenshot turn, AI reply,
 * one more exchange" without reaching arbitrarily far into the session.
 */
private const val IMAGE_REPLAY_LOOKBACK = 4

/**
 * The most recent user message in the lookback window whose images are still worth
 * replaying, or null. Messages already replayed once are skipped to keep repeated
 * text-only rounds from re-sending the same (expensive) screenshots.
 */
internal fun replaySourceIn(history: List<ChatMessage>): ChatMessage? =
    history.asReversed()
        .take(IMAGE_REPLAY_LOOKBACK)
        .firstOrNull { it.role == ChatRole.User && !it.imagesReplayed && it.images.isNotEmpty() }

/**
 * Images to attach to this assist round. A round with freshly attached images wins;
 * otherwise the images of the most recent not-yet-replayed user message in the
 * lookback window are replayed.
 *
 * Replay is required because the engine deliberately keeps images out of
 * [AssistContext] and the checkpoint, so a text-only follow-up such as "fix the
 * problem above" would otherwise reach the model with no image at all. But each
 * replayed image costs several thousand vision tokens, so a message whose images
 * already went out once ([ChatMessage.imagesReplayed]) is skipped: without the
 * flag, N consecutive text-only rounds would re-send the identical screenshot N
 * times. Once every image-carrying message in the window has been replayed, this
 * returns empty and the follow-up runs text-only (the model still sees the image
 * description markers in the history lines). Cost over context freshness is a
 * deliberate trade-off.
 */
internal fun resolveAssistImages(
    fresh: List<PendingImage>,
    history: List<ChatMessage>,
): List<PendingImage> {
    if (fresh.isNotEmpty()) return fresh
    return replaySourceIn(history)
        ?.images
        ?.take(CreateViewModel.MAX_PENDING_IMAGES)
        .orEmpty()
}

internal fun extractUserHistory(messages: List<ChatMessage>): List<String> {
    val relevant = messages.filter { msg ->
        when (msg.role) {
            ChatRole.User ->
                msg.text.isNotBlank() &&
                    msg.text !in IGNORED_HISTORY_TEXTS &&
                    !msg.text.startsWith("Question:")
            // Error bubbles fold failure details into actions; they are not conversation context.
            ChatRole.Agent -> msg.text.isNotBlank() && msg.actions.isEmpty()
        }
    }
    // Tail only: recent turns matter for coreference in assist/refine prompts.
    return relevant.takeLast(HISTORY_MAX_ENTRIES).map { msg ->
        val imagePlaceholder = if (msg.role == ChatRole.User && msg.images.isNotEmpty()) {
            " [${msg.images.size} screenshot${if (msg.images.size > 1) "s" else ""} attached]"
        } else ""
        val body = when (msg.role) {
            ChatRole.User -> {
                val base = if (!msg.note.isNullOrBlank()) "${msg.text} (Requirements: ${msg.note})" else msg.text
                base + imagePlaceholder
            }
            ChatRole.Agent -> msg.text + imagePlaceholder
        }
        val prefix = if (msg.role == ChatRole.User) "User: " else "Assistant: "
        val line = prefix + body
        if (line.length <= HISTORY_LINE_MAX) line else line.take(HISTORY_LINE_MAX - 3) + "..."
    }
}

internal fun isLikelyUrl(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true) ||
        trimmed.startsWith("github.com/", ignoreCase = true)
}

/**
 * Determines whether a send action is an assist request (incremental modification
 * or conversation) or a fresh generation: a GitHub URL generates, everything else
 * assists. Failure-prone inputs (non-GitHub URL prefixes, bare domains) degrade
 * to assist instead of raising an invalid-URL error; empty input assist is a
 * defensive no-op path.
 */
internal fun isAssistRequest(input: String): Boolean =
    GithubUrlParser.extract(input.trim()).first == null

internal fun resolveAssistInstruction(
    inputUrl: String,
    inputNote: String,
): String {
    val rawNote = inputNote.trim()
    if (rawNote.isNotBlank()) return rawNote
    val rawUrl = inputUrl.trim()
    if (!isLikelyUrl(rawUrl) && rawUrl.isNotBlank()) return rawUrl
    return ""
}

internal fun computeManifestDiff(oldJson: String, newJson: String): List<String> {
    if (oldJson.isBlank()) {
        return if (newJson.isNotBlank()) listOf("Initial manifest") else emptyList()
    }
    if (newJson.isBlank() || oldJson == newJson) {
        return emptyList()
    }

    val oldObj = try {
        Json.parseToJsonElement(oldJson) as? JsonObject
    } catch (_: Exception) {
        null
    }
    val newObj = try {
        Json.parseToJsonElement(newJson) as? JsonObject
    } catch (_: Exception) {
        null
    }

    if (oldObj == null || newObj == null) {
        return listOf("Manifest updated")
    }

    val diffs = mutableListOf<String>()
    val allKeys = (oldObj.keys + newObj.keys).toSortedSet()

    for (key in allKeys) {
        val oldVal = oldObj[key]
        val newVal = newObj[key]

        when {
            oldVal == null && newVal != null -> diffs.add("+ $key")
            oldVal != null && newVal == null -> diffs.add("- $key")
            oldVal != newVal -> {
                if (key == "version" && oldVal is JsonPrimitive && newVal is JsonPrimitive) {
                    diffs.add("~ version: ${oldVal.content} → ${newVal.content}")
                } else {
                    diffs.add("~ $key")
                }
            }
        }
    }
    return diffs
}

/**
 * True when the current finish should be explained as an assist round: the run was in
 * the assist phase AND a previous manifest revision exists to diff against. Internal
 * repair rounds ("repair") are not user assists and return false. Pure helper for the
 * finished-bubble composition in handleFinished.
 */
internal fun isAssistScenario(currentPhase: String?, revisions: List<ManifestRevision>): Boolean {
    if (!currentPhase.equals("assist", ignoreCase = true)) return false
    return revisions.isNotEmpty()
}

/**
 * User-facing notice for a finished-with-issues run: assist rounds say "Assist",
 * initial generation says "Generation". Pure function (toast + issues bubble text).
 */
internal fun finishedWithIssuesNotice(isAssistScenario: Boolean): String =
    if (isAssistScenario) "Assist finished with issues" else "Generation finished with issues"

/**
 * Bubble text for a user assist that the engine answered directly (no manifest
 * change). Uses the engine explanation when present and non-blank, else a generic
 * English fallback. Pure function.
 */
internal fun answeredBubbleText(explanation: String?): String =
    explanation?.trim()?.takeIf { it.isNotEmpty() } ?: "Sorry, I could not produce an answer."

/**
 * Final agent bubble for a successful finish (answered / generated / no-assets).
 * Token usage is attached only when the provider reported a non-zero total, so
 * "0 tokens" footers never render. Pure function (bubble assembly testable).
 */
internal fun agentReplyMessage(text: String, tokenUsage: TokenUsage?): ChatMessage =
    ChatMessage(
        role = ChatRole.Agent,
        text = text,
        tokenUsage = tokenUsage?.takeIf { it.totalTokens > 0 },
    )

/**
 * Text of the newest user bubble in [messages], or null when none exists. Used by
 * the RETRY_ANSWER error action to resend a failed question. Pure function.
 */
internal fun lastUserQuestion(messages: List<ChatMessage>): String? =
    messages.lastOrNull { it.role == ChatRole.User }?.text

/**
 * Deterministic assist explanation (proposal 5.4): human-readable English built from
 * the key-level [computeManifestDiff] output, e.g.
 * "Removed bin, added shortcuts, updated version (1.0.0 → 1.1.0). Validated and ready."
 * Pure function; used when the engine explanation is missing.
 */
internal fun synthesizeAssistExplanation(diff: List<String>): String {
    val removed = mutableListOf<String>()
    val added = mutableListOf<String>()
    val modified = mutableListOf<String>()
    var versionChange: String? = null
    var genericUpdate = false

    for (entry in diff) {
        when {
            entry.startsWith("- ") -> removed.add(entry.removePrefix("- "))
            entry.startsWith("+ ") -> added.add(entry.removePrefix("+ "))
            entry.startsWith("~ ") -> {
                val key = entry.removePrefix("~ ")
                if (key.startsWith("version:")) {
                    versionChange = "updated version (${key.removePrefix("version:").trim()})"
                } else {
                    modified.add(key)
                }
            }
            entry == "Manifest updated" -> genericUpdate = true
            // "Initial manifest" and unknown entries carry no assist detail
        }
    }

    val phrases = buildList {
        if (removed.isNotEmpty()) add("Removed " + removed.joinToString(", "))
        if (added.isNotEmpty()) add("added " + added.joinToString(", "))
        if (modified.isNotEmpty()) add("updated " + modified.joinToString(", "))
        if (versionChange != null) add(versionChange)
    }
    val body = when {
        phrases.isNotEmpty() -> phrases.joinToString(", ").replaceFirstChar { it.uppercaseChar() }
        genericUpdate -> "Updated the manifest"
        else -> "No manifest changes were required"
    }
    return "$body. Validated and ready."
}

/**
 * Deterministic initial-generation explanation (proposal 5.4): describes the manifest's
 * key fields (name, version, url strategy, shortcuts/persist counts), e.g.
 * "Generated manifest for fd 10.2.0 (portable zip). 1 shortcut, 2 persist entries.
 * Validated and ready." Missing fields are omitted robustly; [appName] is the fallback
 * when the manifest has no name field. Pure function.
 */
internal fun synthesizeGenerateExplanation(manifestJson: String, appName: String?): String {
    val obj = try {
        Json.parseToJsonElement(manifestJson) as? JsonObject
    } catch (_: Exception) {
        null
    }

    fun stringField(key: String): String? = obj?.get(key)
        ?.let { it as? JsonPrimitive }
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    // Architecture-package form: "architecture": {"64bit": {"url": ...}}. Used as
    // the strategy source when the top-level url is missing; empty when neither
    // exists (the strategy then degrades to "portable").
    fun architectureFallbackUrl(): String? = obj?.get("architecture")
        ?.let { it as? JsonObject }
        ?.values
        ?.mapNotNull { arch ->
            (arch as? JsonObject)?.get("url")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
                ?.trim()
                ?.takeIf { s -> s.isNotEmpty() }
        }
        ?.firstOrNull()

    val name = stringField("name")
        ?: appName?.trim()?.takeIf { it.isNotEmpty() }
    val version = stringField("version")
    val url = (stringField("url") ?: architectureFallbackUrl()).orEmpty().lowercase()
    val strategy = when {
        url.contains(".zip") -> "portable zip"
        url.contains(".7z") -> "portable 7z archive"
        url.contains(".msix") -> "MSIX package"
        url.contains(".msi") -> "MSI installer"
        url.contains(".exe") -> "installer"
        else -> "portable"
    }

    val shortcuts = (obj?.get("shortcuts") as? JsonArray)?.size ?: 0
    val persistCount = when (val persist = obj?.get("persist")) {
        is JsonArray -> persist.size
        is JsonPrimitive -> if (persist.contentOrNull?.isNotBlank() == true) 1 else 0
        else -> 0
    }

    val subject = buildString {
        append("Generated manifest")
        if (name != null) append(" for $name")
        if (version != null) append(" $version")
        append(" ($strategy)")
    }
    val features = mutableListOf<String>()
    if (shortcuts > 0) features.add(if (shortcuts == 1) "1 shortcut" else "$shortcuts shortcuts")
    if (persistCount > 0) features.add(if (persistCount == 1) "1 persist entry" else "$persistCount persist entries")
    val featureText = if (features.isEmpty()) "" else ". " + features.joinToString(", ")
    return "$subject$featureText. Validated and ready."
}

/**
 * Compose the finished-bubble text following the proposal 5.3 priority:
 * conflict/issues classification keeps the main text (the explanation, when present,
 * is appended as a trailing paragraph); a normal finish uses the engine explanation,
 * else [fallbackText] (the deterministic synthesis). A blank explanation counts as
 * missing. Pure function.
 */
internal fun composeFinishedBubbleText(
    explanation: String?,
    isConflictOnly: Boolean,
    finishedWithIssues: Boolean,
    conflictText: String,
    issuesText: String,
    fallbackText: String,
): String {
    val trimmed = explanation?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        isConflictOnly -> appendExplanationParagraph(conflictText, trimmed)
        finishedWithIssues -> appendExplanationParagraph(issuesText, trimmed)
        else -> trimmed ?: fallbackText
    }
}

private fun appendExplanationParagraph(base: String, explanation: String?): String =
    if (explanation == null) base else "$base\n\n$explanation"
