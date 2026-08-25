package scooper.service

import com.scoopai.core.ToolActivity

object ToolActivityFormatter {

    fun format(activity: ToolActivity): String {
        val name = activity.toolName
        return when (activity) {
            is ToolActivity.Started -> formatStarted(name, activity.toolArgs)
            is ToolActivity.Completed -> formatCompleted(name)
            is ToolActivity.Failed -> "Tool $name failed"
        }
    }

    private fun formatStarted(name: String, args: String?): String {
        return when (name) {
            "fetchRepoInfo" -> "Querying repository info..."
            "fetchLatestRelease" -> "Fetching latest release..."
            "fetchAllReleases" -> "Fetching all releases..."
            "fetchWebPage" -> "Fetching web page..."
            "computeSha256FromUrl" -> "Computing SHA256 hash..."
            "listArchiveContents" -> "Inspecting archive contents..."
            "findExecutables" -> "Scanning executables..."
            "detectInstallerType" -> "Detecting installer type..."
            "writeManifest" -> "Writing manifest..."
            "validateManifest" -> "Validating manifest..."
            "testInstall" -> "Testing installation..."
            "askUser" -> "Waiting for user input..."
            else -> "Running $name..."
        }
    }

    private fun formatCompleted(name: String): String {
        return when (name) {
            "fetchRepoInfo" -> "Repository info fetched"
            "fetchLatestRelease" -> "Latest release fetched"
            "fetchAllReleases" -> "Releases fetched"
            "fetchWebPage" -> "Web page fetched"
            "computeSha256FromUrl" -> "Hash computed"
            "listArchiveContents" -> "Archive inspected"
            "findExecutables" -> "Executables scanned"
            "detectInstallerType" -> "Installer type detected"
            "writeManifest" -> "Manifest written"
            "validateManifest" -> "Manifest validated"
            "testInstall" -> "Test install finished"
            "askUser" -> "User answered"
            else -> "Finished $name"
        }
    }
}
