package scooper.service

import com.scoopai.core.ManifestResult
import com.scoopai.tools.DefaultScoopAiFactory
import com.scoopai.tools.ScoopAiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import scooper.repository.ConfigRepository
import scooper.util.logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Bridge from Scooper Settings.AI into scoop-ai [DefaultScoopAiFactory].
 *
 * Owns a dedicated IO [CoroutineScope] (never TaskQueue). Cancel aborts the running Job.
 */
class ScoopAiBridge(
    private val configRepository: ConfigRepository,
) : AutoCloseable {
    private val logger by logger()
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private var generateJob: Job? = null

    data class AiSettingsSnapshot(
        val provider: String,
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        val githubToken: String,
    ) {
        fun missingReason(): String? {
            if (provider !in SUPPORTED_PROVIDERS) {
                return "Provider must be openai-compatible or deepseek."
            }
            if (apiKey.isBlank()) {
                return "API key is missing. Fill it in Settings.AI, or set SCOOP_AI_API_KEY / DEEPSEEK_API_KEY environment variable."
            }
            if (provider == "openai-compatible" && baseUrl.isBlank()) {
                return "Base URL is required for openai-compatible. Fill it in Settings.AI, or set SCOOP_AI_BASE_URL environment variable."
            }
            return null
        }

        fun isReady(): Boolean = missingReason() == null
    }

    /**
     * Read AI settings with DB first, then environment variable fallback.
     * Env vars: SCOOP_AI_API_KEY / DEEPSEEK_API_KEY, SCOOP_AI_BASE_URL,
     * SCOOP_AI_MODEL, SCOOP_AI_PROVIDER, SCOOP_AI_GITHUB_TOKEN / GITHUB_TOKEN.
     */
    fun readSettings(): AiSettingsSnapshot {
        val config = configRepository.getConfig()
        return AiSettingsSnapshot(
            provider = firstNonBlank(
                config.aiProvider,
                env("SCOOP_AI_PROVIDER"),
                DEFAULT_PROVIDER,
            ),
            apiKey = firstNonBlank(
                config.aiApiKey,
                env("SCOOP_AI_API_KEY"),
                env("DEEPSEEK_API_KEY"),
            ),
            baseUrl = normalizeAiBaseUrl(
                firstNonBlank(
                    config.aiBaseUrl,
                    env("SCOOP_AI_BASE_URL"),
                ),
            ),
            model = firstNonBlank(
                config.aiModel,
                env("SCOOP_AI_MODEL"),
            ),
            githubToken = firstNonBlank(
                config.aiGithubToken,
                env("SCOOP_AI_GITHUB_TOKEN"),
                env("GITHUB_TOKEN"),
            ),
        )
    }

    private fun env(name: String): String = System.getenv(name)?.trim().orEmpty()

    private fun firstNonBlank(vararg values: String): String {
        for (value in values) {
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return ""
    }

    fun toScoopAiConfig(settings: AiSettingsSnapshot = readSettings()): ScoopAiConfig {
        return ScoopAiConfig(
            provider = settings.provider,
            apiKey = settings.apiKey,
            baseUrl = settings.baseUrl.ifBlank { null },
            model = settings.model.ifBlank { null },
            githubToken = settings.githubToken.ifBlank { null },
            nonInteractive = true,
            verify = false,
        )
    }

    /**
     * Start generation on the bridge IO scope. Previous job is cancelled.
     * [onPhase] and [onFinished] may be invoked from a background thread.
     */
    fun startGenerate(
        url: String,
        userPrompt: String?,
        onPhase: (String) -> Unit,
        onFinished: (Result<ManifestResult>) -> Unit,
    ) {
        generateJob?.cancel()
        generateJob = scope.launch {
            try {
                val settings = readSettings()
                val missing = settings.missingReason()
                if (missing != null) {
                    onFinished(Result.failure(IllegalStateException(missing)))
                    return@launch
                }

                val generator = DefaultScoopAiFactory.createGenerator(
                    config = toScoopAiConfig(settings),
                    onPhase = onPhase,
                )
                logger.info("Scoop AI generate start: $url")
                val result = generator.generate(url = url, userPrompt = userPrompt)
                onFinished(Result.success(result))
            } catch (e: CancellationException) {
                onFinished(Result.failure(e))
                throw e
            } catch (e: Exception) {
                logger.error("Scoop AI generate failed: ${e.message}")
                onFinished(Result.failure(e))
            }
        }
    }

    fun cancel() {
        generateJob?.cancel()
        generateJob = null
    }

    val isRunning: Boolean
        get() = generateJob?.isActive == true

    override fun close() {
        cancel()
        supervisorJob.cancel()
    }

    companion object {
        const val DEFAULT_PROVIDER = "openai-compatible"
        val SUPPORTED_PROVIDERS = setOf("openai-compatible", "deepseek")

        /**
         * Strip trailing `/v1` (and `/v1/chat/completions`) so openai-compatible
         * clients do not produce a double `/v1` path. Empty input stays empty.
         */
        fun normalizeAiBaseUrl(baseUrl: String): String {
            var url = baseUrl.trim().trimEnd('/')
            if (url.isEmpty()) return url
            for (suffix in listOf("/v1/chat/completions", "/v1")) {
                if (url.endsWith(suffix, ignoreCase = true)) {
                    return url.dropLast(suffix.length).trimEnd('/')
                }
            }
            return url
        }
    }
}

/**
 * Extract the first github.com repository URL from free text.
 * Returns (normalizedUrl, remainingTextAsPrompt).
 *
 * Accepts:
 * - https://github.com/owner/repo
 * - http://github.com/owner/repo
 * - github.com/owner/repo (https:// is added)
 * - trailing /, .git, /tree/..., ?tab=...
 */
object GithubUrlParser {
    // Capture scheme (optional), owner, repo; allow trailing path/query/fragment after repo.
    // Lookahead ends the match at path/query, whitespace, or end-of-string (free-text notes).
    private val regex = Regex(
        pattern = """(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?(?=[/?#\s]|$)""",
        option = RegexOption.IGNORE_CASE,
    )

    fun extract(text: String): Pair<String?, String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null to ""

        val match = regex.find(trimmed) ?: return null to trimmed
        val owner = match.groupValues[1]
        val repo = match.groupValues[2].removeSuffix(".git")
        if (owner.isBlank() || repo.isBlank()) return null to trimmed

        val normalized = "https://github.com/$owner/$repo"

        // Remove the whole matched github URL (including optional path/query after repo)
        // from remaining free text used as prompt.
        val fullUrlEnd = findUrlEnd(trimmed, match.range.first)
        val remaining = (trimmed.take(match.range.first) + trimmed.drop(fullUrlEnd))
            .replace(Regex("""\s+"""), " ")
            .trim()

        return normalized to remaining
    }

    /** Advance past the full URL from [start] until whitespace or end. */
    private fun findUrlEnd(text: String, start: Int): Int {
        var i = start
        while (i < text.length && !text[i].isWhitespace()) i++
        return i
    }

    fun deriveAppName(url: String): String {
        val name = url.trimEnd('/').substringAfterLast('/').ifBlank { "app" }
        return name.lowercase().replace(Regex("""[^a-z0-9._-]"""), "-")
    }
}
