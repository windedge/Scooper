package scooper.viewmodels

import com.scoopai.core.ToolActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import scooper.data.App
import scooper.data.Bucket
import scooper.service.AiErrorTranslator
import scooper.service.BucketSaveEnv
import scooper.service.CommandResult
import scooper.service.GithubUrlParser
import scooper.service.InstalledAppInfo
import scooper.service.parseVerifyInstallResult

/**
 * App name for the direct-install branch: saved name, then the recorded
 * direct-install name, then the suggested one, then the URL tail.
 * Generated manifests carry no top-level name field, so there is no
 * fallback into the manifest itself.
 */
internal fun resolveDirectInstallAppName(
    saveResult: SaveResult?,
    directInstallName: String?,
    suggestedAppName: String?,
    lastUrl: String,
): String? {
    val fromSave = saveResult?.appName?.trim()?.takeUnless { it.isEmpty() }
    // The direct-install record outranks the suggestion: it is what was
    // actually installed, the suggestion may name a different revision.
    val fromDirectRecord = directInstallName?.trim()?.takeUnless { it.isEmpty() }
    val fromSuggestion = suggestedAppName?.trim()?.takeUnless { it.isEmpty() }
    // deriveAppName falls back to "app" for any blank URL, which would
    // install a garbage-named package; only trust a real URL tail.
    val fromUrl = if (lastUrl.isBlank()) null else GithubUrlParser.deriveAppName(lastUrl)
    return fromSave ?: fromDirectRecord ?: fromSuggestion ?: fromUrl?.takeUnless { it.isBlank() }
}

/**
 * Installed app name for the Open button: the save result first, then the
 * recorded direct-install name, then the suggested one, then the URL tail.
 */
internal fun resolveOpenAppName(state: CreateState, lastUrl: String): String? =
    resolveDirectInstallAppName(state.saveResult, state.directInstallName, state.suggestedAppName, lastUrl)

/** Reply handed to the engine when a tool call lands after its run was cancelled. */
internal const val STALE_EPOCH_SAVE_REPLY_JSON = "{\"success\":false,\"error\":\"cancelled\"}"

internal const val STALE_EPOCH_INSTALL_REPLY_JSON = "{\"success\":false,\"mode\":\"local\",\"error\":\"cancelled\"}"

/** True when the activity drives the verify-install busy flag (see [applyInstallVerifyActivity]). */
internal fun isInstallVerifyActivity(activity: ToolActivity): Boolean =
    activity.toolName == "verify_install"

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

/** Result of the AI install_app path: reply JSON plus the CreateState updates to apply. */
internal data class AiInstallAppResult(
    val resultJson: String,
    /** True when the app is installed for use after this call; drives installedForUse. */
    val installedForUse: Boolean,
    /** True only when this call actually ran the install and it succeeded. */
    val newlyInstalled: Boolean = false,
    /** Pre-masked error for the failure toast; null when nothing failed. */
    val maskedError: String? = null,
    /** Set when scoop exited 0 but did nothing (held app, running process). */
    val skippedMessage: String? = null,
)

/**
 * Shared post-install index refresh: re-read the installed snapshot and the
 * bucket manifest so the index shows the app as INSTALLED. Missing manifest is
 * a no-op; other failures warn without throwing (cancellation rethrown).
 */
internal suspend fun refreshInstalledAppRecordCore(
    bucket: String,
    name: String,
    scoopClient: BucketSaveEnv,
    upsertApp: (App) -> Unit,
    onWarn: (String) -> Unit = {},
    onInstalled: (suspend (App) -> Unit)? = null,
) {
    try {
        withContext(Dispatchers.IO) {
            val installed = scoopClient.installedSnapshot()
            val bucketDir = scoopClient.bucketsBaseDir.resolve(bucket)
            val app = scoopClient.buildAppsFromManifestNames(bucketDir, setOf("$name.json"), Bucket(name = bucket, url = ""), installed).firstOrNull() ?: return@withContext
            upsertApp(app)
            onInstalled?.invoke(app)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        onWarn("Post-install index refresh failed for $name: ${e.message}")
    }
}

/**
 * AI `install_app` implementation (architecture A: host owns the install):
 * run the real scoop install unless the app is already installed in this
 * session, and map the outcome to the JSON reply for the model. Errors are
 * masked so no secrets reach the model or the UI toast.
 */
internal suspend fun aiInstallApp(
    bucket: String,
    name: String,
    alreadyInstalled: Boolean,
    install: suspend (App) -> CommandResult,
): AiInstallAppResult {
    if (alreadyInstalled) {
        return AiInstallAppResult(
            resultJson = buildJsonObject {
                put("success", true)
                put("mode", "bucket")
                put("message", "already installed in this session")
            }.toString(),
            installedForUse = true,
        )
    }
    val result = install(App(name = name, latestVersion = "0", bucket = Bucket(bucket)))
    val skipped = result.skippedMessage?.takeIf { it.isNotBlank() }
    if (result.isSuccess && skipped != null) {
        // Command succeeded but scoop skipped the work: report the skip to the
        // model and do not claim the app is installed for use.
        return AiInstallAppResult(
            resultJson = buildJsonObject {
                put("success", true)
                put("mode", "bucket")
                put("app", name)
                put("bucket", bucket)
                put("skipped", skipped)
            }.toString(),
            installedForUse = false,
            skippedMessage = skipped,
        )
    }
    if (result.isSuccess) {
        return AiInstallAppResult(
            resultJson = buildJsonObject {
                put("success", true)
                put("mode", "bucket")
                put("app", name)
                put("bucket", bucket)
            }.toString(),
            installedForUse = true,
            newlyInstalled = true,
        )
    }
    val detail = result.errorMessage?.takeIf { it.isNotBlank() } ?: "exit code ${result.exitCode}"
    val masked = AiErrorTranslator.maskSecrets(detail)
    return AiInstallAppResult(
        resultJson = buildJsonObject {
            put("success", false)
            put("mode", "bucket")
            put("error", masked)
        }.toString(),
        installedForUse = false,
        maskedError = masked,
    )
}

/**
 * Why a bucket manifest cannot be installed from, or null when it is usable.
 * Uninstall-then-install pre-checks this: the uninstall would otherwise leave
 * the app gone with nothing to install from.
 */
internal fun bucketManifestIssue(manifestText: String?): String? {
    if (manifestText.isNullOrBlank()) return "the bucket has no manifest for this app"
    val obj = try {
        Json.parseToJsonElement(manifestText) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return "the bucket manifest is not valid JSON"
    if (((obj["version"] as? JsonPrimitive)?.content ?: "").isBlank()) {
        return "the bucket manifest has no version"
    }
    if (obj["url"] == null && obj["architecture"] !is JsonObject) {
        return "the bucket manifest has no url or architecture"
    }
    return null
}

/**
 * Whether reinstalling [name] must target the global scope: true only when the
 * installed snapshot reports the app as global; missing entries are local.
 */
internal fun reinstallGlobal(snapshot: Map<String, InstalledAppInfo>, name: String): Boolean =
    snapshot[name.lowercase()]?.isGlobal == true
