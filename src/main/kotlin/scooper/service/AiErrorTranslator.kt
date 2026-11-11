package scooper.service

import com.scoopai.core.ScoopAiGenerationException
import scooper.util.LocalizedText
import scooper.util.tr

/** Suggested user actions shown with an AI error bubble. */
enum class AiErrorAction {
    OPEN_SETTINGS,
    RETRY_FULL,
    RETRY_FROM_ANALYZE,
    RETRY_FROM_GENERATE,
    RETRY_ANSWER,
}

/** Coarse cause category behind an AI generation failure. */
enum class AiErrorCategory {
    CAPABILITY,
    VISION,
    AUTH,
    MODEL_NOT_FOUND,
    BAD_PATH,
    BAD_REQUEST,
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
    val category: AiErrorCategory,
    val detail: LocalizedText,
    val technicalDetails: String? = null,
    val actions: List<AiErrorAction>,
)

/**
 * Lightweight, table-driven pure translator that maps a generation failure
 * [Throwable] to a structured [AiErrorPresentation] (category / detail /
 * suggested actions) and masks API-key secrets. It never translates: the
 * title and detail templates live in [aiErrorTitle] and [aiErrorDetailText]
 * so a language switch re-renders stored failures in the new language.
 *
 * Matching order: ScoopAiGenerationException -> CAPABILITY (core already embeds
 * a hint); image-unsupported -> VISION; 401/403 -> AUTH; 404 -> MODEL_NOT_FOUND
 * or BAD_PATH; 400 -> MODEL_NOT_FOUND or BAD_REQUEST; 429 -> RATE_LIMIT;
 * 5xx -> SERVER; DNS/timeout/refused -> NETWORK_DNS/TIMEOUT/REFUSED; missing
 * base URL -> CONFIG; else UNKNOWN with the cleaned first message verbatim.
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
    private val clientPrefixRegex = Regex("""(?:Error from client:\s*[^:]+:\s*)+""")

    /**
     * True when the failure is about image input, either from the local Koog
     * capability gate ("Model X does not support image") or from a gateway
     * rejecting multimodal requests ("vision is not enabled").
     */
    private fun isVisionUnsupported(text: String): Boolean {
        val mentionsVision = "vision" in text || "multimodal" in text || "image input" in text ||
            "does not support image" in text || "image_url" in text
        val isUnsupported = "not support" in text || "not supported" in text ||
            "not enabled" in text || "unsupported" in text
        return mentionsVision && isUnsupported
    }

    fun translate(error: Throwable): AiErrorPresentation {
        val (rawMessages, isCapability) = collect(error)
        val cleanedMessages = dropEmbeddedCauses(rawMessages)
            .map(::cleanMessage)
            .filter { it.isNotBlank() }
            .distinct()
        val rawDetail = if (cleanedMessages.isEmpty()) "(no error detail)" else cleanedMessages.joinToString("\n")
        // Lowercased for case-insensitive keyword matching; status codes stay intact.
        val text = (rawMessages + cleanedMessages).joinToString("\n").lowercase()

        return when {
            isCapability -> presentation(
                category = AiErrorCategory.CAPABILITY,
                technicalDetails = rawDetail,
                actions = RESUMABLE_ACTIONS,
            )

            isVisionUnsupported(text) -> presentation(
                category = AiErrorCategory.VISION,
                technicalDetails = rawDetail,
                actions = listOf(AiErrorAction.OPEN_SETTINGS),
            )

            "401" in text || "403" in text -> presentation(
                category = AiErrorCategory.AUTH,
                technicalDetails = rawDetail,
                actions = listOf(AiErrorAction.OPEN_SETTINGS),
            )

            ("404" in text || "400" in text) && ("model" in text || "unknown provider" in text || "model_not_found" in text) ||
                "model_not_found" in text || "unknown provider for model" in text -> presentation(
                category = AiErrorCategory.MODEL_NOT_FOUND,
                technicalDetails = rawDetail,
                actions = listOf(AiErrorAction.OPEN_SETTINGS),
            )

            "404" in text -> presentation(
                category = AiErrorCategory.BAD_PATH,
                technicalDetails = rawDetail,
                actions = listOf(AiErrorAction.OPEN_SETTINGS),
            )

            "400" in text -> presentation(
                category = AiErrorCategory.BAD_REQUEST,
                technicalDetails = rawDetail,
                actions = listOf(AiErrorAction.OPEN_SETTINGS),
            )

            "429" in text -> presentation(
                category = AiErrorCategory.RATE_LIMIT,
                technicalDetails = rawDetail,
                actions = RESUMABLE_ACTIONS,
            )

            serverErrorRegex.containsMatchIn(text) -> presentation(
                category = AiErrorCategory.SERVER,
                technicalDetails = rawDetail,
                actions = RESUMABLE_ACTIONS,
            )

            "unknownhost" in text -> presentation(
                category = AiErrorCategory.NETWORK_DNS,
                technicalDetails = rawDetail,
                actions = listOf(AiErrorAction.OPEN_SETTINGS),
            )

            "timed out" in text || "timeout" in text -> presentation(
                category = AiErrorCategory.TIMEOUT,
                technicalDetails = rawDetail,
                actions = RESUMABLE_ACTIONS,
            )

            "connection refused" in text -> presentation(
                category = AiErrorCategory.REFUSED,
                technicalDetails = rawDetail,
                actions = listOf(AiErrorAction.OPEN_SETTINGS),
            )

            "base url" in text || "baseurl" in text || "--base-url" in text -> presentation(
                category = AiErrorCategory.CONFIG,
                technicalDetails = rawDetail,
                actions = listOf(AiErrorAction.OPEN_SETTINGS),
            )

            else -> presentation(
                category = AiErrorCategory.UNKNOWN,
                detail = LocalizedText(raw = cleanedMessages.firstOrNull()),
                technicalDetails = rawDetail,
                actions = RESUMABLE_ACTIONS,
            )
        }
    }

    private val RESUMABLE_ACTIONS = listOf(
        AiErrorAction.RETRY_FULL,
        AiErrorAction.RETRY_FROM_ANALYZE,
        AiErrorAction.RETRY_FROM_GENERATE,
    )

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

    private fun cleanMessage(msg: String): String =
        msg.replace(clientPrefixRegex, "").trim()

    /**
     * Drop cause-chain messages whose text was already embedded verbatim at the
     * tail of an earlier (outer) message after an "Original error:" marker, as
     * [com.scoopai.core.ScoopAiGenerationException] does; distinct() cannot see
     * this substring-shaped duplication.
     */
    private fun dropEmbeddedCauses(messages: List<String>): List<String> {
        return messages.filterIndexed { index, msg ->
            messages.take(index).none { earlier ->
                earlier.endsWith(msg) && earlier.substringBeforeLast("Original error: ") != earlier
            }
        }
    }

    private fun presentation(
        category: AiErrorCategory,
        technicalDetails: String? = null,
        detail: LocalizedText = LocalizedText(),
        actions: List<AiErrorAction>,
    ) = AiErrorPresentation(
        category = category,
        detail = detail,
        technicalDetails = technicalDetails?.ifBlank { null },
        actions = actions,
    )
}

