@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import scooper.data.UIConfig
import scooper.repository.AppsRepository
import scooper.repository.ConfigRepository
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopClient
import scooper.service.ScoopLogStream
import scooper.taskqueue.TaskQueue

/**
 * TDD test for the TestResult card dismiss in CreateViewModel:
 * [CreateViewModel.onDismissTestResult] must clear [CreateState.testResult]
 * so the TestResultPanel is removed, mirroring [CreateViewModel.onDismissSaveResult]
 * for the SaveResultPanel.
 *
 * The VM is built from fake repositories/services (no DB, no network, no real
 * scoop) following the SettingsViewModelFetchModelsTest pattern; the intent is
 * joined via [CreateViewModel.onDismissTestResult]'s returned Job so the
 * assertion is deterministic.
 */
class CreateViewModelDismissTestResultTest {

    private class FakeConfigRepository : ConfigRepository() {
        private var config = UIConfig()
        override fun getConfig(): UIConfig = config
        override fun setConfig(config: UIConfig) {
            this.config = config
        }
    }

    // ---------------------------------------------------------------------
    // onDismissTestResult
    // ---------------------------------------------------------------------

    @Test
    fun `onDismissTestResult clears a present testResult`() = runBlocking {
        val vm = createViewModelWithTestResult()

        assertNotNull(vm.container.stateFlow.value.testResult, "precondition: testResult set")

        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                vm.onDismissTestResult().join()
            }
        }

        assertNull(vm.container.stateFlow.value.testResult)
        vm.close()
    }

    @Test
    fun `onDismissTestResult keeps null testResult null and other state untouched`() = runBlocking {
        val vm = createViewModelWithTestResult()
        val before = vm.container.stateFlow.value

        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                // Dismiss twice: the second no-op must not throw or resurrect state.
                vm.onDismissTestResult().join()
                vm.onDismissTestResult().join()
            }
        }

        val after = vm.container.stateFlow.value
        assertNull(after.testResult)
        // Only testResult changes; saveResult / installVerified stay as they were.
        assertEquals(before.saveResult, after.saveResult)
        assertEquals(before.installVerified, after.installVerified)
        assertEquals(before.manifestJson, after.manifestJson)
        vm.close()
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** VM with fake collaborators; testResult preset via an inline intent. */
    private fun createViewModelWithTestResult(): CreateViewModel = runBlocking {
        val vm = CreateViewModel(
            scoopAiBridge = ScoopAiBridge(
                configRepository = FakeConfigRepository(),
                checkpointService = InMemoryAiCheckpointService(),
            ),
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = ScoopClient(
                logStream = ScoopLogStream(),
                taskQueue = TaskQueue(),
            ),
            appsRepository = AppsRepository(
                scoopClient = ScoopClient(
                    logStream = ScoopLogStream(),
                    taskQueue = TaskQueue(),
                ),
                gitHistoryService = GitHistoryService(),
            ),
            gitHistoryService = GitHistoryService(),
        )
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                vm.container.orbit {
                    reduce {
                        state.copy(
                            testResult = TestResult(
                                success = true,
                                testName = "fd-scooper-test",
                            ),
                        )
                    }
                }
                // orbit blocks are async; wait until the preset state lands.
                while (vm.container.stateFlow.value.testResult == null) delay(10)
            }
        }
        vm
    }
}
