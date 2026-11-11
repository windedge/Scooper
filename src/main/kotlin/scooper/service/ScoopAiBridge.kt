package scooper.service

import com.scoopai.core.ManifestResult
import com.scoopai.core.agent.BinaryImage
import com.scoopai.core.artifacts.ResumeData
import com.scoopai.tools.DefaultScoopAiFactory
import com.scoopai.tools.DefaultToolRegistryFactory
import com.scoopai.tools.ScoopAiConfig
import com.scoopai.tools.config.UsedAppNameScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import scooper.repository.ConfigRepository
import scooper.util.LocalizedText
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
 *
 * AI settings reads and config persistence live in [AiSettingsStore]; this
 * bridge keeps only the engine run lifecycle plus the fetch/test proxies and
 * delegates the settings seam to the injected store.
 */
open class ScoopAiBridge(
    private val configRepository: ConfigRepository,
    private val checkpointService: InMemoryAiCheckpointService,
    private val connectionTester: AiConnectionTester = AiConnectionTester(),
    private val modelsFetcher: AiModelsFetcher = AiModelsFetcher(),
    settingsStore: AiSettingsStore? = null,
) : AutoCloseable {
    private val logger by logger()
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private var generateJob: Job? = null

    /**
     * Settings seam, built lazily from [configRepository] when no store is
     * injected (per-tab bridges in tests construct one without a store).
     */
    internal val settingsStore: AiSettingsStore by lazy {
        settingsStore ?: AiSettingsStore(configRepository)
    }

    /**
     * Read AI settings through the settings store (DB first, env fallback).
     * Kept as a bridge member so existing engine-run callers are untouched.
     */
    fun readSettings(): AiSettingsSnapshot = settingsStore.readSettings()

    /**
     * Read AI settings with a test seam for the environment lookup, forwarded
     * to the settings store (kept for the existing fallback-chain tests).
     */
    internal fun readSettings(envLookup: (String) -> String?): AiSettingsSnapshot {
        return settingsStore.readSettings(envLookup)
    }

    fun toScoopAiConfig(settings: AiSettingsSnapshot = readSettings()): ScoopAiConfig {
        return buildScoopAiConfig(settings)
    }

    /**
     * The five engine event callbacks that always travel together through the
     * run entry points. Only the assist path wires the host tool handlers, so
     * those stay separate parameters next to this bundle.
     */
    protected data class GeneratorCallbacks(
        val onPhase: ((String) -> Unit)?,
        val onPhaseOutput: ((String, String) -> Unit)?,
        val onToolActivity: ((com.scoopai.core.ToolActivity) -> Unit)?,
        val onAskUser: (suspend (String) -> String)?,
        val onQuestionnaire: (suspend (List<com.scoopai.tools.interaction.Question>) -> String)?,
    )

    private fun createGenerator(
        settings: AiSettingsSnapshot,
        callbacks: GeneratorCallbacks,
        onSaveToBucket: (suspend (bucket: String?, name: String?, overwrite: Boolean) -> String)? = null,
        /**
         * Host install callback backing the engine's `install_app` tool.
         * Signature: (bucket, name) -> result JSON. A null or blank bucket
         * asks for a direct install of the current manifest without saving it
         * to any bucket. When null the assist session runs without the tool.
         */
        onInstallApp: (suspend (bucket: String?, name: String) -> String)? = null,
        /**
         * Host read callback backing the engine's `read_manifest_reference`
         * tool. Signature: (bucket, name) -> result JSON. Only wired into an
         * assist session; when null the tool is not registered.
         */
        onReadManifestRef: (suspend (bucket: String, name: String) -> String)? = null,
        /**
         * App names the engine's conflict check must ignore, e.g. the manifest
         * this session itself just saved: at verify time it is already in the
         * bucket, so counting it as a conflict is a false positive.
         */
        ignoreAppNames: Set<String> = emptySet(),
    ) = DefaultScoopAiFactory.createGenerator(
        config = toScoopAiConfig(settings),
        onPhase = callbacks.onPhase,
        onPhaseOutput = { phase, output ->
            checkpointService.update(phase, output)
            callbacks.onPhaseOutput?.invoke(phase, output)
        },
        onToolActivity = callbacks.onToolActivity,
        askUserHandler = callbacks.onAskUser,
        questionnaireHandler = callbacks.onQuestionnaire,
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
        readManifestRefHandler = onReadManifestRef,
    )

    /**
     * Network boundary of [startGenerate]: build the generator and run it.
     * Split out so tests can override only this seam while the real single
     * slot cancel / launch / onFinished / cancellation path stays under test.
     */
    protected open suspend fun runGenerate(
        settings: AiSettingsSnapshot,
        url: String,
        userPrompt: String?,
        resumeData: ResumeData?,
        callbacks: GeneratorCallbacks,
        ignoreAppNames: Set<String>,
    ): ManifestResult {
        val generator = createGenerator(
            settings = settings,
            callbacks = callbacks,
            ignoreAppNames = ignoreAppNames,
        )
        logger.info("Scoop AI generate start: $url")
        return generator.generate(url = url, userPrompt = userPrompt, resumeData = resumeData)
    }

    /**
     * Network boundary of [startAssist]: build the generator and run it
     * (see [runGenerate] for why this seam exists).
     */
    protected open suspend fun runAssist(
        settings: AiSettingsSnapshot,
        currentManifest: String,
        instruction: String,
        history: List<String>,
        suggestedAppName: String?,
        images: List<AiImage>,
        evidence: String?,
        callbacks: GeneratorCallbacks,
        onSaveToBucket: (suspend (bucket: String?, name: String?, overwrite: Boolean) -> String)?,
        onInstallApp: (suspend (bucket: String?, name: String) -> String)?,
        onReadManifestRef: (suspend (bucket: String, name: String) -> String)?,
        ignoreAppNames: Set<String>,
    ): ManifestResult {
        val generator = createGenerator(
            settings = settings,
            callbacks = callbacks,
            onSaveToBucket = onSaveToBucket,
            onInstallApp = onInstallApp,
            onReadManifestRef = onReadManifestRef,
            ignoreAppNames = ignoreAppNames,
        )
        logger.info(
            "Scoop AI assist start: $instruction (history=${history.size}, hasEvidence=${evidence != null})",
        )
        return generator.assist(
            manifestJson = currentManifest,
            instruction = instruction,
            history = history,
            evidence = evidence,
            suggestedAppName = suggestedAppName,
            images = images.map { BinaryImage(bytes = it.bytes, mimeType = it.mimeType) },
        )
    }

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
    open fun startGenerate(
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
        launchExclusiveRun("Scoop AI generate", overrideModel, onFinished) { settings ->
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

            val result = runGenerate(
                settings = settings,
                url = url,
                userPrompt = userPrompt,
                resumeData = resumeData,
                callbacks = GeneratorCallbacks(
                    onPhase = onPhase,
                    onPhaseOutput = onPhaseOutput,
                    onToolActivity = onToolActivity,
                    onAskUser = onAskUser,
                    onQuestionnaire = onQuestionnaire,
                ),
                ignoreAppNames = ignoreAppNames,
            )
            onFinished(Result.success(result))
        }
    }

    /**
     * Shared wrapper for the two engine entry points: cancels the previous
     * run, resolves settings (missing settings fail fast through [onFinished])
     * and wraps [body] in the common cancel/cleanup error handling. [errorLabel]
     * only feeds the error log line.
     */
    private fun launchExclusiveRun(
        errorLabel: String,
        overrideModel: String?,
        onFinished: (Result<ManifestResult>) -> Unit,
        body: suspend (AiSettingsSnapshot) -> Unit,
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
                    // Config guards upstream render the structured reason; reaching here
                    // is a programming error, so the raw copy is fine.
                    onFinished(Result.failure(IllegalStateException(missing.render())))
                    return@launch
                }
                body(settings)
            } catch (e: CancellationException) {
                onFinished(Result.failure(e))
                throw e
            } catch (e: Exception) {
                logger.error("$errorLabel failed: ${e.message}", e)
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
        /**
         * Host read callback backing the engine's `read_manifest_reference` tool.
         * Signature: (bucket, name) -> result JSON. The host passes a handler
         * for every assist round of a session where the user referenced any
         * local manifest with @bucket/app; when null the tool is not registered.
         */
        onReadManifestRef: (suspend (bucket: String, name: String) -> String)? = null,
        onFinished: (Result<ManifestResult>) -> Unit,
    ) {
        launchExclusiveRun("Scoop AI assist", overrideModel, onFinished) { settings ->
            val checkpoint = checkpointService.snapshot()
            val evidence = checkpoint?.analyzeJson?.takeIf { it.isNotBlank() }
                ?: checkpoint?.researchJson?.takeIf { it.isNotBlank() }

            val result = runAssist(
                settings = settings,
                currentManifest = currentManifest,
                instruction = instruction,
                history = history,
                suggestedAppName = suggestedAppName,
                images = images,
                evidence = evidence,
                callbacks = GeneratorCallbacks(
                    onPhase = onPhase,
                    onPhaseOutput = onPhaseOutput,
                    onToolActivity = onToolActivity,
                    onAskUser = onAskUser,
                    onQuestionnaire = onQuestionnaire,
                ),
                onSaveToBucket = onSaveToBucket,
                onInstallApp = onInstallApp,
                onReadManifestRef = onReadManifestRef,
                ignoreAppNames = ignoreAppNames,
            )
            onFinished(Result.success(result))
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
                        detail = LocalizedText(raw = e.message ?: "Unknown test failure"),
                        latencyMs = 0,
                        testedEndpoint = "",
                    ),
                )
            }
        }
    }

    /**
     * Query remote /models endpoint for available model names.
     * Open so tests can answer the probe without a network round trip.
     */
    open fun fetchModels(
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
        const val DEFAULT_PROVIDER = AiSettingsStore.DEFAULT_PROVIDER
        val SUPPORTED_PROVIDERS: Set<String> = ProviderCatalog.supportedProviderIds.toSet()

        internal fun buildScoopAiConfig(settings: AiSettingsSnapshot): ScoopAiConfig {
            return ScoopAiConfig(
                provider = settings.provider,
                apiKey = settings.apiKey,
                // A provider with its own default endpoint must not inherit a
                // base URL left over from another provider, otherwise the
                // request silently goes to the stale gateway.
                baseUrl = if (ProviderCatalog.requiresBaseUrl(settings.provider)) {
                    settings.baseUrl.ifBlank { null }
                } else {
                    null
                },
                model = settings.model.ifBlank { null },
                githubToken = settings.githubToken.ifBlank { null },
                skipDownloadConfirm = settings.autoDownload,
                nonInteractive = true,
            )
        }

        /**
         * Compatibility alias for [AiSettingsStore.normalizeAiBaseUrl], kept
         * because the fetch/test proxies normalize the endpoint URL through it.
         */
        fun normalizeAiBaseUrl(baseUrl: String, provider: String = DEFAULT_PROVIDER): String {
            return AiSettingsStore.normalizeAiBaseUrl(baseUrl, provider)
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
