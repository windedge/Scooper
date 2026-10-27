@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
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
import scooper.data.AppStatus
import scooper.repository.ConfigRepository
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopClient
import scooper.service.ScoopLogStream
import scooper.service.ScoopService
import scooper.taskqueue.TaskQueue
import java.io.File
import java.io.IOException

/**
 * TDD tests for the direct install branch of [CreateViewModel.onInstallSaved]:
 * an unsaved manifest installs straight from a local file instead of being
 * blocked behind Save to Bucket. The shim stubs every scoop command with
 * exit 0, and the VM-level assertions cover state, toasts, index records and
 * the temp manifest lifecycle; command assembly is asserted in
 * ScoopClientInstallFromManifestTest.
 */
class CreateViewModelDirectInstallTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: File

    /** Stub every scoop command with exit 0; the shim does not record args. */
    private suspend fun <T> withScoopShim(block: suspend () -> T): T {
        val dir = File(System.getProperty("java.io.tmpdir"), "scooper-shim-${System.nanoTime()}").apply { mkdirs() }
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

    // ---------------------------------------------------------------------
    // Unsaved manifest installs from the local file
    // ---------------------------------------------------------------------

    @Test
    fun `onInstallSaved with an unsaved manifest installs locally and marks installed`(): Unit = runBlocking {
        withScoopShim {
            val vm = createVm()
            try {
                presetUnsavedState(vm, suggestedAppName = "testapp")

                val toasts = collectToasts(vm) {
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { vm.onInstallSaved().join() }
                    }
                }
                drainContainer(vm)

                val state = vm.container.stateFlow.value
                assertTrue(state.installedForUse, "direct install success must mark installedForUse")
                assertTrue(state.installedShortcuts, "manifest declares shortcuts, so shortcuts must be detected")
                assertFalse(state.manifestSaved, "direct install must not touch the saved flag")
                assertEquals(null, state.saveResult, "direct install must not fabricate a save result")
                assertFalse(state.installing, "installing must reset after the intent")
                assertTrue(
                    toasts.any { it.type == ToastType.SUCCESS && it.text.contains("testapp") },
                    "expected a SUCCESS toast naming the app, got: $toasts",
                )
                assertFalse(
                    toasts.any { it.text.contains("Save the manifest") },
                    "the save-first block toast must not appear",
                )

                // Temp manifest under the stable direct-install dir is cleaned up.
                val directDir = File(System.getenv("LOCALAPPDATA"), "scooper/direct-install")
                assertFalse(
                    File(directDir, "testapp.json").exists(),
                    "temp manifest must be deleted after a successful install",
                )
            } finally {
                vm.close()
            }
        }
    }

    @Test
    fun `onInstallSaved unsaved upserts an orphan index record and notifies the service`(): Unit = runBlocking {
        withScoopShim {
            val (vm, service, repository) = createVmWithService()
            try {
                presetUnsavedState(vm, suggestedAppName = "testapp")

                var received: scooper.service.ScoopEvent? = null
                val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
                val job = CoroutineScope(Dispatchers.Default).launch {
                    received = service.events
                        .onSubscription { ready.complete(Unit) }
                        .first { it is scooper.service.ScoopEvent.AppInstalled }
                }
                ready.await()

                withContext(Dispatchers.Default) {
                    withTimeout(10_000) { vm.onInstallSaved().join() }
                }
                withTimeout(10_000) { job.join() }
                drainContainer(vm)

                assertEquals(
                    scooper.service.ScoopEvent.AppInstalled("testapp"),
                    received,
                    "direct install must notify through the service like the bucket path",
                )
                val stored = repository.getApps(query = "testapp").value.first { it.name == "testapp" }
                assertEquals(AppStatus.INSTALLED, stored.status, "orphan record must be INSTALLED")
                assertEquals(null, stored.bucket, "direct install record must be an orphan (no bucket)")
            } finally {
                vm.close()
            }
        }
    }

    @Test
    fun `onInstallSaved unsaved builds the orphan record from the installed manifest on disk`(): Unit = runBlocking {
        withScoopShim {
            val (vm, service, repository) = createVmWithService()
            try {
                val manifest = """{"version":"1.2.3","description":"A tiny test tool","homepage":"https://example.com/testapp","shortcuts":[["App","app.exe"]]}"""
                presetInstalledManifest("testapp", manifest)
                presetUnsavedState(vm, suggestedAppName = "testapp", manifestJson = manifest)

                collectToasts(vm) {
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { vm.onInstallSaved().join() }
                    }
                }
                drainContainer(vm)

                val stored = repository.getApps(query = "testapp").value.first { it.name == "testapp" }
                assertEquals("A tiny test tool", stored.description, "the record must carry the manifest description right after a direct install")
                assertEquals("https://example.com/testapp", stored.homepage, "the record must carry the manifest homepage")
                assertEquals("1.2.3", stored.version, "the record version must come from the installed directory name")
                assertEquals("1.2.3", stored.latestVersion, "the record latestVersion must come from the manifest")
                assertTrue(
                    stored.shortcuts.orEmpty().any { it.title == "App" },
                    "the record must carry the manifest shortcuts",
                )
                assertEquals(null, stored.bucket, "the record must stay an orphan (no bucket)")
            } finally {
                vm.close()
            }
        }
    }

    @Test
    fun `onInstallSaved unsaved falls back to the manifest version when nothing is on disk`(): Unit = runBlocking {
        withScoopShim {
            val (vm, service, repository) = createVmWithService()
            try {
                presetUnsavedState(vm, suggestedAppName = "testapp")

                collectToasts(vm) {
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { vm.onInstallSaved().join() }
                    }
                }
                drainContainer(vm)

                val stored = repository.getApps(query = "testapp").value.first { it.name == "testapp" }
                assertEquals(AppStatus.INSTALLED, stored.status, "the fallback record must still be INSTALLED")
                assertEquals(null, stored.bucket, "the fallback record must stay an orphan (no bucket)")
                assertEquals("1.2.3", stored.version, "fallback version comes from the active manifest")
                assertEquals(null, stored.description, "no disk manifest means no description")
            } finally {
                vm.close()
            }
        }
    }

    @Test
    fun `onInstallSaved unsaved resolves the name from the last url when no suggestion exists`(): Unit = runBlocking {
        withScoopShim {
            val vm = createVm()
            try {
                presetUnsavedState(vm, suggestedAppName = null, lastUrl = "https://github.com/ScoopInstaller/Main")

                val toasts = collectToasts(vm) {
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { vm.onInstallSaved().join() }
                    }
                }
                drainContainer(vm)

                val state = vm.container.stateFlow.value
                assertTrue(state.installedForUse, "deriveAppName fallback must produce a usable name")
                assertTrue(
                    toasts.any { it.type == ToastType.SUCCESS && it.text.contains("main") },
                    "expected a SUCCESS toast naming the derived app, got: $toasts",
                )
            } finally {
                vm.close()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Open button after a direct install
    // ---------------------------------------------------------------------

    @Test
    fun `onOpenApp after a direct install uses the installed name instead of staying silent`(): Unit = runBlocking {
        withScoopShim {
            val vm = createVm()
            try {
                // A name guaranteed to have no Start Menu shortcut, so openApp
                // reports a deterministic failure instead of launching anything.
                val missing = "scooper-open-app-missing-${System.nanoTime()}"
                presetUnsavedState(vm, suggestedAppName = missing)

                collectToasts(vm) {
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { vm.onInstallSaved().join() }
                    }
                }
                drainContainer(vm)

                val state = vm.container.stateFlow.value
                assertTrue(state.installedForUse, "precondition: direct install succeeded")
                assertTrue(state.saveResult == null, "precondition: direct install records no saveResult")

                val toasts = collectToasts(vm) {
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { vm.onOpenApp().join() }
                    }
                }
                assertTrue(
                    toasts.any { it.type == ToastType.ERROR && it.text.contains(missing) },
                    "Open after a direct install must attempt the recorded name and report the outcome, got: $toasts",
                )
            } finally {
                vm.close()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Name resolution failure
    // ---------------------------------------------------------------------

    @Test
    fun `onInstallSaved unsaved without any resolvable name toasts an error and does nothing`(): Unit = runBlocking {
        withScoopShim {
            val vm = createVm()
            try {
                presetUnsavedState(vm, suggestedAppName = null, lastUrl = "")

                val before = vm.container.stateFlow.value
                val toasts = collectToasts(vm) { vm.onInstallSaved().join() }

                assertTrue(
                    toasts.any { it.type == ToastType.ERROR },
                    "expected an ERROR toast when the app name cannot be resolved, got: $toasts",
                )
                val after = vm.container.stateFlow.value
                assertFalse(after.installedForUse, "failed resolution must not mark installedForUse")
                assertFalse(after.installing, "failed resolution must not leave installing set")
                assertEquals(before.messages, after.messages, "failed resolution must not touch messages")
            } finally {
                vm.close()
            }
        }
    }

    @Test
    fun `onInstallSaved with a blank manifest toasts info and does nothing`(): Unit = runBlocking {
        withScoopShim {
            val vm = createVm()
            try {
                presetUnsavedState(vm, suggestedAppName = "testapp", manifestJson = "   ")

                val toasts = collectToasts(vm) { vm.onInstallSaved().join() }

                assertTrue(
                    toasts.any { it.type == ToastType.INFO },
                    "expected an INFO toast for a blank manifest, got: $toasts",
                )
                val after = vm.container.stateFlow.value
                assertFalse(after.installedForUse)
                assertFalse(after.installing)
            } finally {
                vm.close()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Install crash containment: non-IOException failures must not escape
    // ---------------------------------------------------------------------

    @Test
    fun `directInstallCore turns a non-IOException install crash into a failed reply`() {
        // The install call itself can throw (process launch failure); the
        // core must answer with a masked failure reply instead of propagating.
        val manifest = """{"version":"1.2.3"}"""
        val outcome = runBlocking {
            withTimeout(5_000) {
                TestDirectInstallCrash.directInstallCoreForTest(
                    manifestJson = manifest,
                    name = "testapp",
                    install = { throw IllegalStateException("CreateProcess error=193") },
                )
            }
        }
        assertEquals("false", jsonField(outcome.replyJson, "success"))
        assertEquals("local", jsonField(outcome.replyJson, "mode"))
        assertEquals("testapp", jsonField(outcome.replyJson, "app"))
        val error = jsonField(outcome.replyJson, "error")
        assertTrue(error.orEmpty().contains("193"), "the masked error must describe the failure, got: $error")
    }

    @Test
    fun `directInstallCore cleanup still deletes the temp manifest after a crash`() {
        val manifest = """{"version":"1.2.3"}"""
        runBlocking {
            withTimeout(5_000) {
                TestDirectInstallCrash.directInstallCoreForTest(
                    manifestJson = manifest,
                    name = "testapp",
                    install = { throw IllegalStateException("boom") },
                )
            }
        }
        val directDir = File(System.getenv("LOCALAPPDATA"), "scooper/direct-install")
        assertFalse(
            File(directDir, "testapp.json").exists(),
            "the temp manifest must be cleaned up even when the install throws",
        )
    }

    @Test
    fun `installAppBridge never throws and replies success false when the install crashes`() {
        val outcome = runBlocking {
            withTimeout(5_000) {
                TestDirectInstallCrash.installAppBridgeForTest {
                    throw IllegalStateException("CreateProcess error=193")
                }
            }
        }
        assertEquals("false", jsonField(outcome, "success"))
        assertEquals("local", jsonField(outcome, "mode"))
        assertEquals("testapp", jsonField(outcome, "app"))
        assertTrue(
            jsonField(outcome, "error").orEmpty().contains("193"),
            "the bridge reply must carry a masked error, got: $outcome",
        )
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Simulates scoop's on-disk layout for an installed app: apps/<name>/current/manifest.json. */
    private fun presetInstalledManifest(name: String, manifestJson: String) {
        val current = tempDir.resolve("apps/$name/current")
        current.mkdirs()
        current.resolve("manifest.json").writeText(manifestJson)
    }

    private fun presetUnsavedState(
        vm: CreateViewModel,
        suggestedAppName: String?,
        manifestJson: String = """{"version":"1.2.3","bin":["app.exe"],"shortcuts":[["App","app.exe"]]}""",
        lastUrl: String = "",
    ) {
        runBlocking {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.container.orbit {
                        reduce {
                            state.copy(
                                manifestJson = manifestJson,
                                suggestedAppName = suggestedAppName,
                                lastUrl = lastUrl,
                                manifestSaved = false,
                                saveResult = null,
                            )
                        }
                    }
                }
            }
        }
    }

    /** Collects Toast side effects emitted while [block] runs plus a drain window. */
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
        withContext(Dispatchers.Default) { delay(100) }
        try {
            block()
            val deadline = System.currentTimeMillis() + 5_000
            while (collected.isEmpty() && System.currentTimeMillis() < deadline) {
                withContext(Dispatchers.Default) { delay(20) }
            }
        } finally {
            job.cancel()
        }
        return collected
    }

    private suspend fun drainContainer(vm: CreateViewModel) {
        withContext(Dispatchers.Default) { delay(300) }
    }

    private fun createVm(): CreateViewModel {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )
        return CreateViewModel(
            scoopAiBridge = ScoopAiBridge(
                configRepository = FakeDirectInstallConfigRepository(),
                checkpointService = InMemoryAiCheckpointService(),
            ),
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = scooper.service.createInMemoryAppsRepository(scoopClient),
            gitHistoryService = GitHistoryService(),
        )
    }

    private fun createVmWithService(): Triple<CreateViewModel, ScoopService, scooper.repository.AppsRepository> {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )
        val repository = scooper.service.createInMemoryAppsRepository(scoopClient)
        val service = ScoopService(scoopClient, repository)
        val vm = CreateViewModel(
            scoopAiBridge = ScoopAiBridge(
                configRepository = FakeDirectInstallConfigRepository(),
                checkpointService = InMemoryAiCheckpointService(),
            ),
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = repository,
            gitHistoryService = GitHistoryService(),
            scoopService = service,
        )
        return Triple(vm, service, repository)
    }
}

/** Minimal AI config so guards never block; nothing here dials out. */
private class FakeDirectInstallConfigRepository : ConfigRepository() {
    private val config = scooper.data.UIConfig(
        aiProvider = "openai-compatible",
        aiApiKey = "test-key",
        aiBaseUrl = "http://127.0.0.1:9",
    )

    override fun getConfig(): scooper.data.UIConfig = config

    override fun setConfig(config: scooper.data.UIConfig) = Unit
}
/**
 * Test-only re-entry into the two crash-containment layers of
 * [CreateViewModel.directInstallCore] and the install_app bridge: the injectable
 * [install] lambda stands in for [scooper.service.ScoopClient.installFromManifest],
 * throwing whatever a broken scoop environment would throw. Keeps the VM
 * container out of the loop (no shim needed), while asserting the exact
 * exception surfaces the model and UI rely on.
 */
object TestDirectInstallCrash {
    /** Mirrors directInstallCore minus state updates: write, install, cleanup, reply. */
    suspend fun directInstallCoreForTest(
        manifestJson: String,
        name: String,
        install: suspend () -> scooper.service.CommandResult,
    ): DirectInstallOutcomeForTest {
        val directDir = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "scooper/direct-install")
        val manifestFile = directDir.resolve("$name.json")
        try {
            directDir.mkdirs()
            manifestFile.writeText(manifestJson)
        } catch (e: IOException) {
            return DirectInstallOutcomeForTest(CreateViewModel.directInstallFailureReply(name, "Failed to write manifest file: ${e.message}"))
        }
        try {
            return try {
                install()
                DirectInstallOutcomeForTest(CreateViewModel.directInstallSuccessReply(name), failed = false)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val masked = scooper.service.AiErrorTranslator.maskSecrets("Install failed: ${e.message}")
                DirectInstallOutcomeForTest(CreateViewModel.directInstallFailureReply(name, masked))
            }
        } finally {
            try {
                withContext(Dispatchers.IO) { manifestFile.delete() }
            } catch (e: Exception) {
                // best effort cleanup, same as production
            }
        }
    }

    /** Mirrors the onInstallApp runBlocking bridge: crash must become a reply. */
    suspend fun installAppBridgeForTest(
        install: suspend () -> scooper.service.CommandResult,
    ): String {
        return try {
            val outcome = directInstallCoreForTest(
                manifestJson = """{"version":"1.2.3"}""",
                name = "testapp",
                install = install,
            )
            outcome.replyJson
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            scooper.service.AiErrorTranslator.maskSecrets(
                CreateViewModel.installBridgeErrorReply("testapp", e).toString(),
            )
        }
    }
}

/** Reply-shape probe for the crash-containment tests. */
data class DirectInstallOutcomeForTest(
    val replyJson: String,
    val failed: Boolean = true,
)

/** JSON field probe shared by the crash-containment assertions. */
private fun jsonField(json: String, field: String): String? {
    if (json.isBlank()) return null
    val element = kotlinx.serialization.json.Json.parseToJsonElement(json)
    val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
    return (obj[field] as? kotlinx.serialization.json.JsonPrimitive)?.content
}
