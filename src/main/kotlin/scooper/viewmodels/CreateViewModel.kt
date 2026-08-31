package scooper.viewmodels

import com.scoopai.core.ManifestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import scooper.service.PhaseSummaryFormatter
import scooper.service.ScoopAiBridge
import scooper.service.ToolActivityFormatter
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
    val toolActivities: List<String> = emptyList(),
    val summary: String? = null,
    /** Suggested recovery actions for an error bubble; empty for non-error messages. */
    val actions: List<AiErrorAction> = emptyList(),
    /** Detailed technical log/stack for error bubbles (collapsible). */
    val technicalDetails: String? = null,
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
    val gitCommitted: Boolean = false,
    val gitHint: SaveGitHint? = null,
)

data class QuestionnaireOption(
    val label: String,
    val value: String? = null,
    val description: String? = null,
) {
    fun resolvedValue(): String = value?.ifBlank { null } ?: label
}

data class QuestionnaireQuestion(
    val id: String = java.util.UUID.randomUUID().toString(),
    val label: String? = null,
    val prompt: String,
    val options: List<QuestionnaireOption> = emptyList(),
    val allowOther: Boolean = true,
)

data class AskUserRequest(
    val id: String = java.util.UUID.randomUUID().toString(),
    val questions: List<QuestionnaireQuestion>,
) {
    val question: String get() = questions.firstOrNull()?.prompt.orEmpty()

    val isDownloadPrompt: Boolean
        get() = questions.firstOrNull()?.let { q ->
            q.id == "download_confirm" ||
                q.prompt.contains("download", ignoreCase = true) ||
                q.prompt.contains("installer/archive", ignoreCase = true)
        } == true

    constructor(question: String) : this(
        questions = listOf(
            QuestionnaireQuestion(
                prompt = question,
            )
        )
    )
}

enum class RevisionSource {
    INITIAL_GENERATE,
    REFINE,
    TEST_FIX,
    REVERT,
}

data class ManifestRevision(
    val id: String = UUID.randomUUID().toString(),
    val revisionNumber: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val source: RevisionSource,
    val instruction: String? = null,
    val manifestJson: String,
    val diffSummary: List<String> = emptyList(),
)

data class ReviewActionChip(
    val id: String = java.util.UUID.randomUUID().toString(),
    val label: String,
    val prompt: String,
    val isPrimary: Boolean = false,
)

