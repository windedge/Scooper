@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import com.scoopai.core.ManifestResult
import com.scoopai.core.ToolActivity
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
import scooper.data.UIConfig
import scooper.repository.ConfigRepository
import scooper.service.AiErrorAction
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopClient
import scooper.service.ScoopLogStream
import scooper.service.createInMemoryAppsRepository
import scooper.taskqueue.TaskQueue
import java.io.File

/**
 * Create deployment wiring (P1-2b): every step that changes what is installed
 * refreshes [CreateState.deployment] from disk, so the state cannot drift away
 * from the installed copy while the session continues.
 *
 * The VM runs against a real [ScoopClient] rooted at a temp dir with fake
 * repositories, following the CreateViewModelSystemInstalledTest pattern. An
 * installed app is simulated with `apps/<name>/1.0` plus a `current` junction,
 * because [ScoopClient.installedSnapshot] reads the version off the resolved
 * target name.
 */
class CreateViewModelDeploymentTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: File

    /** Junctions created for this test; they must be unlinked before temp cleanup. */
    private val junctions = mutableListOf<File>()

    // ---------------------------------------------------------------------
    // Save wiring
    // ---------------------------------------------------------------------

    @Test
    fun `save success derives an installed deployment with the bucket origin`(): Unit = withVm { vm ->
        markInstalled(name = "testapp", version = "1.0", recordBucket = "main")
        File(tempDir, "buckets/main/bucket").mkdirs()
        presetState(vm, saveDialogOpen = true)

        withContext(Dispatchers.Default) {
            withTimeout(10_000) { vm.onConfirmSave().join() }
        }

        val deployment = vm.container.stateFlow.value.deployment
        assertTrue(deployment is DeploymentState.Installed, "expected Installed, got $deployment")
        deployment as DeploymentState.Installed
        assertEquals("1.0", deployment.installedVersion, "installed version comes from the apps dir")
        assertEquals("1.2.0", deployment.manifestVersion, "manifest version comes from the saved bucket file")
        assertEquals(VersionRelation.Newer, deployment.relation, "1.2.0 is newer than the installed 1.0")
        assertEquals(InstallOrigin.Bucket, deployment.origin, "the install record names a bucket")
        assertEquals("main", deployment.originBucket)
        assertEquals("Update", deploymentLabel(deployment), "a newer manifest offers Update")
        assertEquals(
            DeploymentAction.UpdateForce,
            deploymentAction(deployment, "main"),
            "same bucket as the save bucket: force update instead of uninstall",
        )
    }

    @Test
    fun `renamed save derives the deployment for the new name`(): Unit = withVm { vm ->
        // testapp is installed, but the save goes out as testapp2.
        markInstalled(name = "testapp", version = "1.0", recordBucket = "main")
        File(tempDir, "buckets/main/bucket").mkdirs()
        presetState(vm, saveDialogOpen = true, saveAppName = "testapp2")

        withContext(Dispatchers.Default) {
            withTimeout(10_000) { vm.onConfirmSave().join() }
        }

        val state = vm.container.stateFlow.value
        assertEquals("testapp2", state.saveResult?.appName, "the save must use the dialog name")
        assertEquals(
            DeploymentState.NotInstalled,
            state.deployment,
            "nothing is installed under the saved name, so the deployment is not installed",
        )
    }

    // ---------------------------------------------------------------------
    // Install wiring
    // ---------------------------------------------------------------------

    @Test
    fun `executeInstallApp success derives an installed deployment`(): Unit = withVm { vm ->
        // The shim fakes scoop by materialising the app dir it would create.
        withInstallingShim {
            writeBucketManifest("main", "testapp", """{"version":"1.2.0","bin":["app.exe"]}""")
            presetState(vm)

            withContext(Dispatchers.Default) {
                withTimeout(10_000) { vm.executeInstallApp(bucket = "main", name = "testapp").join() }
            }

            val deployment = vm.container.stateFlow.value.deployment
            assertTrue(deployment is DeploymentState.Installed, "install success must derive Installed, got $deployment")
            deployment as DeploymentState.Installed
            assertEquals("1.0", deployment.installedVersion)
            assertEquals(InstallOrigin.Bucket, deployment.origin)
            assertEquals("main", deployment.originBucket)
        }
    }

    // ---------------------------------------------------------------------
    // Session latch follows disk
    // ---------------------------------------------------------------------

    @Test
    fun `a vanished install clears the session install latch`(): Unit = withVm { vm ->
        // UninstallThenInstall uninstalled the app and then the install failed, or
        // the copy was removed elsewhere: the session latch still said installed,
        // which kept the deploy button disabled with the app already gone.
        presetState(
            vm,
            installedForUse = true,
            deployment = DeploymentState.Installed(
                installedVersion = "1.0",
                manifestVersion = "1.2.0",
                relation = VersionRelation.Newer,
                origin = InstallOrigin.Bucket,
                originBucket = "main",
                inThisSession = true,
            ),
        )
        awaitRefresh(vm)

        val state = vm.container.stateFlow.value
        assertEquals(
            DeploymentState.NotInstalled,
            state.deployment,
            "nothing is on disk, so the deployment must not claim an install",
        )
        assertFalse(state.installedForUse, "a vanished install must re-enable the deploy button")
        assertFalse(state.installedShortcuts, "no install means no shortcuts to open")
    }

    @Test
    fun `a live install keeps the session install latch`(): Unit = withVm { vm ->
        markInstalled(name = "testapp", version = "1.0", recordBucket = "main")
        writeBucketManifest("main", "testapp", """{"version":"1.2.0"}""")
        presetState(vm, installedForUse = true)
        awaitRefresh(vm)

        val state = vm.container.stateFlow.value
        assertTrue(state.deployment is DeploymentState.Installed, "expected Installed, got ${state.deployment}")
        assertTrue(state.installedForUse, "a refresh must not drop a live in-session install")
    }

    // ---------------------------------------------------------------------
    // Generation round wiring (all four handleFinished branches)
    // ---------------------------------------------------------------------

    @Test
    fun `answered round keeps the installed deployment`(): Unit = withVm(
        bridge = StubFinishedBridge(
            finished = Result.success(
                ManifestResult(manifest = "", answered = true, explanation = "It is a CLI tool."),
            ),
        ),
    ) { vm ->
        markInstalled(name = "testapp", version = "1.0", recordBucket = "main")
        writeBucketManifest("main", "testapp", """{"version":"1.2.0"}""")
        presetState(vm, saveResult = saveResultOf("testapp", "main"))

        vm.onSend("https://github.com/owner/testapp")
        awaitIdle(vm)

        val state = vm.container.stateFlow.value
        assertTrue(
            state.messages.any { it.text.contains("It is a CLI tool") },
            "the answered round must land in the transcript: ${state.messages.map { it.text }}",
        )
        // The answered branch never touches the manifest, so the deployment must be
        // re-derived from the saved bucket copy instead of collapsing.
        val deployment = state.deployment
        assertTrue(deployment is DeploymentState.Installed, "answered must not regress, got $deployment")
        deployment as DeploymentState.Installed
        assertEquals("1.0", deployment.installedVersion)
        assertEquals(VersionRelation.Newer, deployment.relation)
    }

    @Test
    fun `failed round keeps the installed deployment`(): Unit = withVm(
        bridge = StubFinishedBridge(finished = Result.failure(RuntimeException("boom"))),
    ) { vm ->
        markInstalled(name = "testapp", version = "1.0", recordBucket = "main")
        writeBucketManifest("main", "testapp", """{"version":"1.2.0"}""")
        presetState(vm, saveResult = saveResultOf("testapp", "main"))

        vm.onSend("https://github.com/owner/testapp")
        awaitIdle(vm)

        val state = vm.container.stateFlow.value
        assertTrue(state.error != null, "the failure must be surfaced")
        // Conservative: the failure changed nothing on disk, so the deployment must
        // survive (and must not be derived from the now blank draft manifest).
        val deployment = state.deployment
        assertTrue(deployment is DeploymentState.Installed, "failure must not clear the state, got $deployment")
        deployment as DeploymentState.Installed
        assertEquals("1.0", deployment.installedVersion)
        assertEquals(VersionRelation.Newer, deployment.relation)
        assertEquals(InstallOrigin.Bucket, deployment.origin)
    }

    // ---------------------------------------------------------------------
    // Pure name resolution feeding the refresh
    // ---------------------------------------------------------------------

    @Test
    fun `deployment app name prefers the saved name over the suggestion`() {
        assertEquals("saved", deploymentAppName(saveResultOf("saved", "main"), "suggested", "direct"))
        assertEquals("suggested", deploymentAppName(null, "suggested", "direct"))
        assertEquals("direct", deploymentAppName(null, null, "direct"))
        assertEquals(null, deploymentAppName(null, null, null))
        assertEquals(
            "saved",
            deploymentAppName(saveResultOf("saved", "main"), "  ", "direct"),
            "blank candidates are skipped",
        )
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Derive the deployment state the button shows. */
    private suspend fun awaitRefresh(vm: CreateViewModel) {
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { vm.refreshDeploymentState().join() }
        }
    }

    /** VM lifecycle plus junction cleanup: JUnit recurses into junctions when deleting. */
    private fun withVm(
        bridge: ScoopAiBridge? = null,
        block: suspend (CreateViewModel) -> Unit,
    ): Unit = runBlocking {
        val vm = createVm(bridge)
        try {
            block(vm)
        } finally {
            vm.close()
            removeJunctions()
        }
    }

    private fun removeJunctions() {
        for (junction in junctions) {
            if (!junction.exists()) continue
            // rmdir unlinks a junction only; it never touches the target dir.
            ProcessBuilder("cmd", "/c", "rmdir", junction.absolutePath).start().waitFor()
        }
        junctions.clear()
    }

    private fun saveResultOf(appName: String, bucket: String) = SaveResult(
        appName = appName,
        bucket = bucket,
        targetPath = File(tempDir, "buckets/$bucket/bucket/$appName.json").absolutePath,
        overwritten = false,
        indexedOk = true,
    )

    /**
     * Simulate an installed app: a version dir plus a `current` junction pointing at
     * it, because installedSnapshot resolves the version from the link target name.
     * Junctions (unlike symlinks) need no elevation on Windows.
     */
    private fun markInstalled(name: String, version: String, recordBucket: String?) {
        val versionDir = File(tempDir, "apps/$name/$version").apply { mkdirs() }
        if (recordBucket != null) {
            File(versionDir, "scoop-install.json").writeText("""{"bucket":"$recordBucket"}""")
        }
        createJunction(File(tempDir, "apps/$name/current"), versionDir)
    }

    private fun createJunction(link: File, target: File) {
        link.parentFile?.mkdirs()
        val process = ProcessBuilder(
            "cmd",
            "/c",
            "mklink",
            "/J",
            link.absolutePath,
            target.absolutePath,
        ).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        val exit = process.waitFor()
        require(exit == 0) { "could not create junction $link (exit $exit)" }
        junctions += link
    }

    private fun writeBucketManifest(bucket: String, name: String, json: String) {
        val dir = File(tempDir, "buckets/$bucket/bucket").apply { mkdirs() }
        File(dir, "$name.json").writeText(json)
    }

    /**
     * Stub scoop command that materialises the install it pretends to run, so the
     * post-install refresh sees the app on disk. Guarded so repeated scoop calls
     * (pre-download, install) stay exit 0.
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

    /** Bridge whose runs finish immediately on a worker thread (never inline). */
    private class StubFinishedBridge(
        configRepository: ConfigRepository,
        checkpointService: InMemoryAiCheckpointService,
        private val finished: Result<ManifestResult>,
    ) : ScoopAiBridge(configRepository, checkpointService) {
        constructor(finished: Result<ManifestResult>) :
            this(FakeConfigRepository(), InMemoryAiCheckpointService(), finished)

        override fun startGenerate(
            url: String,
            userPrompt: String?,
            resumeFrom: AiErrorAction?,
            overrideModel: String?,
            ignoreAppNames: Set<String>,
            onPhase: (String) -> Unit,
            onToolActivity: ((ToolActivity) -> Unit)?,
            onPhaseOutput: ((String, String) -> Unit)?,
            onAskUser: (suspend (String) -> String)?,
            onQuestionnaire: (suspend (List<com.scoopai.tools.interaction.Question>) -> String)?,
            onFinished: (Result<ManifestResult>) -> Unit,
        ) {
            // Out of the calling intent: handleFinished opens its own intent.
            CoroutineScope(Dispatchers.IO).launch {
                delay(20)
                onFinished(finished)
            }
        }
    }

    private suspend fun awaitIdle(vm: CreateViewModel) {
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                while (vm.container.stateFlow.value.running) delay(10)
            }
        }
        // Let the queued handleFinished intent finish its reduce and refresh.
        delay(300)
    }

    private fun createVm(bridge: ScoopAiBridge? = null): CreateViewModel {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )
        return CreateViewModel(
            scoopAiBridge = bridge ?: ScoopAiBridge(
                configRepository = FakeConfigRepository(),
                checkpointService = InMemoryAiCheckpointService(),
            ),
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = createInMemoryAppsRepository(scoopClient),
            gitHistoryService = GitHistoryService(),
        )
    }

    /** Presets the session state the save / install / send paths read. */
    private fun presetState(
        vm: CreateViewModel,
        manifestJson: String = """{"version":"1.2.0","homepage":"https://example.com"}""",
        suggestedAppName: String? = "testapp",
        saveAppName: String = "testapp",
        saveBucket: String = "main",
        saveDialogOpen: Boolean = false,
        saveResult: SaveResult? = null,
        installedForUse: Boolean = false,
        deployment: DeploymentState? = null,
    ) {
        runBlocking {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    vm.container.orbit {
                        reduce {
                            state.copy(
                                manifestJson = manifestJson,
                                suggestedAppName = suggestedAppName,
                                saveAppName = saveAppName,
                                saveBucket = saveBucket,
                                saveDialogOpen = saveDialogOpen,
                                saveCommitToGit = false,
                                overwriteConfirmOpen = false,
                                manifestSaved = saveResult != null,
                                saveResult = saveResult,
                                installedForUse = installedForUse,
                                deployment = deployment ?: state.deployment,
                            )
                        }
                    }
                    delay(100)
                }
            }
        }
    }

    /** Complete AI settings: startGenerate passes the settings gate, the bridge is stubbed. */
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
