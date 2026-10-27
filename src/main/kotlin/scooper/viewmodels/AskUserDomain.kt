package scooper.viewmodels

internal const val DEFAULT_AUTONOMOUS_PROCEED_ANSWER = "Please make your best judgment and proceed autonomously."

/**
 * Whether the composer may accept and send input. A pending questionnaire
 * unlocks the composer even while the engine is running: the send is routed
 * to the questionnaire answer instead of starting a new request.
 */
internal fun composerSendEnabled(
    running: Boolean,
    testing: Boolean,
    askUserPending: Boolean,
    installing: Boolean = false,
): Boolean = askUserPending || (!running && !testing && !installing)

internal fun resolveAskUserBubbleText(response: String, isDownloadPrompt: Boolean): String {
    if (!isDownloadPrompt) return response
    return when {
        response.equals("yes", ignoreCase = true) || response.contains("proceed", ignoreCase = true) -> "Download confirmed"
        response.equals("no", ignoreCase = true) || response.contains("cancel", ignoreCase = true) || response.contains("skip", ignoreCase = true) -> "Download skipped"
        else -> response
    }
}

private val DOWNLOAD_ASSET_EXTENSIONS = setOf(".zip", ".exe", ".msi", ".7z", ".tar.gz", ".tgz")

/**
 * Whether an option label is itself concrete enough to show as the download detail:
 * it names an asset file (archive/installer extension) or a URL. Generic labels such
 * as "Download and proceed" are not asset-like and fall through to prompt extraction,
 * otherwise the two confirm/skip bubbles would be indistinguishable.
 */
internal fun looksLikeDownloadAsset(label: String): Boolean =
    label.contains("http://", ignoreCase = true) ||
        label.contains("https://", ignoreCase = true) ||
        DOWNLOAD_ASSET_EXTENSIONS.any { label.contains(it, ignoreCase = true) }

// Total single-line budget for download-confirm detail notes. Internal so the
// renderer reuses the exact same budget when eliding URL-bearing notes.
internal const val DOWNLOAD_DETAIL_MAX = 88

private const val ELLIPSIS = "…"

private val DOWNLOAD_FILE_NAME_QUOTED = Regex(
    """'([^'\n]*\s*[^'\s\n]*(?:\.(?:zip|exe|msi|7z))[^'\n]*)'""",
    RegexOption.IGNORE_CASE,
)

private val DOWNLOAD_BARE_ASSET = Regex(
    """[A-Za-z0-9][A-Za-z0-9._~:/?#@!\$&'()*+,;=%-]*(?:\.(?:zip|exe|msi|7z|tar\.gz|tgz))[A-Za-z0-9._~:/?#@!\$&'()*+,;=%-]*""",
    RegexOption.IGNORE_CASE,
)

private val DOWNLOAD_URL = Regex("""https?://[^\s'")<>]+""", RegexOption.IGNORE_CASE)

// Size in parentheses right after the file name quote, e.g. "(1.2 MB)".
private val DOWNLOAD_SIZE = Regex("""\(([\d.,]+\s*[KMG]i?B)\)""", RegexOption.IGNORE_CASE)

/**
 * Builds a short single-line note describing what the user confirmed/skipped
 * when answering a download questionnaire. Returns null when the request is
 * not a download prompt or nothing concrete can be extracted. The priority is:
 * 1. the label of the QuestionnaireOption matching the user's answer, but only
 *    when the label itself names an asset (extension or URL); shown verbatim
 *    (budgeted to one line);
 * 2. otherwise the download prompt itself, laid out on one line as the
 *      URL middle-elided by [elideUrlMiddle] to fit the whole line budget;
 *      when no URL is present the file name (plus "(size)" when the prompt
 *      states one) is shown instead;
 *    so generic labels like "Download and proceed" never shadow the real asset;
 * 3. null when no URL and no asset file name can be extracted (and no
 *    matchable asset-like label), so the bubble keeps its main text only.
 * The returned text is the main bubble text companion only; the bubble's main
 * text ("Download confirmed"/"Download skipped") is never changed here.
 */
internal fun buildDownloadConfirmDetail(request: AskUserRequest?, response: String): String? {
    if (request == null || !request.isDownloadPrompt) return null
    val question = request.questions.firstOrNull { q ->
        q.id == "download_confirm" ||
            q.prompt.contains("download", ignoreCase = true) ||
            q.prompt.contains("installer/archive", ignoreCase = true)
    } ?: request.questions.firstOrNull() ?: return null

    val matchedLabel = question.options.firstOrNull { opt ->
        response.trim().equals(opt.label, ignoreCase = true) ||
            response.contains(opt.label, ignoreCase = true)
    }?.label
    return if (matchedLabel != null && looksLikeDownloadAsset(matchedLabel)) {
        // Asset-like option label: show it verbatim as a single line.
        normalizeDownloadLine(matchedLabel)
    } else {
        // Generic (or unmatched) label: rebuild the detail from the prompt so
        // labels like "Download and proceed" never shadow the real asset.
        detailFromDownloadPrompt(question.prompt) ?: matchedLabel?.let(::normalizeDownloadLine)
    }
}

