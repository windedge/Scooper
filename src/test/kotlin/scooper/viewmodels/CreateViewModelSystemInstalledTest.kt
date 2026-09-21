@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
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
import scooper.service.InstalledAppInfo
import scooper.repository.AppsRepository
import scooper.repository.ConfigRepository
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopClient
import scooper.service.ScoopEvent
import scooper.service.ScoopLogStream
import scooper.service.ScoopService
import scooper.taskqueue.TaskQueue
import java.io.File

/**
 * Tests for the systemInstalled lifecycle in [CreateViewModel.startAssist]:
 * before contacting the AI bridge, the intent re-snapshots installation from
 * the local Scoop root, so an already installed app keeps the fact and a
 * stale flag is cleared when nothing is installed.
 *
 * The VM is built with a real [ScoopClient] rooted at a temp dir (no real
 * Scoop) plus fake repositories, following the CreateViewModelOpenAppTest
 * pattern. AI settings are complete but the base URL points at a refused
 * local port, so startAssistInternal passes the missing-settings gate and the
 * systemInstalled reduce without doing real network work; the background
 * assist job started afterwards is never joined and the VM is closed right
 * after the assertions.
 */
class CreateViewModelSystemInstalledTest {

    // Shared per-class temp root (method-parameter File injection needs extra
    // resolver machinery).
    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: java.io.File

    /** Same stub approach as CreateViewModelInstallEventTest: the CLI never runs. */
    private suspend fun <T> withScoopShim(block: suspend () -> T): T {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "scooper-shim-${System.nanoTime()}").apply { mkdirs() }
        val cmd = dir.resolve("scoop.cmd").apply {
            writeText("@echo off\r\necho Installing\r\nexit /b 0\r\n")
            deleteOnExit()
        }
        System.setProperty(scooper.util.TEST_SCOOP_SHIM_ENV, cmd.absolutePath)
        return try {
            block()
        } finally {
            System.clearProperty(scooper.util.TEST_SCOOP_SHIM_ENV)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `assist start preserves the system installed fact`(@TempDir tempDir: File) = runBlocking {
        // A "current" dir under root/apps/testapp makes installedSnapshot report testapp.
        File(tempDir, "apps/testapp/current").mkdirs()
        val vm = createViewModel(tempDir)
        awaitPreset(vm)

        withContext(Dispatchers.Default) {
            withTimeout(10_000) { vm.startAssist("fix it").join() }
        }

        val state = vm.container.stateFlow.value
        assertTrue(state.systemInstalled, "testapp has a current dir, so the fact must survive")
        vm.close()
    }

    @Test
    fun `assist start clears the flag when app is not installed`(@TempDir tempDir: File) = runBlocking {
        // Nothing installed under the empty root; preset a stale flag.
        val vm = createViewModel(tempDir, presetSystemInstalled = true)
        awaitPreset(vm)

        withContext(Dispatchers.Default) {
            withTimeout(10_000) { vm.startAssist("fix it").join() }
        }

        val state = vm.container.stateFlow.value
        assertFalse(state.systemInstalled, "stale flag must be recomputed to false when nothing is installed")
        vm.close()
    }

    // ---------------------------------------------------------------------
    // reinstallGlobal: reinstall must respect how the app was installed
    // ---------------------------------------------------------------------

    @Test
    fun `reinstallGlobal is false for a locally installed app`() {
        val snapshot = mapOf(
            "testapp" to installedInfo(isGlobal = false),
        )
        assertFalse(reinstallGlobal(snapshot, "testapp"))
    }

    @Test
    fun `reinstallGlobal is true for a globally installed app`() {
        val snapshot = mapOf(
            "testapp" to installedInfo(isGlobal = true),
        )
        assertTrue(reinstallGlobal(snapshot, "testapp"))
    }

    @Test
    fun `reinstallGlobal is false when the app is missing from the snapshot`() {
        val snapshot = mapOf(
            "otherapp" to installedInfo(isGlobal = true),
        )
        assertFalse(reinstallGlobal(snapshot, "testapp"))
    }

    // -----------------------------------------------------------------
    // directInstallScope: install side must match the reinstall side
    // -----------------------------------------------------------------

    @Test
    fun `directInstallScope is true only when the snapshot reports global`() {
        assertTrue(
            directInstallScope(mapOf("testapp" to installedInfo(isGlobal = true)), "testapp"),
            "a globally installed app must reinstall global on the install side too",
        )
        assertFalse(
            directInstallScope(mapOf("testapp" to installedInfo(isGlobal = false)), "testapp"),
            "a locally installed app must reinstall local",
        )
        assertFalse(
            directInstallScope(emptyMap(), "testapp"),
            "an app missing from the snapshot installs local",
        )
    }

    @Test
    fun `directInstallScope agrees with reinstallGlobal for every snapshot shape`() {
        val snapshots = listOf(
            emptyMap(),
            mapOf("testapp" to installedInfo(isGlobal = true)),
            mapOf("testapp" to installedInfo(isGlobal = false)),
            mapOf("TESTAPP" to installedInfo(isGlobal = true)),
            mapOf("otherapp" to installedInfo(isGlobal = true)),
        )
        for (snapshot in snapshots) {
            assertEquals(
                reinstallGlobal(snapshot, "testapp"),
                directInstallScope(snapshot, "testapp"),
                "uninstall and install scope must agree for snapshot: $snapshot",
            )
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun installedInfo(isGlobal: Boolean): InstalledAppInfo = InstalledAppInfo(
        name = "testapp",
        version = "1.0",
        isGlobal = isGlobal,
        appDir = File(tempRoot, "apps/testapp"),
    )

    /** Shared throwaway root for info objects that never touch the filesystem. */
    private val tempRoot get() = File(System.getProperty("java.io.tmpdir"))

    /** Waits until the orbit preset (saveResult etc.) has landed in the state. */
    private suspend fun awaitPreset(vm: CreateViewModel) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (vm.container.stateFlow.value.saveResult == null) delay(10)
            }
        }
    }

