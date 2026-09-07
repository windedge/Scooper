package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import scooper.data.App
import scooper.data.AppStatus
import scooper.data.Bucket
import scooper.service.BucketSaveEnv
import scooper.service.InstalledAppInfo
import java.io.File

/**
 * TDD tests for the shared post-install index refresh: after a real install
 * both success paths (manual onInstallSaved and AI install_app) call
 * [refreshInstalledAppRecordCore] so Discover sees the app as INSTALLED.
 */
class CreateViewModelRefreshInstalledAppRecordTest {

    @TempDir
    lateinit var tempDir: File

    // ---------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------

    /**
     * Fake scoop environment backed by a temp dir layout:
     * baseDir/<bucket>/bucket/<name>.json, with a markable installed snapshot.
     * Status becomes INSTALLED when the snapshot contains the app name.
     */
    private inner class FakeScoopEnv(
        buckets: List<String>,
    ) : BucketSaveEnv {
        val buckets: List<String> = buckets
        override val bucketsBaseDir: File get() = tempDir
        override val bucketNames: List<String> get() = buckets

        private val installedNames = mutableSetOf<String>()

        /** Set to make the next buildAppsFromManifestNames call fail. */
        var failWith: Exception? = null

        fun markInstalled(name: String) {
            installedNames.add(name.lowercase())
        }

        override fun installedSnapshot(): Map<String, InstalledAppInfo> = installedNames.associateWith {
            InstalledAppInfo(name = it, version = "1.2.3", isGlobal = false, appDir = tempDir)
        }

        override fun buildAppsFromManifestNames(
            bucketDir: File,
            manifestNames: Set<String>,
            bucket: Bucket,
            installed: Map<String, InstalledAppInfo>,
        ): List<App> {
            failWith?.let { throw it }
            return manifestNames.mapNotNull { fileName ->
                val file = bucketDir.resolve("bucket/$fileName")
                if (!file.exists()) return@mapNotNull null
                val appName = fileName.removeSuffix(".json")
                if (installed.containsKey(appName.lowercase())) {
                    App(
                        name = appName,
                        latestVersion = "1.2.3",
                        version = "1.2.3",
                        status = AppStatus.INSTALLED,
                        bucket = Bucket(bucket.name),
                    )
                } else {
                    App(name = appName, latestVersion = "1.2.3", bucket = Bucket(bucket.name))
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun writeManifest(bucket: String, name: String) {
        val dir = tempDir.resolve(bucket).resolve("bucket")
        dir.mkdirs()
        dir.resolve("$name.json").writeText("""{"version":"1.2.3","bin":["app.exe"]}""")
    }

    // ---------------------------------------------------------------------
    // refreshInstalledAppRecordCore
    // ---------------------------------------------------------------------

    @Test
    fun `manifest in bucket plus markInstalled upserts one INSTALLED app`() = runBlocking {
        val env = FakeScoopEnv(buckets = listOf("main"))
        env.markInstalled("fd")
        writeManifest(bucket = "main", name = "fd")
        val upserted = mutableListOf<App>()

        refreshInstalledAppRecordCore("main", "fd", env, { upserted.add(it) })

        assertEquals(1, upserted.size)
        assertEquals(AppStatus.INSTALLED, upserted[0].status)
        assertEquals("1.2.3", upserted[0].version)
    }

    @Test
    fun `missing manifest file means no upsert and no error`() = runBlocking {
        val env = FakeScoopEnv(buckets = listOf("main"))
        val upserted = mutableListOf<App>()
        val warnings = mutableListOf<String>()

        refreshInstalledAppRecordCore("main", "fd", env, { upserted.add(it) }, { warnings.add(it) })

        assertTrue(upserted.isEmpty())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `build failure warns with the app name instead of throwing`() = runBlocking {
        val env = FakeScoopEnv(buckets = listOf("main"))
        env.failWith = IllegalStateException("boom")
        val upserted = mutableListOf<App>()
        val warnings = mutableListOf<String>()

        refreshInstalledAppRecordCore("main", "fd", env, { upserted.add(it) }, { warnings.add(it) })

        assertTrue(upserted.isEmpty())
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("fd"))
        assertTrue(warnings[0].contains("boom"))
    }

    @Test
    fun `cancellation is rethrown to the caller`() {
        val env = FakeScoopEnv(buckets = listOf("main"))
        env.failWith = CancellationException("cancelled")
        val upserted = mutableListOf<App>()

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                refreshInstalledAppRecordCore("main", "fd", env, { upserted.add(it) }, { })
            }
        }
        assertEquals("cancelled", thrown.message)
        assertTrue(upserted.isEmpty())
    }
}
