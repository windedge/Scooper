package scooper.viewmodels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import scooper.data.App
import scooper.data.Bucket
import scooper.service.BucketSaveEnv
import scooper.service.GitHistoryService
import scooper.service.InstalledAppInfo
import java.io.File

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

/** Maps a written-to-disk save outcome to the SaveResult card; write failures never reach here. */
internal fun saveResultOf(outcome: BucketSaveOutcome.Saved): SaveResult = classifySaveOutcome(
    indexOk = outcome.indexedOk,
    gitCommitted = outcome.gitCommitted,
    gitHint = outcome.gitHint,
    appName = outcome.appName,
    bucket = outcome.bucket,
    targetPath = outcome.targetPath,
    overwritten = outcome.overwritten,
)

/**
 * Classify a written save into the SaveResult card. Best-effort indexing / git-hint
 * failures only degrade the card (`indexOk=false`, `gitHint=null`); write failures
 * are rejected earlier as [BucketSaveOutcome.Failed].
 */
internal fun classifySaveOutcome(
    indexOk: Boolean,
    gitCommitted: Boolean,
    gitHint: SaveGitHint?,
    appName: String,
    bucket: String,
    targetPath: String,
    overwritten: Boolean,
): SaveResult = SaveResult(
    appName = appName,
    bucket = bucket,
    targetPath = targetPath,
    overwritten = overwritten,
    indexedOk = indexOk,
    gitCommitted = gitCommitted,
    gitHint = if (!gitCommitted) gitHint else null,
)

/** App name rule shared by the save dialog and the AI save_to_bucket path. */
internal val APP_NAME_REGEX = Regex("^[a-zA-Z0-9._-]+$")

/** Outcome of the shared manifest-save core, mapped to UI state / tool JSON by the callers. */
internal sealed interface BucketSaveOutcome {
    /** Target file exists and overwrite was not requested. */
    data class NeedsOverwrite(val targetPath: String) : BucketSaveOutcome

    /** Invalid save path or failed file write; [message] is the raw failure reason. */
    data class Failed(val message: String) : BucketSaveOutcome

    /** Manifest written; indexing and git commit are best-effort outcome flags. */
    data class Saved(
        val bucket: String,
        val appName: String,
        val targetPath: String,
        val overwritten: Boolean,
        val indexedOk: Boolean,
        val gitCommitted: Boolean,
        val gitHint: SaveGitHint?,
    ) : BucketSaveOutcome
}

/**
 * Shared core of Save to Bucket, used by both the manual save dialog
 * ([CreateViewModel.performSave]) and the AI `save_to_bucket` tool
 * ([aiSaveToBucket]): resolve the target file, write the manifest, then
 * best-effort index the app and git-commit. UI concerns (overwrite confirm
 * dialog, toasts, CreateState updates) stay with the callers.
 *
 * Collaborators are narrowed to functions so tests can fake them.
 */
internal suspend fun saveManifestCore(
    manifestJson: String,
    bucket: String,
    appName: String,
    commitToGit: Boolean,
    overwrite: Boolean,
    scoopClient: BucketSaveEnv,
    indexSaved: suspend (bucket: String, app: App?) -> Boolean,
    isGitBucket: (File) -> Boolean,
    commitManifest: (bucketDir: File, appName: String, isUpdate: Boolean, version: String?) -> Result<String>,
    onWarn: (String) -> Unit = {},
): BucketSaveOutcome {
    val baseDir = scoopClient.bucketsBaseDir
    val target = baseDir.resolve(bucket).resolve("bucket").resolve("$appName.json")
    val baseCanonical = baseDir.canonicalFile
    val targetCanonical = target.canonicalFile
    val basePath = baseCanonical.path
    val targetPath = targetCanonical.path
    val underBase = targetPath == basePath ||
        targetPath.startsWith(basePath + File.separator)
    if (!underBase) {
        return BucketSaveOutcome.Failed("Invalid save path")
    }

    val overwritten = targetCanonical.exists()
    if (overwritten && !overwrite) {
        return BucketSaveOutcome.NeedsOverwrite(targetPath)
    }

    // Writing the manifest file is the ONLY fatal step; the callers surface the
    // failure (error toast / JSON error) and nothing else runs.
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
        return BucketSaveOutcome.Failed(message)
    }

    // Best-effort: the file is on disk, so indexing / git problems degrade to
    // outcome flags instead of failing the whole save.
    var indexedOk = false
    var gitCommitted = false
    var gitHint: SaveGitHint? = null
    try {
        withContext(Dispatchers.IO) {
            val bucketDir = scoopClient.bucketsBaseDir.resolve(bucket)
            val installed = scoopClient.installedSnapshot()
            val app = scoopClient.buildAppsFromManifestNames(
                bucketDir,
                setOf("$appName.json"),
                Bucket(name = bucket, url = ""),
                installed,
                assumeOrigin = true,
            ).firstOrNull()
            indexedOk = indexSaved(bucket, app)
            if (!indexedOk) onWarn("Saved manifest could not be indexed: $appName.json")

            // A prior direct install recorded the temp manifest path as install source, which
            // scoop update cannot resolve once the file is gone; re-point it at the bucket.
            convergeDirectInstallRecords(bucket, appName, installed, onWarn)

            val isGit = isGitBucket(bucketDir)
            val manifestVersion = try {
                (Json.parseToJsonElement(manifestJson) as? JsonObject)?.get("version")?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }

            if (commitToGit && isGit) {
                val commitRes = commitManifest(bucketDir, appName, overwritten, manifestVersion)
                if (commitRes.isSuccess) {
                    gitCommitted = true
                } else {
                    onWarn("Auto git commit failed for $appName.json: ${commitRes.exceptionOrNull()?.message}")
                }
            }

            if (!gitCommitted) {
                gitHint = buildSaveGitHint(
                    isGitBucket = isGit,
                    bucketDirPath = bucketDir.path,
                    appName = appName,
                    version = manifestVersion,
                    isUpdate = overwritten,
                )
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        onWarn("Post-save indexing / git-hint failed; file still saved: ${e.message}")
    }

    return BucketSaveOutcome.Saved(
        bucket = bucket,
        appName = appName,
        targetPath = targetPath,
        overwritten = overwritten,
        indexedOk = indexedOk,
        gitCommitted = gitCommitted,
        gitHint = gitHint,
    )
}

/**
 * Re-point url-pinned direct-install records for [appName] at [bucket]: scoop re-resolves
 * apps/<name>/current/scoop-install.json on update, so a stale temp-file url breaks
 * `scoop update`. Best-effort: failures only warn.
 */
internal fun convergeDirectInstallRecords(
    bucket: String,
    appName: String,
    installed: Map<String, InstalledAppInfo>,
    onWarn: (String) -> Unit,
) {
    val recordFile = installed[appName.lowercase()]?.appDir?.resolve("current/scoop-install.json") ?: return
    if (!recordFile.isFile) return
    try {
        val record = Json.parseToJsonElement(recordFile.readText()) as? JsonObject ?: return
        val url = (record["url"] as? JsonPrimitive)?.contentOrNull ?: return
        if (!url.replace('\\', '/').lowercase().contains("/scooper/direct-install/")) return
        val converged = JsonObject(record.filterKeys { it != "url" } + ("bucket" to JsonPrimitive(bucket)))
        recordFile.writeText(converged.toString())
    } catch (e: Exception) {
        onWarn("Post-save install-record convergence failed for $appName: ${e.message}")
    }
}

private val trailingWsRegex = Regex("[ \t]+$")

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
