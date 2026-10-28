package scooper.viewmodels

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.data.App
import scooper.data.Bucket
import scooper.service.BucketSaveEnv
import scooper.service.InstalledAppInfo
import java.io.File

/**
 * TDD tests for [SaveCoordinator], the seam that owns the save domain: the
 * dialog defaults, the manual save outcome classification, the AI tool path
 * and the save memory. Fakes cover the four ports so nothing touches a real
 * Scoop installation, git or the settings DB.
 */
class SaveCoordinatorTest {

    @TempDir
    lateinit var tempDir: File

    // ---------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------

    /** Temp-dir Scoop layout: baseDir/<bucket>/bucket/<name>.json. */
    private inner class FakeEnv(private val buckets: List<String>) : BucketSaveEnv {
        override val bucketsBaseDir: File get() = tempDir
        override val bucketNames: List<String> get() = buckets
        override fun installedSnapshot(): Map<String, InstalledAppInfo> = emptyMap()
        override fun buildAppsFromManifestNames(
            bucketDir: File,
            manifestNames: Set<String>,
            bucket: Bucket,
            installed: Map<String, InstalledAppInfo>,
        ): List<App> = manifestNames.mapNotNull { fileName ->
            val file = bucketDir.resolve("bucket/$fileName")
            if (!file.exists()) return@mapNotNull null
            App(name = fileName.removeSuffix(".json"), latestVersion = "1.2.3", bucket = Bucket(bucket.name))
        }
    }

    private class FakeGit(
        private val isGit: Boolean = true,
        private val commitOk: Boolean = true,
    ) : GitBucketPort {
        val commitCalls = mutableListOf<String>()
        var lastVersion: String? = null

        override fun isGitBucket(dir: File): Boolean = isGit

        override fun commitManifest(dir: File, name: String, isUpdate: Boolean, version: String?): Result<String> {
            commitCalls.add("$name:$isUpdate")
            lastVersion = version
            return if (commitOk) Result.success("abc123") else Result.failure(IllegalStateException("git broke"))
        }
    }

    private class FakeMemory(
        private val lastBucket: String? = null,
        private val commitPreference: Boolean? = null,
    ) : SaveMemory {
        val readBuckets = mutableListOf<String>()
        val persistedBuckets = mutableListOf<String>()
        val persistedCommitPreferences = mutableListOf<Boolean>()

        override suspend fun readLastBucket(): String? {
            readBuckets.add("read")
            return lastBucket
        }

        override suspend fun persistLastBucket(bucket: String) {
            persistedBuckets.add(bucket)
        }

        override suspend fun readCommitPreference(): Boolean? = commitPreference

        override suspend fun persistCommitPreference(commit: Boolean) {
            persistedCommitPreferences.add(commit)
        }
    }

    private class FakeIndex(private val ok: Boolean = true) {
        val calls = mutableListOf<String?>()

        val index: suspend (String, App?) -> Boolean = { _, app ->
            calls.add(app?.name)
            app != null && ok
        }
    }

    private fun coordinator(
        env: BucketSaveEnv,
        git: GitBucketPort = FakeGit(),
        index: suspend (String, App?) -> Boolean = FakeIndex().index,
        memory: SaveMemory = FakeMemory(),
        warnings: MutableList<String> = mutableListOf(),
    ) = SaveCoordinator(env, git, index, memory, { warnings.add(it) })

    private fun dialog(
        suggestedAppName: String? = "fd",
        refs: List<Pair<String, String>> = emptyList(),
        currentBucket: String = "",
    ) = SaveDialogRequest(suggestedAppName, refs, currentBucket)

    private fun request(
        appName: String = "fd",
        bucket: String = "main",
        commitToGit: Boolean = true,
        allowOverwrite: Boolean = false,
        manifestJson: String = """{"version":"1.2.3"}""",
    ) = SaveRequest(manifestJson, appName, bucket, commitToGit, allowOverwrite)

    // ---------------------------------------------------------------------
    // openDialog: bucket default priority
    // ---------------------------------------------------------------------

    @Test
    fun `current bucket wins over the remembered last bucket`() = runBlocking {
        val defaults = coordinator(
            env = FakeEnv(listOf("main", "extras")),
            memory = FakeMemory(lastBucket = "extras"),
        ).openDialog(dialog(currentBucket = "main"))

        assertEquals("main", defaults.bucket)
        assertEquals(listOf("extras", "main"), defaults.buckets, "options are sorted for a stable menu")
        assertEquals("fd", defaults.appName, "no reference lock keeps the suggested name")
    }

