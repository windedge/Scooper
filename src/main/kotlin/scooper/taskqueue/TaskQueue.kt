package scooper.taskqueue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val mutex = Mutex()
    private val taskChannel = Channel<Task>(Channel.UNLIMITED)
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    init {
        coroutineScope.launch {
            logger.info("Starting task queue ...")
            for (x in taskChannel) {
                val task = pollTask() ?: continue
                try {
                    logger.info("Run task: ${task::class.simpleName}: ${task.name}")
                    removeTask(task.name)
                    _runningTaskFlow.emit(task)
                    task.execute()
                    _resultFlow.emit(Result.success(task))
                } catch (e: Exception) {
                    logger.error(e.stackTraceToString())
                    _resultFlow.emit(Result.failure(e))
                } finally {
                    logger.info("Task ended: ${task::class.simpleName}: ${task.name}")
                    _runningTaskFlow.emit(null)
                }
            }
        }
    }

    fun getTask(name: String): Task? {
        return pendingTasks[name]
    }

    fun containTask(name: String): Boolean {
        return pendingTasks.containsKey(name)
    }

    suspend fun addTask(task: Task) {
        mutex.withLock {
            logger.info("Add task ${task.name}")
            val oldTask = pendingTasks.put(task.name, task)
            if (oldTask != task) {
                taskChannel.send(task)
                _pendingTasksFlow.emit(pendingTasks.values.toList())
            }
        }
    }

    private suspend fun pollTask(): Task? {
        return mutex.withLock {
            val task = pendingTasks.values.firstOrNull()
            if (task != null) {
                pendingTasks.remove(task.name)
            }
            task
        }
    }

    private suspend fun removeTask(name: String) {
        mutex.withLock {
            pendingTasks.remove(name)
            _pendingTasksFlow.emit(pendingTasks.values.toList())
        }
    }

    suspend fun cancelTask(name: String) {
        logger.info("Cancel task $name")
        removeTask(name)
    }

    suspend fun moveTask(name: String, newPosition: Int) {
        mutex.withLock {
            logger.info("Moving task $name to position $newPosition")
            val task = pendingTasks.remove(name)
            if (task != null) {
                val keys = pendingTasks.keys.toList()
                val values = pendingTasks.values.toList()

                // Create a new list for keys and values
                val newKeys = mutableListOf<String>()
                val newValues = mutableListOf<Task>()

                // Determine the new position bounded by the list size
                val boundedNewPosition = newPosition.coerceAtMost(keys.size)

                // Add elements before the new position
                newKeys.addAll(keys.subList(0, boundedNewPosition))
                newValues.addAll(values.subList(0, boundedNewPosition))

                // Add the moved task
                newKeys.add(name)
                newValues.add(task)

                // Add remaining elements after the new position
                if (boundedNewPosition < keys.size) {
                    newKeys.addAll(keys.subList(boundedNewPosition, keys.size))
                    newValues.addAll(values.subList(boundedNewPosition, values.size))
                }

                // Rebuild the pendingTasks map
                pendingTasks.clear()
                newKeys.zip(newValues).forEach { (k, v) -> pendingTasks[k] = v }

                // Emit the updated list of tasks
                _pendingTasksFlow.emit(pendingTasks.values.toList())
            }
        }
    }

    suspend fun closeQueue() {
        logger.info("Close queue ...")
        taskChannel.close()
        pendingTasks.clear()
        _pendingTasksFlow.emit(listOf())
        coroutineScope.cancel()
    }
}