fun extractReviewActionChips(
    issues: List<String>,
    invalidJson: Boolean,
    testResult: TestResult? = null,
): List<ReviewActionChip> {
    val chips = mutableListOf<ReviewActionChip>()

    if (testResult != null && !testResult.success) {
        val summary = testResult.errorMessage?.lineSequence()?.firstOrNull()?.take(80) ?: "Installation check failed"
        val logTail = testResult.log.lines().takeLast(15).joinToString("\n")
        val fixPrompt = "The test installation failed with error: $summary\nInstallation log output:\n$logTail\nPlease analyze the failure and adjust the manifest (such as extract_dir, pre_install, installer script, or url fragment) to fix the installation error."
        chips.add(
            ReviewActionChip(
                id = "test_fix",
                label = "⚡ Fix install failure",
                prompt = fixPrompt,
                isPrimary = true,
            )
        )
    }

    if (invalidJson) {
        chips.add(
            ReviewActionChip(
                id = "fix_json",
                label = "Fix JSON syntax",
                prompt = "The manifest contains invalid JSON syntax. Please correct the JSON formatting and ensure it is valid JSON.",
                isPrimary = chips.isEmpty(),
            )
        )
    }

    for (issue in issues) {
        val lower = issue.lowercase()
        when {
            lower.contains("conflict") || lower.contains("already exists") || lower.contains("name conflict") -> {
                if (chips.none { it.id == "fix_name_conflict" }) {
                    chips.add(
                        ReviewActionChip(
                            id = "fix_name_conflict",
                            label = "Fix name conflict",
                            prompt = "There is a naming conflict with an existing package. Please choose a new, unique package name by prefixing with the project or publisher name.",
                        )
                    )
                }
            }
            lower.contains("persist") -> {
                if (chips.none { it.id == "fix_persist" }) {
                    chips.add(
                        ReviewActionChip(
                            id = "fix_persist",
                            label = "Fix persist paths",
                            prompt = "Ensure all persist paths are relative to \$dir without ~, absolute paths, or environment variables. Remove any invalid persist paths.",
                        )
                    )
                }
            }
            lower.contains("license") || lower.contains("spdx") -> {
                if (chips.none { it.id == "fix_license" }) {
                    chips.add(
                        ReviewActionChip(
                            id = "fix_license",
                            label = "Fix license",
                            prompt = "Fix the license field to use a valid, explicit SPDX license identifier (e.g. GPL-3.0-only or MIT).",
                        )
                    )
                }
            }
            lower.contains("extract_dir") -> {
                if (chips.none { it.id == "fix_extract_dir" }) {
                    chips.add(
                        ReviewActionChip(
                            id = "fix_extract_dir",
                            label = "Fix extract_dir",
                            prompt = "Fix extract_dir to only strip the top-level version directory, or remove extract_dir if handled by pre_install.",
                        )
                    )
                }
            }
            lower.contains("description") -> {
                if (chips.none { it.id == "fix_description" }) {
                    chips.add(
                        ReviewActionChip(
                            id = "fix_description",
                            label = "Improve description",
                            prompt = "Make the description a concise, single-sentence definition of what the application does (under 100 characters, no marketing fluff).",
                        )
                    )
                }
            }
            lower.contains("autoupdate") || lower.contains("checkver") -> {
                if (chips.none { it.id == "fix_autoupdate" }) {
                    chips.add(
                        ReviewActionChip(
                            id = "fix_autoupdate",
                            label = "Fix autoupdate/checkver",
                            prompt = "Fix autoupdate and checkver configuration to ensure correct \$version placeholders and reliable regex matching.",
                        )
                    )
                }
            }
        }
    }

    return chips
}

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
    val saveCommitToGit: Boolean = true,
    val saveBucketIsGit: Boolean = true,
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
    /** Real-time human-readable tool activity line (e.g. "Querying repository info..."). */
    val currentActivity: String? = null,
    /** Model selected for generation in Create workspace. */
    val selectedModel: String = "",
    /** Available models fetched from remote endpoint. */
    val availableModels: List<String> = emptyList(),
    /** True while fetching available models. */
    val fetchingModels: Boolean = false,
    /** Pending user question from the AskUser tool during generation. */
    val askUserRequest: AskUserRequest? = null,
    /** History of manifest revisions produced during this session. */
    val revisions: List<ManifestRevision> = emptyList(),
    /** ID of currently viewed revision (null = latest). */
    val selectedRevisionId: String? = null,
) {
    val isEmpty: Boolean get() = messages.isEmpty() && manifestJson.isBlank() && !running

    val activeManifestJson: String
        get() = if (selectedRevisionId != null) {
            revisions.firstOrNull { it.id == selectedRevisionId }?.manifestJson ?: manifestJson
        } else {
            manifestJson
        }

    val activeRevision: ManifestRevision?
        get() = if (selectedRevisionId != null) {
            revisions.firstOrNull { it.id == selectedRevisionId }
        } else {
            revisions.lastOrNull()
        }

    val reviewActionChips: List<ReviewActionChip>
        get() = extractReviewActionChips(manifestIssues, manifestInvalidJson, testResult)
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
    private var refineAttemptCount = 0
    private var askUserDeferred: CompletableDeferred<String>? = null
    private val activityChannel = kotlinx.coroutines.channels.Channel<com.scoopai.core.ToolActivity>(
        kotlinx.coroutines.channels.Channel.UNLIMITED,
    )

    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    init {
        coroutineScope.launch {
            activityChannel.receiveAsFlow().collect { activity ->
                val text = ToolActivityFormatter.format(activity)
                intent {
                    if (state.running) {
                        val currentPhase = state.currentPhase
                        val updatedMessages = if (activity is com.scoopai.core.ToolActivity.Completed ||
                            activity is com.scoopai.core.ToolActivity.Failed
                        ) {
                            val logItem = if (activity is com.scoopai.core.ToolActivity.Completed) {
                                "✔ $text"
                            } else {
                                "✖ $text"
                            }
                            val targetIndex = state.messages.indexOfLast {
                                it.role == ChatRole.Agent && it.phase.equals(currentPhase, ignoreCase = true)
                            }
                            if (targetIndex != -1) {
                                val target = state.messages[targetIndex]
                                if (logItem !in target.toolActivities) {
                                    state.messages.toMutableList().apply {
                                        this[targetIndex] = target.copy(toolActivities = target.toolActivities + logItem)
                                    }
                                } else state.messages
                            } else state.messages
                        } else {
                            state.messages
                        }

                        reduce {
                            state.copy(
                                currentActivity = text,
                                messages = updatedMessages,
                            )
                        }
                    }
                }
            }
        }
    }

    override val container: Container<CreateState, AppSideEffect> =
        coroutineScope.container(CreateState()) {
            refreshAiConfig()
        }

    fun refreshAiConfig() = intent {
        val settings = scoopAiBridge.readSettings()
        val missing = settings.missingReason()
        reduce {
            state.copy(
                aiConfigured = missing == null,
                configHint = missing,
                selectedModel = if (state.selectedModel.isBlank()) settings.model else state.selectedModel,
            )
        }
        if (missing == null && state.availableModels.isEmpty()) {
            fetchAvailableModels(settings.provider, settings.apiKey, settings.baseUrl)
        }
    }

    fun onModelChange(model: String) = intent {
        reduce { state.copy(selectedModel = model) }
    }

    fun onFetchModels() = intent {
        val settings = scoopAiBridge.readSettings()
        fetchAvailableModels(settings.provider, settings.apiKey, settings.baseUrl)
    }

    private fun fetchAvailableModels(provider: String, apiKey: String, baseUrl: String?) = intent {
        reduce { state.copy(fetchingModels = true) }
        scoopAiBridge.fetchModels(provider, apiKey, baseUrl) { result ->
            coroutineScope.launch {
                intent {
                    reduce {
                        state.copy(
                            fetchingModels = false,
                            availableModels = if (result.ok) result.models else state.availableModels,
                        )
                    }
                }
            }
        }
    }

    fun onInputChange(text: String) = intent {
        reduce { state.copy(inputUrl = text, inputNote = text, error = null) }
    }

    fun onUrlChange(url: String) = intent {
        reduce { state.copy(inputUrl = url, inputNote = url, error = null) }
    }

    fun onNoteChange(note: String) = intent {
        reduce { state.copy(inputNote = note, inputUrl = note, error = null) }
    }

    fun onSend(input: String? = null, url: String? = null, note: String? = null) = intent {
        if (state.running || state.testing) return@intent
        val text = (input ?: note ?: url ?: state.inputUrl).trim()
        if (text.isBlank()) return@intent

        reduce { state.copy(inputUrl = "", inputNote = "", error = null) }

        if (isRefineRequest(
                hasExistingManifest = state.manifestJson.isNotBlank(),
                lastUrl = state.lastUrl,
                inputUrl = text,
                inputNote = text,
            )
        ) {
            val instruction = resolveRefineInstruction(text, text)
            startRefineInternal(instruction)
        } else {
            val (derivedUrl, derivedNote) = GithubUrlParser.extract(text)
            startGeneration(rawUrlInput = derivedUrl.orEmpty(), noteInput = derivedNote)
        }
    }

    fun startRefine(instruction: String) = intent {
        if (state.running) return@intent
        startRefineInternal(instruction)
    }

    fun onReset() = intent {
        scoopAiBridge.cancel()
        checkpointService.reset()
        refineAttemptCount = 0
        reduce {
            CreateState(
                selectedModel = state.selectedModel,
                availableModels = state.availableModels,
                aiConfigured = state.aiConfigured,
                configHint = state.configHint,
            )
        }
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

    fun onAnswerAskUser(answer: String) = intent {
        val text = answer.trim()
        val response = text.ifBlank { DEFAULT_AUTONOMOUS_PROCEED_ANSWER }
        val isDownload = state.askUserRequest?.isDownloadPrompt == true
        val userBubbleText = resolveAskUserBubbleText(response, isDownload)
        reduce {
            state.copy(
                messages = state.messages + ChatMessage(role = ChatRole.User, text = userBubbleText),
                askUserRequest = null,
            )
        }
        askUserDeferred?.complete(response)
    }

    fun onCancelAskUser() = intent {
        reduce {
            state.copy(
                messages = state.messages + ChatMessage(role = ChatRole.User, text = "Let AI decide"),
                askUserRequest = null,
            )
        }
        askUserDeferred?.complete(DEFAULT_AUTONOMOUS_PROCEED_ANSWER)
    }

    fun onCancel() = intent {
        if (!state.running) return@intent
        askUserDeferred?.complete(DEFAULT_AUTONOMOUS_PROCEED_ANSWER)
        askUserDeferred = null
        scoopAiBridge.cancel()
        reduce {
            state.copy(
                running = false,
                currentPhase = null,
                currentActivity = null,
                askUserRequest = null,
                messages = state.messages + agentMessage("Generation cancelled by user.", "error"),
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
                    "Test install passed for $testName. Package installed successfully and cleaned up.",
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
            // Single reduce at end so intermediate success/error is not lost to Orbit races.
            reduce {
                state.copy(
                    testing = false,
                    testResult = testResult,
                    messages = state.messages + listOfNotNull(resultMsg),
                )
            }
        }
    }

    fun onFixFromTestFailure(summary: String, logTail: String) = intent {
        val prompt = buildString {
            appendLine("The test install for manifest failed. Please fix the manifest according to the test failure:")
            appendLine("Error summary: $summary")
            val cleanLog = logTail.trim()
            if (cleanLog.isNotBlank()) {
                appendLine("Install log excerpt:")
                appendLine(cleanLog.takeLast(1000))
            }
        }.trim()
        onSend(input = prompt)
    }

    fun onApplyActionChip(chip: ReviewActionChip) = intent {
        if (state.manifestJson.isBlank() || state.running || state.testing) return@intent
        startRefineInternal(
            instruction = chip.prompt,
            displayUserText = "⚡ Apply fix: ${chip.label}",
            source = if (chip.isPrimary && chip.id == "test_fix") RevisionSource.TEST_FIX else RevisionSource.REFINE,
        )
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
        val defaultBucketDir = scoopClient.bucketsBaseDir.resolve(defaultBucket)
        val isGit = gitHistoryService.isGitBucket(defaultBucketDir)
        reduce {
            state.copy(
                saveDialogOpen = true,
                saveAppName = state.suggestedAppName.orEmpty(),
                saveBucket = defaultBucket,
                bucketOptions = buckets,
                saveBucketIsGit = isGit,
                saveCommitToGit = isGit,
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
        val bucketDir = scoopClient.bucketsBaseDir.resolve(bucket)
        val isGit = gitHistoryService.isGitBucket(bucketDir)
        reduce {
            state.copy(
                saveBucket = bucket,
                saveBucketIsGit = isGit,
            )
        }
    }

    fun onSaveCommitToGitChange(commit: Boolean) = intent {
        reduce { state.copy(saveCommitToGit = commit) }
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
        var gitCommitted = false
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

                val isGit = gitHistoryService.isGitBucket(bucketDir)
                val manifestVersion = try {
                    (Json.parseToJsonElement(manifestJson) as? JsonObject)?.get("version")?.jsonPrimitive?.content
                } catch (_: Exception) {
                    null
                }

                if (state.saveCommitToGit && isGit) {
                    val commitRes = gitHistoryService.commitManifest(
                        bucketDir = bucketDir,
                        appName = name,
                        isUpdate = overwritten,
                        version = manifestVersion,
                    )
                    if (commitRes.isSuccess) {
                        gitCommitted = true
                    } else {
                        logger.warn("Auto git commit failed for $name.json: ${commitRes.exceptionOrNull()?.message}")
                    }
                }

                if (!gitCommitted) {
                    gitHint = buildSaveGitHint(
                        isGitBucket = isGit,
                        bucketDirPath = bucketDir.path,
                        appName = name,
                        version = manifestVersion,
                        isUpdate = overwritten,
                    )
                }
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
                    gitCommitted = gitCommitted,
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

        val initialPhase = when (resumeFrom) {
            AiErrorAction.RETRY_FROM_ANALYZE -> "analyze"
            AiErrorAction.RETRY_FROM_GENERATE -> "generate"
            else -> "research"
        }
        val startPhaseTitle = PhaseSummaryFormatter.formatPhaseStart(initialPhase, appGuess, 0)
        reduce {
            state.copy(
                messages = state.messages +
                    ChatMessage(role = ChatRole.User, text = urlFromField, note = userPrompt) +
                    agentMessage(startPhaseTitle, initialPhase),
                running = true,
                currentPhase = initialPhase,
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
                inputUrl = "",
                inputNote = "",
            )
        }

        refineAttemptCount = 0
        val modelToUse = state.selectedModel
        scoopAiBridge.startGenerate(
            url = urlFromField,
            userPrompt = userPrompt,
            resumeFrom = resumeFrom,
            overrideModel = modelToUse,
            onPhase = { phase -> handlePhase(phase) },
            onToolActivity = { act -> activityChannel.trySend(act) },
            onPhaseOutput = { phase, out -> handlePhaseOutput(phase, out) },
            onAskUser = { question -> handleAskUser(question) },
            onQuestionnaire = { questions -> handleQuestionnaire(questions) },
            onFinished = { result -> handleFinished(result) },
        )
    }

    private suspend fun handleQuestionnaire(questions: List<com.scoopai.tools.interaction.Question>): String {
        val deferred = CompletableDeferred<String>()
        askUserDeferred = deferred
        val domainQuestions = questions.map { q ->
            QuestionnaireQuestion(
                id = q.id,
                label = q.label,
                prompt = q.prompt,
                options = q.options.map { o ->
                    QuestionnaireOption(label = o.label, value = o.value, description = o.description)
                },
                allowOther = q.allowOther,
            )
        }
        intent {
            reduce {
                state.copy(
                    askUserRequest = AskUserRequest(questions = domainQuestions),
                )
            }
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DEFAULT_AUTONOMOUS_PROCEED_ANSWER
        } finally {
            askUserDeferred = null
            intent {
                reduce {
                    state.copy(askUserRequest = null)
                }
            }
        }
    }

    private suspend fun handleAskUser(question: String): String {
        return handleQuestionnaire(
            listOf(
                com.scoopai.tools.interaction.Question(
                    prompt = question,
                )
            )
        )
    }

    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.startRefineInternal(
        instruction: String,
        displayUserText: String? = null,
        source: RevisionSource = RevisionSource.REFINE,
    ) {
        val text = instruction.trim()
        if (text.isBlank()) return
        val currentManifest = state.manifestJson
        if (currentManifest.isBlank()) {
            postSideEffect(AppSideEffect.Toast("No manifest to refine. Generate one first.", ToastType.ERROR))
            return
        }

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

        refineAttemptCount++
        val startPhaseTitle = PhaseSummaryFormatter.formatPhaseStart(
            phase = "refine",
            appName = state.suggestedAppName,
            refineAttempt = refineAttemptCount,
        )

        val userHistory = extractUserHistory(state.messages)
        val userPrompt = displayUserText?.trim().takeUnless { it.isNullOrBlank() } ?: text

        reduce {
            state.copy(
                messages = state.messages +
                    ChatMessage(role = ChatRole.User, text = userPrompt) +
                    agentMessage(startPhaseTitle, "refine"),
                running = true,
                currentPhase = "refine",
                inputUrl = "",
                inputNote = "",
                testResult = null,
                saveResult = null,
                manifestIssues = emptyList(),
                manifestExhausted = false,
                manifestInvalidJson = false,
                currentActivity = null,
                error = null,
            )
        }

        val modelToUse = state.selectedModel
        scoopAiBridge.startRefine(
            currentManifest = currentManifest,
            instruction = text,
            history = userHistory,
            suggestedAppName = state.suggestedAppName,
            overrideModel = modelToUse,
            onPhase = { phase -> handlePhase(phase) },
            onToolActivity = { act -> activityChannel.trySend(act) },
            onPhaseOutput = { phase, out -> handlePhaseOutput(phase, out) },
            onAskUser = { question -> handleAskUser(question) },
            onQuestionnaire = { questions -> handleQuestionnaire(questions) },
            onFinished = { result -> handleFinished(result) },
        )
    }

    private fun handlePhase(phase: String) = intent {
        if (!state.running) return@intent
        val titleText = PhaseSummaryFormatter.formatPhaseStart(
            phase = phase,
            appName = state.suggestedAppName,
            refineAttempt = refineAttemptCount,
        )
        val lastUserIdx = state.messages.indexOfLast { it.role == ChatRole.User }
        val currentRoundMessages = if (lastUserIdx >= 0) {
            state.messages.subList(lastUserIdx, state.messages.size)
        } else {
            state.messages
        }
        val hasPhaseMessage = currentRoundMessages.any {
            it.role == ChatRole.Agent && it.phase.equals(phase, ignoreCase = true)
        }
        val updatedMessages = if (hasPhaseMessage) {
            state.messages
        } else {
            state.messages + agentMessage(titleText, phase)
        }
        reduce {
            state.copy(
                currentPhase = phase,
                currentActivity = null,
                messages = updatedMessages,
            )
        }
    }

    private fun handlePhaseOutput(phase: String, outputJson: String) = intent {
        if (!state.running) return@intent
        val summary = PhaseSummaryFormatter.formatPhaseOutput(phase, outputJson) ?: return@intent
        val targetIndex = state.messages.indexOfLast {
            it.role == ChatRole.Agent && it.phase.equals(phase, ignoreCase = true)
        }
        val updatedMessages = if (targetIndex != -1) {
            state.messages.toMutableList().apply {
                this[targetIndex] = this[targetIndex].copy(summary = summary)
            }
        } else state.messages
        reduce { state.copy(messages = updatedMessages) }
    }

    private fun handleFinished(result: Result<ManifestResult>) = intent {
        result.fold(
            onSuccess = { manifestResult ->
                if (manifestResult.noAssets) {
                    val name = manifestResult.suggestedAppName ?: state.suggestedAppName ?: "app"
                    reduce {
                        state.finishGeneration(
                            messages = state.messages +
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

                val isConflictOnly = isNameConflictOnly(issues, invalidJson)

                val bubbleText = when {
                    isConflictOnly -> "Done with notice: App name '$name' conflicts with an existing local manifest. See card below."
                    finishedWithIssues -> "Generation finished with issues. See card below for details."
                    else -> "Done. Suggested name: $name. Manifest ready (see card below)."
                }
                val bubblePhase = when {
                    isConflictOnly -> "notice"
                    finishedWithIssues -> "error"
                    else -> "done"
                }

                val toastText = when {
                    isConflictOnly -> "Manifest generated with name conflict: $name"
                    finishedWithIssues -> "Generation finished with issues"
                    else -> "Manifest generated: $name"
                }
                val toastType = when {
                    isConflictOnly -> ToastType.INFO
                    finishedWithIssues -> ToastType.ERROR
                    else -> ToastType.SUCCESS
                }

                reduce {
                    state.finishGeneration(
                        messages = state.messages + agentMessage(bubbleText, bubblePhase),
                        error = null,
                        manifestJson = pretty,
                        suggestedAppName = name,
                        manifestExhausted = finishedWithIssues,
                        manifestInvalidJson = invalidJson,
                        manifestIssues = issues,
                    )
                }
                postSideEffect(
                    AppSideEffect.Toast(toastText, toastType),
                )
            },
            onFailure = { error ->
                if (error is CancellationException) {
                    if (state.running) {
                        reduce {
                            state.finishGeneration(
                                messages = state.messages + agentMessage("Generation cancelled.", "error"),
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
                        messages = state.messages + agentError(presentation.copy(actions = filteredActions)),
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
        technicalDetails = presentation.technicalDetails?.let(AiErrorTranslator::maskSecrets),
        actions = presentation.actions,
    )

    private fun agentMessage(text: String, phase: String) = ChatMessage(
        role = ChatRole.Agent,
        text = text,
        phase = phase,
    )

    fun onSelectRevision(revisionId: String?) = intent {
        reduce { state.copy(selectedRevisionId = revisionId) }
    }

    fun onRevertToRevision(revisionId: String) = intent {
        val target = state.revisions.firstOrNull { it.id == revisionId } ?: return@intent
        val diff = computeManifestDiff(state.manifestJson, target.manifestJson)
        val revertRevision = ManifestRevision(
            revisionNumber = state.revisions.size + 1,
            source = RevisionSource.REVERT,
            instruction = "Revert to revision #${target.revisionNumber}",
            manifestJson = target.manifestJson,
            diffSummary = diff,
        )
        reduce {
            state.copy(
                manifestJson = target.manifestJson,
                revisions = state.revisions + revertRevision,
                selectedRevisionId = null,
                manifestIssues = emptyList(),
                manifestExhausted = false,
                manifestInvalidJson = false,
            )
        }
        postSideEffect(AppSideEffect.Toast("Reverted to revision #${target.revisionNumber}", ToastType.INFO))
    }

    private fun CreateState.finishGeneration(
        messages: List<ChatMessage>,
        error: String? = this.error,
        manifestJson: String = this.manifestJson,
        suggestedAppName: String? = this.suggestedAppName,
        resumableStages: Set<AiErrorAction> = this.resumableStages,
        manifestExhausted: Boolean = this.manifestExhausted,
        manifestInvalidJson: Boolean = this.manifestInvalidJson,
        manifestIssues: List<String> = this.manifestIssues,
    ): CreateState {
        val previousManifest = this.revisions.lastOrNull()?.manifestJson.orEmpty()
        val updatedRevisions = if (manifestJson.isNotBlank() && (this.revisions.isEmpty() || previousManifest != manifestJson)) {
            val diff = computeManifestDiff(previousManifest, manifestJson)
            val source = when {
                this.revisions.isEmpty() -> RevisionSource.INITIAL_GENERATE
                this.currentPhase == "refine" -> RevisionSource.REFINE
                else -> RevisionSource.INITIAL_GENERATE
            }
            val lastUserInstruction = this.messages.lastOrNull { it.role == ChatRole.User }?.text
            this.revisions + ManifestRevision(
                revisionNumber = this.revisions.size + 1,
                source = source,
                instruction = if (source == RevisionSource.REFINE) lastUserInstruction else null,
                manifestJson = manifestJson,
                diffSummary = diff,
            )
        } else {
            this.revisions
        }

        return copy(
            running = false,
            currentPhase = null,
            currentActivity = null,
            messages = messages,
            error = error,
            manifestJson = manifestJson,
            suggestedAppName = suggestedAppName,
            resumableStages = resumableStages,
            manifestExhausted = manifestExhausted,
            manifestInvalidJson = manifestInvalidJson,
            manifestIssues = manifestIssues,
            revisions = updatedRevisions,
            selectedRevisionId = null,
        )
    }

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
internal fun gitCommitCommand(
    bucketDirPath: String,
    appName: String,
    version: String? = null,
    isUpdate: Boolean = false,
): String {
    // cmd rule for an embedded literal double quote is to double it (`""`); Windows
    // paths cannot contain `"`, so this is purely defensive.
    val dir = "\"${bucketDirPath.replace("\"", "\"\"")}\""
    val commitMsg = GitHistoryService.buildCommitMessage(appName, version, isUpdate)
    return "git -C $dir add -- bucket/$appName.json && git -C $dir commit -m \"$commitMsg\""
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
    version: String? = null,
    isUpdate: Boolean = false,
): SaveGitHint? {
    if (!isGitBucket) return null
    return SaveGitHint(
        bucketDir = bucketDirPath,
        command = gitCommitCommand(bucketDirPath, appName, version, isUpdate),
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
    gitCommitted: Boolean,
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
    gitCommitted = writeOk && gitCommitted,
    gitHint = if (writeOk && !gitCommitted) gitHint else null,
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

internal const val DEFAULT_AUTONOMOUS_PROCEED_ANSWER = "Please make your best judgment and proceed autonomously."

private val IGNORED_HISTORY_TEXTS = setOf(
    "Download confirmed",
    "Download skipped",
    "Let AI decide",
)

internal fun resolveAskUserBubbleText(response: String, isDownloadPrompt: Boolean): String {
    if (!isDownloadPrompt) return response
    return when {
        response.equals("yes", ignoreCase = true) || response.contains("proceed", ignoreCase = true) -> "Download confirmed"
        response.equals("no", ignoreCase = true) || response.contains("cancel", ignoreCase = true) || response.contains("skip", ignoreCase = true) -> "Download skipped"
        else -> response
    }
}

internal fun extractUserHistory(messages: List<ChatMessage>): List<String> =
    messages.filter { it.role == ChatRole.User && it.text.isNotBlank() }
        .mapNotNull { msg ->
            if (msg.text in IGNORED_HISTORY_TEXTS || msg.text.startsWith("Question:")) {
                null
            } else if (!msg.note.isNullOrBlank()) {
                "${msg.text} (Requirements: ${msg.note})"
            } else {
                msg.text
            }
        }

internal fun isLikelyUrl(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true) ||
        trimmed.startsWith("github.com/", ignoreCase = true)
}

/**
 * Determines whether a send action is a refine request (incremental modification)
 * or a fresh generation.
 */
internal fun isRefineRequest(
    hasExistingManifest: Boolean,
    lastUrl: String,
    inputUrl: String,
    inputNote: String,
): Boolean {
    if (!hasExistingManifest) return false
    val rawUrl = inputUrl.trim()
    val rawNote = inputNote.trim()
    if (rawNote.isNotBlank() && (rawUrl.isBlank() || rawUrl == lastUrl)) return true
    if (!isLikelyUrl(rawUrl) && rawUrl.isNotBlank()) return true
    return false
}

internal fun resolveRefineInstruction(
    inputUrl: String,
    inputNote: String,
): String {
    val rawNote = inputNote.trim()
    if (rawNote.isNotBlank()) return rawNote
    val rawUrl = inputUrl.trim()
    if (!isLikelyUrl(rawUrl) && rawUrl.isNotBlank()) return rawUrl
    return ""
}

internal fun computeManifestDiff(oldJson: String, newJson: String): List<String> {
    if (oldJson.isBlank()) {
        return if (newJson.isNotBlank()) listOf("Initial manifest") else emptyList()
    }
    if (newJson.isBlank() || oldJson == newJson) {
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
        return listOf("Manifest updated")
    }

    val diffs = mutableListOf<String>()
    val allKeys = (oldObj.keys + newObj.keys).toSortedSet()

    for (key in allKeys) {
        val oldVal = oldObj[key]
        val newVal = newObj[key]

        when {
            oldVal == null && newVal != null -> diffs.add("+ $key")
            oldVal != null && newVal == null -> diffs.add("- $key")
            oldVal != newVal -> {
                if (key == "version" && oldVal is JsonPrimitive && newVal is JsonPrimitive) {
                    diffs.add("~ version: ${oldVal.content} → ${newVal.content}")
                } else {
                    diffs.add("~ $key")
                }
            }
        }
    }
    return diffs
}
