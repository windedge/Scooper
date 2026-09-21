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
import scooper.repository.AppsRepository
import scooper.repository.ConfigRepository
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopClient
import scooper.service.ScoopLogStream
import scooper.taskqueue.TaskQueue
import java.io.File
import scooper.service.AiErrorAction
import scooper.viewmodels.CreateViewModel.Companion.MAX_PENDING_IMAGES
import scooper.viewmodels.CreateViewModel.Companion.PURE_IMAGE_INSTRUCTION

/**
 * Unit tests for [PendingImage] state management: attach cap, remove, send integration,
 * pure-image send (no isBlank early return), history placeholder text.
 */
class CreateViewModelPendingImageTest {

    @TempDir
    lateinit var tempDir: File

    private fun pngImage(id: String = "img-1"): PendingImage =
        PendingImage(id = id, bytes = byteArrayOf(1, 2, 3), mimeType = "image/png")

    private fun jpegImage(id: String = "img-2"): PendingImage =
        PendingImage(id = id, bytes = byteArrayOf(4, 5, 6), mimeType = "image/jpeg")

    // ---------------------------------------------------------------
    // PendingImage equals/hashCode: id-based, not bytes-based
    // ---------------------------------------------------------------

    @Test
    fun `PendingImage equals is id-based so different bytes with same id are equal`() {
        val a = PendingImage(id = "same", bytes = byteArrayOf(1), mimeType = "image/png")
        val b = PendingImage(id = "same", bytes = byteArrayOf(9, 9), mimeType = "image/jpeg")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `PendingImage with different ids are not equal even with same bytes`() {
        val a = PendingImage(id = "a", bytes = byteArrayOf(1, 2, 3), mimeType = "image/png")
        val b = PendingImage(id = "b", bytes = byteArrayOf(1, 2, 3), mimeType = "image/png")
        assertFalse(a == b)
    }

    // ---------------------------------------------------------------
    // Real VM: attach / remove
    // ---------------------------------------------------------------

    @Test
    fun `attachImages caps at MAX_PENDING_IMAGES and drops excess`() = runBlocking {
        val vm = createViewModel()
        withTimeout(5_000) { vm.attachImages((1..5).map { pngImage("img-" + it) }).join() }
        val pending = vm.container.stateFlow.value.pendingImages
        assertEquals(3, pending.size)
        assertEquals(listOf("img-1", "img-2", "img-3"), pending.map { it.id })
        vm.close()
    }

    @Test
    fun `attachImages ignores duplicate ids`() = runBlocking {
        val vm = createViewModel()
        withTimeout(5_000) {
            vm.attachImages(listOf(pngImage("img-1"))).join()
            vm.attachImages(listOf(pngImage("img-1"), jpegImage("img-2"))).join()
        }
        val pending = vm.container.stateFlow.value.pendingImages
        assertEquals(listOf("img-1", "img-2"), pending.map { it.id })
        vm.close()
    }

    @Test
    fun `removePendingImage drops the matching id`() = runBlocking {
        val vm = createViewModel()
        withTimeout(5_000) {
            vm.attachImages(listOf(pngImage("a"), jpegImage("b"), pngImage("c"))).join()
            vm.removePendingImage("b").join()
        }
        val pending = vm.container.stateFlow.value.pendingImages
        assertEquals(listOf("a", "c"), pending.map { it.id })
        vm.close()
    }

    // ---------------------------------------------------------------
    // Real VM: pure-image send
    // ---------------------------------------------------------------

    @Test
    fun `onSend with manifest folds pendingImages into one user message and clears pending state`() = runBlocking {
        val vm = createViewModel()
        presetManifest(vm, "{\"version\":\"1.2.3\",\"bin\":[\"app.exe\"]}")
        withTimeout(5_000) { vm.attachImages(listOf(pngImage("a"), jpegImage("b"))).join() }

        withContext(Dispatchers.Default) {
            withTimeout(5_000) { vm.onSend("").join() }
        }

        val state = vm.container.stateFlow.value
        assertTrue(state.pendingImages.isEmpty(), "pendingImages must be cleared after send")
        val userMessages = state.messages.filter { it.role == ChatRole.User }
        assertEquals(1, userMessages.size, "exactly one user bubble expected, got: " + state.messages)
        assertEquals(PURE_IMAGE_INSTRUCTION, userMessages.single().text)
        assertEquals(listOf("a", "b"), userMessages.single().images.map { it.id })
        vm.close()
    }

    @Test
    fun `onSend without manifest keeps pendingImages and adds no message`() = runBlocking {
        val vm = createViewModel()
        withTimeout(5_000) { vm.attachImages(listOf(pngImage("a"))).join() }

        withContext(Dispatchers.Default) {
            withTimeout(5_000) { vm.onSend("").join() }
        }

        val state = vm.container.stateFlow.value
        assertEquals(1, state.pendingImages.size, "images must be kept when there is no manifest yet")
        assertTrue(state.messages.isEmpty(), "no user bubble expected without a manifest")
        vm.close()
    }

    // ---------------------------------------------------------------
    // Real VM: reset
    // ---------------------------------------------------------------

    @Test
    fun `onReset clears pendingImages`() = runBlocking {
        val vm = createViewModel()
        withTimeout(5_000) {
            vm.attachImages(listOf(pngImage("a"), jpegImage("b"))).join()
            vm.onReset().join()
        }
        assertTrue(vm.container.stateFlow.value.pendingImages.isEmpty())
        vm.close()
    }

    // ---------------------------------------------------------------
    // Helpers: real VM setup
    // ---------------------------------------------------------------

    /**
     * Complete AI settings so startAssistInternal passes the settings gate;
     * the refused port keeps the fire-and-forget assist job harmless.
     */
    private class FakeConfigRepository : ConfigRepository() {
        private val config = UIConfig(
            aiProvider = "openai-compatible",
            aiApiKey = "test-key",
            aiBaseUrl = "http://127.0.0.1:9",
        )

        override fun getConfig(): UIConfig = config

        override fun setConfig(config: UIConfig) = Unit
    }

    private fun createViewModel(): CreateViewModel {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )
        return CreateViewModel(
            scoopAiBridge = ScoopAiBridge(
                configRepository = FakeConfigRepository(),
                checkpointService = InMemoryAiCheckpointService(),
            ),
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = AppsRepository(
                scoopClient = scoopClient,
                gitHistoryService = GitHistoryService(),
            ),
            gitHistoryService = GitHistoryService(),
        )
    }