    private fun createViewModel(
        rootDir: File,
        presetSystemInstalled: Boolean = false,
        scoopService: ScoopService? = null,
    ): CreateViewModel {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = rootDir,
        )
        val vm = CreateViewModel(
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
            scoopService = scoopService,
        )
        runBlocking {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.container.orbit {
                        reduce {
                            state.copy(
                                systemInstalled = presetSystemInstalled,
                                manifestJson = """{"version":"1.0","homepage":"https://example.com"}""",
                                suggestedAppName = "testapp",
                                manifestSaved = true,
                                saveResult = SaveResult(
                                    success = true,
                                    appName = "testapp",
                                    bucket = "main",
                                    targetPath = "C:/bucket/main/testapp.json",
                                    overwritten = false,
                                    indexedOk = true,
                                ),
                            )
                        }
                    }
                    // orbit blocks are async; wait until the preset lands.
                    while (vm.container.stateFlow.value.saveResult == null) delay(10)
                }
            }
        }
        return vm
    }

    // ---------------------------------------------------------------------
    // Install event wiring through the injected ScoopService
    // ---------------------------------------------------------------------

    @Test
    fun `onInstallSaved publishes AppInstalled through the injected service`(): Unit = runBlocking {
        withScoopShim {
        // The refresh reads the bucket manifest after install; without it the
        // record is skipped and no event is published.
        java.io.File(tempDir, "buckets/main/bucket").mkdirs()
        java.io.File(tempDir, "buckets/main/bucket/testapp.json")
            .writeText("""{"version":"1.2.3","bin":["app.exe"]}""")
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )
        val repository = scooper.service.createInMemoryAppsRepository(scoopClient)
        val service = ScoopService(scoopClient, repository)
        val vm = createViewModel(tempDir, scoopService = service)
        var received: ScoopEvent? = null
        // Handshake: wait for the collector to actually subscribe before the
        // install runs, otherwise the event lands with no subscriber around.
        val ready = CompletableDeferred<Unit>()
        // first{} returns as soon as the event arrives, so the collector
        // coroutine finishes by itself instead of hanging forever.
        val job = launch(Dispatchers.Default) {
            received = service.events
                .onSubscription { ready.complete(Unit) }
                .first { it is ScoopEvent.AppInstalled }
        }
        ready.await()

        withContext(Dispatchers.Default) {
            withTimeout(10_000) { vm.onInstallSaved().join() }
        }
        withTimeout(10_000) { job.join() }
        vm.close()

        val event = received
        assertTrue(event is ScoopEvent.AppInstalled, "expected AppInstalled, got: $event")
        assertEquals("testapp", (event as ScoopEvent.AppInstalled).appName)
        }
    }

    /**
     * Complete AI settings so startAssistInternal passes the missing-settings
     * gate; the refused port keeps the fire-and-forget assist job harmless.
     */
    private class FakeConfigRepository : ConfigRepository() {
        private var config = UIConfig(
            aiProvider = "openai-compatible",
            aiApiKey = "test-key",
            aiBaseUrl = "http://127.0.0.1:9",
        )

        override fun getConfig(): UIConfig = config

        override fun setConfig(config: UIConfig) {
            this.config = config
        }
    }
}
