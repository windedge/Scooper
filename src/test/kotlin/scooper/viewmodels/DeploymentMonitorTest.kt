package scooper.viewmodels

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.service.ScoopClient
import scooper.service.ScoopLogStream
import scooper.taskqueue.TaskQueue
import java.io.File

/**
 * [DeploymentMonitor] disk derivation: given the session's saved state it reads
 * the Scoop layout (apps/<name>/<version> + current junction + scoop-install.json
 * + bucket manifest copy) and produces the [DeploymentState] the deploy button
 * renders. An installed app is simulated with a junction, following the
 * CreateViewModelDeploymentTest fixture pattern.
 */
class DeploymentMonitorTest {

    @TempDir
    lateinit var tempDir: File

    /** Junctions created for this test; they must be unlinked before temp cleanup. */
    private val junctions = mutableListOf<File>()

    @org.junit.jupiter.api.AfterEach
    fun removeJunctions() {
        for (junction in junctions) {
            if (!junction.exists()) continue
            // rmdir unlinks a junction only; it never touches the target dir.
            ProcessBuilder("cmd", "/c", "rmdir", junction.absolutePath).start().waitFor()
        }
        junctions.clear()
    }

    // ---------------------------------------------------------------------
    // Not installed / unresolvable
    // ---------------------------------------------------------------------

    @Test
    fun `without any disk state the draft manifest yields not installed`() = runBlocking {
        val state = monitor().derive(
            saveResult = null,
            suggestedAppName = "testapp",
            directInstallName = null,
            fallbackManifestJson = """{"version":"1.2.0"}""",
            inThisSession = false,
        )

        assertEquals(DeploymentState.NotInstalled, state)
    }

    @Test
    fun `an unresolvable app name yields unresolved`() = runBlocking {
        val state = monitor().derive(
            saveResult = saveResultOf("  ", "main"),
            suggestedAppName = "  ",
            directInstallName = null,
            fallbackManifestJson = """{"version":"1.2.0"}""",
            inThisSession = false,
        )

        assertEquals(DeploymentState.UnresolvedName, state)
    }

    @Test
    fun `no manifest anywhere yields no manifest`() = runBlocking {
        markInstalled(name = "testapp", version = "1.0", recordBucket = "main")

        val state = monitor().derive(
            saveResult = null,
            suggestedAppName = "testapp",
            directInstallName = null,
            fallbackManifestJson = null,
            inThisSession = false,
        )

        assertEquals(DeploymentState.NoManifest, state)
    }

    // ---------------------------------------------------------------------
    // Installed versions against the bucket copy
    // ---------------------------------------------------------------------

    @Test
    fun `an installed older copy against a newer bucket manifest derives update`() = runBlocking {
        markInstalled(name = "testapp", version = "1.0", recordBucket = "main")
        writeBucketManifest("main", "testapp", """{"version":"1.2.0"}""")

        val state = monitor().derive(
            saveResult = saveResultOf("testapp", "main"),
            suggestedAppName = null,
            directInstallName = null,
            fallbackManifestJson = """{"version":"0.9"}""",
            inThisSession = false,
        ) as DeploymentState.Installed

        assertEquals("1.0", state.installedVersion)
        assertEquals("1.2.0", state.manifestVersion, "the bucket copy wins over the draft")
        assertEquals(VersionRelation.Newer, state.relation)
        assertEquals(InstallOrigin.Bucket, state.origin)
        assertEquals("main", state.originBucket)
        assertEquals("Update", deploymentLabel(state))
        assertEquals(DeploymentAction.UpdateForce, deploymentAction(state, "main"))
    }

    @Test
    fun `the same installed and bucket version with an in-session install opens only`() = runBlocking {
        markInstalled(name = "testapp", version = "1.2.0", recordBucket = "main")
        writeBucketManifest("main", "testapp", """{"version":"1.2.0"}""")

        val state = monitor().derive(
            saveResult = saveResultOf("testapp", "main"),
            suggestedAppName = null,
            directInstallName = null,
            fallbackManifestJson = null,
            inThisSession = true,
        ) as DeploymentState.Installed

        assertEquals(VersionRelation.Same, state.relation)
        assertEquals(true, state.inThisSession)
        assertEquals(DeploymentAction.OpenOnly, deploymentAction(state, "main"))
    }

    @Test
    fun `an installed newer copy than the bucket manifest derives reinstall`() = runBlocking {
        markInstalled(name = "testapp", version = "2.0", recordBucket = "main")
        writeBucketManifest("main", "testapp", """{"version":"1.2.0"}""")

        val state = monitor().derive(
            saveResult = saveResultOf("testapp", "main"),
            suggestedAppName = null,
            directInstallName = null,
            fallbackManifestJson = null,
            inThisSession = false,
        ) as DeploymentState.Installed

        assertEquals(VersionRelation.Older, state.relation)
        assertEquals("Reinstall", deploymentLabel(state))
    }

