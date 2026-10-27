package scooper.service

import com.scoopai.tools.llm.ProviderEndpoints
import scooper.data.UIConfig
import scooper.repository.ConfigRepository
import scooper.util.logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * AI settings snapshot resolved from the DB and the environment. Pure data
 * plus the readiness check shared by the Create entry guards.
 */
data class AiSettingsSnapshot(
    val provider: String,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val githubToken: String,
    val autoDownload: Boolean = false,
) {
    fun missingReason(): String? {
        if (!ProviderCatalog.exists(provider)) {
            return ProviderEndpoints.unsupportedProviderMessage(provider)
        }
        if (ProviderCatalog.requiresApiKey(provider) && apiKey.isBlank()) {
            val envHint = ProviderCatalog.apiKeyEnvHint(provider)
            val envNames = if (envHint == null) "SCOOP_AI_API_KEY" else "SCOOP_AI_API_KEY or $envHint"
            return "API key is missing. Fill it in Settings > AI, or set $envNames environment variable."
        }
        if (ProviderCatalog.requiresBaseUrl(provider) && baseUrl.isBlank()) {
            return "Base URL is required for $provider. Fill it in Settings > AI, or set SCOOP_AI_BASE_URL environment variable."
        }
        return null
    }

    fun isReady(): Boolean = missingReason() == null
}

/**
 * Reads and persists the AI related entries of [UIConfig], split out of
 * [ScoopAiBridge] (S5): settings reads with the env fallback chain, the
 * best-effort persistence helpers (model, last bucket, commit preference)
 * and the base URL normalize rule. The bridge delegates here so viewmodels
 * can read session memory without touching the engine run seam.
 */
class AiSettingsStore(private val configRepository: ConfigRepository) {
    private val logger by logger()

    /**
     * Read AI settings with DB first, then environment variable fallback.
     * Env vars: SCOOP_AI_API_KEY plus the environment variable of the
     * resolved provider (DEEPSEEK_API_KEY / OPENAI_API_KEY /
     * ANTHROPIC_API_KEY / OPENROUTER_API_KEY / OPENAI_COMPATIBLE_API_KEY),
     * SCOOP_AI_BASE_URL, SCOOP_AI_MODEL, SCOOP_AI_PROVIDER,
     * SCOOP_AI_GITHUB_TOKEN / GITHUB_TOKEN.
     */
    fun readSettings(): AiSettingsSnapshot = readSettings { name -> System.getenv(name) }

    /**
     * Read AI settings with DB first, then environment variable fallback.
     * The API key fallback uses the environment variable of the resolved
     * provider, so every provider reads its own key variable.
     */
    internal fun readSettings(envLookup: (String) -> String?): AiSettingsSnapshot {
        val config = configRepository.getConfig()
        val provider = firstNonBlank(
            config.aiProvider,
            envLookup("SCOOP_AI_PROVIDER"),
            DEFAULT_PROVIDER,
        )
        val providerEnvKey = ProviderCatalog.apiKeyEnvHint(provider).orEmpty()
        return AiSettingsSnapshot(
            provider = provider,
            apiKey = firstNonBlank(
                config.aiApiKey,
                envLookup("SCOOP_AI_API_KEY"),
                if (providerEnvKey.isEmpty()) null else envLookup(providerEnvKey),
            ),
            baseUrl = normalizeAiBaseUrl(
                firstNonBlank(
                    config.aiBaseUrl,
                    envLookup("SCOOP_AI_BASE_URL"),
                ),
                provider,
            ),
            model = firstNonBlank(
                config.aiModel,
                envLookup("SCOOP_AI_MODEL"),
            ),
            githubToken = firstNonBlank(
                config.aiGithubToken,
                envLookup("SCOOP_AI_GITHUB_TOKEN"),
                envLookup("GITHUB_TOKEN"),
            ),
            autoDownload = config.aiAutoDownload,
        )
    }

    private fun firstNonBlank(vararg values: String?): String {
        for (value in values) {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return ""
    }

    /**
     * Persist the model chosen in Create back into Settings.AI so it
     * survives restarts. Failures are logged, never thrown: model
     * persistence is best-effort and must not break generation.
     */
    fun persistModel(model: String) {
        try {
            val current = configRepository.getConfig()
            configRepository.setConfig(withModel(current, model))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to persist selected AI model: ${e.message}")
        }
    }

    /**
     * Persist the bucket used by the Create save dialog so it can be
     * preselected next time. Failures are logged, never thrown: last
     * bucket memory is best-effort and must not break saving.
     */
    fun persistLastBucket(bucket: String) {
        try {
            val current = configRepository.getConfig()
            configRepository.setConfig(withLastBucket(current, bucket))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to persist last used bucket: ${e.message}")
        }
    }

    /**
     * Read the last bucket used by the Create save dialog. Best-effort:
     * returns an empty string when the repository fails.
     */
    fun readLastBucket(): String {
        return try {
            configRepository.getConfig().aiLastBucket
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to read last used bucket: ${e.message}")
            ""
        }
    }

    /**
     * Persist the save dialog "commit to git repository" checkbox so it can
     * be preselected next time. Failures are logged, never thrown:
     * checkbox memory is best-effort and must not break saving.
     */
    fun persistCommitPreference(commit: Boolean) {
        try {
            val current = configRepository.getConfig()
            configRepository.setConfig(withCommitPreference(current, commit))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to persist commit-to-git preference: ${e.message}")
        }
    }

    /**
     * Read the save dialog "commit to git repository" checkbox. Best-effort:
     * returns true (the dialog default) when the repository fails.
     */
    fun readCommitPreference(): Boolean {
        return try {
            configRepository.getConfig().aiCommitToGit
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to read commit-to-git preference: ${e.message}")
            true
        }
    }

    companion object {
        const val DEFAULT_PROVIDER = "openai-compatible"

        /**
         * Pure copy: replace only [UIConfig.aiModel], leaving every other
         * field (and any future fields) untouched. Static so it stays
         * unit-testable without a DB repository.
         */
        internal fun withModel(config: UIConfig, model: String): UIConfig {
            return config.copy(aiModel = model)
        }

        /**
         * Pure copy: replace only [UIConfig.aiLastBucket], leaving every
         * other field (and any future fields) untouched. Static so it stays
         * unit-testable without a DB repository.
         */
        internal fun withLastBucket(config: UIConfig, bucket: String): UIConfig {
            return config.copy(aiLastBucket = bucket)
        }

        /**
         * Pure copy: replace only [UIConfig.aiCommitToGit], leaving every
         * other field (and any future fields) untouched. Static so it stays
         * unit-testable without a DB repository.
         */
        internal fun withCommitPreference(config: UIConfig, commit: Boolean): UIConfig {
            return config.copy(aiCommitToGit = commit)
        }

        /**
         * Strip trailing `/v1` (and `/v1/chat/completions`) so openai-compatible
         * clients do not produce a double `/v1` path. Empty input stays empty.
         *
         * The suffix is only removed for providers that append their own
         * versioned path; OpenRouter and Ollama keep the URL as typed.
         */
        fun normalizeAiBaseUrl(baseUrl: String, provider: String = DEFAULT_PROVIDER): String {
            var url = baseUrl.trim().trimEnd('/')
            if (url.isEmpty()) return url
            if (!ProviderCatalog.stripV1Suffix(provider)) return url
            for (suffix in listOf("/v1/chat/completions", "/v1")) {
                if (url.endsWith(suffix, ignoreCase = true)) {
                    return url.dropLast(suffix.length).trimEnd('/')
                }
            }
            return url
        }
    }
}
