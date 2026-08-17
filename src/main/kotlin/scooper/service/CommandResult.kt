package scooper.service

data class CommandResult(
    val exitCode: Int,
    val errorMessage: String? = null,
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
) {
    val isSuccess: Boolean get() = exitCode == 0
}
