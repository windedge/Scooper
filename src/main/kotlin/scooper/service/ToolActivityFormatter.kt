package scooper.service

import com.scoopai.core.ToolActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private val verifyJson = Json { ignoreUnknownKeys = true }

/**
 * Parsed `verify_install` tool result: the `success` boolean plus the recorded
 * `error` text. Fields are null when the result is absent, not valid JSON, or
 * lacks the respective key. The JSON is parsed exactly once.
 */
internal data class VerifyInstallResult(val success: Boolean?, val error: String?)

internal fun parseVerifyInstallResult(toolResult: String?): VerifyInstallResult {
    val obj = toolResult?.let {
        try {
            verifyJson.parseToJsonElement(it) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }
    return VerifyInstallResult(
        success = obj?.get("success")?.let { (it as? JsonPrimitive)?.booleanOrNull },
        error = obj?.get("error")?.let { (it as? JsonPrimitive)?.contentOrNull },
    )
}

object ToolActivityFormatter {

    fun format(activity: ToolActivity): String {
        val name = activity.toolName
        return when (activity) {
            is ToolActivity.Started -> formatStarted(name, activity.toolArgs)
            is ToolActivity.Completed -> formatCompleted(name, activity.toolResult)
            is ToolActivity.Failed -> "Tool $name failed: ${activity.error}"
        }
    }

    private fun formatStarted(name: String, args: String?): String {
        return when (name) {
            "fetchRepoInfo" -> "Querying repository info..."
            "fetchLatestRelease" -> "Fetching latest release..."
            "fetchAllReleases" -> "Fetching all releases..."
            "listReleaseAssets" -> "Listing release assets..."
            "fetchFileContent" -> "Fetching file content..."
            "fetchWebPage" -> "Fetching web page..."
            "resolveRedirect" -> "Resolving redirect URL..."
            "fetchJson" -> "Fetching JSON metadata..."
            "searchTextInArchive" -> "Searching archive contents..."
            "computeSha256FromUrl" -> "Computing SHA256 hash..."
            "listArchiveContents" -> "Inspecting archive contents..."
            "findExecutables" -> "Scanning executables..."
            "detectInstallerType" -> "Detecting installer type..."
            "validate", "validateManifest" -> "Validating manifest..."
            "__review_manifest__", "reviewManifest" -> "Reviewing manifest..."
            "writeManifest" -> "Writing manifest..."
            "testInstall" -> "Testing installation..."
            "verify_install" -> "Running real install test (scoop install/uninstall)"
            "uninstall" -> "Cleaning up test install"
            "__ask_user__", "askUser" -> "Waiting for user input..."
            "save_to_bucket" -> "Saving to bucket..."
            else -> "Running $name..."
        }
    }

    private fun formatCompleted(name: String, toolResult: String): String {
        return when (name) {
            "fetchRepoInfo" -> "Repository info fetched"
            "fetchLatestRelease" -> "Latest release fetched"
            "fetchAllReleases" -> "Releases fetched"
            "listReleaseAssets" -> "Release assets listed"
            "fetchFileContent" -> "File content fetched"
            "fetchWebPage" -> "Web page fetched"
            "resolveRedirect" -> "Redirect resolved"
            "fetchJson" -> "JSON metadata fetched"
            "searchTextInArchive" -> "Archive searched"
            "computeSha256FromUrl" -> "SHA256 hash computed"
            "listArchiveContents" -> "Archive inspected"
            "findExecutables" -> "Executables scanned"
            "detectInstallerType" -> "Installer type detected"
            "validate", "validateManifest" -> "Manifest validated"
            "__review_manifest__", "reviewManifest" -> "Manifest reviewed"
            "writeManifest" -> "Manifest written"
            "testInstall" -> "Test install finished"
            "verify_install" -> formatVerifyInstallCompleted(toolResult)
            "__ask_user__", "askUser" -> "User answered"
            "save_to_bucket" -> formatSaveToBucketCompleted(toolResult)
            else -> "Finished $name"
        }
    }

    /**
     * Outcome line for `verify_install`: the verifier returns JSON with a `success`
     * boolean. On failure the recorded `error` is surfaced so the user sees why the
     * real install test did not pass.
     */
    private fun formatVerifyInstallCompleted(toolResult: String): String {
        val result = parseVerifyInstallResult(toolResult)
        if (result.success == true) return "Install test passed"
        val error = result.error
        return if (error.isNullOrBlank()) "Install test failed" else "Install test failed: $error"
    }

    /**
     * Outcome line for `save_to_bucket`: the host save returns JSON with a `success`
     * boolean. On failure the recorded `error` is surfaced so the user sees why the
     * save did not go through.
     */
    private fun formatSaveToBucketCompleted(toolResult: String): String {
        val result = parseVerifyInstallResult(toolResult)
        if (result.success == true) return "Saved to bucket"
        val error = result.error
        return if (error.isNullOrBlank()) "Save to bucket failed" else "Save to bucket failed: $error"
    }
}
