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
import org.junit.jupiter.api.Assertions.assertNotNull
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

/**
 * TDD tests for answering a pending questionnaire through the composer.
 *
 * While the engine waits on the questionnaire deferred, running=true locks the
 * composer down; the send must instead be routed to the questionnaire answer
 * path so the user can reply in free text when no options fit.
 */
class CreateViewModelAskUserAnswerTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: File

    // ---------------------------------------------------------------
    // composerSendEnabled: the pure gate the composer UI and submit reuse
    // ---------------------------------------------------------------

    @Test
    fun `idle state allows sending`() {
        assertTrue(composerSendEnabled(running = false, testing = false, askUserPending = false))
    }

    @Test
    fun `running without a pending questionnaire blocks sending`() {
        assertFalse(composerSendEnabled(running = true, testing = false, askUserPending = false))
    }

    @Test
    fun `testing blocks sending`() {
        assertFalse(composerSendEnabled(running = false, testing = true, askUserPending = false))
    }

    @Test
    fun `installing blocks sending`() {
        assertFalse(composerSendEnabled(running = false, testing = false, askUserPending = false, installing = true))
    }

    @Test
    fun `pending questionnaire unlocks sending even while running`() {
        assertTrue(composerSendEnabled(running = true, testing = false, askUserPending = true))
    }

    @Test
    fun `pending questionnaire unlocks sending even while testing`() {
        assertTrue(composerSendEnabled(running = false, testing = true, askUserPending = true))
    }

    @Test
    fun `pending questionnaire unlocks sending even while installing`() {
        assertTrue(composerSendEnabled(running = false, testing = false, askUserPending = true, installing = true))
    }

    // ---------------------------------------------------------------
    // onSend while a questionnaire is pending routes to the answer
    // ---------------------------------------------------------------

    @Test
    fun `onSend during pending questionnaire answers instead of starting a new request`() = runBlocking {
        val vm = createVm()
        try {
            presetPendingQuestionnaire(vm)

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend(input = "Rust").join() }
            }

            val state = vm.container.stateFlow.value
            assertNull(state.askUserRequest, "the questionnaire card must fold after a composer answer")
            assertTrue(
                state.messages.any { it.role == ChatRole.User && it.text == "Rust" },
                "the free-text answer must appear as the user bubble",
            )
            assertTrue(state.running, "answering must not flip running off; the engine is still waiting")
        } finally {
            vm.close()
        }
    }

    @Test
    fun `onSend during pending questionnaire does not start a generation`() = runBlocking {
        val vm = createVm()
        try {
            presetPendingQuestionnaire(vm)
            val epochBefore = vm.container.stateFlow.value.generationEpoch

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend(input = "Use the default architecture").join() }
            }

            val state = vm.container.stateFlow.value
            assertEquals(epochBefore, state.generationEpoch, "no new run may start while answering")
            assertTrue(state.lastUrl.isEmpty(), "the answer text must not be treated as a repository URL")
        } finally {
            vm.close()
        }
    }

    @Test
    fun `blank onSend during pending questionnaire keeps the question open`() = runBlocking {
        val vm = createVm()
        try {
            presetPendingQuestionnaire(vm)

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend(input = "   ").join() }
            }

            val state = vm.container.stateFlow.value
            assertNotNull(state.askUserRequest, "a blank send must not consume the questionnaire")
        } finally {
            vm.close()
        }
    }

    @Test
    fun `blank onSend during pending questionnaire does not toast the busy guard`() = runBlocking {
        val vm = createVm()
        try {
            presetPendingQuestionnaire(vm)

            val toasts = collectToasts(vm) {
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { vm.onSend(input = "").join() }
                }
            }

            assertTrue(
                toasts.none { it.text.contains("Another operation is running") },
                "blank input while a questionnaire waits must stay silent, got: $toasts",
            )
            assertNotNull(
                vm.container.stateFlow.value.askUserRequest,
                "the questionnaire must stay open and unanswered",
            )
        } finally {
            vm.close()
        }
    }

    // ---------------------------------------------------------------
    // onAnswerAskUser: the option-tap path
    // ---------------------------------------------------------------

    @Test
    fun `onAnswerAskUser folds the card and appends the answer bubble`() = runBlocking {
        val vm = createVm()
        try {
            presetPendingQuestionnaire(vm)

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onAnswerAskUser("Layered").join() }
            }

            val state = vm.container.stateFlow.value
            assertNull(state.askUserRequest, "the questionnaire card must fold")
            assertTrue(
                state.messages.any { it.role == ChatRole.User && it.text == "Layered" },
                "the selected option must appear as the user bubble",
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun `onAnswerAskUser with blank text falls back to the autonomous answer`() = runBlocking {
        val vm = createVm()
        try {
            presetPendingQuestionnaire(vm)

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onAnswerAskUser("  ").join() }
            }

            val state = vm.container.stateFlow.value
            assertNull(state.askUserRequest, "the questionnaire card must fold")
            assertTrue(
                state.messages.any { it.text == DEFAULT_AUTONOMOUS_PROCEED_ANSWER },
                "a blank answer must fall back to the autonomous proceed text",
            )
        } finally {
            vm.close()
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Presets a mid-generation state with a pending questionnaire card. */
    private suspend fun presetPendingQuestionnaire(vm: CreateViewModel) {
        vm.container.orbit {
            reduce {
                state.copy(
                    running = true,
                    messages = state.messages + ChatMessage(role = ChatRole.User, text = "github.com/owner/repo"),
                    askUserRequest = AskUserRequest(
                        questions = listOf(
                            QuestionnaireQuestion(
                                id = "q_arch",
                                label = "Architecture",
                                prompt = "Which architecture should the manifest declare?",
                            ),
                        ),
                    ),
                )
            }
        }
        awaitState(vm) { it.running && it.askUserRequest != null }
    }

    /** Waits until [predicate] sees the state. */
    private suspend fun awaitState(vm: CreateViewModel, predicate: (CreateState) -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (!predicate(vm.container.stateFlow.value)) delay(10) }
        }
    }

    /** Collects Toast side effects emitted while [block] runs plus a fixed drain. */
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
        // Give the collector a moment to subscribe before the intent runs.
        withContext(Dispatchers.Default) { delay(100) }
        try {
            block()
            // postToast runs inside the intent, so joining it has posted everything;
            // a fixed drain is enough because the caller asserts absence.
            withContext(Dispatchers.Default) { delay(300) }
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
                configRepository = FakeAskUserConfigRepository(),
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
private class FakeAskUserConfigRepository : ConfigRepository() {
    private val config = scooper.data.UIConfig(
        aiProvider = "openai-compatible",
        aiApiKey = "test-key",
        aiBaseUrl = "http://127.0.0.1:9",
    )

    override fun getConfig(): scooper.data.UIConfig = config

    override fun setConfig(config: scooper.data.UIConfig) = Unit
}
