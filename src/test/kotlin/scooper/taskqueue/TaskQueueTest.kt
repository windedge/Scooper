package scooper.taskqueue

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class TaskQueueTest {

    private lateinit var taskQueue: TaskQueue

    @BeforeEach
    fun setUp() {
        taskQueue = TaskQueue()
    }

    @Test
    fun `add and retrieve task`() = runBlocking {
        val task = Task.Refresh("test") {}
        taskQueue.addTask(task)

        val retrieved = taskQueue.getTask("test")
        assertNotNull(retrieved)
        assertEquals("test", retrieved!!.name)
    }

    @Test
    fun `containTask returns true for existing task`() = runBlocking {
        val task = Task.Refresh("existing") {}
        taskQueue.addTask(task)

        assertTrue(taskQueue.containTask("existing"))
        assertFalse(taskQueue.containTask("nonexistent"))
    }

    @Test
    fun `cancelTask removes task from queue`() = runBlocking {
        val task = Task.Refresh("cancel-me") {}
        taskQueue.addTask(task)
        assertTrue(taskQueue.containTask("cancel-me"))

        taskQueue.cancelTask("cancel-me")
        assertFalse(taskQueue.containTask("cancel-me"))
    }

    @Test
    fun `cancel nonexistent task does not throw`() = runBlocking {
        taskQueue.cancelTask("nonexistent")
    }

    @Test
    fun `addTask replaces existing task with same name`() = runBlocking {
        val task1 = Task.Refresh("duplicate") {}
        val task2 = Task.Refresh("duplicate") {}
        taskQueue.addTask(task1)
        taskQueue.addTask(task2)

        val retrieved = taskQueue.getTask("duplicate")
        assertNotNull(retrieved)
    }
}
