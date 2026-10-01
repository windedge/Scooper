@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.repository.ConfigRepository
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopClient
import scooper.service.ScoopLogStream
import scooper.taskqueue.TaskQueue
import java.io.File

/**
 * TDD tests for the Cancel path: pressing Cancel during a generation (or an
 * ask-user prompt) must leave every busy flag cleared so the next onSend is
 * not blocked by the "Another operation is running" guard.
 *
 * The AI start here points at a refused local port; the background bridge job
 * fails fast with a connection error while the assertions below are still
 * running, so the slow-install cases tolerate a late cancelling bubble that
 * clears the flags again.
 */
class CreateViewModelCancelTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: File

    // ---------------------------------------------------------
    // onCancel: flags reset
    // ---------------------------------------------------------

    @Test
    fun `onCancel clears the running flag`() = runBlocking {
        val vm = createVm()
        try {
            presetRunning(vm, withAskUser = false)

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onCancel().join() }
            }

            val state = vm.container.stateFlow.value
            assertFalse(state.running, "cancel must reset running")
            assertFalse(state.testing, "cancel must reset testing")
            assertFalse(state.installing, "cancel must reset installing")
            assertTrue(
                state.messages.any { it.text.contains("Generation cancelled by user.") },
                "cancel must append the cancelled bubble",
            )
            assertEquals(null, state.askUserRequest, "cancel must clear the ask-user card")
        } finally {
            vm.close()
        }
    }

    // ---------------------------------------------------------
    // onCancel during ask-user: no answered bubble on top
    // ---------------------------------------------------------

    @Test
    fun `onCancel during ask-user adds no answered bubble and clears askUserRequest`() = runBlocking {
        val vm = createVm()
        try {
            presetRunning(vm, withAskUser = true)

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onCancel().join() }
            }

            val state = vm.container.stateFlow.value
            assertFalse(state.running, "cancel must reset running")
            assertEquals(null, state.askUserRequest, "the ask-user card must fold on cancel")
            assertTrue(
                state.messages.none { it.text == "Let AI decide" },
                "the answered bubble belongs to Cancel AskUser, not Cancel",
            )
            assertTrue(
                state.messages.none { it.role == ChatRole.User && it.text == DEFAULT_AUTONOMOUS_PROCEED_ANSWER },
                "cancel must not turn the deferred answer into a user bubble",
            )
        } finally {
            vm.close()
        }
    }

    // ---------------------------------------------------------
    // stuck-flag reproductions: guard releases without another cancel
    // ---------------------------------------------------------

    @Test
    fun `cancel during a hung install releases the send guard`() = runBlocking {
        withScoopShim(hangSeconds = 6) {
            val vm = createVm()
            try {
                presetInstallable(vm)
                vm.onInstallSaved()
                awaitInstallStart(vm)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { vm.onCancel().join() }
                }
                awaitGuardReleased(vm)
            } finally {
                vm.close()
            }
        }
    }

    // ---------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------

    /** Installs a scoop.cmd stub that sleeps, simulating a long install. */
    private fun <T> withScoopShim(hangSeconds: Int, block: suspend () -> T): T {
        val dir = File(System.getProperty("java.io.tmpdir"), "scooper-shim-${System.nanoTime()}").apply { mkdirs() }
        val cmd = dir.resolve("scoop.cmd").apply {
            writeText("@echo off\r\necho Working\r\nping -n $hangSeconds 127.0.0.1 > nul\r\nexit /b 0\r\n")
            deleteOnExit()
        }
        System.setProperty(scooper.util.TEST_SCOOP_SHIM_ENV, cmd.absolutePath)
        return try {
            runBlocking { block() }
        } finally {
            System.clearProperty(scooper.util.TEST_SCOOP_SHIM_ENV)
            dir.deleteRecursively()
        }
    }

    /** Presets a mid-generation state: running=true, optional ask-user card. */
    private suspend fun presetRunning(vm: CreateViewModel, withAskUser: Boolean) {
        vm.container.orbit {
            reduce {
                state.copy(
                    running = true,
                    messages = state.messages + ChatMessage(role = ChatRole.User, text = "github.com/owner/repo"),
                    askUserRequest = if (withAskUser) AskUserRequest(questions = emptyList()) else null,
                )
            }
        }
        awaitState(vm) { it.running && (withAskUser == (it.askUserRequest != null)) }
    }

    /** Presets an unsaved manifest with a resolvable app name for direct install. */
    private suspend fun presetInstallable(vm: CreateViewModel) {
        vm.container.orbit {
            reduce {
                state.copy(
                    manifestJson = """{"version":"1.2.3","bin":["app.exe"]}""",
                    suggestedAppName = "testapp",
                )
            }
        }
        awaitState(vm) { it.suggestedAppName == "testapp" }
    }

    /** Waits until the direct install intent has flipped installing=true. */
    private suspend fun awaitInstallStart(vm: CreateViewModel) {
        awaitState(vm) { it.installing }
    }

    /** Waits until every busy flag is down after the cancel. */
    private suspend fun awaitGuardReleased(vm: CreateViewModel) {
        awaitState(vm) { !it.running && !it.testing && !it.installing }
        val state = vm.container.stateFlow.value
        assertFalse(state.running, "running must clear after cancel")
        assertFalse(state.testing, "testing must clear after cancel")
        assertFalse(state.installing, "installing must clear after cancel")
    }

    /** Waits until [predicate] sees the state. */
    private suspend fun awaitState(vm: CreateViewModel, predicate: (CreateState) -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (!predicate(vm.container.stateFlow.value)) delay(10) }
        }
    }

    private fun createVm(): CreateViewModel {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )
        return CreateViewModel(
            scoopAiBridge = ScoopAiBridge(
                configRepository = FakeCancelConfigRepository(),
                checkpointService = InMemoryAiCheckpointService(),
            ),
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = scooper.service.createInMemoryAppsRepository(scoopClient),
            gitHistoryService = GitHistoryService(),
        )
    }
}

/** Complete AI config; the refused port keeps the bridge job from doing real work. */
private class FakeCancelConfigRepository : ConfigRepository() {
    private val config = scooper.data.UIConfig(
        aiProvider = "openai-compatible",
        aiApiKey = "test-key",
        aiBaseUrl = "http://127.0.0.1:9",
    )

    override fun getConfig(): scooper.data.UIConfig = config

    override fun setConfig(config: scooper.data.UIConfig) = Unit
}
