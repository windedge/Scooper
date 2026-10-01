@file:OptIn(org.orbitmvi.orbit.annotation.OrbitInternal::class)

package scooper.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.App
import scooper.data.AppStatus
import scooper.repository.AppsRepository
import scooper.repository.ConfigRepository
import scooper.service.GitHistoryService
import scooper.service.InMemoryAiCheckpointService
import scooper.service.ScoopAiBridge
import scooper.service.ScoopClient
import scooper.service.ScoopLogStream
import scooper.service.createInMemoryAppsRepository
import scooper.taskqueue.TaskQueue
import java.io.File

/**
 * TDD tests for orphan-row convergence when saving to a bucket: a direct
 * install writes an orphan INSTALLED row (bucketId null), and the save path
 * (indexSavedApp) only upserted the bucket row, leaving two same-name rows.
 * After the fix the save must delete the stale orphan row so Discover shows
 * a single entry. Follows CreateViewModelRefSaveDefaultTest's real-VM setup.
 */
class CreateViewModelOrphanConvergenceTest {

    @TempDir
    lateinit var tempDir: File

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    private class PrefConfigRepository(private val config: scooper.data.UIConfig) : ConfigRepository() {
        override fun getConfig(): scooper.data.UIConfig = config
        override fun setConfig(config: scooper.data.UIConfig) = Unit
    }

    private fun configWithLastBucket(lastBucket: String) = scooper.data.UIConfig(
        aiProvider = "openai-compatible",
        aiApiKey = "test-key",
        aiBaseUrl = "http://127.0.0.1:9",
        aiLastBucket = lastBucket,
    )

    /** Scoop on-disk bucket layout: buckets/<name>/bucket/<app>.json (ScoopClient.kt). */
    private fun makeBucketWithManifest(bucket: String, appName: String) {
        val dir = tempDir.resolve("buckets").resolve(bucket).resolve("bucket")
        dir.mkdirs()
        dir.resolve("$appName.json").writeText("""{"version":"1.2.3"}""")
    }

    private lateinit var appsRepository: AppsRepository

    private fun createViewModel(): CreateViewModel {
        val scoopClient = ScoopClient(
            logStream = ScoopLogStream(),
            taskQueue = TaskQueue(),
            rootDirOverride = tempDir,
        )
        appsRepository = createInMemoryAppsRepository(scoopClient)
        return CreateViewModel(
            scoopAiBridge = ScoopAiBridge(
                configRepository = PrefConfigRepository(configWithLastBucket("main")),
                checkpointService = InMemoryAiCheckpointService(),
            ),
            checkpointService = InMemoryAiCheckpointService(),
            scoopClient = scoopClient,
            appsRepository = appsRepository,
            gitHistoryService = GitHistoryService(),
        )
    }

    private suspend fun preset(vm: CreateViewModel, manifestJson: String) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                vm.container.orbit {
                    reduce {
                        state.copy(
                            manifestJson = manifestJson,
                            suggestedAppName = "testapp",
                            messages = listOf(ChatMessage(role = ChatRole.User, text = "做个便携版")),
                        )
                    }
                }
                while (vm.container.stateFlow.value.manifestJson != manifestJson) delay(10)
            }
        }
    }

    /** Seed the index the way the direct-install path does (orphan, INSTALLED). */
    private fun seedOrphanRow(repository: AppsRepository, name: String) {
        repository.upsertApps(
            listOf(
                App(
                    name = name,
                    latestVersion = "1.0.0",
                    version = "1.0.0",
                    status = AppStatus.INSTALLED,
                    bucket = null,
                ),
            ),
        )
    }

    private fun findRow(repository: AppsRepository, name: String): App? =
        repository.getApps(query = name).value.firstOrNull { it.name == name }

    /** Save flow: confirm the dialog, then confirm the overwrite prompt that a
     *  pre-existing bucket manifest triggers (same as the real UI path). */
    private suspend fun saveThroughDialog(vm: CreateViewModel) {
        withContext(Dispatchers.Default) { vm.onSave().join() }
        withContext(Dispatchers.Default) { vm.onConfirmSave().join() }
        if (vm.container.stateFlow.value.overwriteConfirmOpen) {
            withContext(Dispatchers.Default) { vm.onConfirmOverwrite().join() }
        }
    }

    // ---------------------------------------------------------------
    // Save to bucket converges the orphan row
    // ---------------------------------------------------------------

    @Test
    fun `saving to a bucket deletes the stale orphan INSTALLED row`() = runBlocking {
        makeBucketWithManifest("main", "testapp")
        val vm = createViewModel()
        try {
            preset(vm, manifestJson = """{"version":"1.2.3"}""")
            seedOrphanRow(appsRepository, "testapp")

            saveThroughDialog(vm)

            val row = findRow(appsRepository, "testapp")
            assertEquals(
                1,
                appsRepository.getApps(query = "testapp").value.count { it.name == "testapp" },
                "Discover must show a single same-name row after the save",
            )
            assertEquals("main", row?.bucket?.name, "the surviving row must be the bucket row")
            assertTrue(
                File(tempDir, "buckets/main/bucket/testapp.json").exists(),
                "the manifest file must land in the bucket",
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun `saving to a bucket leaves an unrelated orphan row untouched`() = runBlocking {
        makeBucketWithManifest("main", "testapp")
        val vm = createViewModel()
        try {
            preset(vm, manifestJson = """{"version":"1.2.3"}""")
            seedOrphanRow(appsRepository, "testapp")
            seedOrphanRow(appsRepository, "otherapp")

            saveThroughDialog(vm)

            val other = findRow(appsRepository, "otherapp")
            assertNotNull(other, "an unrelated orphan row must survive the save")
            assertEquals(AppStatus.INSTALLED, other?.status)
            assertNull(other?.bucket, "the unrelated row must stay an orphan")
        } finally {
            vm.close()
        }
    }

    @Test
    fun `a failed parse of the saved manifest keeps the orphan row`() = runBlocking {
        makeBucketWithManifest("main", "testapp")
        val vm = createViewModel()
        try {
            // An unparseable manifest makes buildAppsFromManifestNames return
            // no app, so indexSavedApp skips the whole branch; the orphan row
            // must stay (app == null must not delete anything).
            preset(vm, manifestJson = "not-json{{")
            seedOrphanRow(appsRepository, "testapp")

            saveThroughDialog(vm)

            val row = findRow(appsRepository, "testapp")
            assertNotNull(row, "app == null must not delete the orphan row")
            assertEquals(AppStatus.INSTALLED, row?.status)
            assertNull(row?.bucket, "the row must stay an orphan when nothing was indexed")
        } finally {
            vm.close()
        }
    }
}
