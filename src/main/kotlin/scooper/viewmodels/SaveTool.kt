package scooper.viewmodels

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import scooper.service.AiErrorTranslator

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

internal fun aiSaveFailure(message: String) = AiSaveToBucketResult(
    resultJson = saveToBucketResultJson(BucketSaveOutcome.Failed(message)),
    manifestSaved = false,
    saveResult = null,
    suggestedAppName = null,
)

/** needsBucket reply keeping the suggested name alive for the follow-up save. */
internal fun aiSaveNeedsBucketResult(buckets: List<String>, suggestedAppName: String?) =
    AiSaveToBucketResult(
        resultJson = needsBucketResultJson(buckets),
        manifestSaved = false,
        saveResult = null,
        suggestedAppName = suggestedAppName?.trim()?.takeUnless { it.isEmpty() },
    )

/**
 * Pure result assembly of the AI `save_to_bucket` path: map a [BucketSaveOutcome]
 * to the reply JSON for the model plus the CreateState updates. Validation and the
 * save core live in [SaveCoordinator.saveForTool].
 */
internal fun aiSaveToBucketResult(outcome: BucketSaveOutcome): AiSaveToBucketResult =
    when (outcome) {
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
