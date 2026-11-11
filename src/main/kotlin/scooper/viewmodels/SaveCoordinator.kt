package scooper.viewmodels

import scooper.data.App
import scooper.service.AiErrorTranslator
import scooper.service.AiSettingsStore
import scooper.service.BucketSaveEnv
import scooper.service.GitHistoryService
import java.io.File

/**
 * Git side of a bucket save: repository detection plus the commit itself.
 * Narrow so tests fake it; whether a commit happens at all is decided by the
 * save core from the dialog checkbox, not by the port.
 */
internal interface GitBucketPort {
    fun isGitBucket(dir: File): Boolean

    fun commitManifest(dir: File, name: String, isUpdate: Boolean, version: String?): Result<String>
}

/** Persistent save-dialog memory: last used bucket and the commit checkbox. */
internal interface SaveMemory {
    suspend fun readLastBucket(): String?
    suspend fun persistLastBucket(bucket: String)
    suspend fun readCommitPreference(): Boolean?
    suspend fun persistCommitPreference(commit: Boolean)
}

/** What the view model feeds the save dialog: name guess, refs, current bucket. */
internal data class SaveDialogRequest(
    val suggestedAppName: String?,
    val refs: List<Pair<String, String>>,
    val currentBucket: String,
)

/** Pre-filled save dialog state; [buckets] is the sorted option list, [refLock] the raw
 *  @bucket/app default source whose hint line translates at render time (Settings.kt rule). */
internal data class SaveDialogDefaults(
    val appName: String,
    val bucket: String,
    val buckets: List<String>,
    val isGitBucket: Boolean,
    val refLock: Pair<String, String>?,
    val commitToGit: Boolean,
)

/** A save confirmed from the dialog. */
internal data class SaveRequest(
    val manifestJson: String,
    val appName: String,
    val bucket: String,
    val commitToGit: Boolean,
    val allowOverwrite: Boolean,
)

/**
 * Result of [SaveCoordinator.save]. Toasts and CreateState updates stay with the
 * view model: the coordinator only classifies what happened.
 */
internal sealed interface SaveOutcome {
    data object InvalidName : SaveOutcome
    data object InvalidBucket : SaveOutcome
    data class NeedsOverwrite(val path: String) : SaveOutcome
    data class Failed(val masked: String) : SaveOutcome
    data class Saved(val saveResult: SaveResult, val appName: String, val bucket: String) : SaveOutcome
}

/** Inputs of the AI `save_to_bucket` tool path. */
internal data class ToolSaveRequest(
    val manifestJson: String,
    val bucket: String?,
    val name: String?,
    val overwrite: Boolean,
    val suggestedAppName: String?,
    val commitToGit: Boolean,
)

/**
 * Owns the Save-to-Bucket domain: dialog defaults, the manual save outcome
 * classification, the AI tool path and the save memory. Everything it needs
 * arrives as a port, so the view model only maps outcomes to toasts/state and
 * tests never touch a real Scoop install, git repo or settings DB.
 */
