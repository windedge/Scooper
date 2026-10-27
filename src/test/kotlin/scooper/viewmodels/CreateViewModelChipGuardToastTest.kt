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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.repository.ConfigRepository
import scooper.service.AiErrorAction
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopClient
import scooper.service.ScoopLogStream
import scooper.taskqueue.TaskQueue
import java.io.File

/**
 * TDD for the silently swallowed clicks report: review chips stay clickable
 * while running/testing and rely on the VM guard toast; onAction retry paths
 * toast instead of returning silently when a run is active.
 */
class CreateViewModelChipGuardToastTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: File

    private class RecordingBridge(configRepository: ConfigRepository) : ScoopAiBridge(
        configRepository = configRepository,
        checkpointService = InMemoryAiCheckpointService(),
    ) {
        val assistInstructions = mutableListOf<String>()
        val generateUrls = mutableListOf<String>()
        var assistStarts = 0
        var generateStarts = 0

        override fun startAssist(
            currentManifest: String,
            instruction: String,
            history: List<String>,
            suggestedAppName: String?,
            overrideModel: String?,
            images: List<scooper.service.AiImage>,
            ignoreAppNames: Set<String>,
            onPhase: ((String) -> Unit)?,
            onToolActivity: ((com.scoopai.core.ToolActivity) -> Unit)?,
            onPhaseOutput: ((String, String) -> Unit)?,
            onAskUser: (suspend (String) -> String)?,
            onQuestionnaire: (suspend (List<com.scoopai.tools.interaction.Question>) -> String)?,
            onSaveToBucket: (suspend (String?, String?, Boolean) -> String)?,
            onInstallApp: (suspend (String?, String) -> String)?,
            onReadManifestRef: (suspend (String, String) -> String)?,
            onFinished: (Result<com.scoopai.core.ManifestResult>) -> Unit,
        ) {
            assistStarts++
            assistInstructions.add(instruction)
            onFinished(
                Result.success(
                    com.scoopai.core.ManifestResult(
                        manifest = """{"version":"1.0.0","description":"Better description."}""",
                        suggestedAppName = "app",
                        answered = false,
                    )
                )
            )
        }

        override fun startGenerate(
            url: String,
            userPrompt: String?,
            resumeFrom: AiErrorAction?,
            overrideModel: String?,
            ignoreAppNames: Set<String>,
            onPhase: (String) -> Unit,
            onToolActivity: ((com.scoopai.core.ToolActivity) -> Unit)?,
            onPhaseOutput: ((String, String) -> Unit)?,
            onAskUser: (suspend (String) -> String)?,
            onQuestionnaire: (suspend (List<com.scoopai.tools.interaction.Question>) -> String)?,
            onFinished: (Result<com.scoopai.core.ManifestResult>) -> Unit,
        ) {
            generateStarts++
            generateUrls.add(url)
            onFinished(
                Result.success(
                    com.scoopai.core.ManifestResult(
                        manifest = """{"version":"1.0.0","description":"A tool."}""",
                        suggestedAppName = "app",
                        answered = false,
                    )
                )
            )
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
        appsRepository = scooper.service.createInMemoryAppsRepository(
            ScoopClient(
                logStream = ScoopLogStream(),
                taskQueue = TaskQueue(),
                rootDirOverride = tempDir,
            )
        ),
        gitHistoryService = GitHistoryService(),
    )

    /** Presets the given state fields from any thread. */
    private suspend fun presetState(vm: CreateViewModel, transform: (CreateState) -> CreateState) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                vm.container.orbit {
                    reduce { transform(state) }
                }.join()
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

    // ---------------------------------------------------------------
    // onApplyActionChip guard toasts
    // ---------------------------------------------------------------

    @Test
    fun `onApplyActionChip while running toasts another operation and does not start assist`() = runBlocking {
        val bridge = RecordingBridge(FakeRepo())
        val vm = createVm(bridge)
        try {
            presetState(vm) {
                it.copy(
                    manifestJson = """{"version":"1.0.0","description":"A tool."}""",
                    running = true,
                )
            }
            val chip = ReviewActionChip(label = "Improve description", prompt = "Improve the description of the manifest.")

            val toasts = collectToasts(vm) { vm.onApplyActionChip(chip).join() }

            assertEquals(
                listOf("Another operation is running"),
                toasts.map { t -> t.text },
                "running must toast exactly once",
            )
            assertEquals(0, bridge.assistStarts, "assist must not start while running")
            assertEquals(ToastType.INFO, toasts.single().type)
        } finally {
            vm.close()
        }
    }

    @Test
    fun `onApplyActionChip while testing toasts another operation and does not start assist`() = runBlocking {
        val bridge = RecordingBridge(FakeRepo())
        val chip = ReviewActionChip(label = "Improve description", prompt = "Improve the description of the manifest.")
        val vm = createVm(bridge)
        try {
            presetState(vm) {
                it.copy(
                    manifestJson = """{"version":"1.0.0","description":"A tool."}""",
                    testing = true,
                )
            }

            val toasts = collectToasts(vm) { vm.onApplyActionChip(chip).join() }

            assertEquals(
                listOf("Another operation is running"),
                toasts.map { t -> t.text },
                "testing must toast exactly once",
            )
            assertEquals(0, bridge.assistStarts, "assist must not start while testing")
            assertEquals(ToastType.INFO, toasts.single().type)
        } finally {
            vm.close()
        }
    }

    // ---------------------------------------------------------------
    // onAction retry guard toast
    // ---------------------------------------------------------------

    @Test
    fun `onAction retry while running toasts another operation and does not retry`() = runBlocking {
        val bridge = RecordingBridge(FakeRepo())
        val vm = createVm(bridge)
        try {
            presetState(vm) {
                it.copy(
                    manifestJson = """{"version":"1.0.0","description":"A tool."}""",
                    running = true,
                    lastUrl = "https://github.com/owner/repo",
                )
            }

            val toasts = collectToasts(vm) { vm.onAction(AiErrorAction.RETRY_FULL).join() }

            assertEquals(
                listOf("Another operation is running"),
                toasts.map { t -> t.text },
                "retry while running must toast exactly once",
            )
            assertEquals(0, bridge.generateStarts, "retry must not start a generation while running")
            assertEquals(ToastType.INFO, toasts.single().type)
        } finally {
            vm.close()
        }
    }

    // ---------------------------------------------------------------
    // blank manifest guard toast via the VM entry point
    // ---------------------------------------------------------------

    @Test
    fun `onApplyActionChip with blank manifest toasts no manifest to fix`() = runBlocking {
        val bridge = RecordingBridge(FakeRepo())
        val chip = ReviewActionChip(label = "Improve description", prompt = "Improve the description of the manifest.")
        val vm = createVm(bridge)
        try {
            presetState(vm) { it.copy(manifestJson = "", running = false, testing = false) }

            val toasts = collectToasts(vm) { vm.onApplyActionChip(chip).join() }

            assertEquals(
                listOf("No manifest to fix"),
                toasts.map { t -> t.text },
                "blank manifest must toast exactly once",
            )
            assertEquals(0, bridge.assistStarts, "assist must not start without a manifest")
            assertEquals(ToastType.ERROR, toasts.single().type)
        } finally {
            vm.close()
        }
    }

    private open class FakeRepo : ConfigRepository() {
        private val config = scooper.data.UIConfig(
            aiProvider = "openai-compatible",
            aiApiKey = "k",
            aiBaseUrl = "http://127.0.0.1:9",
        )
        override fun getConfig() = config
        override fun setConfig(config: scooper.data.UIConfig) = Unit
    }
}
