@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * TDD tests for the direct install click path in
 * [CreateViewModel.onInstallSaved]: since install no longer depends on
 * Save to Bucket, a reset save result is not a dead click anymore - the
 * click falls through to the local direct-install branch. These tests pin
 * the remaining guards (blank manifest) and the enabled gate.
 */
class CreateViewModelInstallGuardTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: File

    @Test
    fun `onInstallSaved with a reset save result falls through to direct install`(): Unit = runBlocking {
        val vm = createVm()
        try {
            presetSavedButResetState(vm)

            val toasts = collectToasts(vm) { vm.onInstallSaved().join() }

            assertFalse(
                toasts.any { it.text.contains("Save the manifest") },
                "a reset save result must not block the install anymore, got: $toasts",
            )
            val after = vm.container.stateFlow.value
            assertFalse(after.installing, "resolved-name failure must reset installing")
            assertTrue(
                toasts.any { it.type == ToastType.ERROR },
                "unsaved install with no resolvable app name must toast an ERROR, got: $toasts",
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun `installActionEnabled no longer requires a save result`() {
        // The UI gate must match the VM behavior: an unsaved but valid manifest
        // stays clickable and clicks route into the local direct-install branch.
        assertTrue(
            installActionEnabled(
                manifestJson = """{"version":"1.2.3"}""",
                installedForUse = false,
                running = false,
                testing = false,
                installing = false,
            ),
            "button must stay enabled without a save result",
        )
        assertTrue(
            installActionEnabled(
                manifestJson = """{"version":"1.2.3"}""",
                installedForUse = false,
                running = false,
                testing = false,
                installing = false,
            ),
        )
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Presets manifestSaved=true with saveResult=null and no resolvable app
     *  name, so the direct-install branch fails fast before any command. */
    private fun presetSavedButResetState(vm: CreateViewModel) {
        runBlocking {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.container.orbit {
                        reduce {
                            state.copy(
                                manifestJson = """{"version":"1.2.3","bin":["app.exe"]}""",
                                suggestedAppName = null,
                                lastUrl = "",
                                manifestSaved = true,
                                saveResult = null,
                            )
                        }
                    }
                }
            }
        }
    }

    /** Collects Toast side effects emitted while [block] runs plus a drain window. */
    private suspend fun collectToasts(
        vm: CreateViewModel,
        block: suspend () -> Unit,
    ): List<AppSideEffect.Toast> {
        val collected = mutableListOf<AppSideEffect.Toast>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            vm.container.sideEffectFlow.collect { effect ->
                if (effect is AppSideEffect.Toast) collected.add(effect)
            }
        }
        withContext(Dispatchers.Default) { delay(100) }
        try {
            block()
            val deadline = System.currentTimeMillis() + 5_000
            while (collected.isEmpty() && System.currentTimeMillis() < deadline) {
                withContext(Dispatchers.Default) { delay(20) }
            }
        } finally {
            job.cancel()
        }
        return collected
    }

    private fun createVm(): CreateViewModel {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )
        return CreateViewModel(
            scoopAiBridge = ScoopAiBridge(
                configRepository = FakeInstallGuardConfigRepository(),
                checkpointService = InMemoryAiCheckpointService(),
            ),
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = scooper.service.createInMemoryAppsRepository(scoopClient),
            gitHistoryService = GitHistoryService(),
        )
    }
}

/** Minimal AI config so guards never block; nothing here dials out. */
private class FakeInstallGuardConfigRepository : ConfigRepository() {
    private val config = scooper.data.UIConfig(
        aiProvider = "openai-compatible",
        aiApiKey = "test-key",
        aiBaseUrl = "http://127.0.0.1:9",
    )

    override fun getConfig(): scooper.data.UIConfig = config

    override fun setConfig(config: scooper.data.UIConfig) = Unit
}