    @Test
    fun `remembered last bucket wins over main`() = runBlocking {
        val memory = FakeMemory(lastBucket = "extras")

        val defaults = coordinator(
            env = FakeEnv(listOf("main", "extras")),
            memory = memory,
        ).openDialog(dialog(currentBucket = ""))

        assertEquals("extras", defaults.bucket)
        assertEquals(1, memory.readBuckets.size, "the bucket memory is read exactly once")
    }

    // ---------------------------------------------------------------------
    // openDialog: reference lock
    // ---------------------------------------------------------------------

    @Test
    fun `reference lock pins the bucket and name and produces the hint line`() = runBlocking {
        val defaults = coordinator(
            env = FakeEnv(listOf("main", "extras")),
            memory = FakeMemory(lastBucket = "main"),
        ).openDialog(
            dialog(
                suggestedAppName = "fd",
                refs = listOf("extras" to "fd"),
                currentBucket = "main",
            ),
        )

        assertEquals("extras", defaults.bucket, "the reference bucket overrides the memory")
        assertEquals("fd", defaults.appName)
        assertEquals("Pre-filled from @extras/fd reference", defaults.refHint)
    }

    @Test
    fun `no reference lock clears the hint line`() = runBlocking {
        val defaults = coordinator(env = FakeEnv(listOf("main"))).openDialog(dialog(refs = listOf("main" to "other")))

        assertNull(defaults.refHint, "a name mismatch must not fabricate a hint")
    }

    // ---------------------------------------------------------------------
    // openDialog: commit checkbox inference
    // ---------------------------------------------------------------------

    @Test
    fun `git bucket pre-checks the box only when the preference was checked`() = runBlocking {
        val checked = coordinator(env = FakeEnv(listOf("main")), memory = FakeMemory(commitPreference = true))
            .openDialog(dialog())
        val unchecked = coordinator(env = FakeEnv(listOf("main")), memory = FakeMemory(commitPreference = false))
            .openDialog(dialog())

        assertTrue(checked.commitToGit)
        assertTrue(checked.isGitBucket)
        assertFalse(unchecked.commitToGit, "a remembered unchecked box stays unchecked")
    }

    @Test
    fun `non git bucket never pre-checks the box`() = runBlocking {
        val defaults = coordinator(
            env = FakeEnv(listOf("main")),
            git = FakeGit(isGit = false),
            memory = FakeMemory(commitPreference = true),
        ).openDialog(dialog())

        assertFalse(defaults.isGitBucket)
        assertFalse(defaults.commitToGit)
    }

    // ---------------------------------------------------------------------
    // save: validation
    // ---------------------------------------------------------------------

    @Test
    fun `invalid app name is rejected without writing`() = runBlocking {
        val outcome = coordinator(env = FakeEnv(listOf("main"))).save(request(appName = "bad name!"))

        assertEquals(SaveOutcome.InvalidName, outcome)
        assertFalse(tempDir.resolve("main").exists(), "nothing may be created for an invalid name")
    }

    @Test
    fun `unknown bucket is rejected without writing`() = runBlocking {
        val outcome = coordinator(env = FakeEnv(listOf("main"))).save(request(bucket = "nope"))

        assertEquals(SaveOutcome.InvalidBucket, outcome)
        assertFalse(tempDir.resolve("nope").exists())
    }

    // ---------------------------------------------------------------------
    // save: overwrite / write failures
    // ---------------------------------------------------------------------

    @Test
    fun `existing manifest without overwrite asks for confirmation and keeps the file`() = runBlocking {
        val existing = tempDir.resolve("main/bucket/fd.json")
        existing.parentFile.mkdirs()
        existing.writeText("""{"version":"0.9"}""")

        val outcome = coordinator(env = FakeEnv(listOf("main"))).save(request())

        assertTrue(outcome is SaveOutcome.NeedsOverwrite)
        assertEquals("""{"version":"0.9"}""", existing.readText(), "the existing file must stay untouched")
    }

    @Test
    fun `write failure is reported as Failed and no result card is produced`() = runBlocking {
        // A regular file where the bucket directory belongs makes the write fail.
        tempDir.resolve("main").writeText("not a directory")

        val outcome = coordinator(env = FakeEnv(listOf("main"))).save(request())

        assertTrue(outcome is SaveOutcome.Failed)
        assertTrue((outcome as SaveOutcome.Failed).masked.isNotBlank(), "the failure reason must be surfaced")
    }

    // ---------------------------------------------------------------------
    // save: best-effort degradation
    // ---------------------------------------------------------------------

