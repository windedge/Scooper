package scooper.viewmodels

import com.scoopai.core.ManifestResult
import com.scoopai.core.TokenUsage
import com.scoopai.core.ToolActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container
import org.orbitmvi.orbit.syntax.Syntax
import scooper.data.App
import scooper.data.AppStatus
import scooper.data.Bucket
import scooper.repository.AppsRepository
import scooper.service.AiErrorAction
import scooper.service.AiErrorPresentation
import scooper.service.AiErrorTranslator
import scooper.service.AiSettingsStore
import scooper.service.CommandResult
import scooper.service.GithubUrlParser
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.PhaseSummaryFormatter
import scooper.service.AiImage
import scooper.service.ScoopAiBridge
import scooper.service.ScoopService
import scooper.service.ToolActivityFormatter
import scooper.service.resolveFailureActions
import scooper.service.resumableStagesOf
import scooper.service.ScoopClient
import scooper.util.ImageCodec
import scooper.util.logger
import java.io.File
import java.io.IOException
import java.util.UUID

enum class ChatRole {
    User,
    Agent,
}

/**
 * An image attached by the user but not yet sent. Stored in [CreateState.pendingImages]
 * (max 3); preview bitmaps are cached in memory by id at render time to avoid
 * ByteArray equals/hashCode churn in recomposition.
 */
data class PendingImage(
    val id: String = UUID.randomUUID().toString(),
    val bytes: ByteArray,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingImage) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
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
    /** Images attached by the user to this message (rendered as thumbnails in the bubble). */
    val images: List<PendingImage> = emptyList(),
    /** True once this message's images have already been replayed to the engine in a
     *  later round; prevents re-sending the same images on every text-only follow-up. */
    val imagesReplayed: Boolean = false,
)


/** Git commit hint shown on the SaveResultPanel when the bucket is a Git repository. */
data class SaveGitHint(
    val bucketDir: String,
    val command: String,
)

/** Outcome of a successful Save to Bucket, driving the SaveResultPanel result card. */
data class SaveResult(
    val appName: String,
    val bucket: String,
    val targetPath: String,
    val overwritten: Boolean,
    val indexedOk: Boolean,
    val gitCommitted: Boolean = false,
    val gitHint: SaveGitHint? = null,
)

/**
 * Names the engine's conflict check must ignore: the manifest this session
 * itself just saved is already in the bucket at verify time, so counting it
 * as a conflict would make every follow-up assist run a false positive.
 */
internal fun ignoredNamesForConflictCheck(saveResultAppName: String?): Set<String> =
    setOfNotNull(saveResultAppName?.takeIf { it.isNotBlank() })

/**
 * Pick the bucket the save dialog preselects. Priority: the dialog's current
 * selection, then the last used bucket persisted in Settings, then "main",
 * then the first available bucket. Entries not in [buckets] are ignored so a
 * deleted bucket is never preselected.
 */
internal fun resolveDefaultSaveBucket(
    current: String,
    lastUsed: String,
    buckets: List<String>,
): String = when {
    current in buckets -> current
    lastUsed in buckets -> lastUsed
    "main" in buckets -> "main"
    else -> buckets.first()
}

/**
 * Default for the save dialog "commit to git repository" checkbox: checked
 * only for git buckets, remembering the user's last checkbox choice.
 */
internal fun resolveDefaultCommitToGit(isGitBucket: Boolean, lastPreference: Boolean): Boolean =
    isGitBucket && lastPreference

/**
 * Reference-aware save default: the produced app name matching a referenced
 * manifest's name locks that bucket/name as the save dialog default.
 */
internal fun referenceSaveDefault(
    appName: String,
    refs: List<Pair<String, String>>,
    availableBuckets: List<String>,
): Pair<String, String>? {
    val name = appName.trim()
    if (name.isEmpty()) return null
    return refs.lastOrNull { (bucket, refName) ->
        refName.equals(name, ignoreCase = true) && bucket in availableBuckets
    }
}

/** Dialog hint line explaining a reference-locked default; null without a lock. */
internal fun refLockHintText(lock: Pair<String, String>?): String? =
    lock?.let { (bucket, name) -> "Pre-filled from @$bucket/$name reference" }

/**
 * SaveResultPanel hint for an in-place overwrite: set by saveManifestCore when
 * the target file existed before the write, so a deleted referenced manifest
 * degrades to a fresh save without a hint. Null for a fresh save.
 */
