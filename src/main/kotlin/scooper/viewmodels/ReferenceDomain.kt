package scooper.viewmodels

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import scooper.service.ScoopClient

/**
 * Backs the engine's `read_manifest_reference` tool (architecture A: the host
 * owns the local bucket lookup): locate buckets/<bucket>/bucket/<name>.json in
 * the local scoop root and embed the manifest's raw JSON as a JSON value. Any
 * failure degrades to a diagnosable success:false reply so the session survives.
 */
internal fun readManifestRefReply(scoopClient: ScoopClient, bucket: String, name: String): String {
    val bucketDir = scoopClient.bucketsBaseDir.resolve(bucket)
    if (!bucketDir.isDirectory) {
        return readManifestRefFailure("bucket '$bucket' not found")
    }
    val manifestFile = bucketDir.resolve("bucket/$name.json")
    if (!manifestFile.isFile) {
        return readManifestRefFailure("app '$name' not found in bucket '$bucket'")
    }
    return try {
        val manifest = Json.parseToJsonElement(manifestFile.readText())
        buildJsonObject {
            put("success", true)
            put("bucket", bucket)
            put("name", name)
            put("path", manifestFile.absolutePath)
            put("manifest", manifest)
        }.toString()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        readManifestRefFailure("failed to read manifest for '$name' in bucket '$bucket': ${e.message}")
    }
}

private fun readManifestRefFailure(error: String): String = buildJsonObject {
    put("success", false)
    put("error", error)
}.toString()

/**
 * Builds the @ completion menu index by listing every manifest json directly under
 * buckets/<bucket>/bucket/, the same layout [readManifestRefReply] reads from, so
 * every suggestion is actually loadable by the tool. Best effort: unreadable dirs
 * are skipped; only the directory listing itself failing yields an empty list.
 */