    // ---------------------------------------------------------------------
    // Install record origins
    // ---------------------------------------------------------------------

    @Test
    fun `a direct-file install record yields the direct origin`() = runBlocking {
        markInstalled(name = "testapp", version = "1.0", recordBucket = null, recordJson = """{"url":"file:///C:/tmp/testapp.json"}""")
        writeBucketManifest("main", "testapp", """{"version":"1.2.0"}""")

        val state = monitor().derive(
            saveResult = saveResultOf("testapp", "main"),
            suggestedAppName = null,
            directInstallName = null,
            fallbackManifestJson = null,
            inThisSession = false,
        ) as DeploymentState.Installed

        assertEquals(InstallOrigin.DirectFile, state.origin)
        assertEquals(null, state.originBucket)
        assertEquals(DeploymentAction.UninstallThenInstall, deploymentAction(state, "main"))
    }

    @Test
    fun `a missing install record keeps an unknown origin`() = runBlocking {
        markInstalled(name = "testapp", version = "1.0", recordBucket = null)
        writeBucketManifest("main", "testapp", """{"version":"1.2.0"}""")

        val state = monitor().derive(
            saveResult = saveResultOf("testapp", "main"),
            suggestedAppName = null,
            directInstallName = null,
            fallbackManifestJson = null,
            inThisSession = false,
        ) as DeploymentState.Installed

        assertEquals(InstallOrigin.Unknown, state.origin)
        assertEquals(null, state.originBucket)
    }

    // ---------------------------------------------------------------------
    // Name and bucket resolution
    // ---------------------------------------------------------------------

    @Test
    fun `a renamed save derives the deployment under the saved name`() = runBlocking {
        // testapp is installed, but the save went out as testapp2.
        markInstalled(name = "testapp", version = "1.0", recordBucket = "main")
        writeBucketManifest("main", "testapp2", """{"version":"1.2.0"}""")

        val state = monitor().derive(
            saveResult = saveResultOf("testapp2", "main"),
            suggestedAppName = "testapp",
            directInstallName = null,
            fallbackManifestJson = """{"version":"1.2.0"}""",
            inThisSession = false,
        )

        assertEquals(DeploymentState.NotInstalled, state)
    }

    @Test
    fun `without a bucket copy the draft manifest stands in`() = runBlocking {
        markInstalled(name = "testapp", version = "1.0", recordBucket = "main")

        val state = monitor().derive(
            saveResult = saveResultOf("testapp", "main"),
            suggestedAppName = null,
            directInstallName = null,
            fallbackManifestJson = """{"version":"2.0"}""",
            inThisSession = true,
        ) as DeploymentState.Installed

        assertEquals("2.0", state.manifestVersion)
        assertEquals(VersionRelation.Newer, state.relation)
        assertEquals(true, state.inThisSession)
    }

    @Test
    fun `a direct install name resolves when no save or suggestion exists`() = runBlocking {
        markInstalled(name = "testapp", version = "1.0", recordBucket = null)

        val state = monitor().derive(
            saveResult = null,
            suggestedAppName = null,
            directInstallName = "testapp",
            fallbackManifestJson = """{"version":"0.9"}""",
            inThisSession = false,
        ) as DeploymentState.Installed

        assertEquals("1.0", state.installedVersion, "the direct install name finds the installed copy")
        assertEquals(
            "0.9",
            state.manifestVersion,
            "no save bucket and no record bucket means no bucket copy is looked up; the draft stands in",
        )
    }

    @Test
    fun `the save bucket wins over the record bucket when both have copies`() = runBlocking {
        markInstalled(name = "testapp", version = "1.0", recordBucket = "main")
        writeBucketManifest("main", "testapp", """{"version":"1.5.0"}""")
        writeBucketManifest("extras", "testapp", """{"version":"3.0"}""")

        val state = monitor().derive(
            saveResult = saveResultOf("testapp", "extras"),
            suggestedAppName = null,
            directInstallName = null,
            fallbackManifestJson = null,
            inThisSession = false,
        ) as DeploymentState.Installed

        assertEquals("3.0", state.manifestVersion, "the save bucket is what Scoop will update from first")
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun monitor(): DeploymentMonitor = DeploymentMonitor(
        ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        ),
    )

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
    private fun markInstalled(name: String, version: String, recordBucket: String?, recordJson: String? = recordBucket?.let { """{"bucket":"$it"}""" }) {
        val versionDir = File(tempDir, "apps/$name/$version").apply { mkdirs() }
        if (recordJson != null) {
            File(versionDir, "scoop-install.json").writeText(recordJson)
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
}
