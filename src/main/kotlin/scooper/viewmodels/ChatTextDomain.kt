package scooper.viewmodels

import com.scoopai.core.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import scooper.service.GithubUrlParser
import scooper.util.tr
import scooper.util.trn

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

/**
 * One key-level change between two manifest revisions. Replaces the previous
 * raw-string diff lines so consumers (chip colors, explanation synthesis, tr
 * rendering in a later batch) dispatch on the type instead of parsing prefixes.
 */
sealed interface ManifestDiffEntry {
    /** The diff could not be computed (unparseable JSON): a generic update line. */
    data object Generic : ManifestDiffEntry

    /** Key present only in the new manifest. */
    data class Added(val key: String) : ManifestDiffEntry

    /** Key present only in the old manifest. */
    data class Removed(val key: String) : ManifestDiffEntry

    /** Key present on both sides with a different value (non-version, or non-primitive). */
    data class Modified(val key: String) : ManifestDiffEntry

    /** The version key changed between two primitive values. */
    data class VersionChanged(val from: String, val to: String) : ManifestDiffEntry
}

/**
 * Renders the entry for diff chips and summaries (H2: tr() at render/call time;
 * key names are manifest field names and stay English per design rule 3, so
 * only the symbolic prefix templates and the generic sentinel label are msgids).
 */
fun ManifestDiffEntry.display(): String = when (this) {
    is ManifestDiffEntry.Added -> "+ $key"
    is ManifestDiffEntry.Removed -> "- $key"
    is ManifestDiffEntry.Modified -> "~ $key"
    is ManifestDiffEntry.VersionChanged -> "~ " + tr("version: {{range}}", "range" to versionRange(from, to))
    ManifestDiffEntry.Generic -> tr("Manifest updated")
}

/** Shared "from -> to" formatting for the two version-range msgids. */
internal fun versionRange(from: String, to: String): String = "$from → $to"

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
            // Ask-user answer/cancel bubbles are marked structurally at their single
            // write point, so filtering works no matter which UI language rendered
            // the bubble text (e.g. a localized "Let AI decide").
            ChatRole.User -> msg.text.isNotBlank() && !msg.askUserBubble
            // Error bubbles carry no conversation text (agentError leaves it blank) and are not context.
            ChatRole.Agent -> msg.text.isNotBlank() && msg.errorCategory == null
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

