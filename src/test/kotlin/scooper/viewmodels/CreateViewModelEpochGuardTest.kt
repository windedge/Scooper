package scooper.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
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
import kotlin.coroutines.cancellation.CancellationException

/**
 * TDD for the Bug 1 race: cancelling generation A and immediately starting B
 * must not let A's late CancellationException onFinished fold B's live run
 * ("Generation cancelled." over a run that is actually still working).
 *
 * The bridge subclass captures the onFinished callback of run A and replays it
 * after B has started, simulating the queued Orbit dispatch arrival.
 */
class CreateViewModelEpochGuardTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: File

    /** Records the last startGenerate callback set so a test can replay it late. */
    private class CapturingBridge(configRepository: ConfigRepository) : ScoopAiBridge(
        configRepository = configRepository,
        checkpointService = InMemoryAiCheckpointService(),
    ) {
        var lastFinished: ((Result<com.scoopai.core.ManifestResult>) -> Unit)? = null
        var startCount = 0

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
            startCount++
            lastFinished = onFinished
        }
    }

    private fun createVm(bridge: ScoopAiBridge): CreateViewModel = CreateViewModel(
        scoopAiBridge = bridge,
        checkpointService = InMemoryAiCheckpointService(),
        scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        ),
        appsRepository = scooper.service.createInMemoryAppsRepository(ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )),
        gitHistoryService = GitHistoryService(),
    )

    private class FakeRepo : ConfigRepository() {
        private val config = scooper.data.UIConfig(
            aiProvider = "openai-compatible",
            aiApiKey = "k",
            aiBaseUrl = "http://127.0.0.1:9",
        )
        override fun getConfig() = config
        override fun setConfig(config: scooper.data.UIConfig) = Unit
    }

    private suspend fun awaitState(vm: CreateViewModel, predicate: (CreateState) -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (!predicate(vm.container.stateFlow.value)) delay(10) }
        }
    }

    @Test
    fun `late onFinished of a cancelled run cannot fold the next running generation`() = runBlocking {
        val bridge = CapturingBridge(FakeRepo())
        val vm = createVm(bridge)
        try {
            // Run A starts and captures its onFinished callback (epoch 1).
            withContext(Dispatchers.Default) { withTimeout(5_000) { vm.onSend(url = "github.com/owner/repoA").join() } }
            awaitState(vm) { it.running }
            @Suppress("UNCHECKED_CAST")
            val finishA = requireNotNull(bridge.lastFinished) { "bridge must capture the onFinished callback" }
            val epochAfterA = vm.container.stateFlow.value.generationEpoch

            // Cancel A: epoch must bump so A's queued callbacks go stale.
            withContext(Dispatchers.Default) { withTimeout(5_000) { vm.onCancel().join() } }
            val epochAfterCancel = vm.container.stateFlow.value.generationEpoch
            assertTrue(epochAfterCancel > epochAfterA, "cancel must bump the epoch")

            // Run B starts (epoch bumped again by the start).
            withContext(Dispatchers.Default) { withTimeout(5_000) { vm.onSend(url = "github.com/owner/repoB").join() } }
            awaitState(vm) { it.running }

            // A's cancellation unwinds late, after B is already running.
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { finishA(Result.failure(CancellationException("cancelled"))) }
            }
            awaitState(vm) { vm.container.stateFlow.value.activeExecution != null }
            delay(200)

            val state = vm.container.stateFlow.value
            assertTrue(state.running, "run B must still be running after A's late finish")
            assertNotNull(state.activeExecution, "run B must keep its execution card")
            assertNull(state.error, "A's cancellation must not surface as B's error")
            assertTrue(
                state.messages.none { it.text == "Generation cancelled." },
                "no cancelling bubble may appear while B runs",
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun `stale replies report failure to the engine`() {
        val save = kotlinx.serialization.json.Json.parseToJsonElement(STALE_EPOCH_SAVE_REPLY_JSON)
        val install = kotlinx.serialization.json.Json.parseToJsonElement(STALE_EPOCH_INSTALL_REPLY_JSON)
        fun success(element: kotlinx.serialization.json.JsonElement): Boolean {
            val v = (element as kotlinx.serialization.json.JsonObject)["success"] as kotlinx.serialization.json.JsonPrimitive
            return v.content.toBoolean()
        }
        assertTrue(!success(save))
        assertTrue(!success(install))
    }

    @Test
    fun `verify install activity detection matches only verify_install`() {
        assertTrue(isInstallVerifyActivity(com.scoopai.core.ToolActivity.Started("verify_install", "{}")))
        assertTrue(!isInstallVerifyActivity(com.scoopai.core.ToolActivity.Started("fetchRepoInfo", "{}")))
    }
}
