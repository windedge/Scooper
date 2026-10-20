package scooper.util

/**
 * Raised when the user stops a running task: blocking loops cannot be interrupted by
 * coroutine cancellation, so they poll the TaskQueue stop flag and throw this to abort.
 */
class OperationCancelledException(message: String) : RuntimeException(message)
