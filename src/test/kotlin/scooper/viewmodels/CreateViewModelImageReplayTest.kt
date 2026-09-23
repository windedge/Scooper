@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.UIConfig
import scooper.repository.AppsRepository
import scooper.repository.ConfigRepository
import scooper.service.AiImage
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopClient
import scooper.service.ScoopLogStream
import com.scoopai.core.ManifestResult
import scooper.taskqueue.TaskQueue
import java.io.File

/**
 * TDD tests for image replay across assist rounds: a user who uploaded a screenshot
 * in round N and then sends a text-only follow-up in round N+1 ("fix the problem
 * above") must still have that screenshot delivered to the engine, because the
 * engine intentionally never replays images itself (images never enter AssistContext
 * or the checkpoint).
 */
class CreateViewModelImageReplayTest {

    @TempDir
    lateinit var tempDir: File

    private fun image(id: String): PendingImage =
        PendingImage(id = id, bytes = id.toByteArray(), mimeType = "image/png")

    private fun userMessage(text: String, images: List<PendingImage> = emptyList()): ChatMessage =
        ChatMessage(role = ChatRole.User, text = text, images = images)

    private fun idsOf(imgs: List<PendingImage>): List<String> = imgs.map { it.id }

    private fun bytesOf(imgs: List<AiImage>): List<String> = imgs.map { String(it.bytes) }

    // ---------------------------------------------------------------
    // resolveAssistImages: pure selection rule
    // ---------------------------------------------------------------

    @Test
    fun `keeps this round images and ignores history when a new image was attached`() {
        val fresh = listOf(image("fresh"))
        val history = listOf(userMessage("earlier", listOf(image("old"))))

        assertEquals(listOf("fresh"), idsOf(resolveAssistImages(fresh, history)))
    }

    @Test
    fun `reuses the previous round images when this round is text only`() {
        val old = listOf(image("screenshot-1"))
        val history = listOf(
            userMessage("here is the error", old),
            ChatMessage(role = ChatRole.Agent, text = "I can see the error."),
        )

        assertEquals(listOf("screenshot-1"), idsOf(resolveAssistImages(emptyList(), history)))
    }