    @Test
    fun `index failure still saves and degrades to an unindexed result`() = runBlocking {
        val warnings = mutableListOf<String>()
        val index = FakeIndex(ok = false)

        val outcome = coordinator(
            env = FakeEnv(listOf("main")),
            git = FakeGit(isGit = false),
            index = index.index,
            warnings = warnings,
        ).save(request())
        val saved = outcome as SaveOutcome.Saved

        assertTrue(tempDir.resolve("main/bucket/fd.json").exists(), "the file must be written")
        assertFalse(saved.saveResult.indexedOk)
        assertTrue(warnings.any { it.contains("indexed") }, "the indexing miss must be warned, got: $warnings")
    }

    @Test
    fun `git commit failure still saves and degrades to the manual hint`() = runBlocking {
        val warnings = mutableListOf<String>()

        val outcome = coordinator(
            env = FakeEnv(listOf("main")),
            git = FakeGit(isGit = true, commitOk = false),
            warnings = warnings,
        ).save(request(commitToGit = true))
        val saved = outcome as SaveOutcome.Saved

        assertFalse(saved.saveResult.gitCommitted)
        assertNotNull(saved.saveResult.gitHint, "a failed commit degrades to the manual git hint")
        assertTrue(warnings.isNotEmpty(), "the commit failure must be warned")
    }

    // ---------------------------------------------------------------------
    // save: memory writes
    // ---------------------------------------------------------------------

    @Test
    fun `successful save remembers the bucket and the commit preference`() = runBlocking {
        val memory = FakeMemory(commitPreference = true)

        val outcome = coordinator(
            env = FakeEnv(listOf("main", "extras")),
            memory = memory,
        ).save(request(bucket = "extras", commitToGit = false))
        val saved = outcome as SaveOutcome.Saved

        assertEquals("extras", saved.bucket)
        assertEquals("fd", saved.appName)
        assertEquals(listOf("extras"), memory.persistedBuckets)
        assertEquals(listOf(false), memory.persistedCommitPreferences, "the confirmed checkbox value is remembered")
    }

    @Test
    fun `failed save does not touch the memory`() = runBlocking {
        val memory = FakeMemory()
        tempDir.resolve("main").writeText("not a directory")

        val outcome = coordinator(env = FakeEnv(listOf("main")), memory = memory).save(request())

        assertTrue(outcome is SaveOutcome.Failed)
        assertTrue(memory.persistedBuckets.isEmpty())
        assertTrue(memory.persistedCommitPreferences.isEmpty())
    }

    // ---------------------------------------------------------------------
    // saveForTool: AI save_to_bucket path
    // ---------------------------------------------------------------------

    @Test
    fun `tool save without a bucket returns needsBucket and writes nothing`() = runBlocking {
        val git = FakeGit()

        val result = coordinator(env = FakeEnv(listOf("extras", "main")), git = git).saveForTool(
            ToolSaveRequest(
                manifestJson = """{"version":"1.2.3"}""",
                bucket = null,
                name = "fd",
                overwrite = false,
                suggestedAppName = "fd",
                commitToGit = true,
            ),
        )

        assertFalse(result.manifestSaved)
        assertNull(result.saveResult)
        assertTrue(result.resultJson.contains("needsBucket"), "the model must be told to ask for a bucket")
        assertEquals(0, git.commitCalls.size)
        assertFalse(tempDir.resolve("main/bucket/fd.json").exists())
    }

    @Test
    fun `tool save with an explicit bucket writes the manifest`() = runBlocking {
        val git = FakeGit()

        val result = coordinator(env = FakeEnv(listOf("main")), git = git).saveForTool(
            ToolSaveRequest(
                manifestJson = """{"version":"1.2.3"}""",
                bucket = "main",
                name = null,
                overwrite = false,
                suggestedAppName = "fd",
                commitToGit = true,
            ),
        )

        assertTrue(result.manifestSaved)
        assertEquals("fd", result.suggestedAppName)
        assertEquals(1, git.commitCalls.size)
        assertTrue(tempDir.resolve("main/bucket/fd.json").exists())
    }

    // ---------------------------------------------------------------------
    // cancellation contract
    // ---------------------------------------------------------------------

    @Test
    fun `cancellation from the index step is rethrown instead of degrading`() = runBlocking {
        val cancelling: suspend (String, App?) -> Boolean = { _, _ -> throw kotlinx.coroutines.CancellationException("stop") }

        var thrown: Throwable? = null
        try {
            coordinator(env = FakeEnv(listOf("main")), index = cancelling).save(request())
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue(thrown is kotlinx.coroutines.CancellationException, "CancellationException must propagate, got: $thrown")
    }
}
