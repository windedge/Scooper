package scooper.service

import com.scoopai.core.ScoopAiGenerationException

/** Suggested user actions shown with an AI error bubble. */
enum class AiErrorAction {
    OPEN_SETTINGS,
    RETRY_FULL,
    RETRY_FROM_ANALYZE,
    RETRY_FROM_GENERATE,
}

/** Coarse cause category behind an AI generation failure. */
enum class AiErrorCategory {
    CAPABILITY,
    AUTH,
    MODEL_NOT_FOUND,
    BAD_PATH,
    RATE_LIMIT,
    SERVER,
    NETWORK_DNS,
    TIMEOUT,
    REFUSED,
    CONFIG,
    UNKNOWN,
}

/** User-facing view of an AI generation failure. */
data class AiErrorPresentation(
    val title: String,
    val detail: String,
    val actions: List<AiErrorAction>,
)

/**
 * Lightweight, table-driven pure translator that maps a generation failure
 * [Throwable] to a friendly [AiErrorPresentation] (title / detail / suggested
 * actions) and masks API-key secrets.
 *
 * Matching rules walk the cause chain up to [CAUSE_DEPTH] levels (with a
 * visited set so circular chains cannot loop) and run a keyword-ordered table:
 *
 *  1. [ScoopAiGenerationException] -> CAPABILITY, detail passes through (core
 *     already embeds an actionable hint; we do not re-translate it).
 *  2. 401 / 403 -> AUTH; 404 -> MODEL_NOT_FOUND (message names a model) or
 *     BAD_PATH; 429 -> RATE_LIMIT; 5xx -> SERVER.
 *  3. UnknownHost -> NETWORK_DNS; "timed out"/timeout -> TIMEOUT;
 *     "Connection refused" -> REFUSED.
 *  4. missing base URL -> CONFIG.
 *  5. fallback UNKNOWN.
 *
 * No ai.koog types are imported: Koog-style failures are matched purely on the
 * gateway message text, so the algorithm also works for other LLM stacks.
 */
object AiErrorTranslator {

    /** Max cause-chain depth inspected per throwable. */
    private const val CAUSE_DEPTH = 5

    /** Matches OpenAI-style `sk-...` API key tokens (both plain and dummy ones). */
    private val skRegex = Regex("""sk-[A-Za-z0-9_-]{8,}""")
    private val serverErrorRegex = Regex("""\b5\d{2}\b""")

    fun translate(error: Throwable): AiErrorPresentation {
        val (messages, isCapability) = collect(error)
        val detail = if (messages.isEmpty()) "(no error detail)" else messages.joinToString("\n")
        // Lowercased for case-insensitive keyword matching; status codes stay intact.
        val text = messages.joinToString("\n").lowercase()

        return when {
            isCapability -> presentation(
                title = "Model capability issue",
                detail = detail,
                actions = listOf(
                    AiErrorAction.RETRY_FULL,
                    AiErrorAction.RETRY_FROM_ANALYZE,
                    AiErrorAction.RETRY_FROM_GENERATE,
                ),
            )

            "401" in text || "403" in text -> presentation(
                title = "Authentication failed",
                detail = "Your API key may be invalid or expired. Check it in Settings.AI. " +
                    "Original error: $detail",
                actions = listOf(AiErrorAction.OPEN_SETTINGS),
            )

            "404" in text -> if ("model" in text) {
                presentation(
                    title = "Model not found",
                    detail = "The configured model was not found on this provider. Check the model " +
                        "name in Settings.AI. Original error: $detail",
                    actions = listOf(AiErrorAction.OPEN_SETTINGS),
                )
            } else {
                presentation(
                    title = "Resource not found (404)",
                    detail = "The server returned 404, which may mean an incorrect base URL or an " +
                        "unsupported endpoint. Check your base URL in Settings.AI. " +
                        "Original error: $detail",
                    actions = listOf(AiErrorAction.OPEN_SETTINGS),
                )
            }

            "429" in text -> presentation(
                title = "Rate limit exceeded",
                detail = "The provider rate-limited the request. Wait a moment and retry. " +
                    "Original error: $detail",
                actions = listOf(AiErrorAction.RETRY_FULL),
            )

            serverErrorRegex.containsMatchIn(text) -> presentation(
                title = "Server error",
                detail = "The provider returned a server error. This is usually transient; retry later. " +
                    "Original error: $detail",
                actions = listOf(AiErrorAction.RETRY_FULL),
            )

            "unknownhost" in text -> presentation(
                title = "Cannot resolve host",
                detail = "The AI endpoint host could not be resolved. Check your network connection and " +
                    "the base URL in Settings.AI. Original error: $detail",
                actions = listOf(AiErrorAction.OPEN_SETTINGS),
            )

            "timed out" in text || "timeout" in text -> presentation(
                title = "Request timed out",
                detail = "The request timed out. Check your network connection and retry. " +
                    "Original error: $detail",
                actions = listOf(AiErrorAction.RETRY_FULL),
            )

            "connection refused" in text -> presentation(
                title = "Connection refused",
                detail = "The connection to the AI endpoint was refused. Check that the server is " +
                    "running and the base URL is correct in Settings.AI. Original error: $detail",
                actions = listOf(AiErrorAction.OPEN_SETTINGS),
            )

            "base url" in text || "baseurl" in text || "--base-url" in text -> presentation(
                title = "Configuration error",
                detail = "Check your AI configuration in Settings.AI (base URL / model / provider). " +
                    "Original error: $detail",
                actions = listOf(AiErrorAction.OPEN_SETTINGS),
            )

            else -> presentation(
                title = "Generation failed",
                detail = detail,
                actions = listOf(AiErrorAction.RETRY_FULL),
            )
        }
    }

    /** Mask `sk-...` API key tokens in [text], leaving everything else untouched. */
    fun maskSecrets(text: String): String = text.replace(skRegex, "sk-***")

    /**
     * Walk the cause chain (max [CAUSE_DEPTH], cycle-safe) collecting non-blank
     * messages (outermost first) and whether any entry is a
     * [ScoopAiGenerationException].
     */
    private fun collect(error: Throwable): Pair<List<String>, Boolean> {
        val visited = mutableSetOf<Throwable>()
        val messages = mutableListOf<String>()
        var isCapability = false
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < CAUSE_DEPTH && visited.add(current)) {
            if (current is ScoopAiGenerationException) isCapability = true
            current.message?.takeIf { it.isNotBlank() }?.let(messages::add)
            current = current.cause
            depth++
        }
        return messages to isCapability
    }

    private fun presentation(
        title: String,
        detail: String,
        actions: List<AiErrorAction>,
    ) = AiErrorPresentation(
        title = title,
        detail = detail,
        actions = actions,
    )
}