internal fun computeManifestDiff(oldJson: String, newJson: String): List<ManifestDiffEntry> {
    if (oldJson.isBlank() || newJson.isBlank() || oldJson == newJson) {
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
        return listOf(ManifestDiffEntry.Generic)
    }

    val diffs = mutableListOf<ManifestDiffEntry>()
    val allKeys = (oldObj.keys + newObj.keys).toSortedSet()

    for (key in allKeys) {
        val oldVal = oldObj[key]
        val newVal = newObj[key]

        when {
            oldVal == null && newVal != null -> diffs.add(ManifestDiffEntry.Added(key))
            oldVal != null && newVal == null -> diffs.add(ManifestDiffEntry.Removed(key))
            oldVal != newVal -> {
                if (key == "version" && oldVal is JsonPrimitive && newVal is JsonPrimitive) {
                    diffs.add(ManifestDiffEntry.VersionChanged(oldVal.content, newVal.content))
                } else {
                    diffs.add(ManifestDiffEntry.Modified(key))
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
    if (isAssistScenario) tr("Assist finished with issues") else tr("Generation finished with issues")

/** Msgid form of [finishedWithIssuesNotice] for structured LocalizedText copy. */
internal fun finishedWithIssuesNoticeMsgid(isAssistScenario: Boolean): String =
    if (isAssistScenario) "Assist finished with issues" else "Generation finished with issues"

/**
 * Bubble text for a user assist that the engine answered directly (no manifest
 * change). Uses the engine explanation when present and non-blank, else the
 * localized fallback (Rule 9: resolved via tr() at enqueue). Pure function.
 */
internal fun answeredBubbleText(explanation: String?): String =
    explanation?.trim()?.takeIf { it.isNotEmpty() } ?: tr("Sorry, I could not produce an answer.")

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
 * Deterministic assist explanation (proposal 5.4): built from the key-level
 * [computeManifestDiff] output, e.g.
 * "Removed bin, added shortcuts, updated version (1.0.0 → 1.1.0). Validated and ready."
 * All segments are tr() msgids resolved at enqueue (Rule 9 content snapshot).
 */
internal fun synthesizeAssistExplanation(diff: List<ManifestDiffEntry>): String {
    val removed = mutableListOf<String>()
    val added = mutableListOf<String>()
    val modified = mutableListOf<String>()
    var versionChange: String? = null
    var genericUpdate = false

    for (entry in diff) {
        when (entry) {
            is ManifestDiffEntry.Removed -> removed.add(entry.key)
            is ManifestDiffEntry.Added -> added.add(entry.key)
            is ManifestDiffEntry.Modified -> modified.add(entry.key)
            is ManifestDiffEntry.VersionChanged ->
                versionChange = tr("updated version ({{range}})", "range" to versionRange(entry.from, entry.to))
            ManifestDiffEntry.Generic -> genericUpdate = true
        }
    }

    val phrases = buildList {
        if (removed.isNotEmpty()) add(tr("Removed {{keys}}", "keys" to removed.joinToString(", ")))
        if (added.isNotEmpty()) add(tr("added {{keys}}", "keys" to added.joinToString(", ")))
        if (modified.isNotEmpty()) add(tr("updated {{keys}}", "keys" to modified.joinToString(", ")))
        if (versionChange != null) add(versionChange)
    }
    val body = when {
        phrases.isNotEmpty() -> phrases.joinToString(", ").replaceFirstChar { it.uppercaseChar() }
        genericUpdate -> tr("Updated the manifest")
        else -> tr("No manifest changes were required")
    }
    return "$body. " + tr("Validated and ready.")
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
        url.contains(".zip") -> tr("portable zip")
        url.contains(".7z") -> tr("portable 7z archive")
        url.contains(".msix") -> tr("MSIX package")
        url.contains(".msi") -> tr("MSI installer")
        url.contains(".exe") -> tr("installer")
        else -> tr("portable")
    }

    val shortcuts = (obj?.get("shortcuts") as? JsonArray)?.size ?: 0
    val persistCount = when (val persist = obj?.get("persist")) {
        is JsonArray -> persist.size
        is JsonPrimitive -> if (persist.contentOrNull?.isNotBlank() == true) 1 else 0
        else -> 0
    }

    val subject = when {
        name != null && version != null ->
            tr("Generated manifest for {{name}} {{version}}", "name" to name, "version" to version)
        name != null -> tr("Generated manifest for {{name}}", "name" to name)
        version != null -> tr("Generated manifest {{version}}", "version" to version)
        else -> tr("Generated manifest")
    } + " ($strategy)"
    val features = mutableListOf<String>()
    if (shortcuts > 0) {
        features.add(trn("{{n}} shortcut", "{{n}} shortcuts", shortcuts, "n" to "$shortcuts"))
    }
    if (persistCount > 0) {
        features.add(trn("{{n}} persist entry", "{{n}} persist entries", persistCount, "n" to "$persistCount"))
    }
    val featureText = if (features.isEmpty()) "" else ". " + features.joinToString(", ")
    return "$subject$featureText. " + tr("Validated and ready.")
}

/**
 * The engine combines the generate and repair explanations into a single string
 * with this separator (ScoopManifestStrategy.getCombinedExplanation). Split on
 * it and localize the English repair prefix via tr().
 */
private const val REPAIR_SEPARATOR = "\n\nFix applied: "

/**
 * Compose the finished-bubble text following the proposal 5.3 priority:
 * conflict/issues classification keeps the main text (the explanation, when present,
 * is appended as a trailing paragraph); a normal finish uses the engine explanation,
 * else [fallbackText] (the deterministic synthesis). A blank explanation counts as
 * missing. When the engine explanation carries the repair suffix (see
 * [REPAIR_SEPARATOR]) the English "Fix applied: " prefix is split out and localized
 * through tr() at compose time. Pure function.
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
        isConflictOnly -> appendExplanationParagraph(conflictText, trimmed?.let(::localizedExplanation))
        finishedWithIssues -> appendExplanationParagraph(issuesText, trimmed?.let(::localizedExplanation))
        else -> trimmed?.let(::localizedExplanation) ?: fallbackText
    }
}

/**
 * Localize the engine repair prefix in a combined explanation. Explanations
 * without the separator pass through unchanged.
 */
private fun localizedExplanation(explanation: String): String =
    when (val idx = explanation.indexOf(REPAIR_SEPARATOR)) {
        -1 -> explanation
        else ->
            explanation.substring(0, idx) + "\n\n" + tr("Fix applied:") + " " +
                explanation.substring(idx + REPAIR_SEPARATOR.length)
    }

private fun appendExplanationParagraph(base: String, explanation: String?): String =
    if (explanation == null) base else "$base\n\n$explanation"
