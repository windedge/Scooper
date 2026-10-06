@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * TDD for the report: clicking the "Improve description" review action chip must
 * start an assist run whose instruction is the chip prompt (description context).
 */
class CreateViewModelChipAssistTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: File

    private class RecordingBridge(configRepository: ConfigRepository) : ScoopAiBridge(
        configRepository = configRepository,
        checkpointService = InMemoryAiCheckpointService(),
    ) {
        val assistInstructions = mutableListOf<String>()
        var assistStarts = 0

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
            // Simulate an instant successful assist finishing with an updated manifest.
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

    private suspend fun awaitState(vm: CreateViewModel, predicate: (CreateState) -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (!predicate(vm.container.stateFlow.value)) delay(10) }
        }
    }

    @Test
    fun `clicking the improve description chip starts assist with description context`() = runBlocking {
        val bridge = RecordingBridge(FakeRepo())
        val vm = createVm(bridge)
        try {
            // Issue panel state: manifest exists, nothing running, one description issue.
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.container.orbit {
                        reduce {
                            state.copy(
                                manifestJson = """{"version":"1.0.0","description":"${"x".repeat(163)}"}""",
                                manifestIssues = listOf("Description is 163 characters, should be under 100"),
                                manifestSaved = false,
                            )
                        }
                    }.join()
                }
            }

            val chips = vm.container.stateFlow.value.reviewActionChips
            val chip = chips.firstOrNull { it.label == "Improve description" }
            assertNotNull(chip, "a description issue must produce an Improve description chip")

            vm.onApplyActionChip(chip!!)

            awaitState(vm) { !it.running && it.messages.isNotEmpty() }
            assertEquals(1, bridge.assistStarts, "chip click must start an assist run")
            val instruction = bridge.assistInstructions.single()
            assertTrue(
                instruction.contains("description", ignoreCase = true),
                "assist prompt must carry the description context, was: $instruction",
            )
            assertEquals(
                "⚡ Apply fix: Improve description",
                vm.container.stateFlow.value.messages.first { it.role == ChatRole.User }.text,
            )
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
