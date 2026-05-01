package scooper.service

data class CommandResult(
    val exitCode: Int,
    val errorMessage: String? = null,
) {
    val isSuccess: Boolean get() = exitCode == 0
}
