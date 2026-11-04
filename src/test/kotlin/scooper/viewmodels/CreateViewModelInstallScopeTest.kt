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
 * Tests for the reinstall scope helper ([reinstallGlobal]) plus VM wiring that
 * a save or bucket install must
 * leave [CreateState.deployment] truthful: the save refreshes with the name
 * actually used on disk, a bucket install derives Installed.
 *
 * The VM is built with a real [ScoopClient] rooted at a temp dir (no real
 * Scoop) plus fake repositories, following the CreateViewModelOpenAppTest
 * pattern. AI settings are complete but the base URL points at a refused
 * local port, so startAssistInternal passes the missing-settings gate
 * without doing real network work; the background assist job started
 * afterwards is never joined and the VM is closed right after the
 * assertions.
 */
class CreateViewModelInstallScopeTest {

    // Shared per-class temp root (method-parameter File injection needs extra
    // resolver machinery).
    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: java.io.File

    /** Junctions created by the installing shim; removed before JUnit cleanup. */
    private val junctions = mutableListOf<File>()

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

    /**
     * Stub scoop command that materialises the install it pretends to run, so
     * the post-install deployment refresh sees the app on disk. Guarded so
     * repeated scoop calls (pre-download, install) stay exit 0.
     */
    private suspend fun <T> withInstallingShim(block: suspend () -> T): T {
        val root = tempDir.absolutePath.replace("/", "\\")
        val dir = File(System.getProperty("java.io.tmpdir"), "scooper-shim-${System.nanoTime()}").apply { mkdirs() }
        val cmd = dir.resolve("scoop.cmd").apply {
            writeText(
                "@echo off\r\n" +
                    "if not exist \"$root\\apps\\testapp\\current\" (\r\n" +
                    "  mkdir \"$root\\apps\\testapp\\1.0\"\r\n" +
                    "  mklink /J \"$root\\apps\\testapp\\current\" \"$root\\apps\\testapp\\1.0\"\r\n" +
                    "  echo {\"bucket\":\"main\"}> \"$root\\apps\\testapp\\1.0\\scoop-install.json\"\r\n" +
                    ")\r\n" +
                    "exit /b 0\r\n",
            )
            deleteOnExit()
        }
        junctions += File(tempDir, "apps/testapp/current")
        System.setProperty(scooper.util.TEST_SCOOP_SHIM_ENV, cmd.absolutePath)
        return try {
            block()
        } finally {
            System.clearProperty(scooper.util.TEST_SCOOP_SHIM_ENV)
            dir.deleteRecursively()
        }
    }

    /** Removes install junctions: JUnit recurses into them when deleting. */
    private fun removeJunctions() {
        for (junction in junctions) {
            if (!junction.exists()) continue
            // rmdir unlinks a junction only; it never touches the target dir.
            ProcessBuilder("cmd", "/c", "rmdir", junction.absolutePath).start().waitFor()
        }
        junctions.clear()
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

    // ---------------------------------------------------------------------
    // Save and bucket install must keep the deployment state truthful
    // ---------------------------------------------------------------------

    @Test
    fun `performSave success refreshes deployment with the saved name`(): Unit = runBlocking {
        // Already installed on disk; the save must flip the deployment to
        // Installed even though the session carried no deployment fact.
        File(tempDir, "apps/testapp/current").mkdirs()
        File(tempDir, "buckets/main/bucket").mkdirs()
        val (vm, _) = createViewModelWithMemoryRepo()
        try {
            presetSaveDialogState(vm)

            withContext(Dispatchers.Default) {
                withTimeout(10_000) { vm.onConfirmSave().join() }
            }

            val state = vm.container.stateFlow.value
            assertTrue(state.manifestSaved, "save must succeed before the deployment is judged")
            assertEquals("testapp", state.saveResult?.appName)
            assertTrue(
                state.deployment is DeploymentState.Installed,
                "an installed app must derive Installed right after save, got: ${state.deployment}",
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun `executeInstallApp bucket success marks the deployment installed`(): Unit = runBlocking {
        try {
            withInstallingShim {
                // The bucket manifest exists so pre-download and the record
                // refresh find it.
                File(tempDir, "buckets/main/bucket").mkdirs()
                File(tempDir, "buckets/main/bucket/testapp.json")
                    .writeText("""{"version":"1.0","bin":["app.exe"]}""")
                val (vm, _) = createViewModelWithMemoryRepo()
                try {
                    presetSaveDialogState(vm, saveDialogOpen = false)

                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { vm.executeInstallApp(bucket = "main", name = "testapp").join() }
                    }

                    val state = vm.container.stateFlow.value
                    assertTrue(state.installedForUse, "bucket install success must mark installedForUse")
                    val deployment = state.deployment
                    assertTrue(
                        deployment is DeploymentState.Installed,
                        "bucket install success must derive Installed, got: $deployment",
                    )
                    deployment as DeploymentState.Installed
                    assertEquals("1.0", deployment.installedVersion)
                    assertEquals("main", deployment.originBucket)
                } finally {
                    vm.close()
                }
            }
        } finally {
            removeJunctions()
        }
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

    private fun createViewModel(
        rootDir: File,
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
                                manifestJson = """{"version":"1.0","homepage":"https://example.com"}""",
                                suggestedAppName = "testapp",
                                manifestSaved = true,
                                saveResult = SaveResult(
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

    /**
     * Like [createViewModel] but with the throwaway sqlite repository so the
     * save path's indexSavedApp writes never touch a real database.
     */
    private fun createViewModelWithMemoryRepo(): Pair<CreateViewModel, ScoopClient> {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )
        val vm = CreateViewModel(
            scoopAiBridge = ScoopAiBridge(
                configRepository = FakeConfigRepository(),
                checkpointService = InMemoryAiCheckpointService(),
            ),
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = scooper.service.createInMemoryAppsRepository(scoopClient),
            gitHistoryService = GitHistoryService(),
        )
        return vm to scoopClient
    }

    /** Presets the inputs performSave reads. */
    private fun presetSaveDialogState(
        vm: CreateViewModel,
        saveDialogOpen: Boolean = true,
    ) {
        runBlocking {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.container.orbit {
                        reduce {
                            state.copy(
                                manifestJson = """{"version":"1.0","homepage":"https://example.com"}""",
                                suggestedAppName = "testapp",
                                manifestSaved = false,
                                saveResult = null,
                                saveDialogOpen = saveDialogOpen,
                                saveAppName = "testapp",
                                saveBucket = "main",
                                saveCommitToGit = false,
                                overwriteConfirmOpen = false,
                            )
                        }
                    }
                }
            }
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
