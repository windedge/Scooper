@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
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
 * TDD tests for batch H1 (C4): every questionnaire answer / cancel path writes a
 * structured askUserBubble marker on the user bubble, so history filtering works
 * without matching on bubble text. The cancel path bubble text is UI-language
 * dependent (currently `tr("Let AI decide")`), so the marker is the only reliable
 * signal.
 */
class CreateViewModelAskUserMarkerTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: File

    @Test
    fun `option answer writes the ASK_USER_ANSWER marker`() = runBlocking {
        val vm = createVm()
        try {
            presetPendingQuestionnaire(vm)

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onAnswerAskUser("Layered").join() }
            }

            val state = vm.container.stateFlow.value
            assertNull(state.askUserRequest, "the questionnaire card must fold")
            val bubble = state.messages.lastOrNull { it.role == ChatRole.User }
            assertNotNull(bubble, "the answer must appear as a user bubble")
            assertEquals(true, bubble?.askUserBubble, "the answer bubble must carry the answer marker")
            assertEquals("Layered", bubble?.text)
        } finally {
            vm.close()
        }
    }

    @Test
    fun `cancel writes the ASK_USER_CANCEL marker on the Let AI decide bubble`() = runBlocking {
        val vm = createVm()
        try {
            presetPendingQuestionnaire(vm)

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onCancelAskUser().join() }
            }

            val state = vm.container.stateFlow.value
            assertNull(state.askUserRequest, "the questionnaire card must fold on cancel")
            val bubble = state.messages.lastOrNull { it.role == ChatRole.User }
            assertNotNull(bubble, "the cancel path must append a user bubble")
            assertEquals(true, bubble?.askUserBubble, "the cancel bubble must carry the cancel marker")
            assertEquals("Let AI decide", bubble?.text)
        } finally {
            vm.close()
        }
    }

    @Test
    fun `download confirm answer carries the marker with the resolved bubble text`() = runBlocking {
        val vm = createVm()
        try {
            presetPendingQuestionnaire(vm, downloadConfirm = true)

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onAnswerAskUser("yes").join() }
            }

            val state = vm.container.stateFlow.value
            val bubble = state.messages.lastOrNull { it.role == ChatRole.User }
            assertNotNull(bubble, "the answer must appear as a user bubble")
            assertEquals(true, bubble?.askUserBubble, "download confirm answers carry the marker too")
            assertEquals("Download confirmed", bubble?.text)
        } finally {
            vm.close()
        }
    }

    @Test
    fun `plain user bubbles default to no ask-user marker`() {
        assertEquals(false, ChatMessage(role = ChatRole.User, text = "hello").askUserBubble)
    }

    /** Presets a mid-generation state with a pending questionnaire card. */
    private suspend fun presetPendingQuestionnaire(vm: CreateViewModel, downloadConfirm: Boolean = false) {
        vm.container.orbit {
            reduce {
                state.copy(
                    running = true,
                    messages = state.messages + ChatMessage(role = ChatRole.User, text = "github.com/owner/repo"),
                    askUserRequest = AskUserRequest(
                        questions = listOf(
                            QuestionnaireQuestion(
                                id = if (downloadConfirm) "download_confirm" else "q_arch",
                                label = "Architecture",
                                prompt = if (downloadConfirm) "Download and inspect installer/archive at https://example.com/app.zip?" else "Which architecture should the manifest declare?",
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
            withTimeout(5_000) { while (!predicate(vm.container.stateFlow.value)) kotlinx.coroutines.delay(10) }
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
                configRepository = FakeMarkerConfigRepository(),
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
private class FakeMarkerConfigRepository : ConfigRepository() {
    private val config = scooper.data.UIConfig(
        aiProvider = "openai-compatible",
        aiApiKey = "test-key",
        aiBaseUrl = "http://127.0.0.1:9",
    )

    override fun getConfig(): scooper.data.UIConfig = config

    override fun setConfig(config: scooper.data.UIConfig) = Unit
}
