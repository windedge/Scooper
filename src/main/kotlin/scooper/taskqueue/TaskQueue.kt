package scooper.taskqueue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
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
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    /** 通知有新任务加入的信号量 */
    private val _taskSignal = kotlinx.coroutines.channels.Channel<Unit>(Channel.RENDEZVOUS)

    init {
        coroutineScope.launch {
            logger.info("Starting task queue ...")
            while (true) {
                // 等待信号
                _taskSignal.receive()
                // 处理所有可用任务
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
            _runningTaskFlow.emit(task)
            task.execute()
            _resultFlow.emit(Result.success(task))
        } catch (e: Exception) {
            logger.error(e.stackTraceToString())
            _resultFlow.emit(Result.failure(e))
        } finally {
            logger.info("Task ended: ${task::class.simpleName}: ${task.name}")
            _runningTaskFlow.emit(null)
            // 当前任务完成后，如果还有待处理任务，继续发送信号
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

    fun containTask(name: String): Boolean {
        return pendingTasks.containsKey(name)
    }

    suspend fun addTask(task: Task) {
        mutex.withLock {
            logger.info("Add task ${task.name}")
            pendingTasks[task.name] = task
            _pendingTasksFlow.emit(pendingTasks.values.toList())
        }
        _taskSignal.send(Unit)
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
