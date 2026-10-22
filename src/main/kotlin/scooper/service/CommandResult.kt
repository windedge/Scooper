package scooper.service

data class CommandResult(
    val exitCode: Int,
    val errorMessage: String? = null,
    /** Set when scoop exited 0 but did nothing (held app, running process). */
    val skippedMessage: String? = null,
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * Result of a test install, carrying the full install log and cache prewarm count.
 */
data class TestInstallResult(
    val exitCode: Int,
    val errorMessage: String? = null,
    val output: String = "",
    val prewarmedCount: Int = 0,
    /** Set when scoop exited 0 but skipped the work (held app, running process). */
    val skippedMessage: String? = null,
) {
    val isSuccess: Boolean get() = exitCode == 0
}
