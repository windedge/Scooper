package scooper.taskqueue

import androidx.compose.ui.graphics.vector.rememberVectorPainter
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
import java.util.concurrent.ConcurrentHashMap

@Suppress("unused")
class TaskQueue {
    private val logger by logger()

    private val _resultFlow = MutableSharedFlow<Result<Task>>()
    val resultFlow = _resultFlow.asSharedFlow()

    private val pendingTasks = ConcurrentHashMap<String, Task>()
    private val _pendingTasksFlow = MutableSharedFlow<List<Task>>(replay = 1)
    val pendingTasksFlow = _pendingTasksFlow.asSharedFlow()

    private val _runningTaskFlow = MutableSharedFlow<Task?>(replay = 1)
    val runningTaskFlow = _runningTaskFlow.asSharedFlow()

    private val mutex = Mutex() // 用于确保线程安全
    private val taskChannel = Channel<Task>(Channel.UNLIMITED)
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    init {
        coroutineScope.launch {
            logger.info("Starting task queue ...")
            for (task in taskChannel) {
                if (!pendingTasks.containsKey(task.name)) continue // 如果任务已经被取消，则跳过执行
                try {
                    logger.info("Run task: ${task::class.simpleName}: ${task.name}")
                    removeTask(task.name)
                    _runningTaskFlow.emit(task)
                    task.execute()
                    _resultFlow.emit(Result.success(task))
                } catch (e: Exception) {
                    _resultFlow.emit(Result.failure(e))
                } finally {
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
                _pendingTasksFlow.emit(pendingTasks.values.toList()) // 发射当前任务状态的快照
            }
        }
    }

    private suspend fun removeTask(name: String) {
        pendingTasks.remove(name) // 从待处理任务中移除以取消任务
        _pendingTasksFlow.emit(pendingTasks.values.toList()) // 发射当前任务状态的快照
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
                val updatedTasks = LinkedHashMap(pendingTasks)
                val keys = updatedTasks.keys.toList()
                val values = updatedTasks.values.toList()

                // Remove old entry and add at the new position
                if (newPosition < keys.size) {
                    val newKeys = mutableListOf<String>().apply {
                        addAll(keys.subList(0, newPosition))
                        add(name)
                        addAll(keys.subList(newPosition, keys.size))
                    }
                    val newValues = mutableListOf<Task>().apply {
                        addAll(values.subList(0, newPosition))
                        add(task)
                        addAll(values.subList(newPosition, values.size))
                    }
                    pendingTasks.clear()
                    newKeys.zip(newValues).forEach { (k, v) -> pendingTasks[k] = v }
                } else {
                    pendingTasks.putAll(updatedTasks)
                    pendingTasks[name] = task
                }
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