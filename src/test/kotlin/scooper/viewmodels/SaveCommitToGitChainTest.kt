package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.service.BucketSaveEnv
import scooper.service.FakeBucketSaveEnv
import java.io.File

/**
 * TDD for the Bug 2 break: saveManifestCore only ran the commit when
 * commitToGit was true, but the dialog checkbox value never reached the
 * commit decision on the overwrite path, so a checked "Commit to Git" save
 * produced an uncommitted bucket with a manual git hint instead of a commit.
 *
 * These tests pin the contract: a git bucket with commitToGit=true and a
 * writable repo commits through the injected commitManifest; commitToGit=false
 * never commits; a commit failure degrades to the manual hint.
 */
class SaveCommitToGitChainTest {

    @TempDir
    lateinit var tempDir: File

    private fun envWith(buckets: List<String>): BucketSaveEnv = FakeBucketSaveEnv(tempDir, buckets)

    private class RecordingDeps(isGit: Boolean) {
        val commitCalls = mutableListOf<String>()
        val isGitBucket: (File) -> Boolean = { isGit }
        val commitManifest: (File, String, Boolean, String?) -> Result<String> = { dir, appName, isUpdate, version ->
            commitCalls.add("$appName:$isUpdate:$version")
            Result.success("committed")
        }
        val indexSaved: suspend (String, scooper.data.App?) -> Boolean = { _, _ -> true }
        val warnings = mutableListOf<String>()
        val onWarn: (String) -> Unit = { warnings.add(it) }
    }

    private suspend fun save(
        commitToGit: Boolean,
        env: BucketSaveEnv,
        deps: RecordingDeps,
        overwrite: Boolean = false,
    ): BucketSaveOutcome = saveManifestCore(
        manifestJson = """{"version":"1.2.3"}""",
        bucket = "main",
        appName = "fd",
        commitToGit = commitToGit,
        overwrite = overwrite,
        scoopClient = env,
        indexSaved = deps.indexSaved,
        isGitBucket = deps.isGitBucket,
        commitManifest = deps.commitManifest,
        onWarn = deps.onWarn,
    )

    @Test
    fun `checked checkbox on a git bucket commits through the service`() = kotlinx.coroutines.runBlocking {
        val env = envWith(listOf("main"))
        val deps = RecordingDeps(isGit = true)

        val outcome = save(commitToGit = true, env = env, deps = deps)

        assertTrue(outcome is BucketSaveOutcome.Saved)
        assertEquals(1, deps.commitCalls.size, "commitToGit=true must call commitManifest once")
        assertEquals(listOf("fd:false:1.2.3"), deps.commitCalls)
        assertTrue((outcome as BucketSaveOutcome.Saved).gitCommitted)
        assertEquals(null, outcome.gitHint, "committed saves must not show the manual hint")
    }

    @Test
    fun `unchecked checkbox never commits and falls back to the manual hint`() = kotlinx.coroutines.runBlocking {
        val env = envWith(listOf("main"))
        val deps = RecordingDeps(isGit = true)

        val outcome = save(commitToGit = false, env = env, deps = deps)

        assertTrue(outcome is BucketSaveOutcome.Saved)
        assertEquals(0, deps.commitCalls.size, "commitToGit=false must not commit")
        assertTrue((outcome as BucketSaveOutcome.Saved).gitHint != null)
        assertFalse((outcome as BucketSaveOutcome.Saved).gitCommitted)
    }

    @Test
    fun `commit failure keeps the save and produces the manual hint`() = kotlinx.coroutines.runBlocking {
        val env = envWith(listOf("main"))
        val deps = RecordingDeps(isGit = true)
        val failing: (File, String, Boolean, String?) -> Result<String> = { _, _, _, _ ->
            Result.failure(IllegalStateException("git unavailable"))
        }

        val outcome = saveManifestCore(
            manifestJson = """{"version":"1.2.3"}""",
            bucket = "main",
            appName = "fd",
            commitToGit = true,
            overwrite = false,
            scoopClient = env,
            indexSaved = deps.indexSaved,
            isGitBucket = deps.isGitBucket,
            commitManifest = failing,
            onWarn = deps.onWarn,
        )

        assertTrue(outcome is BucketSaveOutcome.Saved)
        assertTrue(!(outcome as BucketSaveOutcome.Saved).gitCommitted)
        assertTrue(outcome.gitHint != null, "failed commit degrades to the manual hint")
        assertTrue(deps.warnings.isNotEmpty())
    }
}