internal class SaveCoordinator(
    private val env: BucketSaveEnv,
    private val git: GitBucketPort,
    private val index: suspend (bucket: String, app: App?) -> Boolean,
    private val memory: SaveMemory,
    private val warn: (String) -> Unit,
) {
    /** Bucket options offered by the save dialog: blank entries dropped, sorted. */
    fun bucketOptions(): List<String> = env.bucketNames.filter { it.isNotBlank() }.sorted()

    /** Git repository check for one bucket, used when the user switches buckets. */
    fun isGitBucket(bucket: String): Boolean = git.isGitBucket(env.bucketsBaseDir.resolve(bucket))

    /**
     * Resolve the pre-filled dialog: a matching @bucket/app reference locks the
     * bucket and name (still editable), otherwise the bucket memory decides,
     * then "main". The git checkbox follows the bucket type plus the remembered
     * preference, so a non-git bucket never pre-checks it.
     */
    suspend fun openDialog(request: SaveDialogRequest): SaveDialogDefaults {
        val buckets = bucketOptions()
        val suggested = request.suggestedAppName.orEmpty()
        if (buckets.isEmpty()) {
            // The caller guards this case; failing soft keeps an empty installation harmless.
            return SaveDialogDefaults(
                appName = suggested,
                bucket = "",
                buckets = emptyList(),
                isGitBucket = false,
                refLock = null,
                commitToGit = false,
            )
        }
        val refLock = referenceSaveDefault(
            appName = suggested,
            refs = request.refs,
            availableBuckets = buckets,
        )
        val bucket = refLock?.first ?: resolveDefaultSaveBucket(
            current = request.currentBucket,
            lastUsed = memory.readLastBucket().orEmpty(),
            buckets = buckets,
        )
        val isGit = isGitBucket(bucket)
        return SaveDialogDefaults(
            appName = refLock?.second ?: suggested,
            bucket = bucket,
            buckets = buckets,
            isGitBucket = isGit,
            refLock = refLock,
            commitToGit = resolveDefaultCommitToGit(
                isGitBucket = isGit,
                lastPreference = memory.readCommitPreference() ?: true,
            ),
        )
    }

    /**
     * Run a confirmed save: validate the name and bucket, then delegate to the
     * shared save core. Memory is written only once the manifest is on disk.
     */
    suspend fun save(request: SaveRequest): SaveOutcome {
        val name = validSaveName(request.appName) ?: return SaveOutcome.InvalidName

        val bucket = validSaveBucket(request.bucket) ?: return SaveOutcome.InvalidBucket

        val outcome = runSaveCore(
            manifestJson = request.manifestJson,
            bucket = bucket,
            name = name,
            commitToGit = request.commitToGit,
            overwrite = request.allowOverwrite,
        )
        return when (outcome) {
            is BucketSaveOutcome.NeedsOverwrite -> SaveOutcome.NeedsOverwrite(outcome.targetPath)
            is BucketSaveOutcome.Failed -> SaveOutcome.Failed(AiErrorTranslator.maskSecrets(outcome.message))
            is BucketSaveOutcome.Saved -> {
                memory.persistLastBucket(bucket)
                memory.persistCommitPreference(request.commitToGit)
                SaveOutcome.Saved(
                    saveResult = saveResultOf(outcome),
                    appName = outcome.appName,
                    bucket = outcome.bucket,
                )
            }
        }
    }

    /**
     * AI `save_to_bucket` backend. One validation pass shared with [save]: no
     * silent default bucket, the model must ask; failures become the JSON reply
     * assembled in [aiSaveToBucketResult].
     */
    suspend fun saveForTool(request: ToolSaveRequest): AiSaveToBucketResult {
        if (request.manifestJson.isBlank()) return aiSaveFailure("No manifest to save")

        val buckets = bucketOptions()
        val bucketArg = request.bucket?.trim()?.takeUnless { it.isEmpty() }
        if (bucketArg == null) {
            if (buckets.isEmpty()) return aiSaveFailure("No Scoop buckets found")
            // No silent default: hand the bucket list to the model so it asks
            // the user to pick one. Nothing is written and no state changes.
            return aiSaveNeedsBucketResult(buckets, request.suggestedAppName)
        }
        val bucket = validSaveBucket(bucketArg)
            ?: return aiSaveFailure("Bucket '$bucketArg' does not exist in the local Scoop installation")

        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() }
            ?: request.suggestedAppName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: return aiSaveFailure("No app name provided and no suggested app name is available")
        if (!APP_NAME_REGEX.matches(name)) {
            return aiSaveFailure("App name may only contain letters, digits, '.', '_' and '-'")
        }

        val outcome = runSaveCore(
            manifestJson = request.manifestJson,
            bucket = bucket,
            name = name,
            commitToGit = request.commitToGit,
            overwrite = request.overwrite,
        )
        return aiSaveToBucketResult(outcome)
    }

    /** Write the manifest with the ports bound here; the shared call of both save paths. */
    private suspend fun runSaveCore(
        manifestJson: String,
        bucket: String,
        name: String,
        commitToGit: Boolean,
        overwrite: Boolean,
    ): BucketSaveOutcome = saveManifestCore(
        manifestJson = manifestJson,
        bucket = bucket,
        appName = name,
        commitToGit = commitToGit,
        overwrite = overwrite,
        scoopClient = env,
        indexSaved = index,
        isGitBucket = git::isGitBucket,
        commitManifest = git::commitManifest,
        onWarn = warn,
    )

    /** Trimmed non-blank name passing [APP_NAME_REGEX]; the shared name rule of both save paths. */
    private fun validSaveName(raw: String?): String? =
        raw?.trim()?.takeUnless { it.isEmpty() }?.takeIf { APP_NAME_REGEX.matches(it) }

    /** Trimmed non-blank bucket present in the local installation; the shared bucket rule. */
    private fun validSaveBucket(raw: String?): String? =
        raw?.trim()?.takeUnless { it.isEmpty() }?.takeIf { it in env.bucketNames }
}

/** Adapts [GitHistoryService] to [GitBucketPort]. */
internal fun gitBucketPort(service: GitHistoryService): GitBucketPort = object : GitBucketPort {
    override fun isGitBucket(dir: File): Boolean = service.isGitBucket(dir)

    override fun commitManifest(dir: File, name: String, isUpdate: Boolean, version: String?): Result<String> =
        service.commitManifest(dir, name, isUpdate, version)
}

/** Adapts [AiSettingsStore] to [SaveMemory]; a blank bucket reads as "never saved". */
internal fun aiSettingsSaveMemory(store: AiSettingsStore): SaveMemory = object : SaveMemory {
    override suspend fun readLastBucket(): String? = store.readLastBucket().takeIf { it.isNotBlank() }

    override suspend fun persistLastBucket(bucket: String) = store.persistLastBucket(bucket)

    override suspend fun readCommitPreference(): Boolean? = store.readCommitPreference()

    override suspend fun persistCommitPreference(commit: Boolean) = store.persistCommitPreference(commit)
}
