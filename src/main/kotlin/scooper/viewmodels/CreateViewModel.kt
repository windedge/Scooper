package scooper.viewmodels

import com.scoopai.core.ManifestResult
import com.scoopai.core.TokenUsage
import com.scoopai.core.ToolActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
import scooper.service.resolveFailureActions
import scooper.service.resumableStagesOf
import scooper.service.parseVerifyInstallResult
import scooper.service.ScoopClient
import scooper.util.logger
import java.io.File
import java.util.UUID

enum class ChatRole {
    User,
    Agent,
}

/**
 * A finalized conversation bubble (architecture proposal 5.1: transient execution
 * state lives in [CreateState.activeExecution]; this message carries the reply text
 * plus the folded execution steps for later review).
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    /** Conversation body: the user instruction, or the AI reply (explanation or synthesized text). */
    val text: String,
    /** Optional user requirement attached to a URL message. */
    val note: String? = null,
    /** Download-confirm details are always a single line; other notes (requirements, errors) wrap freely. */
    val noteSingleLine: Boolean = false,
    /** Execution steps folded in from [ActiveExecution] when the run finished (for review). */
    val executionSteps: List<ExecutionStep> = emptyList(),
    /** Review toggle for the folded execution card; failures auto-expand. */
    val isExecutionExpanded: Boolean = false,
    /** Suggested recovery actions for an error bubble; empty for non-error messages. */
    val actions: List<AiErrorAction> = emptyList(),
    /** Detailed technical log/stack for error bubbles (collapsible). */
    val technicalDetails: String? = null,
    /** Cumulative token usage of the run that produced this reply; null when unreported. */
    val tokenUsage: TokenUsage? = null,
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
    ASSIST,
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

enum class DiffLineType {
    UNCHANGED,
    ADDED,
    DELETED,
}

data class DiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
)

/**
 * Computes a line-by-line unified diff between [oldText] and [newText] (LCS algorithm).
 */
