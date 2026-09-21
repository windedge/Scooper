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

/**
 * TDD tests for the AI chat direct-install bridge: when the model calls
 * `install_app` without a bucket, the host must install the current manifest
 * from its local file (the same mechanism as the manual onInstallSaved local
 * branch), reply JSON with mode=local, and record an orphan index entry.
 * Bucket installs keep their classic path and reply mode=bucket.
 */
class CreateViewModelAiDirectInstallTest {

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

    private fun jsonField(json: String, field: String): String? {
        if (json.isBlank()) return null
        val element = kotlinx.serialization.json.Json.parseToJsonElement(json)
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
        return (obj[field] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }

    /**
     * Waits until the recorded install reply is non-blank. The reply lands in
     * state through a separate intent, so a single read right after join can
     * still see the previous value.
     */
    private suspend fun awaitInstallReply(vm: CreateViewModel): String {
        val deadline = System.currentTimeMillis() + 5_000
        var reply = ""
        while (System.currentTimeMillis() < deadline) {
            reply = vm.container.stateFlow.value.lastInstallReply
            if (reply.isNotBlank()) return reply
            withContext(Dispatchers.Default) { delay(20) }
        }
        return reply
    }

    // ---------------------------------------------------------------------
    // Direct install (bucket omitted)
    // ---------------------------------------------------------------------

    @Test
    fun `install_app without a bucket direct-installs locally and replies mode local`(): Unit = runBlocking {
        withScoopShim {
            val vm = createVm()
            try {
                presetDirectInstallState(vm, suggestedAppName = "testapp")

                val toasts = collectToasts(vm) {
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { vm.executeInstallApp(bucket = null, name = "testapp").join() }
                    }
                }
                drainContainer(vm)

                val state = vm.container.stateFlow.value
                assertTrue(state.installedForUse, "direct install success must mark installedForUse")
                assertTrue(state.systemInstalled, "direct install success must mark systemInstalled")
                assertFalse(state.manifestSaved, "direct install must not touch the saved flag")
                assertTrue(
                    toasts.any { it.type == ToastType.SUCCESS && it.text.contains("testapp") },
                    "expected a SUCCESS toast naming the app, got: $toasts",
                )
                assertTrue(
                    toasts.any { it.type == ToastType.SUCCESS && it.text.contains("without saving") },
                    "the success toast must tell the user nothing was saved to a bucket, got: $toasts",
                )
            } finally {
                vm.close()
            }
        }
    }