/** Translated title for [category]; a snapshot read (translation happens at call time). */
fun aiErrorTitle(category: AiErrorCategory): String = when (category) {
    AiErrorCategory.CAPABILITY -> tr("Model capability issue")
    AiErrorCategory.VISION -> tr("Model does not accept images")
    AiErrorCategory.AUTH -> tr("Authentication failed")
    AiErrorCategory.MODEL_NOT_FOUND -> tr("Model not found")
    AiErrorCategory.BAD_PATH -> tr("Resource not found (404)")
    AiErrorCategory.BAD_REQUEST -> tr("Bad request (400)")
    AiErrorCategory.RATE_LIMIT -> tr("Rate limit exceeded")
    AiErrorCategory.SERVER -> tr("Server error")
    AiErrorCategory.NETWORK_DNS -> tr("Cannot resolve host")
    AiErrorCategory.TIMEOUT -> tr("Request timed out")
    AiErrorCategory.REFUSED -> tr("Connection refused")
    AiErrorCategory.CONFIG -> tr("Configuration error")
    AiErrorCategory.UNKNOWN -> tr("Generation failed")
}

/** Detail template for [category]; verbatim [detail] raw text passes through untranslated. */
fun aiErrorDetailText(category: AiErrorCategory, detail: LocalizedText): String {
    detail.raw?.let { return it }
    return when (category) {
        AiErrorCategory.CAPABILITY -> tr(
            "The model does not support function calling (tool_calls). Please switch to a tool-capable model (e.g. deepseek-chat, gpt-4o, claude) in Settings > AI.",
        )
        AiErrorCategory.VISION -> tr(
            "The current model cannot process image input. Switch to a multimodal model (e.g. gpt-4o, claude, gemini) in Settings > AI, or send the request without images.",
        )
        AiErrorCategory.AUTH -> tr("Your API key may be invalid or expired. Check it in Settings > AI.")
        AiErrorCategory.MODEL_NOT_FOUND -> tr("The configured model was not found on this provider. Check the model name in Settings > AI.")
        AiErrorCategory.BAD_PATH -> tr("The server returned 404. Check your base URL in Settings > AI.")
        AiErrorCategory.BAD_REQUEST -> tr("The AI endpoint returned 400 Bad Request. Check your model and parameters in Settings > AI.")
        AiErrorCategory.RATE_LIMIT -> tr("The provider rate-limited the request. Wait a moment and retry, or switch to another model.")
        AiErrorCategory.SERVER -> tr("The provider returned a temporary server error. This is usually transient; retry later.")
        AiErrorCategory.NETWORK_DNS -> tr("The AI endpoint host could not be resolved. Check your network connection and the base URL in Settings > AI.")
        AiErrorCategory.TIMEOUT -> tr("The request timed out. Check your network connection and retry.")
        AiErrorCategory.REFUSED -> tr("The connection to the AI endpoint was refused. Check that the server is running and the base URL is correct in Settings > AI.")
        AiErrorCategory.CONFIG -> tr("Check your AI configuration in Settings > AI (base URL / model / provider).")
        AiErrorCategory.UNKNOWN -> tr("An unexpected error occurred during generation.")
    }
}