internal fun saveUpdatedHintText(result: SaveResult): String? =
    if (result.overwritten) "Updated existing manifest at ${result.bucket}/${result.appName}" else null

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
): List<ReviewActionChip> {
    val chips = mutableListOf<ReviewActionChip>()


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

/** Guard for review action chips: returns the blocking toast when the click
 *  must be dropped (no manifest first, then busy); null applies the chip. */
internal fun applyActionChipBlockToast(
    manifestJson: String,
    running: Boolean,
    testing: Boolean,
): Pair<String, ToastType>? = when {
    manifestJson.isBlank() -> "No manifest to fix" to ToastType.ERROR
    running || testing -> "Another operation is running" to ToastType.INFO
    else -> null
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
    /** Non-null when the dialog defaults were locked from a @bucket/app reference. */
    val saveRefHint: String? = null,
    val overwriteConfirmOpen: Boolean = false,
    val saveResult: SaveResult? = null,
    /** True when a real install test passed (AI verify_install). */
    val installVerified: Boolean = false,
    /** True when the current manifest has been saved to a bucket. */
    val manifestSaved: Boolean = false,
    /** True while an install-for-use of the saved manifest is in flight. */
    val installing: Boolean = false,
    /** True when the saved manifest was installed for real use in this session. */
    val installedForUse: Boolean = false,
    /** Manifest vs installed copy, derived from disk inputs by
     *  [CreateViewModel.refreshDeploymentState]. Single source of truth for the
     *  deploy button consumed by CreateScreen. */
    val deployment: DeploymentState = DeploymentState.NoManifest,
    /** True when the app installed for use in this session has Start Menu shortcuts. */
    val installedShortcuts: Boolean = false,
    /** Name the app was installed under when saveResult is absent (direct installs). */
    val directInstallName: String? = null,
    /** True when the current session is a user assist (revise existing manifest). */
    val assistSession: Boolean = false,
    /** Suggested resume stages currently available from the in-memory checkpoint. */
    val resumableStages: Set<AiErrorAction> = emptySet(),
    /** True when generation finished but the manifest is not ready (assist exhausted or invalid JSON). */
    val manifestExhausted: Boolean = false,
    /** True when the finished manifest is not valid Scoop manifest JSON (blank counts as invalid).
     *  Drives Save to Bucket button gating (see [C2 exhausted escape door]). */
    val manifestInvalidJson: Boolean = false,
    /** Unresolved validation/review issues when [manifestExhausted] is true. */
    val manifestIssues: List<String> = emptyList(),
    /** Transient execution progress of the running session; null when idle. */
    val activeExecution: ActiveExecution? = null,
    /** Monotonic engine-run tag; callbacks captured under an older epoch are dropped. */
    val generationEpoch: Long = 0L,
    /** Monotonic deployment-refresh tag; an older refresh result must not overwrite
     *  a newer one (see [CreateViewModel.refreshDeploymentState]). */
    val deploymentEpoch: Long = 0L,
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
    /** Images pending to be sent with the next message (max 3). */
    val pendingImages: List<PendingImage> = emptyList(),
    /** Local bucket/app index for the @ completion menu; loaded lazily, best effort. */
    val manifestRefSuggestions: List<ManifestRefSuggestion> = emptyList(),
    /** Raw result JSON of the last AI install_app call, for assertions and debugging. */
    val lastInstallReply: String = "",
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
        get() = extractReviewActionChips(manifestIssues, manifestInvalidJson)

    /** First @bucket/app token in the composer draft (note wins over URL), or null
     *  when the draft has none. Drives the Create empty-state copy switch: a
     *  reference turns the hero into modify-oriented guidance. */
    val composerManifestRef: String?
        get() = extractManifestRefs(inputNote.ifBlank { inputUrl })
            .firstOrNull()
            ?.let { "@${it.first}/${it.second}" }
}

class CreateViewModel(
    internal val scoopAiBridge: ScoopAiBridge,
    /** Settings seam (S5); defaults to the bridge's store for existing callers. */
    internal val aiSettingsStore: AiSettingsStore? = null,
    private val checkpointService: InMemoryAiCheckpointService = InMemoryAiCheckpointService(),
    private val scoopClient: ScoopClient,
    private val appsRepository: AppsRepository,
    private val gitHistoryService: GitHistoryService,
    private val scoopService: ScoopService? = null,
) : ContainerHost<CreateState, AppSideEffect>, AutoCloseable {
    private val logger by logger()

    /** Settings read/persist seam; falls back to the bridge's own store. */
    private val aiSettings: AiSettingsStore get() = aiSettingsStore ?: scoopAiBridge.settingsStore

    /** Disk read/derive seam for the deploy button; see [DeploymentMonitor]. */
    private val deploymentMonitor = DeploymentMonitor(scoopClient)

    /** Save-to-bucket domain seam; see [SaveCoordinator]. */
    private val saveCoordinator = SaveCoordinator(
        env = scoopClient,
        git = gitBucketPort(gitHistoryService),
        index = ::indexSavedApp,
        memory = aiSettingsSaveMemory(aiSettings),
        warn = { message -> logger.warn(message) },
    )
    /** Readiness, model and model-list seam shared by both starts; see [AiGate]. */
    private val aiGate = AiGate(aiSettings, scoopAiBridge)
    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private var assistAttemptCount = 0
    private var askUserDeferred: CompletableDeferred<String>? = null

    /**
     * Child job running the current real install launched by [onInstallSaved].
     * The job owns the busy flag: its own finally clears `installing`, so a
     * user cancel cannot leave the flag stuck behind a blocked event loop.
     */
    private var installingJob: Job? = null

    /**
     * True when this instance backs a non-active tab of a multi-tab Create
     * page. A background tab's engine events fold silently into its own state
     * (visible when the user returns to the tab) instead of popping toasts.
     * Volatile: written by the tab controller, read from engine callbacks.
 */
    @Volatile
    internal var isBackgroundTab: Boolean = false

    // ---------------------------------------------------------------
    // tab-bar knowledge, pushed down from CreateTabController so it never
    // digs into container.stateFlow.value
    // ---------------------------------------------------------------

    /** True while an engine run, test install or app install is in flight. */
    internal val isBusy: Boolean
        get() = container.stateFlow.value.let { it.running || it.testing || it.installing }

    /** True when the session holds a manifest that has not been saved yet. */
    internal val hasUnsavedWork: Boolean
        get() = container.stateFlow.value.let { it.manifestJson.isNotBlank() && !it.manifestSaved }

    /** Blank session that can be reused as a Discover handoff target. */
    internal val isBlankForHandoff: Boolean
        get() = container.stateFlow.value.let {
            it.messages.isEmpty() && it.manifestJson.isBlank() &&
                !it.running && !it.testing && !it.installing
        }

    /**
     * Discover "Chat with AI" handoff: the tab controller seeds the
     * referenced @bucket/app name so the tab title and engine calls carry it
     * before any run finishes; a later result overwrites it as usual.
     */
    internal fun seedSuggestedAppName(name: String) = intent {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) reduce { state.copy(suggestedAppName = trimmed) }
    }

    /**
     * Tab bar label candidate: the engine's suggested app name, else the
     * Discover handoff name, else the url tail; null when nothing applies.
     */
    internal fun tabTitleCandidate(handoffTitle: String?): String? =
        container.stateFlow.value.let { s ->
            s.suggestedAppName
                ?: handoffTitle
                ?: s.lastUrl.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
        }

    /**
     * Monotonic tag of the engine run the async callbacks belong to. Start
     * paths capture it before registering the callbacks; the handle* callbacks
     * drop events captured under an older epoch, and onCancel bumps it so the
     * cancelled run's queued callbacks become stale immediately.
     */
    private var generationEpoch: Long = 0L

    /** Activity pump and repair-round bookkeeping; see [ExecutionTimeline]. */
    private val executionTimeline = ExecutionTimeline(
        formatToolActivity = ToolActivityFormatter::format,
    )

    /** Local bucket index for @ references; see [ReferenceResolver]. */
    private val referenceResolver = ReferenceResolver(scoopClient)

    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    init {
        coroutineScope.launch {
            executionTimeline.collect { activity, text ->
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
        val readiness = aiGate.readiness()
        val model = aiGate.currentModel()
        reduce {
            state.copy(
                aiConfigured = readiness.configured,
                configHint = readiness.hint,
                selectedModel = if (state.selectedModel.isBlank()) model else state.selectedModel,
            )
        }
        if (readiness.configured && state.availableModels.isEmpty()) {
            fetchAvailableModels(notifyFailure = false)
        }
    }

    fun onModelChange(model: String) = intent {
        reduce { state.copy(selectedModel = model) }
        // Persist immediately so the choice survives restarts; best-effort.
        aiGate.persistModel(model)
    }

    fun onFetchModels() = intent {
        fetchAvailableModels(notifyFailure = true)
    }

    /**
     * Lazily fills the @ completion menu from the local bucket directories.
     * Best effort: any failure or empty root just leaves an empty menu, never an error.
     */
    fun ensureManifestRefSuggestions() = intent {
        if (state.manifestRefSuggestions.isNotEmpty()) return@intent
        val suggestions = referenceResolver.ensureIndex() ?: return@intent
        if (suggestions.isNotEmpty()) {
            reduce { state.copy(manifestRefSuggestions = suggestions) }
        }
    }

    /**
     * Fill the model menu through [AiGate]. A failed probe keeps the previous
     * list; only an explicit refresh ([notifyFailure]) toasts the reason, the
     * automatic first fill stays silent.
     */
    private fun fetchAvailableModels(notifyFailure: Boolean) = intent {
        reduce { state.copy(fetchingModels = true) }
        val models = try {
            aiGate.fetchModels()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reduce { state.copy(fetchingModels = false) }
            if (notifyFailure) {
                val message = e.message?.takeIf { it.isNotBlank() } ?: "Could not fetch the model list"
                postToast(AppSideEffect.Toast(AiErrorTranslator.maskSecrets(message), ToastType.ERROR))
            }
            return@intent
        }
        reduce {
            state.copy(
                fetchingModels = false,
                // A successful probe owns the list even when empty: keeping the
                // stale entries would offer models the endpoint no longer has.
                availableModels = models,
            )
        }
    }

    fun onInputChange(text: String) = intent {
        reduce { state.copy(inputUrl = text, inputNote = text, error = null) }
    }

    /**
     * Attach pending images (from clipboard paste or file dialog). Caps at [MAX_PENDING_IMAGES];
     * excess images are dropped with a toast. Duplicate ids are ignored.
     */
    fun attachImages(images: List<PendingImage>) = intent {
        if (images.isEmpty()) return@intent
        val existing = state.pendingImages
        val existingIds = existing.map { it.id }.toSet()
        val newImages = images.filter { it.id !in existingIds }
        val combined = existing + newImages
        if (combined.size > MAX_PENDING_IMAGES) {
            val capped = combined.take(MAX_PENDING_IMAGES)
            val dropped = combined.size - capped.size
            reduce { state.copy(pendingImages = capped) }
            postToast(
                AppSideEffect.Toast(
                    "At most $MAX_PENDING_IMAGES images allowed, $dropped dropped",
                    ToastType.INFO,
                ),
            )
        } else if (newImages.isNotEmpty()) {
            reduce { state.copy(pendingImages = combined) }
        }
    }

    fun removePendingImage(id: String) = intent {
        reduce { state.copy(pendingImages = state.pendingImages.filterNot { it.id == id }) }
    }

    fun onUrlChange(url: String) = intent {
        reduce { state.copy(inputUrl = url, inputNote = url, error = null) }
    }

    fun onNoteChange(note: String) = intent {
        reduce { state.copy(inputNote = note, inputUrl = note, error = null) }
    }

    fun onSend(input: String? = null, url: String? = null, note: String? = null) = intent {
        // The decision block lives in planSend: questionnaire answer, busy guard,
        // blank/no-image no-op, pure-image round, assist vs generate routing.
        val decision = planSend(
            input = input,
            note = note,
            url = url,
            composerText = state.inputUrl,
            askUserPending = state.askUserRequest != null,
            busy = state.running || state.testing || state.installing,
            images = state.pendingImages,
        )
        when (decision) {
            SendDecision.Ignored -> return@intent

            is SendDecision.AnswerAskUser -> {
                answerAskUserInternal(decision.answer)
                return@intent
            }

            SendDecision.Busy -> {
                postToast(AppSideEffect.Toast("Another operation is running", ToastType.INFO))
                return@intent
            }

            is SendDecision.Assist -> {
                reduce { state.copy(inputUrl = "", inputNote = "", error = null) }
                startAssistInternal(decision.instruction, images = decision.images)
            }

            is SendDecision.Generate -> {
                reduce { state.copy(inputUrl = "", inputNote = "", error = null) }
                startGeneration(rawUrlInput = decision.url, noteInput = decision.note, images = decision.images)
            }
        }
    }

    fun startAssist(instruction: String) = intent {
        if (state.running) return@intent
        startAssistInternal(instruction)
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
                if (state.running) {
                    postToast(AppSideEffect.Toast("Another operation is running", ToastType.INFO))
                    return@intent
                }
                val url = state.lastUrl.ifBlank { state.inputUrl }
                if (url.isBlank()) {
                    postToast(AppSideEffect.Toast("Nothing to retry", ToastType.ERROR))
                    return@intent
                }
                // Resume only when the checkpoint covers the requested stage (RETRY_FULL
                // never matches), otherwise degrade to a full retry.
                val resumeFrom = action.takeIf { it in state.resumableStages }
                startGeneration(rawUrlInput = url, noteInput = state.lastNote, resumeFrom = resumeFrom)
            }
            AiErrorAction.RETRY_ANSWER -> {
                if (state.running) {
                    postToast(AppSideEffect.Toast("Another operation is running", ToastType.INFO))
                    return@intent
                }
                // Resend the latest question (startAssistInternal no-ops on blank text).
                lastUserQuestion(state.messages)?.let { startAssistInternal(it) }
            }
        }
    }


    fun onAnswerAskUser(answer: String) = intent {
        answerAskUserInternal(answer)
    }

    /**
     * Shared answer path for option taps and composer free text: folds the
     * card, appends the user bubble and releases the waiting engine deferred.
     */
    private suspend fun Syntax<CreateState, AppSideEffect>.answerAskUserInternal(answer: String) {
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
        askUserDeferred?.complete(DEFAULT_AUTONOMOUS_PROCEED_ANSWER)
        askUserDeferred = null
        scoopAiBridge.cancel()
        // Invalidate the cancelled run's pending callbacks before the engine
        // job unwinds: its CancellationException onFinished is delivered
        // through Orbit's serial dispatch channel after this intent, so it
        // would otherwise run after a new generation has set running=true.
        reduce { state.copy(generationEpoch = state.generationEpoch + 1) }
        // The timeline gate follows the same epoch, so the cancelled run's
        // buffered activities are dropped by the collector too.
        executionTimeline.setCurrentEpoch(state.generationEpoch + 1)
        // A real install running in parallel is stopped too: the child job
        // owns its busy flag and clears it in its own finally, so the send
        // guard is never stuck.
        installingJob?.cancel()
        reduce {
            // Guard the whole UI with every busy flag false even when the engine
            // already emitted its own cancelling bubble first: a duplicate
            // bubble is harmless, a stuck busy flag is not.
            state.copy(askUserRequest = null, running = false, testing = false, installing = false)
                .finishGeneration(
                    messages = state.messages + agentMessage("Generation cancelled by user."),
                    error = null,
                )
        }
        // A cancelled round may have installed or saved before the cancel, and it
        // drops the run's plan inputs, so re-derive the deployment from disk.
        refreshDeploymentStateInIntent()
        postToast(AppSideEffect.Toast("Cancelled", ToastType.INFO))
    }

    fun onCopyDone() = intent {
        postToast(AppSideEffect.Toast("Manifest copied to clipboard", ToastType.SUCCESS))
    }

    fun onDismissSaveResult() = intent {
        reduce { state.copy(saveResult = null) }
        // The cleared save result no longer feeds the plan (bucket, name), so the
        // deployment has to be derived again instead of staying on stale inputs.
        refreshDeploymentStateInIntent()
    }

    fun onApplyActionChip(chip: ReviewActionChip) = intent {
        applyActionChipBlockToast(state.manifestJson, state.running, state.testing)?.let { (text, type) ->
            postToast(AppSideEffect.Toast(text, type))
            return@intent
        }
        startAssistInternal(
            instruction = chip.prompt,
            displayUserText = "⚡ Apply fix: ${chip.label}",
            source = RevisionSource.ASSIST,
        )
    }

    /** Install the manifest for real use (not the throwaway test install).
     *  The button action is derived from [CreateState.deployment] (see
     *  [deploymentAction]): direct install, bucket install, forced update,
     *  uninstall-then-install, or open only. */
    fun onInstallSaved() = intent {
        if (state.running || state.testing || state.installing) {
            postToast(AppSideEffect.Toast("Another operation is running", ToastType.INFO))
            return@intent
        }
        val manifestJson = state.activeManifestJson
        if (manifestJson.isBlank()) {
            postToast(AppSideEffect.Toast("No manifest to install", ToastType.INFO))
            return@intent
        }
        // The plan the user saw when clicking. A never-derived state (NoManifest)
        // means no plan was shown, so the re-derived one is trusted instead.
        val planDerived = state.deployment != DeploymentState.NoManifest
        val actionAtClick = deploymentAction(state.deployment, state.saveResult?.bucket)

        installingJob = coroutineScope.launch {
            reduce { state.copy(installing = true) }
            try {
                refreshDeploymentStateInIntent()
                val saveResult = state.saveResult
                val action = deploymentAction(state.deployment, saveResult?.bucket)
                if (planDerived && action != actionAtClick) {
                    // The installed copy moved under the button (another window
                    // installed, uninstalled or saved): never run the stale plan.
                    postToast(
                        AppSideEffect.Toast(
                            "Deployment state changed, review the button and retry",
                            ToastType.WARNING,
                        ),
                    )
                    return@launch
                }
                when (action) {
                    DeploymentAction.None ->
                        if (!planDerived) installLegacy(manifestJson)
                    DeploymentAction.OpenOnly -> openInstalledApp()
                    DeploymentAction.InstallDirect -> installFromCurrentManifest(manifestJson)
                    DeploymentAction.InstallFromBucket ->
                        if (saveResult != null) {
                            installFromBucketCore(saveResult.appName, saveResult.bucket, force = false)
                        } else {
                            // Never a silent no-op: without a bucket the plan cannot run.
                            postToast(AppSideEffect.Toast("Save the manifest to a bucket first", ToastType.ERROR))
                        }
                    DeploymentAction.UpdateForce -> {
                        // The installed copy records where it came from, so a cleared
                        // save result (assist round, dismissed result card) still
                        // updates the right bucket. Neither source naming a bucket is
                        // a refusal, never a silent no-op.
                        val bucket = deployBucket(state.deployment, saveResult?.bucket)
                        val name = deploymentAppName(state.saveResult, state.suggestedAppName, state.directInstallName)
                        if (bucket == null || name == null) {
                            postToast(
                                AppSideEffect.Toast(
                                    "Cannot update: no bucket is known for this app",
                                    ToastType.ERROR,
                                ),
                            )
                        } else {
                            installFromBucketCore(name, bucket, force = true)
                        }
                    }
                    DeploymentAction.UninstallThenInstall ->
                        if (saveResult != null) {
                            uninstallThenInstallFromBucket(saveResult.appName, saveResult.bucket)
                        } else {
                            // No bucket to install from: the local direct-install core
                            // already uninstalls the previous copy first.
                            installFromCurrentManifest(manifestJson)
                        }
                }
            } finally {
                // Always clear the flag inside the child job: success,
                // failure and a user cancel all release the send guard here.
                installingJob = null
                reduce { state.copy(installing = false) }
            }
        }
    }

    /**
     * Pre-plan routing, kept for callers whose deployment state was never derived
     * (the button is not rendered then, so only programmatic calls land here).
     */
    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.installLegacy(
        manifestJson: String,
    ) {
        val saveResult = state.saveResult
        if (state.manifestSaved && saveResult != null) {
            val snapshot = withContext(Dispatchers.IO) { scoopClient.installedSnapshot() }
            val alreadyInstalled = snapshot.containsKey(saveResult.appName.lowercase())
            installFromBucketCore(saveResult.appName, saveResult.bucket, force = alreadyInstalled)
        } else {
            installFromCurrentManifest(manifestJson)
        }
    }

    /**
     * Install or force-update a bucket manifest. Scoop exits 0 after doing nothing
     * for a held app or a still-running process, so a skipped result is reported as
     * a warning and must not claim the app is installed for use.
     */
    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.installFromBucketCore(
        name: String,
        bucket: String,
        force: Boolean,
    ) {
        val snapshot = withContext(Dispatchers.IO) { scoopClient.installedSnapshot() }
        val app = App(name = name, latestVersion = "0", bucket = Bucket(bucket))
        val result = withContext(Dispatchers.IO) {
            if (force) {
                scoopClient.update(app, global = reinstallGlobal(snapshot, name), force = true)
            } else {
                scoopClient.install(app, global = false)
            }
        }
        finishBucketInstall(name, bucket, result)
    }

    /**
     * Reinstall from the save bucket: uninstall the foreign copy first, then install
     * from the bucket. The bucket manifest is pre-checked because a missing or
     * unusable file would leave the app uninstalled with nothing to install from.
     */
    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.uninstallThenInstallFromBucket(
        name: String,
        bucket: String,
    ) {
        val issue = withContext(Dispatchers.IO) { bucketManifestIssue(deploymentMonitor.bucketManifestText(bucket, name)) }
        if (issue != null) {
            postToast(AppSideEffect.Toast("Cannot reinstall $name: $issue", ToastType.ERROR))
            return
        }
        val snapshot = withContext(Dispatchers.IO) { scoopClient.installedSnapshot() }
        val app = App(name = name, latestVersion = "0", bucket = Bucket(bucket))
        // Uninstall and install must agree on the scope, or a global app would be
        // removed from the global root and reinstalled as a local one.
        val scope = reinstallGlobal(snapshot, name)
        val result = withContext(Dispatchers.IO) {
            val uninstallResult = scoopClient.uninstall(app, global = scope, purge = false)
            if (!uninstallResult.isSuccess) uninstallResult else scoopClient.install(app, global = scope)
        }
        finishBucketInstall(name, bucket, result)
    }

    /** Record and report the outcome of a bucket install or update. */
    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.finishBucketInstall(
        name: String,
        bucket: String,
        result: CommandResult,
    ) {
        val skipped = result.skippedMessage?.takeIf { it.isNotBlank() }
        if (result.isSuccess && skipped == null) {
            reduce {
                state.copy(
                    installedForUse = true,
                    installedShortcuts = manifestHasShortcuts(state.activeManifestJson),
                )
            }
            refreshInstalledAppRecord(bucket, name)
        }
        refreshDeploymentStateInIntent()
        when {
            skipped != null -> postToast(AppSideEffect.Toast(skipped, ToastType.WARNING))
            result.isSuccess -> postToast(AppSideEffect.Toast("Installed $name", ToastType.SUCCESS))
            else -> {
                val detail = result.errorMessage?.takeIf { it.isNotBlank() }
                    ?: "exit code ${result.exitCode}"
                postToast(AppSideEffect.Toast(AiErrorTranslator.maskSecrets(detail), ToastType.ERROR))
            }
        }
    }

    /**
     * Direct-install the unsaved manifest from a local file: resolve the app
     * name, then share the install core with the AI path.
     */
    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.installFromCurrentManifest(
        manifestJson: String,
    ) {
        val name = resolveDirectInstallAppName(state.saveResult, state.directInstallName, state.suggestedAppName, state.lastUrl)
        if (name == null || !APP_NAME_REGEX.matches(name)) {
            postToast(
                AppSideEffect.Toast(
                    "Cannot determine the app name; save it to a bucket or enter an URL first",
                    ToastType.ERROR,
                ),
            )
            return
        }
        directInstallCore(manifestJson, name, successToast = "Installed $name")
    }


    /**
     * Record the direct-installed app for Discover as an orphan (no bucket).
     * Must go through the plural upsert: the singular one has no orphan
     * filter and would clear the bucketId of a same-name bucket row.
     */
    private suspend fun upsertOrphanInstalledApp(manifestJson: String, name: String) {
        // Prefer the on-disk manifest scoop just installed so the record matches
        // what a full sync would build; the shim-less fallback keeps name and
        // version only when the install layout is not present (tests, failures).
        val app = withContext(Dispatchers.IO) { scoopClient.installedAppRecord(name) }
            ?: manualOrphanRecord(manifestJson, name)
        appsRepository.upsertApps(listOf(app))
        // Publish through ScoopService so every screen reacts to the install.
        scoopService?.notifyInstalled(app)
    }

    /** Name-and-version-only orphan record from the active manifest text. */
    private fun manualOrphanRecord(manifestJson: String, name: String): App {
        val version = try {
            (Json.parseToJsonElement(manifestJson) as? JsonObject)?.get("version")
                ?.let { (it as? JsonPrimitive)?.content?.takeUnless { c -> c.isBlank() } }
        } catch (_: Exception) {
            null
        } ?: "0"
        return App(
            name = name,
            latestVersion = version,
            version = version,
            status = AppStatus.INSTALLED,
            bucket = null,
        )
    }

    // ---------------------------------------------------------------
    // deployment state
    // ---------------------------------------------------------------

    /**
     * Recompute [CreateState.deployment] from disk: the installed snapshot, the
     * app's install record and the bucket manifest Scoop would update from. Disk
     * reads stay outside the state block; the write-back is guarded by
     * [CreateState.deploymentEpoch] so a slow refresh cannot overwrite a newer one.
     *
     * Every step that changes what is installed calls this (wiring points are
     * listed in [finishWithDeploymentRefresh] and the save/install paths).
     */
    internal suspend fun refreshDeploymentState() = intent { refreshDeploymentStateInIntent() }

    /** [refreshDeploymentState] for callers that already run inside an intent. */
    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.refreshDeploymentStateInIntent() {
        // Bump before reading: a concurrent, slower refresh bumps later and its
        // write-back then finds a mismatching epoch and drops itself.
        val epoch = state.deploymentEpoch + 1
        reduce { state.copy(deploymentEpoch = epoch) }
        val derived = deploymentMonitor.derive(
            saveResult = state.saveResult,
            suggestedAppName = state.suggestedAppName,
            directInstallName = state.directInstallName,
            fallbackManifestJson = state.activeManifestJson,
            inThisSession = state.installedForUse,
        )
        if (state.deploymentEpoch != epoch) return
        // The session latch may only be dropped when the copy it claimed is gone:
        // UninstallThenInstall can uninstall and then fail to install, leaving
        // nothing on disk while the latch still disabled the deploy button.
        val vanished = derived == DeploymentState.NotInstalled &&
            state.deployment is DeploymentState.Installed
        reduce {
            state.copy(
                deployment = derived,
                installedForUse = if (vanished) false else state.installedForUse,
                installedShortcuts = if (vanished) false else state.installedShortcuts,
            )
        }
    }

    /** Launch the installed app through its Start Menu shortcut. */
    fun onOpenApp() = intent { openInstalledApp() }

    /** [onOpenApp] body, shared with the OpenOnly deploy plan. */
    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.openInstalledApp() {
        if (!state.installedForUse) return
        val name = resolveOpenAppName(state, state.lastUrl)
        if (name == null || !APP_NAME_REGEX.matches(name)) {
            postToast(
                AppSideEffect.Toast(
                    "Cannot determine the installed app name; cannot open it",
                    ToastType.ERROR,
                ),
            )
            return
        }
        val result = withContext(Dispatchers.IO) { scoopClient.openApp(name) }
        if (!result.isSuccess) {
            val detail = result.errorMessage?.takeIf { it.isNotBlank() }
                ?: "exit code ${result.exitCode}"
            postToast(AppSideEffect.Toast(AiErrorTranslator.maskSecrets(detail), ToastType.ERROR))
        }
    }

    fun onSave() = intent {
        if (state.manifestJson.isBlank()) {
            postToast(AppSideEffect.Toast("No manifest to save", ToastType.ERROR))
            return@intent
        }
        if (saveCoordinator.bucketOptions().isEmpty()) {
            postToast(AppSideEffect.Toast("No Scoop buckets found", ToastType.ERROR))
            return@intent
        }
        // Reference lock, bucket memory and the git checkbox default all live in
        // SaveCoordinator.openDialog; this intent only mirrors the defaults into state.
        val defaults = saveCoordinator.openDialog(
            SaveDialogRequest(
                suggestedAppName = state.suggestedAppName,
                refs = userManifestRefs(state.messages),
                currentBucket = state.saveBucket,
            ),
        )
        reduce {
            state.copy(
                saveDialogOpen = true,
                saveAppName = defaults.appName,
                saveBucket = defaults.bucket,
                bucketOptions = defaults.buckets,
                saveBucketIsGit = defaults.isGitBucket,
                saveRefHint = defaults.refHint,
                saveCommitToGit = defaults.commitToGit,
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
        val isGit = saveCoordinator.isGitBucket(bucket)
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
        runSave(allowOverwrite = state.overwriteConfirmOpen)
    }

    fun onConfirmOverwrite() = intent {
        runSave(allowOverwrite = true)
    }

    fun onDismissOverwrite() = intent {
        reduce { state.copy(overwriteConfirmOpen = false) }
    }

    /** Runs a confirmed save through [SaveCoordinator]; only toasts and state live here. */
    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.runSave(
        allowOverwrite: Boolean,
    ) {
        val manifestJson = state.manifestJson
        if (manifestJson.isBlank()) {
            postToast(AppSideEffect.Toast("No manifest to save", ToastType.ERROR))
            reduce {
                state.copy(
                    saveDialogOpen = false,
                    overwriteConfirmOpen = false,
                )
            }
            return
        }

        // Capture the checkbox as the user confirmed it, before any reduce.
        val commitToGit = state.saveCommitToGit
        val outcome = saveCoordinator.save(
            SaveRequest(
                manifestJson = manifestJson,
                appName = state.saveAppName,
                bucket = state.saveBucket,
                commitToGit = commitToGit,
                allowOverwrite = allowOverwrite,
            ),
        )
        // Overwrite confirmation stays a dialog flow here; the AI path instead
        // replies needsOverwrite to the model.
        when (outcome) {
            SaveOutcome.InvalidName -> postToast(
                AppSideEffect.Toast(
                    "App name may only contain letters, digits, '.', '_' and '-'",
                    ToastType.ERROR,
                ),
            )

            SaveOutcome.InvalidBucket -> postToast(AppSideEffect.Toast("Please select a valid bucket", ToastType.ERROR))

            is SaveOutcome.NeedsOverwrite -> reduce { state.copy(overwriteConfirmOpen = true) }

            is SaveOutcome.Failed -> {
                logger.error("Save to bucket failed: ${outcome.masked}")
                postToast(AppSideEffect.Toast(outcome.masked, ToastType.ERROR))
            }

            is SaveOutcome.Saved -> {
                reduce {
                    state.copy(
                        saveDialogOpen = false,
                        overwriteConfirmOpen = false,
                        suggestedAppName = outcome.appName,
                        manifestSaved = true,
                        saveResult = outcome.saveResult,
                    )
                }
                // The saved file is what the deploy button will compare against.
                refreshDeploymentStateInIntent()
                postToast(
                    AppSideEffect.Toast(
                        "Saved ${outcome.appName}.json to bucket/${outcome.bucket}",
                        ToastType.SUCCESS,
                    ),
                )
            }
        }
    }

    /** AI save_to_bucket tool backend: run the shared save and reply JSON to the model. */
    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.executeSaveToBucket(
        bucket: String?,
        name: String?,
        overwrite: Boolean,
    ): String {
        val effect = saveCoordinator.saveForTool(
            ToolSaveRequest(
                manifestJson = state.manifestJson,
                bucket = bucket,
                name = name,
                overwrite = overwrite,
                suggestedAppName = state.suggestedAppName,
                commitToGit = state.saveCommitToGit,
            ),
        )
        if (effect.manifestSaved) {
            reduce {
                state.copy(
                    suggestedAppName = effect.suggestedAppName,
                    manifestSaved = true,
                    saveResult = effect.saveResult,
                )
            }
            refreshDeploymentStateInIntent()
        }
        return effect.resultJson
    }

    /** AI install_app tool backend: run the real install and reply JSON to the model. */
    fun executeInstallApp(
        bucket: String?,
        name: String,
    ) = intent {
        // A missing or blank bucket means "direct install": the model asked to
        // install the current manifest without saving it to any bucket.
        val reply = if (bucket.isNullOrBlank()) {
            executeDirectInstallForAi(name)
        } else {
            val effect = aiInstallApp(
                bucket = bucket,
                name = name,
                alreadyInstalled = state.installedForUse,
                install = { app ->
                    withContext(Dispatchers.IO) {
                        // Reinstall via forced update when the app was installed before
                        // this session so the fixed manifest takes effect; respect how
                        // it was originally installed (global vs local).
                        val installed = scoopClient.installedSnapshot()[app.name.lowercase()]
                        if (installed != null) {
                            scoopClient.update(app, global = installed.isGlobal, force = true)
                        } else {
                            scoopClient.install(app, global = false)
                        }
                    }
                },
            )
            reduce {
                state.copy(
                    installedForUse = effect.installedForUse,
                    installedShortcuts = effect.installedForUse && manifestHasShortcuts(state.activeManifestJson),
                )
            }
            // Also after a failed attempt: it may have changed what is on disk.
            refreshDeploymentStateInIntent()
            when {
                // Scoop can exit 0 after doing nothing (held app, running process).
                effect.skippedMessage != null ->
                    postToast(AppSideEffect.Toast(effect.skippedMessage, ToastType.WARNING))
                effect.newlyInstalled -> {
                    refreshInstalledAppRecord(bucket, name)
                    postToast(AppSideEffect.Toast("Installed $name", ToastType.SUCCESS))
                }
                effect.maskedError != null -> postToast(AppSideEffect.Toast(effect.maskedError, ToastType.ERROR))
            }
            effect.resultJson
        }
        reduce { state.copy(lastInstallReply = reply) }
    }

    /**
     * Direct-install the current manifest on behalf of the AI `install_app`
     * tool. Shares the install core with the manual path but takes the name
     * from the model (validated, no resolution chain), exposes the reply JSON
     * to the model via [lastInstallReply], and tells the user in chat that the
     * app was installed without saving it to a bucket.
     */
    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.executeDirectInstallForAi(
        name: String,
    ): String {
        val manifestJson = state.activeManifestJson
        if (manifestJson.isBlank()) {
            val reply = directInstallFailureReply(name, "no manifest to install")
            postToast(AppSideEffect.Toast("No manifest to install", ToastType.ERROR))
            return reply
        }
        if (!APP_NAME_REGEX.matches(name)) {
            val reply = directInstallFailureReply(name, "invalid app name: $name")
            postToast(AppSideEffect.Toast(AiErrorTranslator.maskSecrets("Invalid app name: $name"), ToastType.ERROR))
            return reply
        }

        val outcome = directInstallCore(
            manifestJson = manifestJson,
            name = name,
            successToast = "Installed $name without saving it to a bucket",
        )
        if (outcome.isSuccess) {
            reduce {
                state.copy(messages = state.messages + agentMessage("Installed $name directly, without saving it to a bucket."))
            }
        }
        return outcome.replyJson
    }

    /**
     * JSON reply for a direct AI install. [error] should already be safe to
     * show the model (pre-masked by the caller when it may hold secrets).
     */
    private fun directInstallReply(success: Boolean, name: String, error: String? = null): String =
        if (success) directInstallSuccessReply(name) else directInstallFailureReply(name, error)

    /**
     * Shared direct-install core behind the manual and AI paths: write the
     * manifest to a stable per-app file, install from it, record the orphan
     * index entry on success, and clean the file up in any outcome. Returns
     * the reply JSON for the AI path (the manual path ignores it).
     */
    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.directInstallCore(
        manifestJson: String,
        name: String,
        successToast: String = "Installed $name",
    ): DirectInstallOutcome {
        val directDir = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "scooper/direct-install")
        val manifestFile = directDir.resolve("$name.json")
        try {
            directDir.mkdirs()
            manifestFile.writeText(manifestJson)
        } catch (e: IOException) {
            logger.warn("Direct install: failed to write temp manifest for $name: ${e.message}")
            val masked = AiErrorTranslator.maskSecrets("Failed to write manifest file: ${e.message}")
            postToast(AppSideEffect.Toast(masked, ToastType.ERROR))
            return DirectInstallOutcome(directInstallReply(success = false, name = name, error = masked))
        }

        try {
            // Scoop names the install after the manifest file, so the resolved
            // name must drive both the file name and the recorded app name.
            val snapshot = withContext(Dispatchers.IO) { scoopClient.installedSnapshot() }
            // Uninstall and install scope must agree, or a global app would be
            // uninstalled from global and reinstalled as local (scope flip).
            val scope = reinstallGlobal(snapshot, name)
            val result = withContext(Dispatchers.IO) {
                scoopClient.installFromManifest(
                    app = App(name = name, latestVersion = "0"),
                    manifestFile = manifestFile,
                    global = scope,
                    reinstallGlobal = scope,
                )
            }
            if (result.isSuccess) {
                val skipped = result.skippedMessage?.takeIf { it.isNotBlank() }
                if (skipped != null) {
                    // Exit 0 without installing anything: warn instead of claiming success.
                    refreshDeploymentStateInIntent()
                    postToast(AppSideEffect.Toast(skipped, ToastType.WARNING))
                    return DirectInstallOutcome(directInstallReply(success = true, name = name, error = null))
                }
                reduce {
                    state.copy(
                        installedForUse = true,
                        installedShortcuts = manifestHasShortcuts(state.activeManifestJson),
                        directInstallName = name,
                    )
                }
                refreshDeploymentStateInIntent()
                upsertOrphanInstalledApp(manifestJson, name)
                postToast(AppSideEffect.Toast(successToast, ToastType.SUCCESS))
                return DirectInstallOutcome(directInstallReply(success = true, name = name))
            }
            val detail = result.errorMessage?.takeIf { it.isNotBlank() }
                ?: "exit code ${result.exitCode}"
            val masked = AiErrorTranslator.maskSecrets(detail)
            postToast(AppSideEffect.Toast(masked, ToastType.ERROR))
            return DirectInstallOutcome(directInstallReply(success = false, name = name, error = masked))
        } catch (e: Exception) {
            // A crashing install (process launch failure, etc.) must become a
            // failed reply instead of escaping into the caller's coroutine;
            // cancellation is never a failure and propagates.
            if (e is CancellationException) throw e
            logger.warn("Direct install of $name crashed: ${e.message}")
            val masked = AiErrorTranslator.maskSecrets("Install failed: ${e.message}")
            postToast(AppSideEffect.Toast(masked, ToastType.ERROR))
            return DirectInstallOutcome(directInstallFailureReply(name, masked))
        } finally {
            try {
                withContext(Dispatchers.IO) {
                    manifestFile.delete()
                }
            } catch (e: Exception) {
                logger.warn("Direct install: failed to delete temp manifest for $name: ${e.message}")
            }
        }
    }

    /** Outcome of the shared direct-install core: reply JSON for the AI path. */
    private data class DirectInstallOutcome(
        val replyJson: String,
    ) {
        val isSuccess: Boolean get() = replyJson.contains("\"success\":true")
    }

    /** Index the freshly saved manifest for Discover: ensure bucket, drop the
     *  stale direct-install orphan row (the bucket row takes over), upsert. */
    private suspend fun indexSavedApp(bucket: String, app: App?): Boolean {
        appsRepository.ensureBucket(bucket)
        if (app != null) {
            appsRepository.deleteOrphanInstalled(app.name)
            appsRepository.upsertApp(app)
        }
        return app != null
    }

    /** Post-install: refresh the index record so Discover shows the app as installed. */
    private suspend fun refreshInstalledAppRecord(bucket: String, name: String) =
        refreshInstalledAppRecordCore(bucket, name, scoopClient, appsRepository::upsertApp, { logger.warn(it) }) { app ->
            // Publish through ScoopService so every screen reacts to the install.
            scoopService?.notifyInstalled(app)
        }

    companion object {
        /** Maximum number of images that can be attached to a single message. */
        const val MAX_PENDING_IMAGES = 3

        /** Fixed instruction used when sending images without any text. */
        const val PURE_IMAGE_INSTRUCTION = "Please inspect the attached screenshot(s) and update the manifest accordingly."

        /** Toast/state hint when a generate start carries no usable GitHub url. */
        internal const val INVALID_URL_HINT = "Please enter a GitHub repository URL (github.com/owner/repo)."

        /** Success reply JSON for a direct install (mode local). */
        internal fun directInstallSuccessReply(name: String): String =
            directInstallReplyJson(success = true, name = name)

        /** Failure reply JSON for a direct install; [error] must be safe to show the model. */
        internal fun directInstallFailureReply(name: String, error: String?): String =
            directInstallReplyJson(success = false, name = name, error = error)

        /**
         * Structured error reply for an install_app bridge crash. Mask the
         * result before handing it to the model.
         */
        internal fun installBridgeErrorReply(name: String, error: Throwable): String =
            directInstallReplyJson(
                success = false,
                name = name,
                error = error.message?.takeIf { it.isNotBlank() }
                    ?: error::class.simpleName
                    ?: "install failed",
            )

        private fun directInstallReplyJson(success: Boolean, name: String, error: String? = null): String = buildJsonObject {
            put("success", success)
            put("mode", "local")
            put("app", name)
            if (error != null) put("error", error)
        }.toString()
    }

    /**
     * Shared readiness gate of the generate and assist entries. False means the
     * view model already reduced the hint into state and toasted it.
     */
    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.requireAiReady(): Boolean {
        val readiness = aiGate.readiness()
        if (readiness.configured) return true
        val hint = readiness.hint.orEmpty()
        reduce {
            state.copy(
                aiConfigured = false,
                configHint = readiness.hint,
                error = readiness.hint,
            )
        }
        postToast(AppSideEffect.Toast(hint, ToastType.ERROR))
        return false
    }

    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.startGeneration(
        rawUrlInput: String,
        noteInput: String,
        resumeFrom: AiErrorAction? = null,
        images: List<PendingImage> = emptyList(),
    ) {
        if (!requireAiReady()) return

        // Read before reduce clears it: the name this session saved must be
        // excluded from the engine's conflict check during this run.
        val plan = planGenerate(
            rawUrlInput = rawUrlInput,
            noteInput = noteInput,
            savedAppName = state.saveResult?.appName,
            resumeFrom = resumeFrom,
            images = images,
        )
        if (plan == null) {
            reduce { state.copy(error = INVALID_URL_HINT) }
            postToast(AppSideEffect.Toast(INVALID_URL_HINT, ToastType.ERROR))
            return
        }

        reduce {
            state.copy(
                messages = state.messages +
                    ChatMessage(role = ChatRole.User, text = plan.url, note = plan.userPrompt, images = plan.images),
                running = true,
                currentPhase = plan.initialPhase,
                assistSession = false,
                // Transient execution card starts here; engine onPhase/onToolActivity
                // events aggregate into it until the run finishes.
                activeExecution = ActiveExecution(
                    steps = listOf(
                        ExecutionStep(phase = plan.initialPhase, title = plan.phaseTitle, isRunning = true),
                    ),
                    currentStepStartedAtMs = System.currentTimeMillis(),
                ),
                lastUrl = plan.url,
                lastNote = plan.userPrompt.orEmpty(),
                manifestJson = "",
                suggestedAppName = plan.appGuess,
                saveResult = null,
                installedForUse = false,
                installedShortcuts = false,
                directInstallName = null,
                error = null,
                manifestExhausted = false,
                manifestInvalidJson = false,
                manifestIssues = emptyList(),
                aiConfigured = true,
                configHint = null,
                inputUrl = "",
                inputNote = "",
                pendingImages = emptyList(),
            )
        }

        assistAttemptCount = 0
        executionTimeline.resetRounds()
        // Tag this run: the bridge cancels the previous job and Orbit then
        // replays the cancelled run's queued CancellationException callback
        // after the new run has already set running=true, so every async
        // callback carries the epoch it started under and drops itself when
        // the state has moved on to a newer run.
        val epoch = state.generationEpoch + 1
        reduce { state.copy(generationEpoch = epoch) }
        executionTimeline.setCurrentEpoch(epoch)
        val modelToUse = state.selectedModel
        scoopAiBridge.startGenerate(
            url = plan.url,
            userPrompt = plan.userPrompt,
            resumeFrom = resumeFrom,
            overrideModel = modelToUse,
            ignoreAppNames = plan.ignoredNames,
            onPhase = { phase -> handlePhase(epoch, phase) },
            onToolActivity = { act -> routeToolActivity(epoch, act) },
            onPhaseOutput = { phase, out -> handlePhaseOutput(epoch, phase, out) },
            onAskUser = { question -> handleAskUser(question) },
            onQuestionnaire = { questions -> handleQuestionnaire(questions) },
            onFinished = { result -> handleFinished(epoch, result) },
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
        images: List<PendingImage> = emptyList(),
    ) {
        // A blank instruction is a defensive no-op, never a toast (onAction can
        // reach this path with no user question left).
        if (instruction.isBlank()) return
        // An empty manifest is a pure conversation session: the engine answers
        // text-only and never touches state.manifestJson until a URL generates.
        val currentManifest = state.manifestJson

        if (!requireAiReady()) return

        assistAttemptCount++
        executionTimeline.resetRounds()

        // Read before reduce clears it: the name this session saved must be
        // excluded from the engine's conflict check during this run.
        val plan = planAssist(
            instruction = instruction,
            displayUserText = displayUserText,
            manifestJson = currentManifest,
            messages = state.messages,
            images = images,
            suggestedAppName = state.suggestedAppName,
            savedAppName = state.saveResult?.appName,
            attempt = assistAttemptCount,
        ) ?: return

        reduce {
            state.copy(
                // Only the freshly attached images go on the new bubble: a replayed
                // thumbnail must not render as if the user uploaded it again.
                messages = state.messages +
                    ChatMessage(role = ChatRole.User, text = plan.userPrompt, images = images),
                running = true,
                currentPhase = "assist",
                assistSession = true,
                activeExecution = ActiveExecution(
                    steps = listOf(
                        ExecutionStep(phase = "assist", title = plan.phaseTitle, isRunning = true),
                    ),
                    currentStepStartedAtMs = System.currentTimeMillis(),
                ),
                inputUrl = "",
                inputNote = "",
                saveResult = null,
                installedForUse = false,
                installedShortcuts = false,
                directInstallName = null,
                manifestIssues = emptyList(),
                manifestExhausted = false,
                manifestInvalidJson = false,
                error = null,
                pendingImages = emptyList(),
            )
        }
        // The round just cleared the save result, so the plan must come from disk
        // instead of the (now stale) pre-round inputs.
        refreshDeploymentStateInIntent()

        // Same epoch tagging as startGeneration (see the comment there).
        val epoch = state.generationEpoch + 1
        reduce { state.copy(generationEpoch = epoch) }
        executionTimeline.setCurrentEpoch(epoch)
        val modelToUse = state.selectedModel
        scoopAiBridge.startAssist(
            currentManifest = plan.currentManifest,
            instruction = plan.instruction,
            history = plan.history,
            suggestedAppName = plan.suggestedAppName,
            overrideModel = modelToUse,
            images = plan.images.map { AiImage(bytes = it.bytes, mimeType = it.mimeType) },
            ignoreAppNames = plan.ignoredNames,
            onPhase = { phase -> handlePhase(epoch, phase) },
            onToolActivity = { act -> routeToolActivity(epoch, act) },
            onPhaseOutput = { phase, out -> handlePhaseOutput(epoch, phase, out) },
            onAskUser = { question -> handleAskUser(question) },
            onQuestionnaire = { questions -> handleQuestionnaire(questions) },
            onSaveToBucket = { bucket, name, overwrite ->
                if (epochMatches(epoch)) executeSaveToBucket(bucket, name, overwrite)
                else STALE_EPOCH_SAVE_REPLY_JSON
            },
            // Session-persistent read_manifest_reference wiring: as long as any
            // user bubble in the conversation referenced @bucket/app (including
            // this round's message), every round passes the handler so the tool
            // stays registered; with no references at all it stays absent.
            onReadManifestRef = if (!plan.hasManifestRefs) {
                null
            } else {
                { bucket, name -> referenceResolver.readRef(bucket, name) }
            },
            onInstallApp = { bucket, name ->
                if (!epochMatches(epoch)) return@startAssist STALE_EPOCH_INSTALL_REPLY_JSON
                // The engine's handler signature is suspend-and-returns-JSON;
                // the intent runs on the VM scope, so bridge it and hand the
                // model the reply only after the install settled. A crash here
                // must degrade to a failure reply, never escape into the
                // engine coroutine and kill the assist session.
                runBlocking {
                    try {
                        executeInstallApp(bucket, name).join()
                        container.stateFlow.value.lastInstallReply
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        AiErrorTranslator.maskSecrets(installBridgeErrorReply(name, e))
                    }
                }
            },
            onFinished = { result -> handleFinished(epoch, result) },
        )

        plan.replaySourceId?.let { sourceId ->
            reduce {
                state.copy(
                    messages = state.messages.map { msg ->
                        if (msg.id == sourceId) msg.copy(imagesReplayed = true) else msg
                    },
                )
            }
        }
    }

    /** Epoch guard: true when [epoch] still matches the live engine run. */
    private fun epochMatches(epoch: Long): Boolean =
        container.stateFlow.value.generationEpoch == epoch

    /**
     * Route engine tool activity: step-card lines via the channel, verify flags
     * via intent. Both the channel event and the verify flag are tagged with the
     * epoch so late events of a cancelled run are dropped on both paths.
     */
    private fun routeToolActivity(epoch: Long, act: ToolActivity) {
        if (!epochMatches(epoch)) return
        if (!executionTimeline.offer(epoch, act)) return
        if (isInstallVerifyActivity(act)) handleToolActivity(act)
    }

    /**
     * Suppresses user-facing toasts for a background tab; state changes still
     * fold into the tab so switching back shows the up-to-date session.
     */
    internal fun shouldPostToast(): Boolean = !isBackgroundTab

    /** Toast gate for background tabs (see [shouldPostToast]). */
    internal suspend fun Syntax<CreateState, AppSideEffect>.postToast(sideEffect: AppSideEffect) {
        if (shouldPostToast()) postSideEffect(sideEffect)
    }

    private fun handleToolActivity(act: ToolActivity) = intent {
        reduce { applyInstallVerifyActivity(state, act) }
    }

    private fun handlePhase(epoch: Long, phase: String) = intent {
        if (!epochMatches(epoch)) return@intent
        val titleText = PhaseSummaryFormatter.formatPhaseStart(
            phase = phase,
            appName = state.suggestedAppName,
            // Only the repair phase consumes the in-session round counter.
            attempt = executionTimeline.repairAttempt(phase),
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

    private fun handlePhaseOutput(epoch: Long, phase: String, outputJson: String) = intent {
        if (!epochMatches(epoch)) return@intent
        val summary = PhaseSummaryFormatter.formatPhaseOutput(phase, outputJson) ?: return@intent
        reduce {
            state.copy(
                activeExecution = setPhaseSummary(state.activeExecution, phase, summary),
            )
        }
    }

    /**
     * Close a generation round: run the branch-specific reduces in [finish], then
     * refresh the deployment state. Every [handleFinished] branch goes through here
     * so a newly added branch cannot forget the post-run deployment refresh.
     */
    private suspend fun org.orbitmvi.orbit.syntax.Syntax<CreateState, AppSideEffect>.finishWithDeploymentRefresh(
        finish: suspend () -> Unit,
    ) {
        finish()
        refreshDeploymentStateInIntent()
    }

    private fun handleFinished(epoch: Long, result: Result<ManifestResult>) = intent {
        if (!epochMatches(epoch)) return@intent
        result.fold(
            onSuccess = { manifestResult ->
                if (manifestResult.answered) {
                    val answerText = answeredBubbleText(manifestResult.explanation)
                    // A direct answer never touches the manifest (guard-rail guaranteed),
                    // but the assist step already carries the deterministic "Updated
                    // manifest ..." line written from the engine snapshot. Rewrite it
                    // before finishGeneration folds the active execution into the
                    // finalized message.
                    finishWithDeploymentRefresh {
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
                    }
                    return@fold
                }

                if (manifestResult.noAssets) {
                    val name = manifestResult.suggestedAppName ?: state.suggestedAppName ?: "app"
                    // The engine synthesizes the noAssets explanation; fall back to the
                    // generic notice when it is missing. Abnormal ending: auto-expand.
                    val noAssetsText = manifestResult.explanation?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "No Windows assets found for this repository."
                    finishWithDeploymentRefresh {
                        reduce {
                            state.finishGeneration(
                                messages = state.messages + agentReplyMessage(noAssetsText, manifestResult.tokenUsage),
                                error = "No Windows assets found",
                                suggestedAppName = name,
                                executionExpanded = true,
                            )
                        }
                    }
                    postToast(AppSideEffect.Toast("No Windows assets found", ToastType.ERROR))
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

                finishWithDeploymentRefresh {
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
                }
                postToast(
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
                // Conservative: a failure changes nothing on disk, so the refresh
                // re-derives the same state instead of clearing it.
                finishWithDeploymentRefresh {
                    reduce {
                        state.finishGeneration(
                            messages = state.messages + agentError(presentation.copy(actions = filteredActions)),
                            error = safe,
                            resumableStages = resumableStages,
                            executionExpanded = true,
                        )
                    }
                }
                postToast(AppSideEffect.Toast(safe, ToastType.ERROR))
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

    /**
     * Select a revision for review; null goes back to the latest one. Deployment
     * policy for this round: the state is derived from [CreateState.activeManifestJson]
     * only by an explicit refresh, so a selection made here keeps the current
     * [CreateState.deployment] untouched. Browsing an older revision therefore never
     * rewrites the deploy state of the session; the deploy plan re-derives from
     * [deploymentAction] against the selected manifest when it runs.
     */
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

    /**
     * Append a revert revision and move the session back onto it. Deployment policy for
     * this round matches [onSelectRevision]: the revert clears the session flags but does
     * not refresh [CreateState.deployment], because the revert manifest was not saved to
     * a bucket yet and the bucket copy (what Scoop updates from) did not change. The
     * refresh happens once the reverted manifest is saved or installed.
     */
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
        postToast(AppSideEffect.Toast("Reverted to revision #${target.revisionNumber}", ToastType.INFO))
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
        executionTimeline.drain()
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

        val (installVerified, manifestSaved, installedForUse, installedShortcuts) = newManifestResetsVerification(
            newManifest = manifestJson,
            currentManifest = this.manifestJson,
            installVerified = this.installVerified,
            manifestSaved = this.manifestSaved,
            installedForUse = this.installedForUse,
            installedShortcuts = this.installedShortcuts,
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
            installing = false,
            installVerified = installVerified,
            manifestSaved = manifestSaved,
            installedForUse = installedForUse,
            installedShortcuts = installedShortcuts,
            pendingImages = emptyList(),
        )
    }

    override fun close() {
        // The bridge is per-tab and owned by the controller lifecycle, but
        // cancelling in-flight generation here keeps tab-less VM usages safe.
        scoopAiBridge.cancel()
        supervisorJob.cancel()
    }
}
