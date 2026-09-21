package scooper.service

import com.scoopai.core.ManifestResult
import com.scoopai.core.agent.BinaryImage
import com.scoopai.tools.DefaultScoopAiFactory
import com.scoopai.tools.DefaultToolRegistryFactory
import com.scoopai.tools.ScoopAiConfig
import com.scoopai.tools.config.UsedAppNameScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import scooper.data.UIConfig
import scooper.repository.ConfigRepository
import scooper.util.logger
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Image bytes handed to the engine s assist session. Purely transient: never
 * persisted to checkpoints, only forwarded as a Koog binary attachment.
 */
data class AiImage(val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AiImage) return false
        return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()
}

/**
 * Bridge from Scooper Settings.AI into scoop-ai [DefaultScoopAiFactory].
 *
 * Owns a dedicated IO [CoroutineScope] (never TaskQueue). Cancel aborts the running Job.
 */
open class ScoopAiBridge(
    private val configRepository: ConfigRepository,
    private val checkpointService: InMemoryAiCheckpointService,
    private val connectionTester: AiConnectionTester = AiConnectionTester(),
    private val modelsFetcher: AiModelsFetcher = AiModelsFetcher(),
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
        val autoDownload: Boolean = false,
    ) {
        fun missingReason(): String? {
            if (provider !in SUPPORTED_PROVIDERS) {
                return "Provider must be openai-compatible."
            }
            if (apiKey.isBlank()) {
                return "API key is missing. Fill it in Settings > AI, or set SCOOP_AI_API_KEY environment variable."
            }
            if (baseUrl.isBlank()) {
                return "Base URL is required for openai-compatible. Fill it in Settings > AI, or set SCOOP_AI_BASE_URL environment variable."
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
            autoDownload = config.aiAutoDownload,
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
        return buildScoopAiConfig(settings)
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

    private fun createGenerator(
        settings: AiSettingsSnapshot,
        onPhase: ((String) -> Unit)?,
        onPhaseOutput: ((String, String) -> Unit)?,
        onToolActivity: ((com.scoopai.core.ToolActivity) -> Unit)?,
        onAskUser: (suspend (String) -> String)?,
        onQuestionnaire: (suspend (List<com.scoopai.tools.interaction.Question>) -> String)?,
        onSaveToBucket: (suspend (bucket: String?, name: String?, overwrite: Boolean) -> String)? = null,
        /**
         * Host install callback backing the engine's `install_app` tool.
         * Signature: (bucket, name) -> result JSON. A null or blank bucket
         * asks for a direct install of the current manifest without saving it
         * to any bucket. When null the assist session runs without the tool.
         */
        onInstallApp: (suspend (bucket: String?, name: String) -> String)? = null,
        /**
         * App names the engine's conflict check must ignore, e.g. the manifest
         * this session itself just saved: at verify time it is already in the
         * bucket, so counting it as a conflict is a false positive.
         */
        ignoreAppNames: Set<String> = emptySet(),
    ) = DefaultScoopAiFactory.createGenerator(
        config = toScoopAiConfig(settings),
        onPhase = onPhase,
        onPhaseOutput = { phase, output ->
            checkpointService.update(phase, output)
            onPhaseOutput?.invoke(phase, output)
        },
        onToolActivity = onToolActivity,
        askUserHandler = onAskUser,
        questionnaireHandler = onQuestionnaire,
        usedAppNamesProvider = if (ignoreAppNames.isEmpty()) {
            UsedAppNameScanner::scanDefault
        } else {
            {
                // Scoop manifest names and saveResult casing are not guaranteed to
                // match, so subtract the exclusion set in lowercase on both sides.
                UsedAppNameScanner.scanDefault().map { it.lowercase() }.toSet() -
                    ignoreAppNames.map { it.lowercase() }.toSet()
            }
        },
        saveToBucketHandler = onSaveToBucket,
        installAppHandler = onInstallApp,
    )

    /**
     * Start generation on the bridge IO scope. Previous job is cancelled.
     * [onPhase] and [onFinished] may be invoked from a background thread.
     *
     * When [resumeFrom] is null, a fresh run starts: the checkpoint is reset to
     * a new instance for this url/prompt and phase outputs are captured as the
     * run progresses. When [resumeFrom] is a RETRY_FROM_* action, the existing
     * checkpoint is reused to build [ResumeData]; if the checkpoint cannot
     * satisfy the requested stage it degrades to a full regeneration (the
     * checkpoint is kept, never cleared, on failure or cancel).
     */
    fun startGenerate(
        url: String,
        userPrompt: String?,
        resumeFrom: AiErrorAction? = null,
        overrideModel: String? = null,
        /** App names the engine's conflict check must ignore (see [createGenerator]). */
        ignoreAppNames: Set<String> = emptySet(),
        onPhase: (String) -> Unit,
        onToolActivity: ((com.scoopai.core.ToolActivity) -> Unit)? = null,
        onPhaseOutput: ((String, String) -> Unit)? = null,
        onAskUser: (suspend (String) -> String)? = null,
        onQuestionnaire: (suspend (List<com.scoopai.tools.interaction.Question>) -> String)? = null,
        onFinished: (Result<ManifestResult>) -> Unit,
    ) {
        generateJob?.cancel()
        generateJob = scope.launch {
            try {
                val rawSettings = readSettings()
                val settings = if (!overrideModel.isNullOrBlank()) {
                    rawSettings.copy(model = overrideModel.trim())
                } else rawSettings
                val missing = settings.missingReason()
                if (missing != null) {
                    onFinished(Result.failure(IllegalStateException(missing)))
                    return@launch
                }

                val resumeData = if (resumeFrom != null) {
                    val data = buildResumeData(
                        cp = checkpointService.snapshot(),
                        from = resumeFrom,
                        runId = UUID.randomUUID().toString(),
                    )
                    if (data != null) {
                        logger.info("Resuming from $resumeFrom (runId=${data.runId})")
                    } else {
                        logger.warn("Checkpoint cannot resume from $resumeFrom; falling back to full regeneration")
                    }
                    data
                } else {
                    // Fresh run: start a new checkpoint for this url/prompt.
                    checkpointService.beginRun(url, userPrompt.orEmpty())
                    null
                }

                val generator = createGenerator(
                    settings = settings,
                    onPhase = onPhase,
                    onPhaseOutput = onPhaseOutput,
                    onToolActivity = onToolActivity,
                    onAskUser = onAskUser,
                    onQuestionnaire = onQuestionnaire,
                    ignoreAppNames = ignoreAppNames,
                )
                logger.info("Scoop AI generate start: $url")
                val result = generator.generate(url = url, userPrompt = userPrompt, resumeData = resumeData)
                onFinished(Result.success(result))
            } catch (e: CancellationException) {
                onFinished(Result.failure(e))
                throw e
            } catch (e: Exception) {
                logger.error("Scoop AI generate failed: ${e.message}", e)
                onFinished(Result.failure(e))
            } finally {
                DefaultToolRegistryFactory.cleanup()
            }
        }
    }

    /**
     * Start an asynchronous manifest assist run using an existing manifest
     * and user instructions, skipping research and analyze phases.
     */
    open fun startAssist(
        currentManifest: String,
        instruction: String,
        history: List<String> = emptyList(),
        suggestedAppName: String? = null,
        overrideModel: String? = null,
        /** User screenshots attached to this message (assist only, never replayed). */
        images: List<AiImage> = emptyList(),
        /** App names the engine's conflict check must ignore (see [createGenerator]). */
        ignoreAppNames: Set<String> = emptySet(),
        onPhase: ((String) -> Unit)? = null,
        onToolActivity: ((com.scoopai.core.ToolActivity) -> Unit)? = null,
        onPhaseOutput: ((String, String) -> Unit)? = null,
        onAskUser: (suspend (String) -> String)? = null,
        onQuestionnaire: (suspend (List<com.scoopai.tools.interaction.Question>) -> String)? = null,
        /**
         * Host save callback backing the engine's `save_to_bucket` tool.
         * Signature: (bucket, name, overwrite) -> result JSON. When null the
         * assist session runs without the tool.
         */
        onSaveToBucket: (suspend (bucket: String?, name: String?, overwrite: Boolean) -> String)? = null,
        /**
         * Host install callback backing the engine's `install_app` tool.
         * Signature: (bucket, name) -> result JSON. A null or blank bucket
         * asks for a direct install of the current manifest without saving it
         * to any bucket. When null the assist session runs without the tool.
         */
        onInstallApp: (suspend (bucket: String?, name: String) -> String)? = null,
        onFinished: (Result<ManifestResult>) -> Unit,
    ) {
        generateJob?.cancel()
        generateJob = scope.launch {
            try {
                val rawSettings = readSettings()
                val settings = if (!overrideModel.isNullOrBlank()) {
                    rawSettings.copy(model = overrideModel.trim())
                } else rawSettings
                val missing = settings.missingReason()
                if (missing != null) {
                    onFinished(Result.failure(IllegalStateException(missing)))
                    return@launch
                }

                val generator = createGenerator(
                    settings = settings,
                    onPhase = onPhase,
                    onPhaseOutput = onPhaseOutput,
                    onToolActivity = onToolActivity,
                    onAskUser = onAskUser,
                    onQuestionnaire = onQuestionnaire,
                    onSaveToBucket = onSaveToBucket,
                    onInstallApp = onInstallApp,
                    ignoreAppNames = ignoreAppNames,
                )
                val checkpoint = checkpointService.snapshot()
                val evidence = checkpoint?.analyzeJson?.takeIf { it.isNotBlank() }
                    ?: checkpoint?.researchJson?.takeIf { it.isNotBlank() }

                logger.info("Scoop AI assist start: $instruction (history=${history.size}, hasEvidence=${evidence != null})")
                val result = generator.assist(
                    manifestJson = currentManifest,
                    instruction = instruction,
                    history = history,
                    evidence = evidence,
                    suggestedAppName = suggestedAppName,
                    images = images.map { BinaryImage(bytes = it.bytes, mimeType = it.mimeType) },
                )
                onFinished(Result.success(result))
            } catch (e: CancellationException) {
                onFinished(Result.failure(e))
                throw e
            } catch (e: Exception) {
                logger.error("Scoop AI assist failed: ${e.message}", e)
                onFinished(Result.failure(e))
            } finally {
                DefaultToolRegistryFactory.cleanup()
            }
        }
    }

    /**
     * Run a one-shot connectivity probe for the given (possibly unsaved)
     * AI form values without going through scoop-ai. Started on the bridge
     * IO scope; [onResult] is invoked from a background thread with the probe
     * result.
     */
    fun testConnection(
        provider: String,
        apiKey: String,
        baseUrl: String?,
        model: String?,
        onResult: (AiConnectionTestResult) -> Unit,
    ) {
        scope.launch {
            try {
                val result = connectionTester.test(provider, apiKey, baseUrl, model)
                onResult(result)
            } catch (e: Exception) {
                // Probe already swallows its own failures into a result; this is a safety net.
                logger.error("AI connection test failed unexpectedly: ${e.message}")
                onResult(
                    AiConnectionTestResult(
                        ok = false,
                        category = AiErrorCategory.UNKNOWN,
                        detail = e.message ?: "Unknown test failure",
                        latencyMs = 0,
                        testedEndpoint = "",
                    ),
                )
            }
        }
    }

    /**
     * Query remote /models endpoint for available model names.
     */
    fun fetchModels(
        provider: String,
        apiKey: String,
        baseUrl: String?,
        onResult: (FetchModelsResult) -> Unit,
    ) {
        scope.launch {
            try {
                val result = modelsFetcher.fetchModels(provider, apiKey, baseUrl)
                onResult(result)
            } catch (e: Exception) {
                logger.error("AI fetch models failed unexpectedly: ${e.message}")
                onResult(
                    FetchModelsResult(
                        ok = false,
                        errorMessage = e.message ?: "Unknown failure when fetching models",
                    ),
                )
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
        val SUPPORTED_PROVIDERS = setOf("openai-compatible")

        /**
         * Pure mapping from an [AiSettingsSnapshot] to a [ScoopAiConfig].
         * Kept as a static function so config mapping stays unit-testable
         * without constructing a bridge (which needs a DB repository).
         */
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

        internal fun buildScoopAiConfig(settings: AiSettingsSnapshot): ScoopAiConfig {
            return ScoopAiConfig(
                provider = settings.provider,
                apiKey = settings.apiKey,
                baseUrl = settings.baseUrl.ifBlank { null },
                model = settings.model.ifBlank { null },
                githubToken = settings.githubToken.ifBlank { null },
                skipDownloadConfirm = settings.autoDownload,
                nonInteractive = true,
            )
        }

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
