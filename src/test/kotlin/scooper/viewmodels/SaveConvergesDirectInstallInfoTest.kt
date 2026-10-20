package scooper.viewmodels

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.App
import scooper.data.Bucket
import scooper.service.BucketSaveEnv
import scooper.service.InstalledAppInfo
import java.io.File

/**
 * A direct install from the temporary manifest makes scoop record url = <temp path> in
 * scoop-install.json, so scoop update fails once the temp file is gone: saving to a bucket
 * must converge the record back to a bucket install (drop url, add bucket).
 */
class SaveConvergesDirectInstallInfoTest {

    @TempDir
    lateinit var tempDir: File

    // ---------------------------------------------------------------
    // Fixtures: temp-dir scoop layout (buckets + installed apps)
    // ---------------------------------------------------------------

    /** Fake scoop environment: bucketsBaseDir is a real dir with buckets/<name>/bucket/. */
    private inner class FakeScoopEnv : BucketSaveEnv {
        override val bucketsBaseDir: File get() = tempDir.resolve("buckets")
        override val bucketNames: List<String> get() = listOf("main")
        override fun installedSnapshot(): Map<String, InstalledAppInfo> {
            val appsDir = tempDir.resolve("apps")
            return appsDir.listFiles()?.filter { it.isDirectory }?.associate {
                it.name.lowercase() to InstalledAppInfo(
                    name = it.name,
                    version = "1.2.3",
                    isGlobal = false,
                    appDir = it,
                )
            } ?: emptyMap()
        }
        override fun buildAppsFromManifestNames(
            bucketDir: File,
            manifestNames: Set<String>,
            bucket: Bucket,
            installed: Map<String, InstalledAppInfo>,
        ): List<App> = manifestNames.map {
            App(name = it.removeSuffix(".json"), latestVersion = "1.2.3", bucket = Bucket(bucket.name))
        }
    }

    private fun save(env: FakeScoopEnv, overwrite: Boolean = false): BucketSaveOutcome.Saved = runBlocking {
        val outcome = saveManifestCore(
            manifestJson = """{"version":"1.2.3","bin":["app.exe"]}""",
            bucket = "main",
            appName = "gotify-desktop",
            commitToGit = false,
            overwrite = overwrite,
            scoopClient = env,
            indexSaved = { _, _ -> true },
            isGitBucket = { false },
            commitManifest = { _, _, _, _ -> Result.success("abc") },
        )
        assertTrue(outcome is BucketSaveOutcome.Saved, "save must succeed, got $outcome")
        outcome as BucketSaveOutcome.Saved
    }

    /** apps/<name>/current is a plain dir standing in for the junction. */
    private fun writeInstallRecord(name: String, json: String): File {
        val dir = tempDir.resolve("apps/$name/current")
        dir.mkdirs()
        return dir.resolve("scoop-install.json").apply { writeText(json) }
    }

    private fun readInstallRecord(name: String): String =
        tempDir.resolve("apps/$name/current/scoop-install.json").readText()

    // ---------------------------------------------------------------
    // Convergence: url-pinned record becomes a bucket record
    // ---------------------------------------------------------------

    @Test
    fun `save converges a direct-install url record into the bucket`() {
        val env = FakeScoopEnv()
        env.bucketsBaseDir.mkdirs()
        writeInstallRecord(
            "gotify-desktop",
            """
            {
                "url":  "C:\\Users\\user\\AppData\\Local\\scooper\\direct-install\\gotify-desktop.json",
                "architecture": "64bit"
            }
            """.trimIndent(),
        )

        save(env)

        val text = readInstallRecord("gotify-desktop")
        assertFalse(text.contains("url"), "the stale direct-install url must be gone: $text")
        assertTrue(text.contains("\"bucket\""), "the record must point at the bucket: $text")
    }

    @Test
    fun `save leaves an existing bucket record untouched`() {
        val env = FakeScoopEnv()
        env.bucketsBaseDir.mkdirs()
        val original = """
            {
                "bucket": "main",
                "architecture": "64bit"
            }
        """.trimIndent()
        writeInstallRecord("gotify-desktop", original)

        save(env)

        assertEquals(original, readInstallRecord("gotify-desktop"))
    }

    @Test
    fun `save converges even when no install record exists`() {
        val env = FakeScoopEnv()
        env.bucketsBaseDir.mkdirs()

        save(env)

        // No crash, no record created for an app that is not installed.
        assertFalse(tempDir.resolve("apps/gotify-desktop").exists())
    }

    // ---------------------------------------------------------------
    // Convergence decision table: only scooper direct-install urls converge
    // ---------------------------------------------------------------

    @Test
    fun `save converges only records that pin a scooper direct-install url`() {
        val env = FakeScoopEnv()
        env.bucketsBaseDir.mkdirs()
        // Backslashes are doubled in JSON; a single backslash is not valid JSON.
        val cases = listOf(
            """{"url":"C:\\a\\scooper\\direct-install\\x.json"}""" to true,
            """{"url":"D:\\Users\\u\\AppData\\Local\\Scooper\\Direct-Install\\x.json"}""" to true,
            """{"url":"C:\\tmp\\x.json"}""" to false,
            """{"bucket":"main"}""" to false,
            """{"url":null}""" to false,
            "not json" to false,
            "" to false,
        )

        for ((record, expectConverged) in cases) {
            writeInstallRecord("gotify-desktop", record)

            // The bucket manifest persists between cases, so allow the overwrite.
            save(env, overwrite = true)

            val text = readInstallRecord("gotify-desktop")
            if (expectConverged) {
                assertFalse(text.contains("url"), "url must be dropped for: $record, got: $text")
                assertTrue(text.contains("\"bucket\""), "bucket must be set for: $record, got: $text")
            } else {
                assertEquals(record, text, "record must stay untouched for: $record")
            }
        }
    }
}
