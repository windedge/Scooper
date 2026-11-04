@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
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
 * TDD for multi-tab Create sessions: tab lifecycle (create / switch / close)
 * and the Discover "Chat with AI" prefill seam.
 *
 * The controller owns one CreateViewModel per tab sharing the same
 * dependencies; the single default tab must behave exactly like the old
 * single-session CreateViewModel.
 */
class CreateViewModelTabTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: File

    /**
     * Test double that suspends inside [runGenerate] until [finishPendingRun]
     * completes its gate, so the real base-class startGenerate path (single
     * slot job cancel, scope launch, onFinished, CancellationException branch)
     * runs while only the network boundary is replaced.
     */
    private class RecordingBridge(
        configRepository: ConfigRepository,
        checkpointService: InMemoryAiCheckpointService,
    ) : ScoopAiBridge(
        configRepository = configRepository,
        checkpointService = checkpointService,
    ) {
        val startedUrls = java.util.Collections.synchronizedList(mutableListOf<String>())
        var cancelled = false
        var gate = CompletableDeferred<com.scoopai.core.ManifestResult>()

        /** Fresh gate so the next run suspends again instead of reusing the completed one. */
        fun resetGate() {
            gate = CompletableDeferred()
        }

        override suspend fun runGenerate(
            settings: scooper.service.AiSettingsSnapshot,
            url: String,
            userPrompt: String?,
            resumeData: com.scoopai.core.artifacts.ResumeData?,
            callbacks: ScoopAiBridge.GeneratorCallbacks,
            ignoreAppNames: Set<String>,
        ): com.scoopai.core.ManifestResult {
            startedUrls.add(url)
            try {
                return gate.await()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                cancelled = true
                throw e
            }
        }

        fun finishPendingRun(appName: String?) {
            gate.complete(
                com.scoopai.core.ManifestResult(
                    manifest = """{"version":"1.0.0"}""",
                    suggestedAppName = appName,
                    answered = false,
                )
            )
        }
    }

    private class FakeRepo : ConfigRepository() {
        private val config = scooper.data.UIConfig(
            aiProvider = "openai-compatible",
            aiApiKey = "k",
            aiBaseUrl = "http://127.0.0.1:9",
        )
        override fun getConfig() = config
        override fun setConfig(config: scooper.data.UIConfig) = Unit
    }

    /** Running flag of a tab's view model, reading through selectTab + activeViewModel. */
    private fun isRunning(controller: CreateTabController, tabId: String): Boolean {
        controller.selectTab(tabId)
        return controller.activeViewModel().container.stateFlow.value.running
    }

    private fun createController(): CreateTabController = CreateTabController(
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
        checkpointFactory = { InMemoryAiCheckpointService() },
        bridgeFactory = { cp -> RecordingBridge(FakeRepo(), cp) },
    )

    private suspend fun awaitTab(controller: CreateTabController, predicate: (CreateTabUiState) -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (!predicate(controller.uiState.value)) delay(10) }
        }
    }

    private suspend fun awaitPredicate(predicate: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (!predicate()) delay(10) }
        }
    }

    // ---------------------------------------------------------------
    // tab lifecycle
    // ---------------------------------------------------------------

    @Test
    fun `starts with one blank active tab`() = runBlocking {
        createController().use { controller ->
            val state = controller.uiState.value
            assertEquals(1, state.tabs.size)
            assertEquals(state.activeTabId, state.tabs.first().tabId)
            assertTrue(state.tabs.first().title == "New Chat")
            assertTrue(controller.activeViewModel().container.stateFlow.value.isEmpty)
        }
    }

    @Test
    fun `createTab adds a tab and makes it active`() = runBlocking {
        createController().use { controller ->
            val first = controller.activeTabId()
            controller.createTab()
            awaitTab(controller) { it.tabs.size == 2 && it.activeTabId != first }
            val second = controller.activeTabId()
            assertEquals(2, controller.uiState.value.tabs.size)
            assertTrue(controller.activeViewModel().container.stateFlow.value.isEmpty)
            assertTrue(controller.uiState.value.tabs.last().tabId == second)
        }
    }

    @Test
    fun `selectTab switches the active view model`() = runBlocking {
        createController().use { controller ->
            val first = controller.activeTabId()
            controller.createTab()
            awaitTab(controller) { it.tabs.size == 2 }

            controller.selectTab(first)
            awaitTab(controller) { it.activeTabId == first }
            assertEquals(first, controller.activeTabId())

            val second = controller.uiState.value.tabs.last().tabId
            controller.selectTab(second)
            awaitTab(controller) { it.activeTabId == second }
            assertEquals(second, controller.activeTabId())
        }
    }

    @Test
    fun `switching tabs gates toasts to the inactive view models`() = runBlocking {
        createController().use { controller ->
            val firstVm = controller.activeViewModel()
            assertFalse(firstVm.isBackgroundTab)
            assertTrue(firstVm.shouldPostToast())

            controller.createTab()
            awaitTab(controller) { it.tabs.size == 2 }
            assertTrue(firstVm.isBackgroundTab, "the tab left behind must render silently")
            assertFalse(firstVm.shouldPostToast())
            assertFalse(controller.activeViewModel().isBackgroundTab)

            val first = controller.uiState.value.tabs.first().tabId
            controller.selectTab(first)
            awaitTab(controller) { it.activeTabId == first }
            assertFalse(firstVm.isBackgroundTab, "returning to the tab must re-enable its toasts")
            assertTrue(firstVm.shouldPostToast())
        }
    }

    @Test
    fun `tab titles default to New Chat and follow suggested app name after send`() = runBlocking {
        createController().use { controller ->
            val first = controller.activeTabId()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.activeViewModel().onSend(url = "github.com/owner/ripgrep").join() }
            }
            awaitTab(controller) { it.tabs.first { t -> t.tabId == first }.title == "ripgrep" }
            assertEquals("ripgrep", controller.uiState.value.tabs.first { it.tabId == first }.title)
        }
    }

    // ---------------------------------------------------------------
    // close
    // ---------------------------------------------------------------

    @Test
    fun `closeTabRequest on a blank tab closes it directly`() = runBlocking {
        createController().use { controller ->
            controller.createTab()
            awaitTab(controller) { it.tabs.size == 2 }
            val second = controller.activeTabId()

            controller.closeTabRequest(second)
            awaitTab(controller) { it.tabs.size == 1 }
            assertEquals(1, controller.uiState.value.tabs.size)
        }
    }

    @Test
    fun `closeTabRequest on a tab with an unsaved manifest closes it directly`() = runBlocking {
        createController().use { controller ->
            val tabId = controller.activeTabId()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.activeViewModel().onSend(url = "github.com/owner/repo").join() }
            }
            awaitTab(controller) { it.tabs.first { t -> t.tabId == tabId }.guardClose }

            controller.closeTabRequest(tabId)
            awaitTab(controller) { it.tabs.none { t -> t.tabId == tabId } }
        }
    }

    @Test
    fun `closing the active tab selects the nearest neighbour`() = runBlocking {
        createController().use { controller ->
            val first = controller.activeTabId()
            controller.createTab()
            awaitTab(controller) { it.tabs.size == 2 }
            val second = controller.activeTabId()
            controller.selectTab(first)
            awaitTab(controller) { it.activeTabId == first }

            controller.closeTabRequest(first)
            awaitTab(controller) { it.tabs.size == 1 }
            assertEquals(second, controller.activeTabId(), "closing tab 0 must select the right neighbour")
        }
    }

    @Test
    fun `closing the last tab resets to a single blank tab`() = runBlocking {
        createController().use { controller ->
            val only = controller.activeTabId()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.activeViewModel().onSend(url = "github.com/owner/repo").join() }
            }
            awaitTab(controller) { it.tabs.first { t -> t.tabId == only }.guardClose }

            controller.closeTabRequest(only)
            awaitTab(controller) { it.tabs.size == 1 && it.tabs.first().tabId != only }

            val replacement = controller.activeTabId()
            assertTrue(controller.activeViewModel().container.stateFlow.value.isEmpty, "replacement tab must be blank")
            assertTrue(controller.uiState.value.tabs.first().title == "New Chat")
            assertTrue(replacement != only)
        }
    }

    @Test
    fun `closing an inactive busy tab keeps the active tab`() = runBlocking {
        createController().use { controller ->
            val first = controller.activeTabId()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.activeViewModel().onSend(url = "github.com/owner/repo").join() }
            }
            awaitTab(controller) { it.tabs.first { t -> t.tabId == first }.guardClose }

            controller.createTab()
            awaitTab(controller) { it.tabs.size == 2 }

            controller.closeTabRequest(first)
            awaitTab(controller) { it.tabs.size == 1 }
            assertTrue(controller.uiState.value.tabs.first().tabId != first)
        }
    }

    // ---------------------------------------------------------------
    // close: saved vs running tabs
    // ---------------------------------------------------------------

    @Test
    fun `closeTabRequest on a saved tab closes it directly`() = runBlocking {
        tempDir.resolve("buckets").resolve("main").mkdirs()
        createController().use { controller ->
            val tabId = controller.activeTabId()
            val vm = controller.activeViewModel()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend(url = "github.com/owner/repo").join() }
            }
            (controller.bridgeFor(tabId) as RecordingBridge).finishPendingRun("fd")
            awaitPredicate { vm.container.stateFlow.value.manifestJson.isNotBlank() }

            vm.onSave()
            awaitPredicate { vm.container.stateFlow.value.saveDialogOpen }
            vm.onSaveAppNameChange("fd")
            vm.onSaveBucketChange("main")
            withContext(Dispatchers.Default) { withTimeout(5_000) { vm.onConfirmSave().join() } }
            awaitPredicate { vm.container.stateFlow.value.manifestSaved }
            awaitTab(controller) { !it.tabs.first { t -> t.tabId == tabId }.guardClose }

            controller.closeTabRequest(tabId)
            awaitTab(controller) { it.tabs.none { t -> t.tabId == tabId } }
        }
    }

    @Test
    fun `closeTabRequest on a running tab closes it directly and cancels the run`() = runBlocking {
        tempDir.resolve("buckets").resolve("main").mkdirs()
        createController().use { controller ->
            val tabId = controller.activeTabId()
            val vm = controller.activeViewModel()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend(url = "github.com/owner/repo").join() }
            }
            (controller.bridgeFor(tabId) as RecordingBridge).finishPendingRun("fd")
            awaitPredicate { vm.container.stateFlow.value.manifestJson.isNotBlank() }

            vm.onSave()
            awaitPredicate { vm.container.stateFlow.value.saveDialogOpen }
            vm.onSaveAppNameChange("fd")
            vm.onSaveBucketChange("main")
            withContext(Dispatchers.Default) { withTimeout(5_000) { vm.onConfirmSave().join() } }
            awaitPredicate { vm.container.stateFlow.value.manifestSaved }

            // A new run starts on the saved tab: the tab is busy again.
            val bridge = controller.bridgeFor(tabId) as RecordingBridge
            bridge.resetGate()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.onSend(url = "github.com/owner/repo2").join() }
            }
            awaitTab(controller) { it.tabs.first { t -> t.tabId == tabId }.guardClose }
            awaitPredicate { bridge.startedUrls.size == 2 }

            controller.closeTabRequest(tabId)
            awaitTab(controller) { it.tabs.none { t -> t.tabId == tabId } }
            awaitPredicate { bridge.cancelled }
        }
    }

    // ---------------------------------------------------------------
    // per-tab isolation
    // ---------------------------------------------------------------

    @Test
    fun `sessions are isolated between tabs`() = runBlocking {
        createController().use { controller ->
            val first = controller.activeTabId()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.activeViewModel().onSend(url = "github.com/owner/tabOne").join() }
            }
            awaitPredicate { controller.activeViewModel().container.stateFlow.value.lastUrl == "https://github.com/owner/tabOne" }

            controller.createTab()
            awaitTab(controller) { it.tabs.size == 2 }
            val second = controller.activeTabId()
            assertTrue(controller.activeViewModel().container.stateFlow.value.messages.isEmpty())
            assertTrue(controller.activeViewModel().container.stateFlow.value.manifestJson.isBlank())

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.activeViewModel().onSend(url = "github.com/owner/tabTwo").join() }
            }
            awaitPredicate { controller.activeViewModel().container.stateFlow.value.lastUrl == "https://github.com/owner/tabTwo" }

            controller.selectTab(first)
            awaitTab(controller) { it.activeTabId == first }
            assertTrue(
                controller.activeViewModel().container.stateFlow.value.messages.any { it.text == "https://github.com/owner/tabOne" },
                "tab one keeps its own conversation",
            )
            assertEquals("https://github.com/owner/tabOne", controller.activeViewModel().container.stateFlow.value.lastUrl)
            controller.selectTab(second)
            awaitTab(controller) { it.activeTabId == second }
            assertEquals("https://github.com/owner/tabTwo", controller.activeViewModel().container.stateFlow.value.lastUrl)
        }
    }

    // ---------------------------------------------------------------
    // Discover prefill seam (batch 1: seam only, no UI button)
    // ---------------------------------------------------------------

    @Test
    fun `openModifySession reuses the blank active tab with prefilled composer`() = runBlocking {
        createController().use { controller ->
            controller.openModifySession(bucket = "main", name = "ripgrep")
            awaitTab(controller) { it.tabs.first().composerPrefill != null }
            // Trailing space terminates the @ token so the completion menu stays closed.
            assertEquals("@main/ripgrep ", controller.uiState.value.tabs.first().composerPrefill)
            assertEquals("ripgrep", controller.uiState.value.tabs.first().title)
            assertEquals(1, controller.uiState.value.tabs.size)
        }
    }

    @Test
    fun `openModifySession opens a new tab when the active one is used`() = runBlocking {
        createController().use { controller ->
            val first = controller.activeTabId()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.activeViewModel().onSend(url = "github.com/owner/repo").join() }
            }
            awaitTab(controller) { it.tabs.first { t -> t.tabId == first }.guardClose }

            controller.openModifySession(bucket = "extras", name = "sumatrapdf")
            awaitTab(controller) { it.tabs.size == 2 && it.activeTabId != first }
            val tab = controller.uiState.value.tabs.first { it.tabId != first }
            assertEquals("@extras/sumatrapdf ", tab.composerPrefill)
            assertEquals("sumatrapdf", tab.title, "handoff names the tab after the app right away")
        }
    }

    @Test
    fun `openModifySession seeds the blank tab with the referenced app name`() = runBlocking {
        createController().use { controller ->
            controller.openModifySession(bucket = "main", name = "ripgrep")
            awaitPredicate {
                controller.activeViewModel().container.stateFlow.value.suggestedAppName == "ripgrep"
            }
            assertEquals(1, controller.uiState.value.tabs.size, "blank tab must be reused, not duplicated")
        }
    }

    @Test
    fun `openModifySession seeds the referenced app name on a newly opened tab`() = runBlocking {
        createController().use { controller ->
            val first = controller.activeTabId()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.activeViewModel().onSend(url = "github.com/owner/repo").join() }
            }
            awaitTab(controller) { it.tabs.first { t -> t.tabId == first }.guardClose }

            controller.openModifySession(bucket = "extras", name = "sumatrapdf")
            awaitTab(controller) { it.tabs.size == 2 && it.activeTabId != first }
            awaitPredicate {
                controller.activeViewModel().container.stateFlow.value.suggestedAppName == "sumatrapdf"
            }
        }
    }

    @Test
    fun `consumeComposerPrefill clears the pending prefill`() = runBlocking {
        createController().use { controller ->
            controller.openModifySession(bucket = "main", name = "fd")
            awaitTab(controller) { it.tabs.first().composerPrefill != null }

            controller.consumeComposerPrefill(controller.activeTabId())
            awaitTab(controller) { it.tabs.first().composerPrefill == null }
        }
    }

    @Test
    fun `tab title falls back to the url tail`() = runBlocking {
        createController().use { controller ->
            val tabId = controller.activeTabId()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.activeViewModel().onSend(url = "github.com/owner/MyTool").join() }
            }
            awaitTab(controller) { it.tabs.first { t -> t.tabId == tabId }.title == "mytool" }
        }
    }

    // ---------------------------------------------------------------
    // per-tab bridge isolation (bug: shared single-slot bridge cancelled
    // tab A's engine job when tab B started its own run)
    // ---------------------------------------------------------------

    @Test
    fun `two tabs running concurrently do not cancel each other`() = runBlocking {
        createController().use { controller ->
            val tabA = controller.activeTabId()
            val bridgeA = controller.bridgeFor(tabA) as RecordingBridge
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.activeViewModel().onSend(url = "github.com/owner/alpha").join() }
            }
            awaitPredicate { bridgeA.startedUrls.isNotEmpty() && controller.uiState.value.tabs.first { it.tabId == tabA }.guardClose }
            assertTrue(bridgeA.isRunning, "tab A run must be in flight")

            controller.createTab()
            awaitTab(controller) { it.tabs.size == 2 }
            val tabB = controller.activeTabId()
            val bridgeB = controller.bridgeFor(tabB) as RecordingBridge
            assertTrue(bridgeA !== bridgeB, "each tab must get its own bridge instance")

            withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.activeViewModel().onSend(url = "github.com/owner/beta").join() }
            }
            awaitPredicate { bridgeB.startedUrls.isNotEmpty() }

            // Tab B finishing must not stop tab A.
            bridgeB.finishPendingRun("beta")
            awaitPredicate { !bridgeB.isRunning && !isRunning(controller, tabB) }
            assertTrue(bridgeA.isRunning, "tab A must still be running after tab B finished")
            assertFalse(bridgeA.cancelled, "tab A run must not be cancelled by tab B start")
            assertTrue(
                controller.uiState.value.tabs.first { it.tabId == tabA }.guardClose,
                "tab A session is still busy",
            )

            // Tab A still completes normally on its own gate.
            bridgeA.finishPendingRun("alpha")
            awaitPredicate { !bridgeA.isRunning && !isRunning(controller, tabA) }
            assertEquals(listOf("https://github.com/owner/alpha"), bridgeA.startedUrls.toList())
            assertEquals(listOf("https://github.com/owner/beta"), bridgeB.startedUrls.toList())
            assertEquals("alpha", controller.uiState.value.tabs.first { it.tabId == tabA }.title)
        }
    }

    @Test
    fun `cancelling one tab leaves the other tab run untouched`() = runBlocking {
        createController().use { controller ->
            val tabA = controller.activeTabId()
            val bridgeA = controller.bridgeFor(tabA) as RecordingBridge
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.activeViewModel().onSend(url = "github.com/owner/alpha").join() }
            }
            awaitPredicate { bridgeA.startedUrls.isNotEmpty() }

            controller.createTab()
            awaitTab(controller) { it.tabs.size == 2 }
            val tabB = controller.activeTabId()
            val bridgeB = controller.bridgeFor(tabB) as RecordingBridge
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.activeViewModel().onSend(url = "github.com/owner/beta").join() }
            }
            awaitPredicate { bridgeB.startedUrls.isNotEmpty() }

            controller.selectTab(tabB)
            awaitTab(controller) { it.activeTabId == tabB }

            controller.activeViewModel().onCancel().join()
            awaitPredicate { !bridgeB.isRunning && !isRunning(controller, tabB) }
            assertTrue(bridgeA.isRunning, "tab A must keep running when tab B is cancelled")
            assertFalse(bridgeA.cancelled, "tab A engine job must not be cancelled by tab B cancel")
        }
    }

    // ---------------------------------------------------------------
    // aggregated running flag (bug: the sidebar indicator only observed
    // the active tab, so a background tab run stopped blinking it)
    // ---------------------------------------------------------------

    @Test
    fun `anyRunning is true while a background tab run is in flight`() = runBlocking {
        createController().use { controller ->
            val tabA = controller.activeTabId()
            val bridgeA = controller.bridgeFor(tabA) as RecordingBridge
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.activeViewModel().onSend(url = "github.com/owner/alpha").join() }
            }
            awaitPredicate { bridgeA.isRunning }

            controller.createTab()
            awaitTab(controller) { it.tabs.size == 2 && it.activeTabId != tabA }

            awaitTab(controller) { it.anyRunning }
            assertTrue(bridgeA.isRunning, "precondition: tab A run must still be in flight")
        }
    }

    @Test
    fun `anyRunning is false when every tab is idle`() = runBlocking {
        createController().use { controller ->
            controller.createTab()
            awaitTab(controller) { it.tabs.size == 2 }
            assertFalse(controller.uiState.value.anyRunning, "fresh tabs must not blink the indicator")

            val tabA = controller.activeTabId()
            controller.selectTab(tabA)
            awaitTab(controller) { it.activeTabId == tabA }
            val bridgeA = controller.bridgeFor(tabA) as RecordingBridge
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.activeViewModel().onSend(url = "github.com/owner/alpha").join() }
            }
            awaitPredicate { bridgeA.isRunning }
            assertTrue(controller.uiState.value.anyRunning, "precondition: the active run must blink the indicator")

            bridgeA.finishPendingRun("alpha")
            awaitPredicate { !bridgeA.isRunning && !isRunning(controller, tabA) }
            awaitTab(controller) { !it.anyRunning }
            assertFalse(controller.uiState.value.anyRunning)
        }
    }
}