/**
 * Extracts the download detail from the prompt as a single line: the URL
 * middle-elided to fit [DOWNLOAD_DETAIL_MAX], with the whole budget given to
 * it. When no URL is present it falls back to the file name (plus "(size)"
 * when the prompt states one). Returns null when neither a URL nor an asset
 * file name can be extracted, so the caller keeps the bubble's main text only.
 */
internal fun detailFromDownloadPrompt(prompt: String): String? {
    val url = DOWNLOAD_URL.find(prompt)?.value?.trim()?.takeIf { it.isNotBlank() }
    if (url != null) return elideUrlMiddle(url, DOWNLOAD_DETAIL_MAX)
    val quoted = DOWNLOAD_FILE_NAME_QUOTED.find(prompt)
    if (quoted != null) {
        // Trim leftover wrapping quotes: prompts sometimes leak a trailing
        // quote into the extracted name (e.g. "name.zip'").
        val fileName = quoted.groupValues[1].trim().trim('\'', '"').trim()
        val size = sizeAfterQuote(prompt, quoted.range.last + 1)
        return normalizeDownloadLine(if (size != null) "$fileName ($size)" else fileName)
    }
    val bare = DOWNLOAD_BARE_ASSET.find(prompt)?.value
        ?.trim()
        ?.trim('\'', '"')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    if (bare != null) return normalizeDownloadLine(bare)
    return null
}

/** Size in "(1.2 MB)" form directly following the closing quote at [from]. */
private fun sizeAfterQuote(prompt: String, from: Int): String? {
    val rest = prompt.substring(from)
    val match = DOWNLOAD_SIZE.find(rest) ?: return null
    // The parenthesis must directly follow the quote; only whitespace in between.
    if (rest.substring(0, match.range.first).isNotBlank()) return null
    return match.groupValues[1].replace(Regex("\\s+"), " ").trim()
}

/**
 * Elides the middle of an overlong URL, keeping the scheme://host prefix and
 * the last path segment (the file name) intact, separated by "/…/". When the
 * intermediate path allows it, the longest prefix that still fits is kept
 * (e.g. "https://github.com/user/…/v1.0.zip"). Query and fragment are dropped
 * before measuring; if the file name itself cannot fit it is tail truncated
 * with an ellipsis so the result never exceeds [maxLen]. URLs at or under
 * [maxLen] are returned unchanged.
 */
internal fun elideUrlMiddle(url: String, maxLen: Int): String {
    if (maxLen <= 0) return ""
    if (url.length <= maxLen) return url
    // Query/fragment never survive elision; dropping them may already fit.
    val clean = url.substringBefore('?').substringBefore('#')
    if (clean.length <= maxLen) return clean
    val schemeEnd = clean.indexOf("://")
    if (schemeEnd < 0) return clean.take(maxLen - 1) + ELLIPSIS
    val firstSlash = clean.indexOf('/', schemeEnd + 3)
    val schemeAndHost = if (firstSlash < 0) clean else clean.take(firstSlash)
    val path = if (firstSlash < 0) "" else clean.substring(firstSlash)
    val lastSlash = path.lastIndexOf('/')
    val last = if (lastSlash < 0) "" else path.substring(lastSlash + 1)
    if (last.isEmpty()) {
        // No path segments (bare or trailing-slash host): tail truncate.
        return clean.take(maxLen - 1) + ELLIPSIS
    }
    val minResult = "$schemeAndHost/$ELLIPSIS/$last"
    if (minResult.length > maxLen) {
        // Host + "/…/" + file name still too wide: tail truncate the name.
        val nameRoom = maxLen - schemeAndHost.length - 3
        if (nameRoom >= 2) {
            return "$schemeAndHost/$ELLIPSIS/" + last.take(nameRoom - 1) + ELLIPSIS
        }
        return clean.take(maxLen - 1) + ELLIPSIS
    }
    // Greedy: keep the longest intermediate path prefix that still fits.
    var best = minResult
    var slash = path.indexOf('/', 1)
    while (slash in 1 until lastSlash) {
        val candidate = schemeAndHost + path.substring(0, slash) + "/$ELLIPSIS/$last"
        if (candidate.length > maxLen) break
        best = candidate
        slash = path.indexOf('/', slash + 1)
    }
    return best
}

internal fun normalizeDownloadLine(raw: String): String {
    val collapsed = raw.replace(Regex("\\s+"), " ").trim()
    // URLs get middle elision so scheme, host and file name stay visible.
    if (collapsed.startsWith("http", ignoreCase = true)) {
        return elideUrlMiddle(collapsed, DOWNLOAD_DETAIL_MAX)
    }
    return if (collapsed.length > DOWNLOAD_DETAIL_MAX) {
        collapsed.take(DOWNLOAD_DETAIL_MAX - 1) + ELLIPSIS
    } else {
        collapsed
    }
}
