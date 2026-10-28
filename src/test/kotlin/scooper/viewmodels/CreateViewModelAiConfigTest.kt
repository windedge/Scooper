@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.UIConfig
import scooper.repository.ConfigRepository
import scooper.service.AiSettingsStore
import scooper.service.FetchModelsResult
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopClient
import scooper.service.ScoopLogStream
import scooper.taskqueue.TaskQueue
import java.io.File

// ---------------------------------------------------------------
// CreateViewModel AI config wiring after the AiGate extraction
// (S2 step 4): onSend is gated by the shared readiness check, a
// failed /models probe keeps the previous list, and the model
// choice is persisted immediately. The bridge is faked so nothing
// here touches the network.
// ---------------------------------------------------------------
class CreateViewModelAiConfigTest {

    @TempDir
    lateinit var tempDir: File

    private class MutableConfigRepository(var stored: UIConfig = UIConfig()) : ConfigRepository() {
        override fun getConfig(): UIConfig = stored

        override fun setConfig(config: UIConfig) {
            stored = config
        }
    }

    /** Records the start calls and answers the /models probe from [modelsResult]. */
    private class FakeBridge(
        configRepository: ConfigRepository,
        var modelsResult: FetchModelsResult =
            FetchModelsResult(ok = true, models = listOf("remote-a", "remote-b")),
    ) : ScoopAiBridge(
        configRepository = configRepository,
        checkpointService = InMemoryAiCheckpointService(),
    ) {
        val startedUrls = mutableListOf<String>()
        var startCalls = 0

        override fun startGenerate(
            url: String,
            userPrompt: String?,
            resumeFrom: scooper.service.AiErrorAction?,
            overrideModel: String?,
            ignoreAppNames: Set<String>,
            onPhase: (String) -> Unit,
            onToolActivity: ((com.scoopai.core.ToolActivity) -> Unit)?,
            onPhaseOutput: ((String, String) -> Unit)?,
            onAskUser: (suspend (String) -> String)?,
            onQuestionnaire: (suspend (List<com.scoopai.tools.interaction.Question>) -> String)?,
            onFinished: (Result<com.scoopai.core.ManifestResult>) -> Unit,
        ) {
            startCalls++
            startedUrls.add(url)
        }

        override fun fetchModels(
            provider: String,
            apiKey: String,
            baseUrl: String?,
            onResult: (FetchModelsResult) -> Unit,
        ) {
            onResult(modelsResult)
        }
    }

    private fun configured(): UIConfig = UIConfig(
        aiProvider = "openai-compatible",
        aiApiKey = "sk-test",
        aiBaseUrl = "http://127.0.0.1:9",
    )