    @Test
    fun `returns empty when no earlier user message carried an image`() {
        val history = listOf(
            userMessage("plain text"),
            ChatMessage(role = ChatRole.Agent, text = "ok"),
        )

        assertTrue(resolveAssistImages(emptyList(), history).isEmpty())
        assertTrue(resolveAssistImages(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `reuses the most recent image carrying user message`() {
        val history = listOf(
            userMessage("first error", listOf(image("oldest"))),
            ChatMessage(role = ChatRole.Agent, text = "seen"),
            userMessage("second error", listOf(image("newest"))),
        )

        assertEquals(listOf("newest"), idsOf(resolveAssistImages(emptyList(), history)))
    }

    @Test
    fun `stops looking back once the replay window is exhausted`() {
        val history = listOf(
            userMessage("round 1 error", listOf(image("round-1"))),
            ChatMessage(role = ChatRole.Agent, text = "a"),
            userMessage("round 2"),
            ChatMessage(role = ChatRole.Agent, text = "b"),
            userMessage("round 3"),
            ChatMessage(role = ChatRole.Agent, text = "c"),
            userMessage("round 4"),
        )

        assertTrue(
            resolveAssistImages(emptyList(), history).isEmpty(),
            "an image older than the replay window must not be re-sent",
        )
    }

    @Test
    fun `caps replayed images at MAX_PENDING_IMAGES`() {
        val many = (1..5).map { image("img-" + it) }
        val history = listOf(userMessage("many screenshots", many))

        assertEquals(
            listOf("img-1", "img-2", "img-3"),
            idsOf(resolveAssistImages(emptyList(), history)),
        )
    }

    @Test
    fun `skips a replayed message in favor of an older unplayed one`() {
        val history = listOf(
            userMessage("first error", listOf(image("older"))),
            ChatMessage(role = ChatRole.Agent, text = "seen"),
            ChatMessage(
                role = ChatRole.User,
                text = "second error",
                images = listOf(image("newest")),
                imagesReplayed = true,
            ),
        )

        assertEquals(listOf("older"), idsOf(resolveAssistImages(emptyList(), history)))
    }

    @Test
    fun `returns empty when every image carrying message was already replayed`() {
        val history = listOf(
            ChatMessage(
                role = ChatRole.User,
                text = "the error",
                images = listOf(image("shot")),
                imagesReplayed = true,
            ),
            ChatMessage(role = ChatRole.Agent, text = "seen"),
        )

        assertTrue(resolveAssistImages(emptyList(), history).isEmpty())
    }

    // ---------------------------------------------------------------
    // Real VM: round 2 keeps the round 1 screenshot (the reported bug)
    // ---------------------------------------------------------------

    @Test
    fun `text only follow-up in round 2 still sends the round 1 screenshot to the engine`(): Unit = runBlocking {
        val bridge = CapturingBridge(FakeConfigRepository())
        val vm = createViewModel(bridge)
        try {
            presetManifest(vm, MANIFEST)
            presetMessages(
                vm,
                listOf(
                    userMessage("what is this error?", listOf(image("dav1d-screenshot"))),
                    ChatMessage(role = ChatRole.Agent, text = "The dav1d.dll file is missing."),
                ),
            )

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend("solve the problem described above").join() }
            }
            awaitCaptured(bridge)

            assertEquals(1, bridge.captured.size, "one assist call expected, got: " + bridge.captured)
            assertEquals(
                listOf("dav1d-screenshot"),
                bytesOf(bridge.captured.single()),
                "round 2 must re-send the round 1 screenshot bytes",
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun `replayed images are not recorded on the new user bubble`(): Unit = runBlocking {
        val bridge = CapturingBridge(FakeConfigRepository())
        val vm = createViewModel(bridge)
        try {
            presetManifest(vm, MANIFEST)
            presetMessages(vm, listOf(userMessage("what is this error?", listOf(image("shot-1")))))

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend("fix it").join() }
            }
            awaitCaptured(bridge)
            awaitUntil { !vm.container.stateFlow.value.running }

            val lastUser = vm.container.stateFlow.value.messages.last { it.role == ChatRole.User }
            assertTrue(
                lastUser.images.isEmpty(),
                "a text-only follow-up must not render a replayed thumbnail as re-uploaded",
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun `second consecutive text only round no longer replays the images`(): Unit = runBlocking {
        val bridge = CapturingBridge(FakeConfigRepository())
        val vm = createViewModel(bridge)
        try {
            presetManifest(vm, MANIFEST)
            presetMessages(
                vm,
                listOf(
                    userMessage("what is this error?", listOf(image("shot-1"))),
                    ChatMessage(role = ChatRole.Agent, text = "The dav1d.dll file is missing."),
                ),
            )

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend("fix the error").join() }
                awaitUntil { !vm.container.stateFlow.value.running }
                withTimeout(5_000) { vm.onSend("also align the shortcuts").join() }
            }
            awaitUntil { bridge.captured.size >= 2 }

            assertEquals(2, bridge.captured.size, "two assist calls expected, got: " + bridge.captured)
            assertEquals(
                listOf("shot-1"),
                bytesOf(bridge.captured[0]),
                "round 2 replays the screenshot exactly once",
            )
            assertTrue(
                bridge.captured[1].isEmpty(),
                "round 3 must not re-send the already replayed images",
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun `the replay source message is flagged after the replay round`(): Unit = runBlocking {
        val bridge = CapturingBridge(FakeConfigRepository())
        val vm = createViewModel(bridge)
        try {
            presetManifest(vm, MANIFEST)
            presetMessages(vm, listOf(userMessage("what is this error?", listOf(image("shot-1")))))

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend("fix it").join() }
            }
            awaitCaptured(bridge)
            awaitUntil {
                vm.container.stateFlow.value.messages
                    .firstOrNull { it.images.isNotEmpty() }
                    ?.imagesReplayed == true
            }

            val source = vm.container.stateFlow.value.messages.first { it.images.isNotEmpty() }
            assertTrue(
                source.imagesReplayed,
                "the message whose images were just replayed must be flagged",
            )
        } finally {
            vm.close()
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private suspend fun awaitCaptured(bridge: CapturingBridge) {
        awaitUntil { bridge.captured.isNotEmpty() }
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            withContext(Dispatchers.Default) { delay(10) }
        }
    }

    private suspend fun presetMessages(vm: CreateViewModel, messages: List<ChatMessage>) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                vm.container.orbit { reduce { state.copy(messages = messages) } }
                while (vm.container.stateFlow.value.messages.size != messages.size) delay(10)
            }
        }
    }

    private suspend fun presetManifest(vm: CreateViewModel, manifestJson: String) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                vm.container.orbit {
                    reduce { state.copy(manifestJson = manifestJson, suggestedAppName = "testapp") }
                }
                while (vm.container.stateFlow.value.manifestJson != manifestJson) delay(10)
            }
        }
    }

    private fun createViewModel(bridge: ScoopAiBridge): CreateViewModel {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )
        return CreateViewModel(
            scoopAiBridge = bridge,
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = AppsRepository(
                scoopClient = scoopClient,
                gitHistoryService = GitHistoryService(),
            ),
            gitHistoryService = GitHistoryService(),
        )
    }

    /** Records the images handed to each assist call instead of dialing out. */
    private class CapturingBridge(configRepository: ConfigRepository) : ScoopAiBridge(
        configRepository = configRepository,
        checkpointService = InMemoryAiCheckpointService(),
    ) {
        val captured = mutableListOf<List<AiImage>>()

        override fun startAssist(
            currentManifest: String,
            instruction: String,
            history: List<String>,
            suggestedAppName: String?,
            overrideModel: String?,
            images: List<AiImage>,
            ignoreAppNames: Set<String>,
            onPhase: ((String) -> Unit)?,
            onToolActivity: ((com.scoopai.core.ToolActivity) -> Unit)?,
            onPhaseOutput: ((String, String) -> Unit)?,
            onAskUser: (suspend (String) -> String)?,
            onQuestionnaire: (suspend (List<com.scoopai.tools.interaction.Question>) -> String)?,
            onSaveToBucket: (suspend (bucket: String?, name: String?, overwrite: Boolean) -> String)?,
            onInstallApp: (suspend (bucket: String?, name: String) -> String)?,
            onReadManifestRef: (suspend (bucket: String, name: String) -> String)?,
            onFinished: (Result<com.scoopai.core.ManifestResult>) -> Unit,
        ) {
            captured.add(images)
            onFinished(Result.success(ManifestResult(manifest = currentManifest)))
        }
    }

    private class FakeConfigRepository : ConfigRepository() {
        private val config = UIConfig(
            aiProvider = "openai-compatible",
            aiApiKey = "test-key",
            aiBaseUrl = "http://127.0.0.1:9",
        )

        override fun getConfig(): UIConfig = config

        override fun setConfig(config: UIConfig) = Unit
    }

    private companion object {
        const val MANIFEST = "{\"version\":\"1.2.3\",\"bin\":[\"app.exe\"]}"
    }
}