fun computeDetailedLineDiff(oldText: String, newText: String): List<DiffLine> {
    if (oldText.isBlank()) {
        return newText.lines().mapIndexed { idx, line ->
            DiffLine(type = DiffLineType.ADDED, text = line, newLineNumber = idx + 1)
        }
    }
    if (oldText == newText) {
        return newText.lines().mapIndexed { idx, line ->
            DiffLine(type = DiffLineType.UNCHANGED, text = line, oldLineNumber = idx + 1, newLineNumber = idx + 1)
        }
    }

    val oldLines = oldText.lines()
    val newLines = newText.lines()
    val n = oldLines.size
    val m = newLines.size

    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in 0 until n) {
        for (j in 0 until m) {
            dp[i + 1][j + 1] = if (oldLines[i] == newLines[j]) {
                dp[i][j] + 1
            } else {
                maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
    }

    val result = mutableListOf<DiffLine>()
    var i = n
    var j = m
    while (i > 0 || j > 0) {
        if (i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1]) {
            result.add(
                DiffLine(
                    type = DiffLineType.UNCHANGED,
                    text = oldLines[i - 1],
                    oldLineNumber = i,
                    newLineNumber = j,
                )
            )
            i--
            j--
        } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
            result.add(
                DiffLine(
                    type = DiffLineType.ADDED,
                    text = newLines[j - 1],
                    newLineNumber = j,
                )
            )
            j--
        } else if (i > 0) {
            result.add(
                DiffLine(
                    type = DiffLineType.DELETED,
                    text = oldLines[i - 1],
                    oldLineNumber = i,
                )
            )
            i--
        }
    }

    return result.reversed()
}

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
    /** True when a real install test passed (Test Install button or AI verify_install). */
    val installVerified: Boolean = false,
    /** True when the current manifest has been saved to a bucket. */
    val manifestSaved: Boolean = false,
    /** True when the current session is a user assist (revise existing manifest). */
    val assistSession: Boolean = false,
    /** Suggested resume stages currently available from the in-memory checkpoint. */
    val resumableStages: Set<AiErrorAction> = emptySet(),
    /** True when generation finished but the manifest is not ready (assist exhausted or invalid JSON). */
    val manifestExhausted: Boolean = false,
    /** True when the finished manifest is not valid Scoop manifest JSON (blank counts as invalid).
     *  Drives Test Install / Save to Bucket button gating (see [C2 exhausted escape door]). */
    val manifestInvalidJson: Boolean = false,
    /** Unresolved validation/review issues when [manifestExhausted] is true. */
    val manifestIssues: List<String> = emptyList(),
    /** Transient execution progress of the running session; null when idle. */
    val activeExecution: ActiveExecution? = null,
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
    private var assistAttemptCount = 0
    private val repairRoundCounter = RepairRoundCounter()
    private var askUserDeferred: CompletableDeferred<String>? = null
    private val activityChannel = Channel<ToolActivity>(Channel.UNLIMITED)

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
                        reduce {
                            state.copy(
                                activeExecution = attachToolActivity(state.activeExecution, activity, text),
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
        // Persist immediately so the choice survives restarts; best-effort.
        scoopAiBridge.persistModel(model)
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

        if (isAssistRequest(
                hasExistingManifest = state.manifestJson.isNotBlank(),
                lastUrl = state.lastUrl,
                inputUrl = text,
                inputNote = text,
            )
        ) {
            val instruction = resolveAssistInstruction(text, text)
            startAssistInternal(instruction)
        } else {
            val (derivedUrl, derivedNote) = GithubUrlParser.extract(text)
            startGeneration(rawUrlInput = derivedUrl.orEmpty(), noteInput = derivedNote)
        }
    }

    fun startAssist(instruction: String) = intent {
        if (state.running) return@intent
        startAssistInternal(instruction)
    }

    fun onReset() = intent {
        scoopAiBridge.cancel()
        drainActivityEvents(activityChannel)
        checkpointService.reset()
        assistAttemptCount = 0
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
            AiErrorAction.RETRY_ANSWER -> {
                if (state.running) return@intent
                // Resend the latest question (startAssistInternal no-ops on blank text).
                lastUserQuestion(state.messages)?.let { startAssistInternal(it) }
            }
        }
    }


    fun onAnswerAskUser(answer: String) = intent {
        val text = answer.trim()
        val response = text.ifBlank { DEFAULT_AUTONOMOUS_PROCEED_ANSWER }
        val request = state.askUserRequest
        val isDownload = request?.isDownloadPrompt == true
        val userBubbleText = resolveAskUserBubbleText(response, isDownload)
        val downloadDetail = buildDownloadConfirmDetail(request, response)
        reduce {
            state.copy(
                messages = state.messages + ChatMessage(role = ChatRole.User, text = userBubbleText, note = downloadDetail, noteSingleLine = true),
                askUserRequest = null,
            )
        }
        askUserDeferred?.complete(response)
    }

    fun onCancelAskUser() = intent {
        val downloadDetail = buildDownloadConfirmDetail(state.askUserRequest, DEFAULT_AUTONOMOUS_PROCEED_ANSWER)
        reduce {
            state.copy(
                messages = state.messages + ChatMessage(role = ChatRole.User, text = "Let AI decide", note = downloadDetail, noteSingleLine = true),
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
            // Cancel just folds the execution card without auto-expanding it.
            state.copy(askUserRequest = null).finishGeneration(
                messages = state.messages + agentMessage("Generation cancelled by user."),
                error = null,
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
            resultMsg = agentMessage(safe)
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
                    installVerified = if (testResult?.success == true) true else state.installVerified,
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
        startAssistInternal(
            instruction = chip.prompt,
            displayUserText = "⚡ Apply fix: ${chip.label}",
            source = if (chip.isPrimary && chip.id == "test_fix") RevisionSource.TEST_FIX else RevisionSource.ASSIST,
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
                manifestSaved = true,
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
                    ChatMessage(role = ChatRole.User, text = urlFromField, note = userPrompt),
                running = true,
                currentPhase = initialPhase,
                assistSession = false,
                // Transient execution card starts here; engine onPhase/onToolActivity
                // events aggregate into it until the run finishes.
                activeExecution = ActiveExecution(
                    steps = listOf(
                        ExecutionStep(phase = initialPhase, title = startPhaseTitle, isRunning = true),
                    ),
                    currentStepStartedAtMs = System.currentTimeMillis(),
                ),
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

        assistAttemptCount = 0
        repairRoundCounter.reset()
        val modelToUse = state.selectedModel
        scoopAiBridge.startGenerate(
            url = urlFromField,
            userPrompt = userPrompt,
            resumeFrom = resumeFrom,
            overrideModel = modelToUse,
            onPhase = { phase -> handlePhase(phase) },
            onToolActivity = ::routeToolActivity,
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

    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.startAssistInternal(
        instruction: String,
        displayUserText: String? = null,
        source: RevisionSource = RevisionSource.ASSIST,
    ) {
        val text = instruction.trim()
        if (text.isBlank()) return
        val currentManifest = state.manifestJson
        if (currentManifest.isBlank()) {
            postSideEffect(AppSideEffect.Toast("No manifest to assist. Generate one first.", ToastType.ERROR))
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

        assistAttemptCount++
        repairRoundCounter.reset()
        val startPhaseTitle = PhaseSummaryFormatter.formatPhaseStart(
            phase = "assist",
            appName = state.suggestedAppName,
            attempt = assistAttemptCount,
        )

        val userHistory = extractUserHistory(state.messages)
        val userPrompt = displayUserText?.trim().takeUnless { it.isNullOrBlank() } ?: text

        reduce {
            state.copy(
                messages = state.messages + ChatMessage(role = ChatRole.User, text = userPrompt),
                running = true,
                currentPhase = "assist",
                assistSession = true,
                activeExecution = ActiveExecution(
                    steps = listOf(
                        ExecutionStep(phase = "assist", title = startPhaseTitle, isRunning = true),
                    ),
                    currentStepStartedAtMs = System.currentTimeMillis(),
                ),
                inputUrl = "",
                inputNote = "",
                testResult = null,
                saveResult = null,
                manifestIssues = emptyList(),
                manifestExhausted = false,
                manifestInvalidJson = false,
                error = null,
            )
        }

        val modelToUse = state.selectedModel
        scoopAiBridge.startAssist(
            currentManifest = currentManifest,
            instruction = text,
            history = userHistory,
            suggestedAppName = state.suggestedAppName,
            overrideModel = modelToUse,
            onPhase = { phase -> handlePhase(phase) },
            onToolActivity = ::routeToolActivity,
            onPhaseOutput = { phase, out -> handlePhaseOutput(phase, out) },
            onAskUser = { question -> handleAskUser(question) },
            onQuestionnaire = { questions -> handleQuestionnaire(questions) },
            onFinished = { result -> handleFinished(result) },
        )
    }

    /** Route engine tool activity: step-card lines via the channel, verify flags via intent. */
    private fun routeToolActivity(act: ToolActivity) {
        activityChannel.trySend(act)
        handleToolActivity(act)
    }

    private fun handleToolActivity(act: ToolActivity) = intent {
        reduce { applyInstallVerifyActivity(state, act) }
    }

    private fun handlePhase(phase: String) = intent {
        if (!state.running) return@intent
        val titleText = PhaseSummaryFormatter.formatPhaseStart(
            phase = phase,
            appName = state.suggestedAppName,
            // Only the repair phase consumes the in-session round counter.
            attempt = repairRoundCounter.onPhase(phase),
        )
        reduce {
            state.copy(
                currentPhase = phase,
                activeExecution = appendPhaseStep(
                    active = state.activeExecution,
                    phase = phase,
                    title = titleText,
                    nowMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun handlePhaseOutput(phase: String, outputJson: String) = intent {
        if (!state.running) return@intent
        val summary = PhaseSummaryFormatter.formatPhaseOutput(phase, outputJson) ?: return@intent
        reduce {
            state.copy(
                activeExecution = setPhaseSummary(state.activeExecution, phase, summary),
            )
        }
    }

    private fun handleFinished(result: Result<ManifestResult>) = intent {
        result.fold(
            onSuccess = { manifestResult ->
                if (manifestResult.answered) {
                    val answerText = answeredBubbleText(manifestResult.explanation)
                    // A direct answer never touches the manifest (guard-rail guaranteed),
                    // but the assist step already carries the deterministic "Updated
                    // manifest ..." line written from the engine snapshot. Rewrite it
                    // before finishGeneration folds the active execution into the
                    // finalized message.
                    reduce {
                        state.copy(
                            activeExecution = state.activeExecution?.let { active ->
                                active.copy(steps = markAnsweredSummary(active.steps))
                            },
                        )
                    }
                    reduce {
                        state.finishGeneration(
                            messages = state.messages + agentReplyMessage(answerText, manifestResult.tokenUsage),
                            error = null,
                        )
                    }
                    return@fold
                }

                if (manifestResult.noAssets) {
                    val name = manifestResult.suggestedAppName ?: state.suggestedAppName ?: "app"
                    // The engine synthesizes the noAssets explanation; fall back to the
                    // generic notice when it is missing. Abnormal ending: auto-expand.
                    val noAssetsText = manifestResult.explanation?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "No Windows assets found for this repository."
                    reduce {
                        state.finishGeneration(
                            messages = state.messages + agentReplyMessage(noAssetsText, manifestResult.tokenUsage),
                            error = "No Windows assets found",
                            suggestedAppName = name,
                            executionExpanded = true,
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

                // Explanation priority (proposal 5.3): conflict/issues notices stay the
                // main text (explanation appended as a trailing paragraph); a normal
                // finish uses the engine explanation, else the deterministic synthesis.
                val isAssist = isAssistScenario(state.currentPhase, state.revisions)
                val synthesized = if (isAssist) {
                    val previousManifest = state.revisions.lastOrNull()?.manifestJson.orEmpty()
                    synthesizeAssistExplanation(computeManifestDiff(previousManifest, pretty))
                } else {
                    synthesizeGenerateExplanation(pretty, name)
                }
                val bubbleText = composeFinishedBubbleText(
                    explanation = manifestResult.explanation,
                    isConflictOnly = isConflictOnly,
                    finishedWithIssues = finishedWithIssues,
                    conflictText = "Done with notice: App name '$name' conflicts with an existing local manifest. See card below.",
                    issuesText = finishedWithIssuesNotice(isAssist) + ". See card below for details.",
                    fallbackText = synthesized,
                )

                val toastText = when {
                    isConflictOnly -> "Manifest generated with name conflict: $name"
                    finishedWithIssues -> finishedWithIssuesNotice(isAssist)
                    else -> "Manifest generated: $name"
                }
                val toastType = when {
                    isConflictOnly -> ToastType.INFO
                    finishedWithIssues -> ToastType.ERROR
                    else -> ToastType.SUCCESS
                }

                reduce {
                    state.finishGeneration(
                        messages = state.messages + agentReplyMessage(bubbleText, manifestResult.tokenUsage),
                        error = null,
                        manifestJson = pretty,
                        suggestedAppName = name,
                        manifestExhausted = finishedWithIssues,
                        manifestInvalidJson = invalidJson,
                        manifestIssues = issues,
                        executionExpanded = finishedWithIssues,
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
                                messages = state.messages + agentMessage("Generation cancelled."),
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
                val filteredActions = resolveFailureActions(
                    assistSession = state.assistSession,
                    translated = presentation.actions,
                    resumable = resumableStages,
                )
                reduce {
                    state.finishGeneration(
                        messages = state.messages + agentError(presentation.copy(actions = filteredActions)),
                        error = safe,
                        resumableStages = resumableStages,
                        executionExpanded = true,
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
                // purge = true: test installs must not leave persist data behind.
                scoopClient.uninstall(
                    App(name = testName, latestVersion = "0"),
                    global = false,
                    purge = true,
                )
            }
        } catch (e: Exception) {
            logger.warn("Test install cleanup failed for $testName: ${e.message}")
        } finally {
            try {
                // Fallback: older scoop ignores -p or the uninstall may have
                // failed, so remove the persist dir directly (best-effort).
                if (scoopClient.cleanupPersistDir(testName)) {
                    logger.info("Removed leftover persist dir for test app $testName")
                }
            } catch (e: Exception) {
                logger.warn("Failed to remove persist dir for test app $testName: ${e.message}")
            }
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
        note = AiErrorTranslator.maskSecrets(presentation.detail),
        technicalDetails = presentation.technicalDetails?.let(AiErrorTranslator::maskSecrets),
        actions = presentation.actions,
    )

    private fun agentMessage(text: String) = ChatMessage(
        role = ChatRole.Agent,
        text = text,
    )

    fun onSelectRevision(revisionId: String?) = intent {
        reduce { state.copy(selectedRevisionId = revisionId) }
    }

    /**
     * Toggle the execution review accordion of a finalized message (stage 3).
     * Unknown ids leave the state unchanged (see [toggleExecutionExpanded]).
     */
    fun onToggleExecutionExpanded(messageId: String) = intent {
        reduce { state.copy(messages = toggleExecutionExpanded(state.messages, messageId)) }
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
                installVerified = false,
                manifestSaved = false,
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
        executionExpanded: Boolean = false,
    ): CreateState {
        // Late-event race defense (proposal 5.1): drain residual buffered activity
        // events BEFORE collapsing so the finalized step list is consistent.
        drainActivityEvents(activityChannel)
        val collapsedMessages = collapseActiveExecution(messages, activeExecution, executionExpanded, System.currentTimeMillis())

        val previousManifest = this.revisions.lastOrNull()?.manifestJson.orEmpty()
        val updatedRevisions = if (manifestJson.isNotBlank() && (this.revisions.isEmpty() || previousManifest != manifestJson)) {
            val diff = computeManifestDiff(previousManifest, manifestJson)
            val source = when {
                this.revisions.isEmpty() -> RevisionSource.INITIAL_GENERATE
                this.currentPhase == "assist" || this.currentPhase == "repair" -> RevisionSource.ASSIST
                else -> RevisionSource.INITIAL_GENERATE
            }
            val lastUserInstruction = this.messages.lastOrNull { it.role == ChatRole.User }?.text
            this.revisions + ManifestRevision(
                revisionNumber = this.revisions.size + 1,
                source = source,
                instruction = if (source == RevisionSource.ASSIST) lastUserInstruction else null,
                manifestJson = manifestJson,
                diffSummary = diff,
            )
        } else {
            this.revisions
        }

        val (installVerified, manifestSaved) = newManifestResetsVerification(
            newManifest = manifestJson,
            currentManifest = this.manifestJson,
            installVerified = this.installVerified,
            manifestSaved = this.manifestSaved,
        )
        return copy(
            running = false,
            currentPhase = null,
            activeExecution = null,
            messages = collapsedMessages,
            error = error,
            manifestJson = manifestJson,
            suggestedAppName = suggestedAppName,
            resumableStages = resumableStages,
            manifestExhausted = manifestExhausted,
            manifestInvalidJson = manifestInvalidJson,
            manifestIssues = manifestIssues,
            revisions = updatedRevisions,
            selectedRevisionId = null,
            testing = false,
            installVerified = installVerified,
            manifestSaved = manifestSaved,
        )
    }

    override fun close() {
        // Cancel in-flight generation only; bridge is a Koin singleton shared for app lifetime
        scoopAiBridge.cancel()
        supervisorJob.cancel()
    }
}

/**
 * Drives CreateState from AI verify_install activity: Started shows loading, then a
 * Completed/Failed hides it; a verified success flips [CreateState.installVerified].
 */
internal fun applyInstallVerifyActivity(state: CreateState, activity: ToolActivity): CreateState {
    if (activity.toolName != "verify_install") return state
    return when (activity) {
        is ToolActivity.Started -> state.copy(testing = true)
        is ToolActivity.Completed -> {
            val ok = parseVerifyInstallResult(activity.toolResult).success
            state.copy(
                testing = false,
                installVerified = if (ok == true) true else state.installVerified,
            )
        }
        is ToolActivity.Failed -> state.copy(testing = false)
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

/** Classification of a finished generation, splitting assist exhaustion from invalid JSON. */
data class FinishClassification(
    /** True when the manifest is not valid Scoop manifest JSON. */
    val invalidJson: Boolean,
    /** True when the run ended with issues (assist exhausted and/or invalid JSON). Drives badge/toast. */
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
 * with unresolved assist issues can still be test-installed (the UI keeps the buttons
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
private fun detailFromDownloadPrompt(prompt: String): String? {
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

private fun normalizeDownloadLine(raw: String): String {
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

private const val HISTORY_MAX_ENTRIES = 12
private const val HISTORY_LINE_MAX = 400

internal fun extractUserHistory(messages: List<ChatMessage>): List<String> {
    val relevant = messages.filter { msg ->
        when (msg.role) {
            ChatRole.User ->
                msg.text.isNotBlank() &&
                    msg.text !in IGNORED_HISTORY_TEXTS &&
                    !msg.text.startsWith("Question:")
            // Error bubbles fold failure details into actions; they are not conversation context.
            ChatRole.Agent -> msg.text.isNotBlank() && msg.actions.isEmpty()
        }
    }
    // Tail only: recent turns matter for coreference in assist/refine prompts.
    return relevant.takeLast(HISTORY_MAX_ENTRIES).map { msg ->
        val body = when (msg.role) {
            ChatRole.User ->
                if (!msg.note.isNullOrBlank()) "${msg.text} (Requirements: ${msg.note})" else msg.text
            ChatRole.Agent -> msg.text
        }
        val prefix = if (msg.role == ChatRole.User) "User: " else "Assistant: "
        val line = prefix + body
        if (line.length <= HISTORY_LINE_MAX) line else line.take(HISTORY_LINE_MAX - 3) + "..."
    }
}

internal fun isLikelyUrl(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true) ||
        trimmed.startsWith("github.com/", ignoreCase = true)
}

/**
 * Determines whether a send action is an assist request (incremental modification)
 * or a fresh generation.
 */
internal fun isAssistRequest(
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

internal fun resolveAssistInstruction(
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

/**
 * True when the current finish should be explained as an assist round: the run was in
 * the assist phase AND a previous manifest revision exists to diff against. Internal
 * repair rounds ("repair") are not user assists and return false. Pure helper for the
 * finished-bubble composition in handleFinished.
 */
internal fun isAssistScenario(currentPhase: String?, revisions: List<ManifestRevision>): Boolean {
    if (!currentPhase.equals("assist", ignoreCase = true)) return false
    return revisions.isNotEmpty()
}

/**
 * User-facing notice for a finished-with-issues run: assist rounds say "Assist",
 * initial generation says "Generation". Pure function (toast + issues bubble text).
 */
internal fun finishedWithIssuesNotice(isAssistScenario: Boolean): String =
    if (isAssistScenario) "Assist finished with issues" else "Generation finished with issues"

/**
 * Bubble text for a user assist that the engine answered directly (no manifest
 * change). Uses the engine explanation when present and non-blank, else a generic
 * English fallback. Pure function.
 */
internal fun answeredBubbleText(explanation: String?): String =
    explanation?.trim()?.takeIf { it.isNotEmpty() } ?: "Sorry, I could not produce an answer."

/**
 * Final agent bubble for a successful finish (answered / generated / no-assets).
 * Token usage is attached only when the provider reported a non-zero total, so
 * "0 tokens" footers never render. Pure function (bubble assembly testable).
 */
internal fun agentReplyMessage(text: String, tokenUsage: TokenUsage?): ChatMessage =
    ChatMessage(
        role = ChatRole.Agent,
        text = text,
        tokenUsage = tokenUsage?.takeIf { it.totalTokens > 0 },
    )

/**
 * Text of the newest user bubble in [messages], or null when none exists. Used by
 * the RETRY_ANSWER error action to resend a failed question. Pure function.
 */
internal fun lastUserQuestion(messages: List<ChatMessage>): String? =
    messages.lastOrNull { it.role == ChatRole.User }?.text

/**
 * Deterministic assist explanation (proposal 5.4): human-readable English built from
 * the key-level [computeManifestDiff] output, e.g.
 * "Removed bin, added shortcuts, updated version (1.0.0 → 1.1.0). Validated and ready."
 * Pure function; used when the engine explanation is missing.
 */
internal fun synthesizeAssistExplanation(diff: List<String>): String {
    val removed = mutableListOf<String>()
    val added = mutableListOf<String>()
    val modified = mutableListOf<String>()
    var versionChange: String? = null
    var genericUpdate = false

    for (entry in diff) {
        when {
            entry.startsWith("- ") -> removed.add(entry.removePrefix("- "))
            entry.startsWith("+ ") -> added.add(entry.removePrefix("+ "))
            entry.startsWith("~ ") -> {
                val key = entry.removePrefix("~ ")
                if (key.startsWith("version:")) {
                    versionChange = "updated version (${key.removePrefix("version:").trim()})"
                } else {
                    modified.add(key)
                }
            }
            entry == "Manifest updated" -> genericUpdate = true
            // "Initial manifest" and unknown entries carry no assist detail
        }
    }

    val phrases = buildList {
        if (removed.isNotEmpty()) add("Removed " + removed.joinToString(", "))
        if (added.isNotEmpty()) add("added " + added.joinToString(", "))
        if (modified.isNotEmpty()) add("updated " + modified.joinToString(", "))
        if (versionChange != null) add(versionChange)
    }
    val body = when {
        phrases.isNotEmpty() -> phrases.joinToString(", ").replaceFirstChar { it.uppercaseChar() }
        genericUpdate -> "Updated the manifest"
        else -> "No manifest changes were required"
    }
    return "$body. Validated and ready."
}

/**
 * Deterministic initial-generation explanation (proposal 5.4): describes the manifest's
 * key fields (name, version, url strategy, shortcuts/persist counts), e.g.
 * "Generated manifest for fd 10.2.0 (portable zip). 1 shortcut, 2 persist entries.
 * Validated and ready." Missing fields are omitted robustly; [appName] is the fallback
 * when the manifest has no name field. Pure function.
 */
internal fun synthesizeGenerateExplanation(manifestJson: String, appName: String?): String {
    val obj = try {
        Json.parseToJsonElement(manifestJson) as? JsonObject
    } catch (_: Exception) {
        null
    }

    fun stringField(key: String): String? = obj?.get(key)
        ?.let { it as? JsonPrimitive }
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    // Architecture-package form: "architecture": {"64bit": {"url": ...}}. Used as
    // the strategy source when the top-level url is missing; empty when neither
    // exists (the strategy then degrades to "portable").
    fun architectureFallbackUrl(): String? = obj?.get("architecture")
        ?.let { it as? JsonObject }
        ?.values
        ?.mapNotNull { arch ->
            (arch as? JsonObject)?.get("url")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
                ?.trim()
                ?.takeIf { s -> s.isNotEmpty() }
        }
        ?.firstOrNull()

    val name = stringField("name")
        ?: appName?.trim()?.takeIf { it.isNotEmpty() }
    val version = stringField("version")
    val url = (stringField("url") ?: architectureFallbackUrl()).orEmpty().lowercase()
    val strategy = when {
        url.contains(".zip") -> "portable zip"
        url.contains(".7z") -> "portable 7z archive"
        url.contains(".msix") -> "MSIX package"
        url.contains(".msi") -> "MSI installer"
        url.contains(".exe") -> "installer"
        else -> "portable"
    }

    val shortcuts = (obj?.get("shortcuts") as? JsonArray)?.size ?: 0
    val persistCount = when (val persist = obj?.get("persist")) {
        is JsonArray -> persist.size
        is JsonPrimitive -> if (persist.contentOrNull?.isNotBlank() == true) 1 else 0
        else -> 0
    }

    val subject = buildString {
        append("Generated manifest")
        if (name != null) append(" for $name")
        if (version != null) append(" $version")
        append(" ($strategy)")
    }
    val features = mutableListOf<String>()
    if (shortcuts > 0) features.add(if (shortcuts == 1) "1 shortcut" else "$shortcuts shortcuts")
    if (persistCount > 0) features.add(if (persistCount == 1) "1 persist entry" else "$persistCount persist entries")
    val featureText = if (features.isEmpty()) "" else ". " + features.joinToString(", ")
    return "$subject$featureText. Validated and ready."
}

/**
 * Compose the finished-bubble text following the proposal 5.3 priority:
 * conflict/issues classification keeps the main text (the explanation, when present,
 * is appended as a trailing paragraph); a normal finish uses the engine explanation,
 * else [fallbackText] (the deterministic synthesis). A blank explanation counts as
 * missing. Pure function.
 */
internal fun composeFinishedBubbleText(
    explanation: String?,
    isConflictOnly: Boolean,
    finishedWithIssues: Boolean,
    conflictText: String,
    issuesText: String,
    fallbackText: String,
): String {
    val trimmed = explanation?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        isConflictOnly -> appendExplanationParagraph(conflictText, trimmed)
        finishedWithIssues -> appendExplanationParagraph(issuesText, trimmed)
        else -> trimmed ?: fallbackText
    }
}

private fun appendExplanationParagraph(base: String, explanation: String?): String =
    if (explanation == null) base else "$base\n\n$explanation"
