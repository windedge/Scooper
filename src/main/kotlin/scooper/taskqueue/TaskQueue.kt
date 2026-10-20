package scooper.taskqueue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import scooper.util.OperationCancelledException
import scooper.util.logger

@Suppress("unused")
class TaskQueue {
    private val logger by logger()

    private val _resultFlow = MutableSharedFlow<Result<Task>>()
    val resultFlow = _resultFlow.asSharedFlow()

    private val pendingTasks = LinkedHashMap<String, Task>()
    private val _pendingTasksFlow = MutableSharedFlow<List<Task>>(replay = 1)
    val pendingTasksFlow = _pendingTasksFlow.asSharedFlow()

    private val _runningTaskFlow = MutableSharedFlow<Task?>(replay = 1)
    val runningTaskFlow = _runningTaskFlow.asSharedFlow()

    private val _progressFlow = MutableStateFlow<Int?>(null)
    val progressFlow: StateFlow<Int?> = _progressFlow.asStateFlow()

    @Volatile
    private var runningTaskName: String? = null

    /**
     * Set by [cancelRunningTask]. Task bodies do blocking work coroutine cancellation cannot
     * interrupt, so they poll [isRunningTaskCancelled] to abort cooperatively.
     */
    @Volatile
    private var runningTaskCancelled = false

    private val mutex = Mutex()
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    /**
     * Signal channel to notify when a new task is added. Conflated so addTask never
     * suspends: a rendezvous send would block the caller while another task runs.
     */
    private val _taskSignal = kotlinx.coroutines.channels.Channel<Unit>(Channel.CONFLATED)

    init {
        coroutineScope.launch {
            logger.info("Starting task queue ...")
            while (true) {
                // Wait for signal
                _taskSignal.receive()
                // Process the next available task
                processNextTask()
            }
        }
    }

    private suspend fun processNextTask() {
        val task = mutex.withLock {
            pendingTasks.values.firstOrNull()?.also {
                pendingTasks.remove(it.name)
                _pendingTasksFlow.emit(pendingTasks.values.toList())
            }
        } ?: return

        try {
            logger.info("Run task: ${task::class.simpleName}: ${task.name}")
            runningTaskName = task.name
            runningTaskCancelled = false
            _progressFlow.value = null
            _runningTaskFlow.emit(task)
            task.execute()
            _resultFlow.emit(Result.success(task))
        } catch (e: Exception) {
            // A cancelled task aborts by throwing, which is not an error worth a stack trace.
            if (!runningTaskCancelled) logger.error(e.stackTraceToString())
            _resultFlow.emit(
                if (runningTaskCancelled) {
                    Result.failure(OperationCancelledException("Task cancelled: ${task.name}"))
                } else {
                    Result.failure(e)
                },
            )
        } finally {
            logger.info("Task ended: ${task::class.simpleName}: ${task.name}")
            runningTaskName = null
            runningTaskCancelled = false
            _progressFlow.value = null
            _runningTaskFlow.emit(null)
            // After current task finishes, signal if there are pending tasks
            mutex.withLock {
                if (pendingTasks.isNotEmpty()) {
                    _taskSignal.trySend(Unit)
                }
            }
        }
    }

    fun getTask(name: String): Task? {
        return pendingTasks[name]
    }

    /** Update progress of the currently running task (0..100). */
    fun updateProgress(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _progressFlow.value = clamped
    }

    fun containTask(name: String): Boolean {
        return pendingTasks.containsKey(name)
    }

    /** True once the user asked the currently running task to stop. */
    fun isRunningTaskCancelled(): Boolean = runningTaskCancelled

    /**
     * Ask the currently running task to stop; false when idle. The task body aborts
     * cooperatively by polling [isRunningTaskCancelled].
     */
    fun cancelRunningTask(): Boolean {
        val running = runningTaskName ?: return false
        logger.info("Cancel running task $running")
        runningTaskCancelled = true
        return true
    }

    suspend fun addTask(task: Task) {
        mutex.withLock {
            logger.info("Add task ${task.name}")
            pendingTasks[task.name] = task
            _pendingTasksFlow.emit(pendingTasks.values.toList())
        }
        _taskSignal.trySend(Unit)
    }

    suspend fun cancelTask(name: String) {
        logger.info("Cancel task $name")
        mutex.withLock {
            pendingTasks.remove(name)
            _pendingTasksFlow.emit(pendingTasks.values.toList())
        }
    }

    suspend fun moveTask(name: String, newPosition: Int) {
        mutex.withLock {
            logger.info("Moving task $name to position $newPosition")
            val task = pendingTasks.remove(name) ?: return@withLock

            val keys = pendingTasks.keys.toList()
            val values = pendingTasks.values.toList()

            val newKeys = mutableListOf<String>()
            val newValues = mutableListOf<Task>()

            val boundedNewPosition = newPosition.coerceAtMost(keys.size)

            newKeys.addAll(keys.subList(0, boundedNewPosition))
            newValues.addAll(values.subList(0, boundedNewPosition))

            newKeys.add(name)
            newValues.add(task)

            if (boundedNewPosition < keys.size) {
                newKeys.addAll(keys.subList(boundedNewPosition, keys.size))
                newValues.addAll(values.subList(boundedNewPosition, keys.size))
            }

            pendingTasks.clear()
            newKeys.zip(newValues).forEach { (k, v) -> pendingTasks[k] = v }

            _pendingTasksFlow.emit(pendingTasks.values.toList())
        }
    }

    suspend fun closeQueue() {
        logger.info("Close queue ...")
        _taskSignal.close()
        mutex.withLock {
            pendingTasks.clear()
            _pendingTasksFlow.emit(listOf())
        }
        coroutineScope.cancel()
    }
}
