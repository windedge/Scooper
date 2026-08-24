package scooper.viewmodels

import com.scoopai.core.ManifestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import scooper.data.App
import scooper.data.Bucket
import scooper.repository.AppsRepository
import scooper.service.AiErrorAction
import scooper.service.AiErrorPresentation
import scooper.service.AiErrorTranslator
import scooper.service.GithubUrlParser
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.resolveErrorActions
import scooper.service.resumableStagesOf
import scooper.service.ScoopClient
import scooper.util.logger
import java.io.File
import java.util.UUID

enum class ChatRole {
    User,
    Agent,
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
    val phase: String? = null,
    val note: String? = null,
    val isTyping: Boolean = false,
    /** Suggested recovery actions for an error bubble; empty for non-error messages. */
    val actions: List<AiErrorAction> = emptyList(),
)

data class TestResult(
    val success: Boolean,
    val testName: String,
    val errorMessage: String? = null,
    val prewarmedCount: Int = 0,
    val log: String = "",
)

/** Git commit hint shown on the SaveResultPanel when the bucket is a Git repository. */
data class SaveGitHint(
    val bucketDir: String,
    val command: String,
)

/** Outcome of a successful Save to Bucket, driving the SaveResultPanel result card. */
data class SaveResult(
    val success: Boolean,
    val appName: String,
    val bucket: String,
    val targetPath: String,
    val overwritten: Boolean,
    val indexedOk: Boolean,
    val gitHint: SaveGitHint? = null,
)

data class CreateState(
    val messages: List<ChatMessage> = emptyList(),
    val inputUrl: String = "",
    val inputNote: String = "",
    val lastUrl: String = "",
    val lastNote: String = "",
    val running: Boolean = false,
    val testing: Boolean = false,
    val currentPhase: String? = null,
    val manifestJson: String = "",
    val suggestedAppName: String? = null,
    val error: String? = null,
    val aiConfigured: Boolean = true,
    val configHint: String? = null,
    val saveDialogOpen: Boolean = false,
    val saveAppName: String = "",
    val saveBucket: String = "",
    val bucketOptions: List<String> = emptyList(),
    val overwriteConfirmOpen: Boolean = false,
    val testResult: TestResult? = null,
    val saveResult: SaveResult? = null,
    /** Suggested resume stages currently available from the in-memory checkpoint. */
    val resumableStages: Set<AiErrorAction> = emptySet(),
    /** True when generation finished but the manifest is not ready (refine exhausted or invalid JSON). */
    val manifestExhausted: Boolean = false,
    /** True when the finished manifest is not valid Scoop manifest JSON (blank counts as invalid).
     *  Drives Test Install / Save to Bucket button gating (see [C2 exhausted escape door]). */
    val manifestInvalidJson: Boolean = false,
    /** Unresolved validation/review issues when [manifestExhausted] is true. */
    val manifestIssues: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = messages.isEmpty()
}

