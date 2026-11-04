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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.data.AppStatus
import scooper.repository.AppsRepository
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
 * TDD tests for the install event wiring in [CreateViewModel]: the manual
 * install path (onInstallSaved) refreshes the index through
 * refreshInstalledAppRecordCore, which notifies the injected [ScoopService]
 * so every screen receives AppInstalled. Without the service the VM still
 * works, it just stays silent, matching the old behavior.
 *
 * The manifest is written under a temp Scoop root so
 * refreshInstalledAppRecordCore really parses and upserts the app.
 */
class CreateViewModelInstallEventTest {

    // Shared per-class temp root (method-parameter File injection needs extra
    // resolver machinery).
    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: java.io.File

    // ---------------------------------------------------------------------
    // Test-only scoop shim: "scoop install/update" resolves to a stub, so the
    // CLI never hangs or touches the real Scoop root.
    // ---------------------------------------------------------------------

    private suspend fun <T> withScoopShim(block: suspend () -> T): T {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "scooper-shim-${System.nanoTime()}").apply { mkdirs() }
        val cmd = dir.resolve("scoop.cmd").apply {
            writeText("@echo off\r\necho Installing\r\nexit /b 0\r\n")
            deleteOnExit()
        }
        // Fake URL + pre-created cache file: the downloader sees an "already
        // cached" hit and never touches the network.
        val manifest = tempDir.resolve("buckets/main/bucket/testapp.json")
        val version = "1.2.3"
        val fakeUrl = "http://127.0.0.1:9/testapp-$version.zip"
        val client = scooper.service.ScoopClient(
            logStream = scooper.service.ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )
        val cacheName = client.cacheFileNameForTest("testapp", version, fakeUrl)
        client.cacheDir.resolve(cacheName).apply { parentFile?.mkdirs() }.writeText("fake")
        manifest.parentFile?.mkdirs()
        manifest.writeText(
            """{"version":"$version","url":"$fakeUrl"}"""
        )
        System.setProperty(scooper.util.TEST_SCOOP_SHIM_ENV, cmd.absolutePath)
        return try {
            block()
        } finally {
            System.clearProperty(scooper.util.TEST_SCOOP_SHIM_ENV)
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------
    // Event wiring through the injected service
    // ---------------------------------------------------------------------

    @Test
    fun `onInstallSaved notifies the service so AppInstalled is published`(): Unit = runBlocking {
        withScoopShim {
        val (vm, service, repository) = createVmWithService(tempDir)
        writeInstalledManifest(tempDir)
        awaitPreset(vm)
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
        drainContainer(vm)
        vm.close()

        assertEquals(ScoopEvent.AppInstalled("testapp"), received)
        val stored = repository.getApps(query = "testapp").value.first { it.name == "testapp" }
        assertEquals(AppStatus.INSTALLED, stored.status)
        }
    }

    // ---------------------------------------------------------------------
    // Backward compatibility: no service means no crash, index still updates
    // ---------------------------------------------------------------------

    @Test
    fun `onInstallSaved without a service still upserts the index record`(): Unit = runBlocking {
        withScoopShim {
        val (vm, repository) = createVmWithoutService(tempDir)
        writeInstalledManifest(tempDir)
        awaitPreset(vm)

        withContext(Dispatchers.Default) {
            withTimeout(10_000) { vm.onInstallSaved().join() }
        }
        drainContainer(vm)
        vm.close()

        val stored = repository.getApps(query = "testapp").value.first { it.name == "testapp" }
        assertEquals(AppStatus.INSTALLED, stored.status)
        }
    }

    // ---------------------------------------------------------------------
    // refreshInstalledAppRecordCore invokes the onInstalled hook
    // ---------------------------------------------------------------------

    @Test
    fun `onInstalled hook receives the app after the index upsert`() = runBlocking {
        val bucketDir = tempRoot.resolve("main/bucket")
        bucketDir.mkdirs()
        bucketDir.resolve("testapp.json").writeText("""{"version":"1.2.3","bin":["app.exe"]}""")
        val env = InstallEventFakeScoopEnv(tempRoot, buckets = listOf("main"))
        env.markInstalled("testapp")
        val notified = mutableListOf<scooper.data.App>()

        refreshInstalledAppRecordCore(
            "main",
            "testapp",
            env,
            { },
            {},
        ) { app -> notified.add(app) }

        assertEquals(1, notified.size)
        assertEquals("testapp", notified[0].name)
        assertEquals(AppStatus.INSTALLED, notified[0].status)
    }

    @Test
    fun `onInstalled hook is skipped when the manifest is missing`() = runBlocking {
        val env = InstallEventFakeScoopEnv(tempRoot, buckets = listOf("main"))
        env.markInstalled("testapp")
        val notified = mutableListOf<scooper.data.App>()

        refreshInstalledAppRecordCore("main", "testapp", env, { }, {}, { app -> notified.add(app) })

        assertTrue(notified.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Shared throwaway root; one instance per test method so writes and the
     * fake environment always agree on the same directory.
     */
    private val tempRoot by lazy {
        File(System.getProperty("java.io.tmpdir"), "scooper-install-event-${System.nanoTime()}")
    }

    /** Writes the manifest plus a "current" dir so the refresh sees INSTALLED. */
    private fun writeInstalledManifest(root: File) {
        val bucketDir = File(root, "buckets/main/bucket")
        bucketDir.mkdirs()
        // architecture keeps the manifest installable without a download (the
        // uninstall-then-install pre-check needs url or architecture).
        bucketDir.resolve("testapp.json")
            .writeText("""{"version":"1.2.3","architecture":{"64bit":{"bin":["app.exe"]}}}""")
        File(root, "apps/testapp/current").mkdirs()
    }

    /**
     * Fake scoop environment backed by a temp layout:
     * baseDir/<bucket>/bucket/<name>.json with a markable installed snapshot.
     */
    private class InstallEventFakeScoopEnv(
        private val baseDir: File,
        private val buckets: List<String>,
    ) : scooper.service.BucketSaveEnv {
        override val bucketsBaseDir: File get() = baseDir
        override val bucketNames: List<String> get() = buckets

        private val installedNames = mutableSetOf<String>()

        fun markInstalled(name: String) {
            installedNames.add(name.lowercase())
        }

        override fun installedSnapshot(): Map<String, scooper.service.InstalledAppInfo> =
            installedNames.associateWith {
                scooper.service.InstalledAppInfo(
                    name = it,
                    version = "1.2.3",
                    isGlobal = false,
                    appDir = baseDir,
                )
            }

        override fun buildAppsFromManifestNames(
            bucketDir: File,
            manifestNames: Set<String>,
            bucket: scooper.data.Bucket,
            installed: Map<String, scooper.service.InstalledAppInfo>,
        ): List<scooper.data.App> = manifestNames.mapNotNull { fileName ->
            val file = bucketDir.resolve("bucket/$fileName")
            if (!file.exists()) return@mapNotNull null
            val appName = fileName.removeSuffix(".json")
            if (installed.containsKey(appName.lowercase())) {
                scooper.data.App(
                    name = appName,
                    latestVersion = "1.2.3",
                    version = "1.2.3",
                    status = AppStatus.INSTALLED,
                    bucket = scooper.data.Bucket(bucket.name),
                )
            } else {
                scooper.data.App(name = appName, latestVersion = "1.2.3", bucket = scooper.data.Bucket(bucket.name))
            }
        }
    }

    /** Presets a saved, ready-to-install state so onInstallSaved has work to do. */
    private fun presetSavedState(vm: CreateViewModel) {
        runBlocking {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.container.orbit {
                        reduce {
                            state.copy(
                                manifestJson = """{"version":"1.2.3","bin":["app.exe"]}""",
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
                    while (vm.container.stateFlow.value.saveResult == null) delay(10)
                }
            }
        }
    }

    /** Waits until the orbit preset (saveResult etc.) has landed in the state. */
    private suspend fun awaitPreset(vm: CreateViewModel) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (vm.container.stateFlow.value.saveResult == null) delay(10)
            }
        }
    }

    /** Drains fire-and-forget side effect launches before the VM is closed. */
    private suspend fun drainContainer(vm: CreateViewModel) {
        withContext(Dispatchers.Default) { delay(300) }
    }

    private fun createVmWithService(rootDir: File): Triple<CreateViewModel, ScoopService, AppsRepository> {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = rootDir,
        )
        val repository = scooper.service.createInMemoryAppsRepository(scoopClient)
        val service = ScoopService(scoopClient, repository)
        val vm = CreateViewModel(
            scoopAiBridge = ScoopAiBridge(
                configRepository = FakeInstallEventConfigRepository(),
                checkpointService = InMemoryAiCheckpointService(),
            ),
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = repository,
            gitHistoryService = GitHistoryService(),
            scoopService = service,
        )
        presetSavedState(vm)
        return Triple(vm, service, repository)
    }

    private fun createVmWithoutService(rootDir: File): Pair<CreateViewModel, AppsRepository> {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = rootDir,
        )
        val repository = scooper.service.createInMemoryAppsRepository(scoopClient)
        val vm = CreateViewModel(
            scoopAiBridge = ScoopAiBridge(
                configRepository = FakeInstallEventConfigRepository(),
                checkpointService = InMemoryAiCheckpointService(),
            ),
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = repository,
            gitHistoryService = GitHistoryService(),
        )
        presetSavedState(vm)
        return vm to repository
    }
}

/** Minimal AI config so guards never block; nothing here dials out. */
private class FakeInstallEventConfigRepository : scooper.repository.ConfigRepository() {
    private val config = scooper.data.UIConfig(
        aiProvider = "openai-compatible",
        aiApiKey = "test-key",
        aiBaseUrl = "http://127.0.0.1:9",
    )

    override fun getConfig(): scooper.data.UIConfig = config

    override fun setConfig(config: scooper.data.UIConfig) = Unit
}