    /** Presets a manifest so assist-mode sending is possible, then waits for it to land. */
    private suspend fun presetManifest(vm: CreateViewModel, manifestJson: String) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                vm.container.orbit {
                    reduce {
                        state.copy(manifestJson = manifestJson, suggestedAppName = "testapp")
                    }
                }
                while (vm.container.stateFlow.value.manifestJson != manifestJson) delay(10)
            }
        }
    }

    /** Simulates a direct install in progress. */
    private suspend fun presetInstalling(vm: CreateViewModel) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                vm.container.orbit {
                    reduce { state.copy(installing = true) }
                }
                while (!vm.container.stateFlow.value.installing) delay(10)
            }
        }
    }

    // ---------------------------------------------------------------
    // onSend guard: an in-progress install must block sending
    // ---------------------------------------------------------------

    @Test
    fun `onSend while installing toasts and adds no message`() = runBlocking {
        val vm = createViewModel()
        presetManifest(vm, "{\"version\":\"1.2.3\",\"bin\":[\"app.exe\"]}")
        presetInstalling(vm)

        val toasts = mutableListOf<AppSideEffect.Toast>()
        val collector = CoroutineScope(Dispatchers.Default).launch {
            vm.container.sideEffectFlow.collect { effect ->
                if (effect is AppSideEffect.Toast) toasts.add(effect)
            }
        }
        withContext(Dispatchers.Default) { delay(100) }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend("please install it").join() }
            }
            withContext(Dispatchers.Default) { delay(200) }
        } finally {
            collector.cancel()
        }

        assertTrue(
            toasts.any { it.type == ToastType.INFO && it.text.contains("Another operation is running") },
            "an in-progress install must block sending with the busy toast, got: $toasts",
        )
        assertTrue(
            vm.container.stateFlow.value.messages.isEmpty(),
            "no user bubble may appear while an install is running",
        )
        vm.close()
    }
}
