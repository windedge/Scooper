package scooper.taskqueue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import scooper.util.OperationCancelledException

/**
 * TaskQueue consumes tasks on a background coroutine, so asserting on
 * getTask/containTask right after addTask is inherently racy. These tests
 * assert on the SharedFlows (replay=1) whose emission order is deterministic.
 */
class TaskQueueTest {

    private lateinit var taskQueue: TaskQueue

    @BeforeEach
    fun setUp() {
        taskQueue = TaskQueue()
    }

    @Test
    fun `addTask emits task to pendingTasksFlow and resultFlow on completion`() = runBlocking {
        val pending = mutableListOf<List<Task>>()
        val pendingJob = CoroutineScope(Dispatchers.Unconfined).launch {
            taskQueue.pendingTasksFlow.take(2).toList(pending)
        }
        val results = mutableListOf<Result<Task>>()
        val resultJob = CoroutineScope(Dispatchers.Unconfined).launch {
            taskQueue.resultFlow.take(1).toList(results)
        }

        taskQueue.addTask(Task.Refresh("test") {})

        withTimeout(5000) {
            resultJob.join()
            pendingJob.join()
        }

        assertEquals("test", results.single().getOrThrow().name)
        assertTrue(pending.any { list -> list.any { it.name == "test" } })
        assertTrue(pending.any { list -> list.none { it.name == "test" } })
    }

    @Test
    fun `addTask replaces existing task with same name`() = runBlocking {
        // Keep the first task blocked until the second addTask completes,
        // so the second add is guaranteed to be a replacement, not a second entry.
        val firstTaskStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseFirst = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstTask = Task.Refresh("duplicate") {
            firstTaskStarted.complete(Unit)
            releaseFirst.await()
        }
        val secondTask = Task.Refresh("duplicate") {}

        taskQueue.addTask(firstTask)
        withTimeout(5000) { firstTaskStarted.await() }

        // First task is now running; adding a second task with the same name
        // must show up in pendingTasksFlow with exactly one entry.
        // Note: pendingTasksFlow has replay=1; the replayed [] (after the first
        // task was dequeued) arrives first, so take(2) and check the last emission.
        val pending = mutableListOf<List<Task>>()
        val pendingJob = CoroutineScope(Dispatchers.Unconfined).launch {
            taskQueue.pendingTasksFlow.take(2).toList(pending)
        }
        taskQueue.addTask(secondTask)
        withTimeout(5000) { pendingJob.join() }

        assertEquals(listOf(secondTask), pending.last())
        releaseFirst.complete(Unit)
        Unit
    }

    @Test
    fun `cancelTask removes task from queue`() = runBlocking {
        // Block the consumer so cancelTask is observable before execution
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        taskQueue.addTask(Task.Refresh("blocking") {
            started.complete(Unit)
            release.await()
        })
        withTimeout(5000) { started.await() }

        taskQueue.addTask(Task.Refresh("cancel-me") {})
        assertTrue(taskQueue.containTask("cancel-me"))

        taskQueue.cancelTask("cancel-me")
        assertFalse(taskQueue.containTask("cancel-me"))

        release.complete(Unit)
        Unit
    }

    @Test
    fun `cancel nonexistent task does not throw`() = runBlocking {
        taskQueue.cancelTask("nonexistent")
    }

    @Test
    fun `getTask returns null for unknown name`() {
        assertNotNull(taskQueue)
        assertEquals(null, taskQueue.getTask("nonexistent"))
    }
    // ---------------------------------------------------------------
    // Running-task cancellation
    // ---------------------------------------------------------------

    @Test
    fun `cancelRunningTask stops the running task and emits cancelled result`() = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val results = mutableListOf<Result<Task>>()
        val resultJob = CoroutineScope(Dispatchers.Unconfined).launch {
            taskQueue.resultFlow.take(1).toList(results)
        }
        taskQueue.addTask(Task.Refresh("running") {
            started.complete(Unit)
            while (!taskQueue.isRunningTaskCancelled()) {
                kotlinx.coroutines.delay(10)
            }
            // Cancelled task bodies abort by throwing, see ScoopClient download loops.
            throw OperationCancelledException("cancelled by user")
        })
        withTimeout(5000) { started.await() }

        assertEquals("running", taskQueue.runningTaskFlow.first()?.name)
        assertTrue(taskQueue.cancelRunningTask())
        withTimeout(5000) { resultJob.join() }

        assertTrue(results.single().isFailure)
        assertTrue(results.single().exceptionOrNull() is OperationCancelledException)
        withTimeout(5000) { taskQueue.runningTaskFlow.first { it == null } }
        Unit
    }

    @Test
    fun `cancelRunningTask returns false when the queue is idle`() {
        assertFalse(taskQueue.cancelRunningTask())
        assertFalse(taskQueue.isRunningTaskCancelled())
    }
}