    @Test
    fun `install_app direct install replies json with mode local and app name`(): Unit = runBlocking {
        withScoopShim {
            val vm = createVm()
            try {
                presetDirectInstallState(vm, suggestedAppName = "testapp")

                lateinit var reply: String
                collectToasts(vm) {
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) {
                            vm.executeInstallApp(bucket = null, name = "testapp").join()
                        }
                    }
                    reply = awaitInstallReply(vm)
                }

                assertEquals("true", jsonField(reply, "success"))
                assertEquals("local", jsonField(reply, "mode"))
                assertEquals("testapp", jsonField(reply, "app"))
                assertEquals(null, jsonField(reply, "bucket"), "a local install reply must not claim a bucket")
            } finally {
                vm.close()
            }
        }
    }

    @Test
    fun `install_app direct install upserts an orphan index record and notifies the service`(): Unit = runBlocking {
        withScoopShim {
            val (vm, service, repository) = createVmWithService()
            try {
                presetDirectInstallState(vm, suggestedAppName = "testapp")

                var received: scooper.service.ScoopEvent? = null
                val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
                val job = CoroutineScope(Dispatchers.Default).launch {
                    received = service.events
                        .onSubscription { ready.complete(Unit) }
                        .first { it is scooper.service.ScoopEvent.AppInstalled }
                }
                ready.await()

                withContext(Dispatchers.Default) {
                    withTimeout(10_000) { vm.executeInstallApp(bucket = null, name = "testapp").join() }
                }
                withTimeout(10_000) { job.join() }
                drainContainer(vm)

                assertEquals(
                    scooper.service.ScoopEvent.AppInstalled("testapp"),
                    received,
                    "direct install must notify through the service like the manual path",
                )
                val stored = repository.getApps(query = "testapp").value.first { it.name == "testapp" }
                assertEquals(AppStatus.INSTALLED, stored.status, "orphan record must be INSTALLED")
                assertEquals(null, stored.bucket, "AI direct install record must be an orphan (no bucket)")
            } finally {
                vm.close()
            }
        }
    }

    @Test
    fun `install_app direct install builds the orphan record from the installed manifest on disk`(): Unit = runBlocking {
        withScoopShim {
            val (vm, service, repository) = createVmWithService()
            try {
                val manifest = """{"version":"1.2.3","description":"A tiny test tool","homepage":"https://example.com/testapp"}"""
                presetInstalledManifest("testapp", manifest)
                presetDirectInstallState(vm, suggestedAppName = "testapp", manifestJson = manifest)

                collectToasts(vm) {
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { vm.executeInstallApp(bucket = null, name = "testapp").join() }
                    }
                }
                drainContainer(vm)

                val stored = repository.getApps(query = "testapp").value.first { it.name == "testapp" }
                assertEquals("A tiny test tool", stored.description, "the record must carry the manifest description right after a direct install")
                assertEquals("https://example.com/testapp", stored.homepage, "the record must carry the manifest homepage")
                assertEquals("1.2.3", stored.version, "the record version must come from the installed directory name")
                assertEquals(null, stored.bucket, "the record must stay an orphan (no bucket)")
            } finally {
                vm.close()
            }
        }
    }

    @Test
    fun `install_app direct install appends an agent message saying nothing was saved`(): Unit = runBlocking {
        withScoopShim {
            val vm = createVm()
            try {
                presetDirectInstallState(vm, suggestedAppName = "testapp")

                collectToasts(vm) {
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { vm.executeInstallApp(bucket = null, name = "testapp").join() }
                    }
                }
                drainContainer(vm)

                val state = vm.container.stateFlow.value
                val agentTexts = state.messages.filter { it.role == ChatRole.Agent }.map { it.text }
                assertTrue(
                    agentTexts.any { it.contains("testapp") && it.contains("without saving") },
                    "expected an agent chat message about the direct install, got: $agentTexts",
                )
            } finally {
                vm.close()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Direct install failure paths
    // ---------------------------------------------------------------------

    @Test
    fun `install_app direct install with a blank manifest fails without installing`(): Unit = runBlocking {
        withScoopShim {
            val vm = createVm()
            try {
                presetDirectInstallState(vm, suggestedAppName = "testapp", manifestJson = "   ")

                lateinit var reply: String
                val toasts = collectToasts(vm) {
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { vm.executeInstallApp(bucket = null, name = "testapp").join() }
                    }
                    reply = awaitInstallReply(vm)
                }

                assertEquals("false", jsonField(reply, "success"))
                assertEquals("local", jsonField(reply, "mode"))
                assertTrue(
                    toasts.any { it.type == ToastType.ERROR },
                    "expected an ERROR toast for a blank manifest, got: $toasts",
                )
                val state = vm.container.stateFlow.value
                assertFalse(state.installedForUse, "a failed direct install must not mark installedForUse")
                assertFalse(state.installing, "installing must reset after the intent")
            } finally {
                vm.close()
            }
        }
    }

    @Test
    fun `install_app direct install rejects an invalid app name`(): Unit = runBlocking {
        withScoopShim {
            val vm = createVm()
            try {
                presetDirectInstallState(vm, suggestedAppName = "testapp")

                lateinit var reply: String
                collectToasts(vm) {
                    withContext(Dispatchers.Default) {
                        withTimeout(10_000) { vm.executeInstallApp(bucket = null, name = "bad name!").join() }
                    }
                    reply = awaitInstallReply(vm)
                }

                assertEquals("false", jsonField(reply, "success"))
                assertEquals("local", jsonField(reply, "mode"))
                val state = vm.container.stateFlow.value
                assertFalse(state.installedForUse, "an invalid name must not mark installedForUse")
                // The temp manifest must never be written under an unsafe name.
                val directDir = File(System.getenv("LOCALAPPDATA"), "scooper/direct-install")
                assertFalse(
                    File(directDir, "bad name!.json").exists(),
                    "an invalid name must never reach the filesystem",
                )
            } finally {
                vm.close()
            }
        }
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

    private fun presetDirectInstallState(
        vm: CreateViewModel,
        suggestedAppName: String?,
        manifestJson: String = """{"version":"1.2.3","bin":["app.exe"],"shortcuts":[["App","app.exe"]]}""",
    ) {
        runBlocking {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.container.orbit {
                        reduce {
                            state.copy(
                                manifestJson = manifestJson,
                                suggestedAppName = suggestedAppName,
                                manifestSaved = false,
                                saveResult = null,
                                lastInstallReply = "",
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
                configRepository = FakeAiDirectInstallConfigRepository(),
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
                configRepository = FakeAiDirectInstallConfigRepository(),
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
private class FakeAiDirectInstallConfigRepository : ConfigRepository() {
    private val config = scooper.data.UIConfig(
        aiProvider = "openai-compatible",
        aiApiKey = "test-key",
        aiBaseUrl = "http://127.0.0.1:9",
    )

    override fun getConfig(): scooper.data.UIConfig = config

    override fun setConfig(config: scooper.data.UIConfig) = Unit
}
