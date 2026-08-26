package scooper.service

import com.scoopai.core.ToolActivity

object ToolActivityFormatter {

    fun format(activity: ToolActivity): String {
        val name = activity.toolName
        return when (activity) {
            is ToolActivity.Started -> formatStarted(name, activity.toolArgs)
            is ToolActivity.Completed -> formatCompleted(name)
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
            "__ask_user__", "askUser" -> "Waiting for user input..."
            else -> "Running $name..."
        }
    }

    private fun formatCompleted(name: String): String {
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
            "__ask_user__", "askUser" -> "User answered"
            else -> "Finished $name"
        }
    }
}