class CreateViewModel(
    private val scoopAiBridge: ScoopAiBridge,
    private val checkpointService: InMemoryAiCheckpointService,
    private val scoopClient: ScoopClient,
    private val appsRepository: AppsRepository,
    private val gitHistoryService: GitHistoryService,
) : ContainerHost<CreateState, AppSideEffect>, AutoCloseable {
    private val logger by logger()
    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + supervisorJob)

    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    override val container: Container<CreateState, AppSideEffect> =
        coroutineScope.container(CreateState()) {
            refreshAiConfig()
        }

    fun refreshAiConfig() = intent {
        val missing = scoopAiBridge.readSettings().missingReason()
        reduce {
            state.copy(
                aiConfigured = missing == null,
                configHint = missing,
            )
        }
    }

    fun onUrlChange(url: String) = intent {
        reduce { state.copy(inputUrl = url, error = null) }
    }

    fun onNoteChange(note: String) = intent {
        reduce { state.copy(inputNote = note) }
    }

    fun onSend(url: String? = null, note: String? = null) = intent {
        if (state.running) return@intent
        val rawUrl = url ?: state.inputUrl
        val rawNote = note ?: state.inputNote
        // Keep state in sync so regenerate / future reads see the latest values
        reduce { state.copy(inputUrl = rawUrl, inputNote = rawNote, error = null) }
        startGeneration(rawUrlInput = rawUrl, noteInput = rawNote)
    }

    /**
     * Handle a recovery action offered on an AI error bubble.
     * OPEN_SETTINGS navigation is owned by the UI layer (the VM has no navigator).
     * RETRY_FULL restarts the full generation; RETRY_FROM_ANALYZE / RETRY_FROM_GENERATE
     * resume from a later phase when the in-memory checkpoint has the required stage
     * (otherwise they degrade to a full retry).
     */
    fun onAction(action: AiErrorAction) = intent {
        when (action) {
            AiErrorAction.OPEN_SETTINGS -> {
                // Handled by the UI layer (opens Settings.AI).
            }
            AiErrorAction.RETRY_FULL,
            AiErrorAction.RETRY_FROM_ANALYZE,
            AiErrorAction.RETRY_FROM_GENERATE,
            -> {
                if (state.running) return@intent
                val url = state.lastUrl.ifBlank { state.inputUrl }
                if (url.isBlank()) {
                    postSideEffect(AppSideEffect.Toast("Nothing to retry", ToastType.ERROR))
                    return@intent
                }
                // Resume only when the checkpoint covers the requested stage (RETRY_FULL
                // never matches), otherwise degrade to a full retry.
                val resumeFrom = action.takeIf { it in state.resumableStages }
                startGeneration(rawUrlInput = url, noteInput = state.lastNote, resumeFrom = resumeFrom)
            }
        }
    }

    fun onRegenerate() = intent {
        if (state.running) return@intent
        val url = state.lastUrl.ifBlank { state.inputUrl }
        if (url.isBlank()) {
            postSideEffect(AppSideEffect.Toast("Nothing to regenerate", ToastType.ERROR))
            return@intent
        }
        startGeneration(rawUrlInput = url, noteInput = state.lastNote)
    }

    fun onCancel() = intent {
        if (!state.running) return@intent
        scoopAiBridge.cancel()
        reduce {
            state.copy(
                running = false,
                currentPhase = null,
                messages = state.messages.filterNot { it.isTyping } +
                    agentMessage("Generation cancelled by user.", "error"),
            )
        }
        postSideEffect(AppSideEffect.Toast("Cancelled", ToastType.INFO))
    }

    fun onCopyDone() = intent {
        postSideEffect(AppSideEffect.Toast("Manifest copied to clipboard", ToastType.SUCCESS))
    }

    fun onDismissSaveResult() = intent {
        reduce { state.copy(saveResult = null) }
    }

    fun onTestInstall() = intent {
        if (state.running || state.testing) {
            postSideEffect(AppSideEffect.Toast("Another operation is running", ToastType.INFO))
            return@intent
        }
        val manifestJson = state.manifestJson
        if (manifestJson.isBlank()) {
            postSideEffect(AppSideEffect.Toast("No manifest to test install", ToastType.ERROR))
            return@intent
        }

        val baseName = state.suggestedAppName?.takeIf { it.isNotBlank() } ?: "app"
        val testName = "$baseName-scooper-test"
        val tempFile = File(System.getProperty("java.io.tmpdir"), "$testName.json")
        val startMsg = agentMessage(
            "Starting test install as $testName...",
            "test-install",
        )

        reduce {
            state.copy(
                testing = true,
                testResult = null,
                messages = state.messages + startMsg,
            )
        }

        var resultMsg: ChatMessage? = null
        var testResult: TestResult? = null
        try {
            val content = rewriteManifestName(manifestJson, testName)
            withContext(Dispatchers.IO) {
                tempFile.writeText(content)
            }

            val result = withContext(Dispatchers.IO) {
                scoopClient.testInstallManifest(testName, tempFile)
            }
            logger.info(
                "Test install finished for $testName: exitCode=${result.exitCode}, " +
                    "isSuccess=${result.isSuccess}, errorMessage=${result.errorMessage}, " +
                    "prewarmed=${result.prewarmedCount}",
            )

            val log = tailLines(result.output, MAX_TEST_LOG_LINES)

            if (result.isSuccess) {
                resultMsg = agentMessage(
                    "Test install passed for $testName. Package installed successfully.",
                    "done",
                )
                testResult = TestResult(
                    success = true,
                    testName = testName,
                    prewarmedCount = result.prewarmedCount,
                    log = log,
                )
            } else {
                val detail = result.errorMessage?.takeIf { it.isNotBlank() }
                    ?: "exit code ${result.exitCode}"
                val safe = AiErrorTranslator.maskSecrets(detail)
                resultMsg = agentMessage(
                    "Test install failed for $testName: $safe",
                    "error",
                )
                val summary = AiErrorTranslator.maskSecrets(
                    errorSummary(result.errorMessage, result.output, MAX_ERROR_SUMMARY_LINES),
                )
                testResult = TestResult(
                    success = false,
                    testName = testName,
                    errorMessage = summary.ifBlank { safe },
                    prewarmedCount = result.prewarmedCount,
                    log = log,
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val message = e.message?.takeIf { it.isNotBlank() }
                ?: e::class.simpleName
                ?: "Test install failed"
            logger.error("Test install failed: $message")
            val safe = AiErrorTranslator.maskSecrets(message)
            resultMsg = agentMessage(safe, "error")
            testResult = TestResult(
                success = false,
                testName = testName,
                errorMessage = safe,
            )
        } finally {
            try {
                withContext(Dispatchers.IO) {
                    cleanupTestInstall(testName, tempFile)
                }
            } catch (e: Exception) {
                logger.warn("Test install cleanup threw for $testName: ${e.message}")
            }
            val cleanupMsg = agentMessage(
                "Test install cleanup finished for $testName.",
                "test-install",
            )
            // Single reduce at end so intermediate success/error is not lost to Orbit races.
            reduce {
                state.copy(
                    testing = false,
                    testResult = testResult,
                    messages = state.messages + listOfNotNull(resultMsg, cleanupMsg),
                )
            }
        }
    }

    fun onSave() = intent {
        if (state.manifestJson.isBlank()) {
            postSideEffect(AppSideEffect.Toast("No manifest to save", ToastType.ERROR))
            return@intent
        }
        val buckets = scoopClient.bucketNames.filter { it.isNotBlank() }.sorted()
        if (buckets.isEmpty()) {
            postSideEffect(AppSideEffect.Toast("No Scoop buckets found", ToastType.ERROR))
            return@intent
        }
        val defaultBucket = when {
            state.saveBucket in buckets -> state.saveBucket
            "main" in buckets -> "main"
            else -> buckets.first()
        }
        reduce {
            state.copy(
                saveDialogOpen = true,
                saveAppName = state.suggestedAppName.orEmpty(),
                saveBucket = defaultBucket,
                bucketOptions = buckets,
                overwriteConfirmOpen = false,
                // C2: reopening the dialog clears the previous result card so it does not
                // linger under the new dialog; a re-save starts from a blank result.
                saveResult = null,
            )
        }
    }

    fun onSaveAppNameChange(name: String) = intent {
        reduce { state.copy(saveAppName = name) }
    }

    fun onSaveBucketChange(bucket: String) = intent {
        reduce { state.copy(saveBucket = bucket) }
    }

    fun onDismissSaveDialog() = intent {
        reduce {
            state.copy(
                saveDialogOpen = false,
                overwriteConfirmOpen = false,
            )
        }
    }

    fun onConfirmSave() = intent {
        performSave(allowOverwrite = state.overwriteConfirmOpen)
    }

    fun onConfirmOverwrite() = intent {
        performSave(allowOverwrite = true)
    }

    fun onDismissOverwrite() = intent {
        reduce { state.copy(overwriteConfirmOpen = false) }
    }

    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.performSave(
        allowOverwrite: Boolean,
    ) {
        val manifestJson = state.manifestJson
        if (manifestJson.isBlank()) {
            postSideEffect(AppSideEffect.Toast("No manifest to save", ToastType.ERROR))
            reduce {
                state.copy(
                    saveDialogOpen = false,
                    overwriteConfirmOpen = false,
                )
            }
            return
        }

        val name = state.saveAppName.trim()
        if (name.isEmpty() || !APP_NAME_REGEX.matches(name)) {
            postSideEffect(
                AppSideEffect.Toast(
                    "App name may only contain letters, digits, '.', '_' and '-'",
                    ToastType.ERROR,
                ),
            )
            return
        }

        val bucket = state.saveBucket.trim()
        if (bucket.isEmpty() || bucket !in scoopClient.bucketNames) {
            postSideEffect(AppSideEffect.Toast("Please select a valid bucket", ToastType.ERROR))
            return
        }

        val baseDir = scoopClient.bucketsBaseDir
        val target = baseDir.resolve(bucket).resolve("bucket").resolve("$name.json")
        val baseCanonical = baseDir.canonicalFile
        val targetCanonical = target.canonicalFile
        val basePath = baseCanonical.path
        val targetPath = targetCanonical.path
        val underBase = targetPath == basePath ||
            targetPath.startsWith(basePath + File.separator)
        if (!underBase) {
            postSideEffect(AppSideEffect.Toast("Invalid save path", ToastType.ERROR))
            return
        }

        val overwritten = targetCanonical.exists()
        if (overwritten && !allowOverwrite) {
            reduce { state.copy(overwriteConfirmOpen = true) }
            return
        }

        var indexedOk = false
        var gitHint: SaveGitHint? = null
        // C2: writing the manifest file is the ONLY fatal step. A failure here is a real
        // save failure: error toast, dialog stays open for retry, and no result card is
        // written (onSave already cleared any previous card).
        try {
            withContext(Dispatchers.IO) {
                targetCanonical.parentFile?.mkdirs()
                targetCanonical.writeText(formatForBucket(manifestJson))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val message = e.message?.takeIf { it.isNotBlank() }
                ?: e::class.simpleName
                ?: "Failed to save manifest"
            logger.error("Save to bucket failed: $message")
            postSideEffect(AppSideEffect.Toast(AiErrorTranslator.maskSecrets(message), ToastType.ERROR))
            return
        }

        // C2: the file is written; the post-save indexing / git-hint steps are best-effort
        // only. If they throw, degrade to a "saved but not indexed" card instead of failing
        // the whole save (the file IS on disk, so a fat error toast would be misleading).
        try {
            withContext(Dispatchers.IO) {
                // Index the newly saved manifest immediately so Discover sees it
                // without waiting for a git commit / full reload.
                appsRepository.ensureBucket(bucket)
                val installed = scoopClient.installedSnapshot()
                val bucketDir = scoopClient.bucketsBaseDir.resolve(bucket)
                indexedOk = scoopClient.buildAppsFromManifestNames(
                    bucketDir,
                    setOf("$name.json"),
                    Bucket(name = bucket, url = ""),
                    installed,
                ).firstOrNull()?.let { app ->
                    appsRepository.upsertApp(app)
                    true
                } ?: false.also {
                    logger.warn("Saved manifest could not be indexed: $name.json")
                }
                gitHint = buildSaveGitHint(
                    isGitBucket = gitHistoryService.isGitBucket(bucketDir),
                    bucketDirPath = bucketDir.path,
                    appName = name,
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn("Post-save indexing / git-hint failed; file still saved: ${e.message}")
        }

        reduce {
            state.copy(
                saveDialogOpen = false,
                overwriteConfirmOpen = false,
                suggestedAppName = name,
                saveResult = classifySaveOutcome(
                    writeOk = true,
                    indexOk = indexedOk,
                    gitHint = gitHint,
                    appName = name,
                    bucket = bucket,
                    targetPath = targetPath,
                    overwritten = overwritten,
                ),
            )
        }
        postSideEffect(
            AppSideEffect.Toast(
                "Saved $name.json to bucket/$bucket",
                ToastType.SUCCESS,
            ),
        )
    }

    companion object {
        private val APP_NAME_REGEX = Regex("^[a-zA-Z0-9._-]+$")
        private const val MAX_TEST_LOG_LINES = 200
        private const val MAX_ERROR_SUMMARY_LINES = 5
    }

    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.startGeneration(
        rawUrlInput: String,
        noteInput: String,
        resumeFrom: AiErrorAction? = null,
    ) {
        val missing = scoopAiBridge.readSettings().missingReason()
        if (missing != null) {
            reduce {
                state.copy(
                    aiConfigured = false,
                    configHint = missing,
                    error = missing,
                )
            }
            postSideEffect(AppSideEffect.Toast(missing, ToastType.ERROR))
            return
        }

        val (urlFromField, extraFromField) = GithubUrlParser.extract(rawUrlInput)
        if (urlFromField == null) {
            val msg = "Please enter a GitHub repository URL (github.com/owner/repo)."
            reduce { state.copy(error = msg) }
            postSideEffect(AppSideEffect.Toast(msg, ToastType.ERROR))
            return
        }

        val userPrompt = listOfNotNull(
            extraFromField.ifBlank { null },
            noteInput.trim().ifBlank { null },
        ).joinToString(" ").ifBlank { null }
        val appGuess = GithubUrlParser.deriveAppName(urlFromField)

        reduce {
            state.copy(
                messages = state.messages +
                    ChatMessage(role = ChatRole.User, text = urlFromField, note = userPrompt) +
                    typingMessage("research"),
                running = true,
                currentPhase = "research",
                lastUrl = urlFromField,
                lastNote = userPrompt.orEmpty(),
                manifestJson = "",
                suggestedAppName = appGuess,
                testResult = null,
                saveResult = null,
                error = null,
                manifestExhausted = false,
                manifestInvalidJson = false,
                manifestIssues = emptyList(),
                aiConfigured = true,
                configHint = null,
                inputUrl = urlFromField,
                inputNote = noteInput,
            )
        }

        scoopAiBridge.startGenerate(
            url = urlFromField,
            userPrompt = userPrompt,
            resumeFrom = resumeFrom,
            onPhase = { phase -> handlePhase(phase) },
            onFinished = { result -> handleFinished(result) },
        )
    }

    private fun handlePhase(phase: String) = intent {
        if (!state.running) return@intent
        val withoutTyping = state.messages.filterNot { it.isTyping }
        reduce {
            state.copy(
                currentPhase = phase,
                messages = withoutTyping +
                    agentMessage(phaseMessage(phase), phase) +
                    typingMessage(phase),
            )
        }
    }

    private fun handleFinished(result: Result<ManifestResult>) = intent {
        val withoutTyping = state.messages.filterNot { it.isTyping }

        result.fold(
            onSuccess = { manifestResult ->
                if (manifestResult.noAssets) {
                    val name = manifestResult.suggestedAppName ?: state.suggestedAppName ?: "app"
                    reduce {
                        state.finishGeneration(
                            messages = withoutTyping +
                                agentMessage("No Windows assets found for this repository.", "error"),
                            error = "No Windows assets found",
                            suggestedAppName = name,
                        )
                    }
                    postSideEffect(AppSideEffect.Toast("No Windows assets found", ToastType.ERROR))
                    return@fold
                }

                val pretty = prettyManifest(manifestResult.manifest)
                val name = manifestResult.suggestedAppName
                    ?: state.suggestedAppName
                    ?: GithubUrlParser.deriveAppName(state.lastUrl)

                val classification = classifyManifestFinish(
                    manifest = manifestResult.manifest,
                    exhausted = manifestResult.exhausted,
                )
                val finishedWithIssues = classification.finishedWithIssues
                val invalidJson = classification.invalidJson
                val issues = if (finishedWithIssues) {
                    when {
                        manifestResult.issues.isNotEmpty() -> manifestResult.issues
                        invalidJson -> listOf("Manifest output is not valid JSON.")
                        else -> listOf("Generation finished with unresolved issues.")
                    }
                } else {
                    emptyList()
                }

                reduce {
                    state.finishGeneration(
                        messages = withoutTyping + agentMessage(
                            if (finishedWithIssues) {
                                "Generation finished with issues. See card below for details."
                            } else {
                                "Done. Suggested name: $name. Manifest ready (see card below)."
                            },
                            if (finishedWithIssues) "error" else "done",
                        ),
                        error = null,
                        manifestJson = pretty,
                        suggestedAppName = name,
                        manifestExhausted = finishedWithIssues,
                        manifestInvalidJson = invalidJson,
                        manifestIssues = issues,
                    )
                }
                postSideEffect(
                    AppSideEffect.Toast(
                        if (finishedWithIssues) {
                            "Generation finished with issues"
                        } else {
                            "Manifest generated: $name"
                        },
                        if (finishedWithIssues) ToastType.ERROR else ToastType.SUCCESS,
                    ),
                )
            },
            onFailure = { error ->
                if (error is CancellationException) {
                    if (state.running) {
                        reduce {
                            state.finishGeneration(
                                messages = withoutTyping + agentMessage("Generation cancelled.", "error"),
                            )
                        }
                    }
                    return@fold
                }

                val presentation = AiErrorTranslator.translate(error)
                logger.error("Create generate failed: ${error.message}")
                val safe = AiErrorTranslator.maskSecrets(presentation.detail)
                // Refresh available resume stages from the checkpoint and hide
                // resume buttons whose stage has no checkpoint data.
                val resumableStages = resumableStagesOf(checkpointService.snapshot())
                val filteredActions = resolveErrorActions(presentation.actions, resumableStages)
                reduce {
                    state.finishGeneration(
                        messages = withoutTyping + agentError(presentation.copy(actions = filteredActions)),
                        error = safe,
                        resumableStages = resumableStages,
                    )
                }
                postSideEffect(AppSideEffect.Toast(safe, ToastType.ERROR))
            },
        )
    }

    private fun prettyManifest(raw: String): String {
        if (raw.isBlank()) return raw
        return try {
            val element = prettyJson.parseToJsonElement(raw)
            prettyJson.encodeToString(JsonElement.serializer(), element)
        } catch (_: Exception) {
            raw
        }
    }

    /** If the manifest has a top-level name field, rewrite it to the test app name. */
    private fun rewriteManifestName(raw: String, testName: String): String {
        return try {
            val element = prettyJson.parseToJsonElement(raw)
            val obj = element.jsonObject
            if (!obj.containsKey("name")) return raw
            val rewritten = JsonObject(
                obj.toMutableMap().apply { put("name", JsonPrimitive(testName)) },
            )
            prettyJson.encodeToString(JsonElement.serializer(), rewritten)
        } catch (_: Exception) {
            raw
        }
    }

    private suspend fun cleanupTestInstall(testName: String, tempFile: File) {
        try {
            val installed = scoopClient.localInstalledAppDirs.any {
                it.name.equals(testName, ignoreCase = true)
            }
            if (installed) {
                scoopClient.uninstall(
                    App(name = testName, latestVersion = "0"),
                    global = false,
                )
            }
            scoopClient.removeCache(testName)
        } catch (e: Exception) {
            logger.warn("Test install cleanup failed for $testName: ${e.message}")
        } finally {
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (e: Exception) {
                logger.warn("Failed to delete temp manifest ${tempFile.absolutePath}: ${e.message}")
            }
        }
    }

    private fun agentError(presentation: AiErrorPresentation): ChatMessage = ChatMessage(
        role = ChatRole.Agent,
        text = presentation.title,
        phase = "error",
        note = AiErrorTranslator.maskSecrets(presentation.detail),
        actions = presentation.actions,
    )

    private fun agentMessage(text: String, phase: String) = ChatMessage(
        role = ChatRole.Agent,
        text = text,
        phase = phase,
    )

    private fun typingMessage(phase: String) = ChatMessage(
        role = ChatRole.Agent,
        text = "",
        phase = phase,
        isTyping = true,
    )

    private fun CreateState.finishGeneration(
        messages: List<ChatMessage>,
        error: String? = this.error,
        manifestJson: String = this.manifestJson,
        suggestedAppName: String? = this.suggestedAppName,
        resumableStages: Set<AiErrorAction> = this.resumableStages,
        manifestExhausted: Boolean = this.manifestExhausted,
        manifestInvalidJson: Boolean = this.manifestInvalidJson,
        manifestIssues: List<String> = this.manifestIssues,
    ) = copy(
        running = false,
        currentPhase = null,
        messages = messages,
        error = error,
        manifestJson = manifestJson,
        suggestedAppName = suggestedAppName,
        resumableStages = resumableStages,
        manifestExhausted = manifestExhausted,
        manifestInvalidJson = manifestInvalidJson,
        manifestIssues = manifestIssues,
    )

    private fun phaseMessage(phase: String): String = when (phase.lowercase()) {
        "research" -> "Researching repository metadata and latest release assets..."
        "analyze" -> "Analyzing Windows assets, computing hashes, and detecting binaries..."
        "generate" -> "Writing Scoop manifest (architecture, checkver, autoupdate)..."
        "refine" -> "Refining manifest based on validation / review feedback..."
        else -> "Working on phase: $phase..."
    }

    override fun close() {
        // Cancel in-flight generation only; bridge is a Koin singleton shared for app lifetime
        scoopAiBridge.cancel()
        supervisorJob.cancel()
    }
}

/** Keep only the last [maxLines] lines of a log to avoid huge panels. */
internal fun tailLines(text: String, maxLines: Int): String {
    val lines = text.lines()
    if (lines.size <= maxLines) return text
    return lines.takeLast(maxLines).joinToString("\n")
}

/**
 * Build a short failure summary: the detected error line plus the tail of the install log.
 *
 * The detected line is prepended (it is usually NOT in the log tail), so the tail is capped
 * at [maxLines] - 1 to guarantee the detected line survives the final cut.
 */
internal fun errorSummary(errorMessage: String?, log: String, maxLines: Int): String {
    val detected = errorMessage?.trim().orEmpty()
    if (detected.isEmpty()) return tailLines(log, maxLines)
    val tail = tailLines(log, maxLines - 1)
    val combined = if (detected in tail) tail else "$detected\n$tail"
    return tailLines(combined, maxLines)
}

private val bucketPrettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "    "
}

/**
 * Builds the `git add && git commit` command for saving a manifest into a Git bucket,
 * for cmd.exe on Windows (Scoop / Git for Windows run from cmd; `&&` is NOT supported by
 * Windows PowerShell 5.1, so this is a single-syntax cmd command).
 *
 * The bucket path is ALWAYS double-quoted: cmd has no backslash escape inside double
 * quotes, so backslashes (e.g. `C:\Users\...`) and `&` inside the quotes stay literal and
 * the `&&` chain survives. The path is kept as-is (forward or back slashes both work in
 * Git for Windows, and normalizing could break UNC). App names are already validated to
 * [A-Za-z0-9._-] by the save dialog, so no escaping is needed inside the double-quoted
 * strings. Uses the `--` separator so a leading-dash app name can never be parsed as an
 * option by git.
 */
internal fun gitCommitCommand(bucketDirPath: String, appName: String): String {
    // cmd rule for an embedded literal double quote is to double it (`""`); Windows
    // paths cannot contain `"`, so this is purely defensive.
    val dir = "\"${bucketDirPath.replace("\"", "\"\"")}\""
    return "git -C $dir add -- bucket/$appName.json && git -C $dir commit -m \"Add $appName.json\""
}

/**
 * Builds the git commit hint for a saved manifest, or null when the bucket is not a Git
 * repository. Discover visibility does not depend on a commit (save already indexes the
 * app via upsertApp), so the hint is purely informational.
 */
internal fun buildSaveGitHint(
    isGitBucket: Boolean,
    bucketDirPath: String,
    appName: String,
): SaveGitHint? {
    if (!isGitBucket) return null
    return SaveGitHint(
        bucketDir = bucketDirPath,
        command = gitCommitCommand(bucketDirPath, appName),
    )
}

/**
 * Classify the save attempt into the SaveResult card.
 *
 * A failed write (`writeOk=false`) is the ONLY fatal failure: the caller shows an error
 * toast and keeps the dialog open, and no card is rendered. Once the file is written
 * (`writeOk=true`), the save is always a success even when the best-effort Discover
 * indexing / git-hint step broke (`indexOk=false`): such a card degrades to
 * indexedOk=false / gitHint=null instead of failing the whole save, so the user is never
 * told the save failed just because indexing hiccuped. `success` stays on the data class
 * for future failure cards; today the UI only renders cards from writeOk=true outcomes.
 */
internal fun classifySaveOutcome(
    writeOk: Boolean,
    indexOk: Boolean,
    gitHint: SaveGitHint?,
    appName: String,
    bucket: String,
    targetPath: String,
    overwritten: Boolean,
): SaveResult = SaveResult(
    success = writeOk,
    appName = appName,
    bucket = bucket,
    targetPath = targetPath,
    overwritten = overwritten,
    indexedOk = writeOk && indexOk,
    gitHint = if (writeOk) gitHint else null,
)
private val trailingWsRegex = Regex("[ \t]+$")

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

/** Classification of a finished generation, splitting refine exhaustion from invalid JSON. */
data class FinishClassification(
    /** True when the manifest is not valid Scoop manifest JSON. */
    val invalidJson: Boolean,
    /** True when the run ended with issues (refine exhausted and/or invalid JSON). Drives badge/toast. */
    val finishedWithIssues: Boolean,
)

/**
 * Classify a finished generation result.
 *
 * The exhausted flag alone must NOT disable Test Install / Save: a valid JSON manifest
 * with unresolved refine issues can still be test-installed (the UI keeps the buttons
 * enabled and only [invalidJson] gates them).
 */
internal fun classifyManifestFinish(manifest: String, exhausted: Boolean): FinishClassification {
    val invalidJson = !isScoopManifestJson(manifest)
    return FinishClassification(
        invalidJson = invalidJson,
        finishedWithIssues = exhausted || invalidJson,
    )
}

/**
 * Normalizes a manifest to Scoop bucket file style: 4-space indent, CRLF, no trailing
 * whitespace, exactly one trailing CRLF. Returns input unchanged when it is not valid JSON.
 */
internal fun formatForBucket(json: String): String {
    if (json.isBlank()) return json
    val pretty = try {
        val element = bucketPrettyJson.parseToJsonElement(json)
        bucketPrettyJson.encodeToString(JsonElement.serializer(), element)
    } catch (_: Exception) {
        return json
    }
    val normalized = pretty.replace("\r\n", "\n")
        .lines()
        .joinToString("\n") { it.replace(trailingWsRegex, "") }
        .replace("\n", "\r\n")
    return normalized.trimEnd('\r', '\n') + "\r\n"
}