    private fun createVm(repo: ConfigRepository, bridge: ScoopAiBridge): CreateViewModel {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )
        return CreateViewModel(
            scoopAiBridge = bridge,
            aiSettingsStore = AiSettingsStore(repo),
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = scooper.service.createInMemoryAppsRepository(scoopClient),
            gitHistoryService = GitHistoryService(),
        )
    }

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

    // -------------------------------------------------------------
    // Readiness gate
    // -------------------------------------------------------------

    @Test
    fun `onSend is blocked with the readiness hint when the AI is not configured`(): Unit = runBlocking {
        // An unknown provider is unready no matter what the environment holds.
        val repo = MutableConfigRepository(UIConfig(aiProvider = "no-such-provider"))
        val bridge = FakeBridge(repo)
        val vm = createVm(repo, bridge)
        try {
            val toasts = collectToasts(vm) {
                withContext(Dispatchers.Default) {
                    withTimeout(10_000) { vm.onSend(input = "https://github.com/sharkdp/fd").join() }
                }
            }
            val state = vm.container.stateFlow.value
            assertEquals(0, bridge.startCalls, "an unready gate must not start a run")
            assertFalse(state.running, "an unready gate must not mark the VM busy")
            assertTrue(state.messages.isEmpty(), "an unready gate must not add a user bubble")
            assertFalse(state.aiConfigured, "the state must record the unready gate")
            assertTrue(
                toasts.any { it.type == ToastType.ERROR && it.text.isNotBlank() },
                "the gate must surface a toast, got: $toasts",
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun `configured send passes the gate and starts a generation`(): Unit = runBlocking {
        val repo = MutableConfigRepository(configured())
        val bridge = FakeBridge(repo)
        val vm = createVm(repo, bridge)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { vm.onSend(input = "https://github.com/sharkdp/fd").join() }
            }
            val state = vm.container.stateFlow.value
            assertTrue(state.aiConfigured, "a configured gate must keep aiConfigured true")
            assertTrue(state.running, "a started run must mark the VM busy")
            assertEquals(listOf("https://github.com/sharkdp/fd"), bridge.startedUrls)
            assertEquals(1, state.messages.count { it.role == ChatRole.User })
        } finally {
            vm.close()
        }
    }

    // -------------------------------------------------------------
    // Model list and model persistence
    // -------------------------------------------------------------

    @Test
    fun `model fetch failure keeps the previous model list`(): Unit = runBlocking {
        val repo = MutableConfigRepository(configured())
        val bridge = FakeBridge(repo, modelsResult = FetchModelsResult(ok = false, errorMessage = "probe failed"))
        val vm = createVm(repo, bridge)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    vm.container.orbit {
                        reduce { state.copy(fetchingModels = false, availableModels = listOf("old-model")) }
                    }
                }
            }
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { vm.onFetchModels().join() }
            }
            // The intent clears the spinner on its own thread; wait for it.
            var state = vm.container.stateFlow.value
            val deadline = System.currentTimeMillis() + 5_000
            while (state.fetchingModels && System.currentTimeMillis() < deadline) {
                withContext(Dispatchers.Default) { delay(20) }
                state = vm.container.stateFlow.value
            }
            assertEquals(listOf("old-model"), state.availableModels, "a failed probe must keep the old list")
            assertFalse(state.fetchingModels, "the spinner must clear even when the probe fails")
        } finally {
            vm.close()
        }
    }

    @Test
    fun `onFetchModels replaces the list on success`(): Unit = runBlocking {
        val repo = MutableConfigRepository(configured())
        val bridge = FakeBridge(repo)
        val vm = createVm(repo, bridge)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { vm.onFetchModels().join() }
            }
            var state = vm.container.stateFlow.value
            val deadline = System.currentTimeMillis() + 5_000
            while (state.availableModels.isEmpty() && System.currentTimeMillis() < deadline) {
                withContext(Dispatchers.Default) { delay(20) }
                state = vm.container.stateFlow.value
            }
            assertEquals(listOf("remote-a", "remote-b"), state.availableModels)
        } finally {
            vm.close()
        }
    }

    @Test
    fun `a successful fetch with an empty model list clears the previous list`(): Unit = runBlocking {
        val repo = MutableConfigRepository(configured())
        val bridge = FakeBridge(repo, modelsResult = FetchModelsResult(ok = true, models = emptyList()))
        val vm = createVm(repo, bridge)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    vm.container.orbit {
                        reduce { state.copy(fetchingModels = false, availableModels = listOf("old-model")) }
                    }
                }
            }
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { vm.onFetchModels().join() }
            }
            var state = vm.container.stateFlow.value
            val deadline = System.currentTimeMillis() + 5_000
            while (state.fetchingModels && System.currentTimeMillis() < deadline) {
                withContext(Dispatchers.Default) { delay(20) }
                state = vm.container.stateFlow.value
            }
            assertTrue(state.availableModels.isEmpty(), "an empty success must clear, not keep, the old list")
            assertFalse(state.fetchingModels)
        } finally {
            vm.close()
        }
    }

    @Test
    fun `onModelChange persists the model immediately`(): Unit = runBlocking {
        val repo = MutableConfigRepository(configured())
        val bridge = FakeBridge(repo)
        val vm = createVm(repo, bridge)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { vm.onModelChange("deepseek-chat").join() }
            }
            assertEquals("deepseek-chat", vm.container.stateFlow.value.selectedModel)
            assertEquals("deepseek-chat", repo.stored.aiModel, "the choice must survive a restart")
        } finally {
            vm.close()
        }
    }
}
