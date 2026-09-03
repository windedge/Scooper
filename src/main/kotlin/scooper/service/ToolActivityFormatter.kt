package scooper.service

import com.scoopai.core.ToolActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

object ToolActivityFormatter {

    private val json = Json { ignoreUnknownKeys = true }

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
            else -> "Finished $name"
        }
    }

    /**
     * Outcome line for `verify_install`: the verifier returns JSON with a `success`
     * boolean. On failure the recorded `error` is surfaced so the user sees why the
     * real install test did not pass.
     */
    private fun formatVerifyInstallCompleted(toolResult: String): String {
        val obj = json.parseToJsonElement(toolResult) as? JsonObject
        val success = obj?.get("success")?.let { (it as? JsonPrimitive)?.booleanOrNull }
        if (success == true) return "Install test passed"
        val error = obj?.get("error")?.let { (it as? JsonPrimitive)?.contentOrNull }
        return if (error.isNullOrBlank()) "Install test failed" else "Install test failed: $error"
    }
}
