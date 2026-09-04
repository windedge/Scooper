package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.coroutines.runBlocking
import scooper.data.App
import scooper.data.Bucket
import scooper.service.BucketSaveEnv
import scooper.service.InstalledAppInfo
import java.io.File

/**
 * TDD tests for the AI `save_to_bucket` path: the [resolveSaveBucket] default
 * bucket rules, the shared [saveManifestCore] (also used by the manual save
 * dialog) and the [aiSaveToBucket] wrapper that maps the outcome to the JSON
 * reply for the model plus the CreateState updates.
 */
class CreateViewModelSaveToBucketTest {

    @TempDir
    lateinit var tempDir: File

    // ---------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------

    /** Fake scoop environment backed by a temp dir layout: baseDir/<bucket>/bucket/<name>.json. */
    private inner class FakeScoopEnv(
        buckets: List<String>,
    ) : BucketSaveEnv {
        val buckets: List<String> = buckets
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

    /** Records of the injected index/git collaborators. */
    private inner class SaveDeps(
        private val isGit: Boolean = true,
        commitOk: Boolean = true,
    ) {
        val indexedBuckets = mutableListOf<String>()
        val indexedApps = mutableListOf<String?>()
        val commitCalls = mutableListOf<Triple<File, String, Boolean>>()
        var lastCommitVersion: String? = null

        val indexSaved: suspend (String, App?) -> Boolean = { bucket, app ->
            indexedBuckets.add(bucket)
            indexedApps.add(app?.name)
            app != null
        }
        val isGitBucket: (File) -> Boolean = { isGit }
        val commitManifest: (File, String, Boolean, String?) -> Result<String> = { dir, appName, isUpdate, version ->
            commitCalls.add(Triple(dir, appName, isUpdate))
            lastCommitVersion = version
            if (commitOk) Result.success("abc123") else Result.failure(IllegalStateException("git failed"))
        }
    }

    private fun aiSave(
        manifestJson: String = """{"version":"1.2.3","bin":["app.exe"]}""",
        bucketArg: String? = null,
        nameArg: String? = null,
        overwrite: Boolean = false,
        suggestedAppName: String? = "fd",
        lastBucket: String = "",
        commitToGit: Boolean = true,
        env: FakeScoopEnv,
        deps: SaveDeps = SaveDeps(),
    ): AiSaveToBucketResult = runBlocking {
        aiSaveToBucket(
            manifestJson = manifestJson,
            bucketArg = bucketArg,
            nameArg = nameArg,
            overwrite = overwrite,
            suggestedAppName = suggestedAppName,
            lastBucket = lastBucket,
            commitToGit = commitToGit,
            scoopClient = env,
            indexSaved = deps.indexSaved,
            isGitBucket = deps.isGitBucket,
            commitManifest = deps.commitManifest,
        )
    }

    private fun resultJsonField(json: String, field: String): String? {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(json)
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
        return (obj[field] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }

    // ---------------------------------------------------------------------
    // resolveSaveBucket: last bucket -> main -> first
    // ---------------------------------------------------------------------

    @Test
    fun `explicit bucket wins over the default rules`() {
        assertEquals(
            "extras",
            resolveSaveBucket("extras", lastBucket = "main", buckets = listOf("main", "extras")),
        )
    }

    @Test
    fun `no explicit bucket falls back to the last used bucket`() {
        assertEquals(
            "extras",
            resolveSaveBucket(null, lastBucket = "extras", buckets = listOf("main", "extras")),
        )
    }

    @Test
    fun `falls back to main when the last used bucket no longer exists`() {
        assertEquals(
            "main",
            resolveSaveBucket(null, lastBucket = "gone", buckets = listOf("main", "extras")),
        )
    }

    @Test
    fun `falls back to the first bucket when main is absent`() {
        assertEquals(
            "extras",
            resolveSaveBucket(null, lastBucket = "", buckets = listOf("extras", "games")),
        )
    }

    @Test
    fun `explicit unknown bucket resolves to null so the caller can reject it`() {
        assertNull(resolveSaveBucket("nope", lastBucket = "main", buckets = listOf("main")))
    }

    @Test
    fun `no buckets at all resolves to null`() {
        assertNull(resolveSaveBucket(null, lastBucket = "", buckets = emptyList()))
    }

    // ---------------------------------------------------------------------
    // aiSaveToBucket: success path
    // ---------------------------------------------------------------------

    @Test
    fun `successful save writes the manifest, reports path and sets manifestSaved`() {
        val env = FakeScoopEnv(buckets = listOf("main", "extras"))
        val deps = SaveDeps()

        val result = aiSave(bucketArg = "main", nameArg = "fd", env = env, deps = deps)

        assertTrue(result.manifestSaved, "a successful save must set manifestSaved")
        assertNotNull(result.saveResult)
        assertEquals("fd", result.saveResult?.appName)
        assertEquals("main", result.saveResult?.bucket)
        assertEquals("fd", result.suggestedAppName)

        val target = tempDir.resolve("main").resolve("bucket").resolve("fd.json")
        assertTrue(target.exists(), "manifest file must be written")
        assertTrue(target.readText().contains("\"version\""), "written file must contain the manifest")

        assertEquals("true", resultJsonField(result.resultJson, "success"))
        val path = resultJsonField(result.resultJson, "path")
        assertNotNull(path)
        assertTrue(path!!.endsWith("fd.json"), "path must point at the saved manifest, got: $path")
        assertEquals("main", resultJsonField(result.resultJson, "bucket"))
        assertEquals("fd", resultJsonField(result.resultJson, "name"))
    }

    @Test
    fun `successful save indexes the app and commits with the manifest version`() {
        val env = FakeScoopEnv(buckets = listOf("main"))
        val deps = SaveDeps()

        val result = aiSave(bucketArg = "main", nameArg = "fd", env = env, deps = deps)

        assertEquals(listOf("main"), deps.indexedBuckets, "index step must receive the bucket")
        assertEquals(listOf("fd"), deps.indexedApps, "index step must receive the parsed app")
        assertEquals(1, deps.commitCalls.size, "git commit must run for a git bucket with commit enabled")
        assertEquals("1.2.3", deps.lastCommitVersion, "commit must carry the manifest version")
        assertNull(result.saveResult?.gitHint, "no git hint when the commit succeeded")
        assertEquals(true, result.saveResult?.gitCommitted)
    }

    @Test
    fun `bucket and name default to last used bucket and suggested app name`() {
        val env = FakeScoopEnv(buckets = listOf("main", "extras"))

        val result = aiSave(lastBucket = "extras", suggestedAppName = "ripgrep", env = env)

        assertTrue(result.manifestSaved, "defaults must produce a successful save")
        assertEquals("extras", resultJsonField(result.resultJson, "bucket"))
        assertEquals("ripgrep", resultJsonField(result.resultJson, "name"))
    }

    @Test
    fun `git commit failure still saves and produces a git hint`() {
        val env = FakeScoopEnv(buckets = listOf("main"))
        val deps = SaveDeps(commitOk = false)

        val result = aiSave(bucketArg = "main", nameArg = "fd", env = env, deps = deps)

        assertTrue(result.manifestSaved, "a failed commit must not fail the save (file is on disk)")
        assertNotNull(result.saveResult?.gitHint, "an uncommitted git bucket must produce a git hint")
        assertNull(resultJsonField(result.resultJson, "needsOverwrite"))
    }

    // ---------------------------------------------------------------------
    // aiSaveToBucket: needs-overwrite path
    // ---------------------------------------------------------------------

    @Test
    fun `existing target without overwrite returns needsOverwrite and does not save`() {
        val env = FakeScoopEnv(buckets = listOf("main"))
        val deps = SaveDeps()
        val existing = tempDir.resolve("main").resolve("bucket").resolve("fd.json")
        existing.parentFile?.mkdirs()
        existing.writeText("""{"version":"0.9"}""")

        val result = aiSave(bucketArg = "main", nameArg = "fd", env = env, deps = deps)

        assertFalse(result.manifestSaved, "an existing file without overwrite must not save")
        assertNull(result.saveResult)
        assertEquals("false", resultJsonField(result.resultJson, "success"))
        assertEquals("already exists", resultJsonField(result.resultJson, "error"))
        assertEquals("true", resultJsonField(result.resultJson, "needsOverwrite"))
        assertEquals("""{"version":"0.9"}""", existing.readText(), "existing file must stay untouched")
        assertEquals(0, deps.commitCalls.size, "no git commit on a rejected save")
    }

    @Test
    fun `existing target with overwrite true replaces the file`() {
        val env = FakeScoopEnv(buckets = listOf("main"))
        val deps = SaveDeps()
        val existing = tempDir.resolve("main").resolve("bucket").resolve("fd.json")
        existing.parentFile?.mkdirs()
        existing.writeText("""{"version":"0.9"}""")

        val result = aiSave(bucketArg = "main", nameArg = "fd", overwrite = true, env = env, deps = deps)

        assertTrue(result.manifestSaved, "overwrite=true must save")
        assertEquals("true", resultJsonField(result.resultJson, "success"))
        assertTrue(existing.readText().contains("1.2.3"), "existing file must be replaced")
        assertEquals(true, result.saveResult?.overwritten)
    }

    // ---------------------------------------------------------------------
    // aiSaveToBucket: failure paths
    // ---------------------------------------------------------------------

    @Test
    fun `blank manifest fails`() {
        val result = aiSave(manifestJson = "   ", env = FakeScoopEnv(buckets = listOf("main")))

        assertFalse(result.manifestSaved)
        assertEquals("false", resultJsonField(result.resultJson, "success"))
        assertNotNull(resultJsonField(result.resultJson, "error"))
    }

    @Test
    fun `explicit unknown bucket fails with a json error`() {
        val env = FakeScoopEnv(buckets = listOf("main"))

        val result = aiSave(bucketArg = "nope", nameArg = "fd", env = env)

        assertFalse(result.manifestSaved)
        assertEquals("false", resultJsonField(result.resultJson, "success"))
        val error = resultJsonField(result.resultJson, "error")
        assertNotNull(error)
        assertTrue(error!!.contains("nope"), "error must name the offending bucket")
    }

    @Test
    fun `no buckets fails`() {
        val result = aiSave(env = FakeScoopEnv(buckets = emptyList()))

        assertFalse(result.manifestSaved)
        val error = resultJsonField(result.resultJson, "error")
        assertNotNull(error)
        assertTrue(error!!.contains("bucket", ignoreCase = true))
    }

    @Test
    fun `missing name and suggested app name fails`() {
        val result = aiSave(nameArg = null, suggestedAppName = null, env = FakeScoopEnv(buckets = listOf("main")))

        assertFalse(result.manifestSaved)
        assertEquals("false", resultJsonField(result.resultJson, "success"))
    }

    @Test
    fun `invalid app name fails`() {
        val result = aiSave(nameArg = "bad name!", env = FakeScoopEnv(buckets = listOf("main")))

        assertFalse(result.manifestSaved)
        val error = resultJsonField(result.resultJson, "error")
        assertNotNull(error)
        assertTrue(error!!.contains("name"), "error must mention the app name rule")
    }

    @Test
    fun `non git bucket saves without commit and without hint`() {
        val env = FakeScoopEnv(buckets = listOf("main"))
        val deps = SaveDeps(isGit = false)

        val result = aiSave(bucketArg = "main", nameArg = "fd", env = env, deps = deps)

        assertTrue(result.manifestSaved)
        assertEquals(0, deps.commitCalls.size, "non-git bucket must not commit")
        assertNull(result.saveResult?.gitHint, "non-git bucket must not produce a git hint")
    }
}