internal fun scanManifestRefSuggestions(scoopClient: ScoopClient): List<ManifestRefSuggestion> {
    val suggestions = mutableListOf<ManifestRefSuggestion>()
    val bucketDirs = try {
        scoopClient.bucketsBaseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
    for (bucketDir in bucketDirs) {
        val entries = try {
            bucketDir.resolve("bucket").listFiles() ?: continue
        } catch (_: Exception) {
            continue
        }
        for (entry in entries) {
            val fileName = entry.name
            if (!fileName.endsWith(".json") || !entry.isFile) continue
            suggestions += ManifestRefSuggestion(bucketDir.name, fileName.removeSuffix(".json"))
        }
    }
    return suggestions
}

/**
 * Scans text for @bucket/app reference tokens: "@" at line start or after
 * whitespace (so email addresses and URL fragments never match), bucket/name
 * chars only, original casing preserved, deduped in order of appearance.
 */
internal fun extractManifestRefs(text: String): List<Pair<String, String>> {
    val regex = Regex("@([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+)")
    val seen = LinkedHashSet<Pair<String, String>>()
    for (match in regex.findAll(text)) {
        // The @ must sit at line start or after whitespace, so email addresses
        // (letter before @) and in-URL fragments (slash before @) never match.
        val start = match.range.first
        if (start > 0 && !text[start - 1].isWhitespace()) continue
        seen += match.groupValues[1] to match.groupValues[2]
    }
    return seen.toList()
}

/** Merged @bucket/app references across the conversation; only user bubbles count (AI mentions are not references). */
internal fun userManifestRefs(messages: List<ChatMessage>): List<Pair<String, String>> =
    messages.filter { it.role == ChatRole.User }
        .flatMap { extractManifestRefs(it.text) }
        .distinct()

/**
 * Incomplete @ token under the caret, or null when no completion applies.
 * The @ must sit at line start or after whitespace and be followed by bucket
 * and name chars with at most one "/" (same shape as [extractManifestRefs]
 * tokens: a reference is always @bucket/app); a fully formed "@bucket/name"
 * followed by whitespace or line end is done and no longer triggers the menu.
 * The returned query is the typed text after the @ and may contain the "/" of
 * a qualified prefix.
 */
private val REF_TOKEN_REGEX = Regex("[A-Za-z0-9_-]*(/?[A-Za-z0-9_-]*)?")

internal fun activeManifestRefToken(text: String, caret: Int): String? {
    if (caret <= 0 || caret > text.length) return null
    val at = text.lastIndexOf('@', caret - 1)
    if (at < 0) return null
    if (at > 0 && !text[at - 1].isWhitespace()) return null
    val token = text.substring(at + 1, caret)
    if (token.isEmpty()) return null
    if (!REF_TOKEN_REGEX.matches(token)) return null
    return token
}

/** One piece of user bubble text: plain text or a @bucket/app reference chip. */
sealed class ManifestRefSegment {
    data class Text(val text: String) : ManifestRefSegment()

    data class Ref(val bucket: String, val name: String) : ManifestRefSegment() {
        /** Original token text as typed, preserving casing (e.g. "@Main/Golang"). */
        val raw: String get() = "@$bucket/$name"
    }
}

/**
 * Splits bubble text into plain-text and reference segments so the UI can render
 * @bucket/app tokens as chips. Uses the same token rule as [extractManifestRefs]
 * (email/URL fragments stay plain text); unmatched parts stay text.
 */
internal fun splitManifestRefSegments(text: String): List<ManifestRefSegment> {
    val regex = Regex("@([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+)")
    val segments = mutableListOf<ManifestRefSegment>()
    var cursor = 0
    for (match in regex.findAll(text)) {
        // Same guard as extractManifestRefs: the @ must sit at line start or after
        // whitespace, so email addresses and in-URL fragments never match.
        val start = match.range.first
        if (start > 0 && !text[start - 1].isWhitespace()) continue
        if (start > cursor) segments += ManifestRefSegment.Text(text.substring(cursor, start))
        segments += ManifestRefSegment.Ref(match.groupValues[1], match.groupValues[2])
        cursor = match.range.last + 1
    }
    if (cursor < text.length) segments += ManifestRefSegment.Text(text.substring(cursor))
    return segments
}

/** One @bucket/app entry in the completion menu index. */
data class ManifestRefSuggestion(val bucket: String, val name: String) {
    /** Token inserted into the input when picked, with trailing space. */
    val insertion: String get() = "@$bucket/$name "
}

/** Max rows shown in the @ completion menu. */
internal const val REF_SUGGESTION_LIMIT = 8

/**
 * Filters the app index for the completion menu: case-insensitive prefix match on
 * name, on bucket, or on the qualified "bucket/name" form; capped at
 * [REF_SUGGESTION_LIMIT]. Pure function.
 */
internal fun filterRefSuggestions(
    suggestions: List<ManifestRefSuggestion>,
    prefix: String,
): List<ManifestRefSuggestion> {
    val p = prefix.trim().lowercase()
    return suggestions.asSequence()
        .filter { s ->
            val qualified = "${s.bucket}/${s.name}".lowercase()
            s.name.lowercase().startsWith(p) || s.bucket.lowercase().startsWith(p) ||
                (p.contains('/') && qualified.startsWith(p))
        }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.bucket.lowercase() }))
        .take(REF_SUGGESTION_LIMIT)
        .toList()
}

/**
 * Replaces the active @ token under the caret with the picked suggestion.
 * [query] is the token text after the @ (as returned by activeManifestRefToken),
 * so the removal span extends one char left to consume the @ itself and the
 * insertion (which already carries its own @) never doubles it.
 * Returns the field unchanged when the token text is no longer at the caret.
 */
internal fun applyRefSuggestion(
    field: TextFieldValue,
    query: String,
    suggestion: ManifestRefSuggestion,
): TextFieldValue {
    val caret = field.selection.min
    if (caret < query.length + 1) return field
    val text = field.text
    val start = caret - query.length - 1
    if (text.getOrNull(start) != '@') return field
    if (text.substring(start + 1, caret) != query) return field
    val insertion = suggestion.insertion
    val before = text.substring(0, start)
    val after = text.substring(caret)
    val newText = before + insertion + after
    return TextFieldValue(newText, TextRange(before.length + insertion.length))
}
