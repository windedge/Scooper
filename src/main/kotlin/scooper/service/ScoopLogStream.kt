package scooper.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Log stream for Scoop CLI output, shared via dependency injection.
 */
class ScoopLogStream {
    private val _logStream = MutableSharedFlow<String>()
    val logStream = _logStream.asSharedFlow()

    suspend fun emit(line: String) {
        _logStream.emit(line)
    }
}
