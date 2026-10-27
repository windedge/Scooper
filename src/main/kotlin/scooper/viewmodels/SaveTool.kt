package scooper.viewmodels

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import scooper.data.App
import scooper.service.AiErrorTranslator
import scooper.service.BucketSaveEnv
import java.io.File

/**
 * Builds the needsBucket reply for a save without an explicit bucket: the model
 * must ask the user to pick one (via ask_user) instead of the host silently
 * applying default bucket rules.
 */
internal fun needsBucketResultJson(buckets: List<String>): String = buildJsonObject {
    put("success", false)
    put("needsBucket", true)
    put("buckets", JsonArray(buckets.map { JsonPrimitive(it) }))
}.toString()

/** Maps a save outcome to the JSON reply the save_to_bucket tool returns to the model. */
internal fun saveToBucketResultJson(outcome: BucketSaveOutcome): String = when (outcome) {
    is BucketSaveOutcome.NeedsOverwrite -> buildJsonObject {
        put("success", false)
        put("error", "already exists")
        put("needsOverwrite", true)
        put("path", outcome.targetPath)
    }.toString()

    is BucketSaveOutcome.Failed -> buildJsonObject {
        put("success", false)
        put("error", AiErrorTranslator.maskSecrets(outcome.message))
    }.toString()

    is BucketSaveOutcome.Saved -> buildJsonObject {
        put("success", true)
        put("path", outcome.targetPath)
        put("bucket", outcome.bucket)
        put("name", outcome.appName)
    }.toString()
}

/** Result of the AI save_to_bucket path: reply JSON plus the CreateState updates to apply. */
internal data class AiSaveToBucketResult(
    val resultJson: String,
    /** True only when the manifest file was written; drives manifestSaved in CreateState. */
    val manifestSaved: Boolean,
    val saveResult: SaveResult?,
    val suggestedAppName: String?,
)

private fun aiSaveFailure(message: String) = AiSaveToBucketResult(
    resultJson = saveToBucketResultJson(BucketSaveOutcome.Failed(message)),
    manifestSaved = false,
    saveResult = null,
    suggestedAppName = null,
)

/**
 * AI `save_to_bucket` implementation (architecture A: host owns the save):
 * run the shared save core and map the outcome to the JSON reply for the
 * model. A save without an explicit bucket never falls back to default bucket
 * rules; it returns needsBucket so the model asks the user to choose.
 */
internal suspend fun aiSaveToBucket(
    manifestJson: String,
    bucketArg: String?,
    nameArg: String?,
    overwrite: Boolean,
    suggestedAppName: String?,
    lastBucket: String,
    commitToGit: Boolean,
    scoopClient: BucketSaveEnv,
    indexSaved: suspend (bucket: String, app: App?) -> Boolean,
    isGitBucket: (File) -> Boolean,
    commitManifest: (bucketDir: File, appName: String, isUpdate: Boolean, version: String?) -> Result<String>,
    onWarn: (String) -> Unit = {},
): AiSaveToBucketResult {
    if (manifestJson.isBlank()) {
        return aiSaveFailure("No manifest to save")
    }

    val buckets = scoopClient.bucketNames.filter { it.isNotBlank() }.sorted()

    val bucket = bucketArg?.trim()?.takeUnless { it.isEmpty() }
    if (bucket == null) {
        if (buckets.isEmpty()) {
            return aiSaveFailure("No Scoop buckets found")
        }
        // No silent default: hand the bucket list to the model so it asks the
        // user to pick one. Nothing is written and no state changes.
        return AiSaveToBucketResult(
            resultJson = needsBucketResultJson(buckets),
            manifestSaved = false,
            saveResult = null,
            suggestedAppName = suggestedAppName?.trim()?.takeUnless { it.isEmpty() },
        )
    }
    if (bucket !in buckets) {
        return aiSaveFailure("Bucket '$bucket' does not exist in the local Scoop installation")
    }

    val name = nameArg?.trim().takeUnless { it.isNullOrEmpty() }
        ?: suggestedAppName?.trim().takeUnless { it.isNullOrEmpty() }
    if (name == null) {
        return aiSaveFailure("No app name provided and no suggested app name is available")
    }
    if (!APP_NAME_REGEX.matches(name)) {
        return aiSaveFailure("App name may only contain letters, digits, '.', '_' and '-'")
    }

    val outcome = saveManifestCore(
        manifestJson = manifestJson,
        bucket = bucket,
        appName = name,
        commitToGit = commitToGit,
        overwrite = overwrite,
        scoopClient = scoopClient,
        indexSaved = indexSaved,
        isGitBucket = isGitBucket,
        commitManifest = commitManifest,
        onWarn = onWarn,
    )

    return when (outcome) {
        is BucketSaveOutcome.Saved -> AiSaveToBucketResult(
            resultJson = saveToBucketResultJson(outcome),
            manifestSaved = true,
            saveResult = saveResultOf(outcome),
            suggestedAppName = outcome.appName,
        )

        else -> AiSaveToBucketResult(
            resultJson = saveToBucketResultJson(outcome),
            manifestSaved = false,
            saveResult = null,
            suggestedAppName = null,
        )
    }
}
