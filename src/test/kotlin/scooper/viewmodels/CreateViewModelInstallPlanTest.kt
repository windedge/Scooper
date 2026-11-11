@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.data.UIConfig
import scooper.repository.ConfigRepository
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopTempRoot
import scooper.service.createInMemoryAppsRepository
import java.io.File

/**
 * Create deploy plan: [CreateViewModel.onInstallSaved] executes the plan derived
 * by [deploymentAction] instead of the saved/installed flags, and refuses to run
 * a plan that changed after the click.
 *
 * The VM runs against a real ScoopClient rooted at a temp dir; a shim scoop
 * records the argv of every command, so the tests assert which commands ran
 * instead of only the resulting state.
 */
class CreateViewModelInstallPlanTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: File

    /** Junctions must be unlinked before temp cleanup: JUnit recurses into them. */
    private val root by lazy { ScoopTempRoot(tempDir) }

    @AfterEach
    fun removeJunctions() = root.removeJunctions()

    // ---------------------------------------------------------------------
    // Plan dispatch
    // ---------------------------------------------------------------------

    @Test
    fun `installed newer same bucket updates instead of installing`(): Unit = withVm { vm, commands ->
        root.markInstalled(name = "testapp", version = "1.0", recordBucket = "main")
        root.writeManifestJson("main", "testapp", """{"version":"1.2.0","architecture":{"64bit":{"bin":["app.exe"]}}}""")
        presetState(vm, saveResult = saveResultOf("testapp", "main"))
        awaitRefresh(vm)

        runClick(vm)

        assertTrue(
            commands().any { it.startsWith("update") && it.contains("main/testapp") },
            "same-bucket newer manifest must force-update the bucket copy, got: ${commands()}",
        )
        assertFalse(
            commands().any { it.startsWith("install") },
            "an installed same-bucket app must not be installed again, got: ${commands()}",
        )
        assertTrue(
            commands().any { it.contains("--force") },
            "a same-version scoop update without --force is a no-op exit 0, got: ${commands()}",
        )
    }

    @Test
    fun `installed in another bucket uninstalls before installing from the save bucket`(): Unit = withVm { vm, commands ->
        root.markInstalled(name = "testapp", version = "1.0", recordBucket = "main")
        root.writeManifestJson("extras", "testapp", """{"version":"1.2.0","architecture":{"64bit":{"bin":["app.exe"]}}}""")
        root.writeManifestJson("main", "testapp", """{"version":"1.0"}""")
        presetState(vm, saveResult = saveResultOf("testapp", "extras"))
        awaitRefresh(vm)

        runClick(vm)

        val uninstallAt = commands().indexOfFirst { it.startsWith("uninstall") && it.contains("testapp") }
        val installAt = commands().indexOfFirst { it.startsWith("install") && it.contains("extras/testapp") }
        assertTrue(uninstallAt >= 0, "the foreign copy must be uninstalled first, got: ${commands()}")
        assertTrue(installAt >= 0, "the save bucket copy must be installed, got: ${commands()}")
        assertTrue(uninstallAt < installAt, "uninstall must run before install, got: ${commands()}")
        assertFalse(
            commands().any { it.contains("-p") },
            "persist data must survive the reinstall, got: ${commands()}",
        )
    }

    @Test
    fun `missing bucket manifest blocks the reinstall without uninstalling`(): Unit = withVm { vm, commands ->
        // Installed from main, saved into extras, but extras has no manifest copy:
        // the uninstall would leave the app gone with nothing to install from.
        root.markInstalled(name = "testapp", version = "1.0", recordBucket = "main")
        root.writeManifestJson("main", "testapp", """{"version":"1.2.0"}""")
        presetState(vm, saveResult = saveResultOf("testapp", "extras"))
        awaitRefresh(vm)

        val toasts = runClick(vm)

        assertEquals(emptyList<String>(), commands(), "the pre-check must run before any scoop command")
        assertTrue(
            toasts.any { it.type == ToastType.ERROR && it.text.contains("Cannot reinstall testapp") },
            "the refusal must be reported, got: $toasts",
        )
        assertTrue(
            vm.container.stateFlow.value.installing.not(),
            "installing must reset after the refusal",
        )
    }

    @Test
    fun `a plan that changed after the click is aborted`(): Unit = withVm { vm, commands ->
        // The button claimed UpdateForce, but nothing is installed on disk anymore.
        root.writeManifestJson("main", "testapp", """{"version":"1.2.0","architecture":{"64bit":{"bin":["app.exe"]}}}""")
        presetState(
            vm,
            saveResult = saveResultOf("testapp", "main"),
            deployment = DeploymentState.Installed(
                installedVersion = "1.0",
                manifestVersion = "1.2.0",
                relation = VersionRelation.Newer,
                origin = InstallOrigin.Bucket,
                originBucket = "main",
                inThisSession = false,
            ),
        )

        val toasts = runClick(vm)

        assertEquals(emptyList<String>(), commands(), "a stale plan must not touch scoop")
        assertTrue(
            toasts.any { it.type == ToastType.WARNING && it.text.contains("Deployment state changed") },
            "the stale plan must be reported, got: $toasts",
        )
        assertFalse(vm.container.stateFlow.value.installedForUse, "an aborted plan installs nothing")
    }

    @Test
    fun `a skipped update warns and keeps installedForUse false`(): Unit = withVm(
        shimOutput = listOf("WARN  'testapp' is held to version 1.0"),
    ) { vm, commands ->
        root.markInstalled(name = "testapp", version = "1.0", recordBucket = "main")
        root.writeManifestJson("main", "testapp", """{"version":"1.2.0","architecture":{"64bit":{"bin":["app.exe"]}}}""")
        presetState(vm, saveResult = saveResultOf("testapp", "main"))
        awaitRefresh(vm)

        val toasts = runClick(vm)

        assertTrue(commands().any { it.startsWith("update") }, "the update must still run, got: ${commands()}")
        val state = vm.container.stateFlow.value
        assertFalse(state.installedForUse, "a held app is not installed for use by this click")
        assertTrue(
            toasts.any { it.type == ToastType.WARNING && it.text.contains("held to version") },
            "the skip must surface as a warning, got: $toasts",
        )
        assertFalse(
            toasts.any { it.type == ToastType.SUCCESS },
            "a skipped update must not report success, got: $toasts",
        )
    }

    @Test
    fun `an update still targets the origin bucket after the save result was cleared`(): Unit = withVm { vm, commands ->
        // The assist round and the dismissed result card clear the save result; the
        // installed copy's recorded bucket is what the update must still use.
        root.markInstalled(name = "testapp", version = "1.0", recordBucket = "main")
        root.writeManifestJson("main", "testapp", """{"version":"1.2.0","architecture":{"64bit":{"bin":["app.exe"]}}}""")
        presetState(vm, saveResult = null)
        awaitRefresh(vm)

        runClick(vm)

        assertTrue(
            commands().any { it.startsWith("update") && it.contains("main/testapp") },
            "a cleared save result must not turn the update into a no-op, got: ${commands()}",
        )
        assertTrue(
            commands().any { it.contains("--force") },
            "the reinstall still has to force the manifest, got: ${commands()}",
        )
        assertFalse(
            commands().any { it.startsWith("install") },
            "the installed copy must be updated instead of reinstalled, got: ${commands()}",
        )
    }

    @Test
    fun `uninstall then install keeps the global scope of the installed copy`(): Unit = withVm { vm, commands ->
        // A global app updated from another bucket used to be uninstalled from the
        // global root and reinstalled into the local one, flipping its scope.
        root.markInstalled(name = "testapp", version = "1.0", recordBucket = "main", global = true)
        root.writeManifestJson("main", "testapp", """{"version":"1.0"}""")
        root.writeManifestJson("extras", "testapp", """{"version":"1.2.0","architecture":{"64bit":{"bin":["app.exe"]}}}""")
        presetState(vm, saveResult = saveResultOf("testapp", "extras"))
        awaitRefresh(vm)

        runClick(vm)

        val uninstall = commands().indexOfFirst { it.startsWith("uninstall") }
        val install = commands().indexOfFirst { it.startsWith("install") }
        assertTrue(uninstall >= 0, "the foreign copy must be uninstalled first, got: ${commands()}")
        assertTrue(install >= 0, "the save bucket copy must be installed, got: ${commands()}")
        assertTrue(uninstall < install, "uninstall must run before install, got: ${commands()}")
        assertTrue(
            commands()[uninstall].contains("-g"),
            "a global app must be uninstalled from the global root, got: ${commands()}",
        )
        assertTrue(
            commands()[install].contains("-g"),
            "the reinstall must use the same scope as the uninstall, got: ${commands()}",
        )
    }

    @Test
    fun `a failed install after a successful uninstall clears the install latch`(): Unit = withVm(
        failOn = "install extras",
        removeOnUninstall = File(tempDir, "apps/testapp/current"),
    ) { vm, commands ->
        // The app was installed in this session, the reinstall uninstalled it and
        // then the install failed: nothing is on disk, so the latch must drop or
        // the button stays disabled with the app already gone.
        root.markInstalled(name = "testapp", version = "1.0", recordBucket = "main")
        root.writeManifestJson("main", "testapp", """{"version":"1.0"}""")
        root.writeManifestJson("extras", "testapp", """{"version":"1.2.0","architecture":{"64bit":{"bin":["app.exe"]}}}""")
        presetState(vm, saveResult = saveResultOf("testapp", "extras"), installedForUse = true)
        awaitRefresh(vm)

        runClick(vm)

        assertTrue(commands().any { it.startsWith("uninstall") }, "the uninstall must run, got: ${commands()}")
        assertTrue(commands().any { it.startsWith("install") }, "the install must be attempted, got: ${commands()}")
        val state = vm.container.stateFlow.value
        assertEquals(DeploymentState.NotInstalled, state.deployment, "the app is gone from disk")
        assertFalse(state.installedForUse, "a gone app must not stay latched, the button must be retryable")
        assertFalse(state.installing, "installing must reset after the failure")
    }

    // ---------------------------------------------------------------------
    // Pre-check rule
    // ---------------------------------------------------------------------

    @Test
    fun `bucket manifest issue names the missing and unusable shapes`() {
        assertEquals("the bucket has no manifest for this app", bucketManifestIssue(null))
        assertEquals("the bucket has no manifest for this app", bucketManifestIssue("  "))
        assertEquals("the bucket manifest is not valid JSON", bucketManifestIssue("{not json"))
        assertEquals("the bucket manifest has no version", bucketManifestIssue("""{"bin":["app.exe"]}"""))
        assertEquals(
            "the bucket manifest has no url or architecture",
            bucketManifestIssue("""{"version":"1.0"}"""),
        )
        assertEquals(null, bucketManifestIssue("""{"version":"1.0","url":"https://example.com/app.zip"}"""))
        assertEquals(
            null,
            bucketManifestIssue("""{"version":"1.0","architecture":{"64bit":{"url":"https://example.com/app.zip"}}}"""),
        )
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** VM lifecycle and shim around one test; junctions are unlinked by [removeJunctions]. */
    private fun withVm(
        shimOutput: List<String> = emptyList(),
        failOn: String? = null,
        removeOnUninstall: File? = null,
        block: suspend (CreateViewModel, () -> List<String>) -> Unit,
    ): Unit = runBlocking {
        val log = File(tempDir, "scoop-commands.log")
        log.writeText("")
        withRecordingShim(log, shimOutput, failOn, removeOnUninstall) {
            val vm = createVm()
            try {
                block(vm) { readCommands(log) }
            } finally {
                vm.close()
            }
        }
    }

    private fun readCommands(log: File): List<String> = log.readLines().map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Stub scoop that appends its argv to [log], optionally prints [output] lines,
     * [failOn] makes matching commands exit 1, and [removeOnUninstall] unlinks that
     * junction so a fake uninstall really removes the app from disk.
     */
    private suspend fun <T> withRecordingShim(
        log: File,
        output: List<String>,
        failOn: String?,
        removeOnUninstall: File?,
        block: suspend () -> T,
    ): T {
        val dir = File(System.getProperty("java.io.tmpdir"), "scooper-shim-${System.nanoTime()}").apply { mkdirs() }
        val cmds = output.joinToString("") { "echo $it\r\n" }
        val failLine = failOn?.let {
            "echo %* | findstr /i /c:\"$it\" >nul\r\nif not errorlevel 1 exit /b 1\r\n"
        } ?: ""
        val removeLine = removeOnUninstall?.let {
            "echo %* | findstr /i /c:\"uninstall\" >nul\r\nif not errorlevel 1 rmdir \"${it.absolutePath}\"\r\n"
        } ?: ""
        val cmd = dir.resolve("scoop.cmd").apply {
            writeText(
                "@echo off\r\n" +
                    "echo %* >> \"${log.absolutePath}\"\r\n" +
                    failLine +
                    removeLine +
                    cmds +
                    "exit /b 0\r\n",
            )
            deleteOnExit()
        }
        // Global scoop calls are prefixed with sudo; forward the tail to the stub
        // so one shim records both scopes.
        dir.resolve("sudo.cmd").apply {
            writeText("@echo off\r\n%*\r\n")
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

    /** Click the deploy button and return the toasts it posted. */
    private suspend fun runClick(vm: CreateViewModel): List<AppSideEffect.Toast> {
        val collected = mutableListOf<AppSideEffect.Toast>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            vm.container.sideEffectFlow.collect { effect ->
                if (effect is AppSideEffect.Toast) collected.add(effect)
            }
        }
        withContext(Dispatchers.Default) { delay(100) }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { vm.onInstallSaved().join() }
            }
            // The plan runs in a child job and joining the intent returns first, so wait
            // for the installing=true edge, then poll until installing=false before
            // reading the resulting state.
            withContext(Dispatchers.Default) {
                runCatching {
                    withTimeout(2_000) { while (!vm.container.stateFlow.value.installing) delay(10) }
                }
                withTimeout(10_000) { while (vm.container.stateFlow.value.installing) delay(10) }
            }
        } finally {
            job.cancel()
        }
        return collected
    }

    /** Derive the deployment state the button shows before the click. */
    private suspend fun awaitRefresh(vm: CreateViewModel) {
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { vm.refreshDeploymentState().join() }
        }
    }

    private fun saveResultOf(appName: String, bucket: String) = SaveResult(
        appName = appName,
        bucket = bucket,
        targetPath = File(tempDir, "buckets/$bucket/bucket/$appName.json").absolutePath,
        overwritten = false,
        indexedOk = true,
    )

    private fun createVm(): CreateViewModel {
        val scoopClient = root.client(globalRoot = true)
        return CreateViewModel(
            scoopAiBridge = ScoopAiBridge(
                configRepository = FakeConfigRepository(),
                checkpointService = InMemoryAiCheckpointService(),
            ),
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = createInMemoryAppsRepository(scoopClient),
            gitHistoryService = GitHistoryService(),
        )
    }

    /** Presets the session state the deploy path reads. */
    private fun presetState(
        vm: CreateViewModel,
        manifestJson: String = """{"version":"1.2.0","homepage":"https://example.com"}""",
        saveResult: SaveResult? = null,
        deployment: DeploymentState? = null,
        installedForUse: Boolean = false,
    ) {
        runBlocking {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.container.orbit {
                        reduce {
                            state.copy(
                                manifestJson = manifestJson,
                                suggestedAppName = "testapp",
                                saveAppName = "testapp",
                                saveBucket = saveResult?.bucket ?: "main",
                                saveCommitToGit = false,
                                manifestSaved = saveResult != null,
                                saveResult = saveResult,
                                deployment = deployment ?: state.deployment,
                                installedForUse = installedForUse,
                            )
                        }
                    }
                    delay(100)
                }
            }
        }
    }

    /** Complete AI settings: the deploy path never contacts the bridge. */
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
