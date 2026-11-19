@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import scooper.util.tr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * TDD for the retry-continue report: clicking Retry on a failed assist run must
 * continue from where it stopped instead of replaying the latest user text.
 *
 * Target behavior:
 * 1. No new user bubble is appended to messages.
 * 2. The bridge instruction is the localized "continue" msgid (tr at click time).
 * 3. The bridge history keeps the original request text exactly once (the failed
 *    round's user message stays, the error bubble is excluded).
 */
class CreateViewModelRetryContinueTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: File

    private class RecordingBridge(configRepository: ConfigRepository) : ScoopAiBridge(
        configRepository = configRepository,
        checkpointService = InMemoryAiCheckpointService(),
    ) {
        val assistInstructions = mutableListOf<String>()
        val assistHistories = mutableListOf<List<String>>()
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
            assistHistories.add(history)
            onFinished(
                Result.failure(
                    com.scoopai.core.ScoopAiGenerationException("boom")
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

    private suspend fun awaitState(vm: CreateViewModel, predicate: (CreateState) -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (!predicate(vm.container.stateFlow.value)) delay(10) }
        }
    }

    /** Failed pure-question assist session: original request, running cleared, RETRY_ANSWER offered. */
    private suspend fun seedFailedAssist(vm: CreateViewModel) {
        presetState(vm) {
            it.copy(
                manifestJson = "",
                running = false,
                testing = false,
                assistSession = true,
                messages = listOf(
                    ChatMessage(role = ChatRole.User, text = "Explain what this tool does"),
                    ChatMessage(
                        role = ChatRole.Agent,
                        text = "",
                        errorCategory = scooper.service.AiErrorCategory.SERVER,
                        actions = listOf(AiErrorAction.RETRY_ANSWER),
                    ),
                ),
            )
        }
    }

    @Test
    fun `retry after failure starts assist with the continue instruction`() = runBlocking {
        val bridge = RecordingBridge(FakeRepo())
        val vm = createVm(bridge)
        try {
            seedFailedAssist(vm)

            vm.onAction(AiErrorAction.RETRY_ANSWER).join()

            awaitState(vm) { !it.running && it.messages.isNotEmpty() }
            assertEquals(1, bridge.assistStarts, "retry must start an assist run")
            assertEquals(
                tr(CONTINUE_INSTRUCTION_MSGID),
                bridge.assistInstructions.single(),
                "instruction must be the localized continue msgid (msgid itself under en)",
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun `retry after failure does not append a user bubble`() = runBlocking {
        val bridge = RecordingBridge(FakeRepo())
        val vm = createVm(bridge)
        try {
            seedFailedAssist(vm)
            val before = vm.container.stateFlow.value.messages

            vm.onAction(AiErrorAction.RETRY_ANSWER).join()

            awaitState(vm) { !it.running && it.messages.isNotEmpty() }
            assertEquals(
                before,
                vm.container.stateFlow.value.messages,
                "retry must not append any new message bubble",
            )
            assertEquals(
                1,
                vm.container.stateFlow.value.messages.count { it.role == ChatRole.User },
                "the original request must stay the only user bubble",
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun `retry after failure keeps the original request exactly once in history`() = runBlocking {
        val bridge = RecordingBridge(FakeRepo())
        val vm = createVm(bridge)
        try {
            seedFailedAssist(vm)

            vm.onAction(AiErrorAction.RETRY_ANSWER).join()

            awaitState(vm) { !it.running && it.messages.isNotEmpty() }
            val history = bridge.assistHistories.single()
            assertEquals(
                1,
                history.count { it.contains("Explain what this tool does") },
                "the original request must appear exactly once in the engine history",
            )
            assertFalse(
                history.any { it.contains(CONTINUE_INSTRUCTION_MSGID) },
                "history must not be polluted by the synthesized instruction",
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun `continue instruction is localized under zh_CN`() = runBlocking {
        val english = scooper.util.Strings.current
        try {
            scooper.util.Strings.update(java.util.Locale.SIMPLIFIED_CHINESE)
            assertEquals(
                "从上次中断的地方继续。",
                tr(CONTINUE_INSTRUCTION_MSGID),
                "zh_CN catalog must translate the continue instruction msgid",
            )
        } finally {
            scooper.util.Strings.update(java.util.Locale.ENGLISH)
            assertEquals(english.locale.toLanguageTag(), scooper.util.Strings.current.locale.toLanguageTag())
        }
    }

    @Test
    fun `retry action chip keeps the Retry label`() {
        assertEquals("Retry", tr("Retry"), "chip copy stays the bare Retry msgid")
    }

    // ---------------------------------------------------------------
    // pure plan path: a retry-style round must not surface a plan-synthesis marker
    // ---------------------------------------------------------------

    @Test
    fun `planAssist keeps the continue instruction verbatim without markers`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "Explain what this tool does"),
        )
        val plan = planAssist(
            instruction = tr(CONTINUE_INSTRUCTION_MSGID),
            displayUserText = null,
            manifestJson = "",
            messages = messages,
            images = emptyList(),
            suggestedAppName = null,
            savedAppName = null,
            attempt = 2,
        )
        assertNotNull(plan)
        assertEquals(
            CONTINUE_INSTRUCTION_MSGID,
            plan!!.instruction,
            "the synthesized instruction must reach the engine verbatim",
        )
        assertEquals(plan.instruction, plan.userPrompt)
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
