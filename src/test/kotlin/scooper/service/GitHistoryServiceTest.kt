package scooper.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import scooper.util.execute
import java.io.File

class GitHistoryServiceTest {

    private val service = GitHistoryService()

    @Test
    fun `buildCommitMessage formats standard Scoop commit messages`() {
        assertEquals(
            "ripgrep: Add version 14.1.0",
            GitHistoryService.buildCommitMessage("ripgrep", "14.1.0", isUpdate = false),
        )
        assertEquals(
            "ripgrep: Add version 14.1.0",
            GitHistoryService.buildCommitMessage("ripgrep", "v14.1.0", isUpdate = false),
        )
        assertEquals(
            "ripgrep: Update to version 14.1.1",
            GitHistoryService.buildCommitMessage("ripgrep", "14.1.1", isUpdate = true),
        )
        assertEquals(
            "ripgrep: Add manifest",
            GitHistoryService.buildCommitMessage("ripgrep", null, isUpdate = false),
        )
        assertEquals(
            "ripgrep: Update manifest",
            GitHistoryService.buildCommitMessage("ripgrep", null, isUpdate = true),
        )
    }

    @Test
    fun `isGitBucket returns false for regular folder and true when dot-git exists`(@TempDir tempDir: File) {
        assertFalse(service.isGitBucket(tempDir))

        val gitDir = tempDir.resolve(".git")
        gitDir.mkdirs()
        assertTrue(service.isGitBucket(tempDir))
    }

    @Test
    fun `commitManifest returns failure when directory is not a git repo`(@TempDir tempDir: File) {
        val result = service.commitManifest(tempDir, "ripgrep", isUpdate = false)
        assertTrue(result.isFailure)
        assertEquals("Not a git repository", result.exceptionOrNull()?.message)
    }

    @Test
    fun `commitManifest stages and commits manifest in a git repo with Scoop style message`(@TempDir tempDir: File) {
        // Initialize git repo in tempDir
        execute("git", "init", asShell = false, workingDir = tempDir)
        execute("git", "config", "user.name", "TestUser", asShell = false, workingDir = tempDir)
        execute("git", "config", "user.email", "test@example.com", asShell = false, workingDir = tempDir)

        // Create bucket/ripgrep.json
        val bucketFolder = tempDir.resolve("bucket")
        bucketFolder.mkdirs()
        val manifest = bucketFolder.resolve("ripgrep.json")
        manifest.writeText("""{"version":"14.1.0"}""")

        val result = service.commitManifest(tempDir, "ripgrep", isUpdate = false, version = "14.1.0")
        assertTrue(result.isSuccess, "commitManifest should succeed: ${result.exceptionOrNull()?.message}")

        // Check git log has the commit
        val logResult = execute("git", "log", "-1", "--pretty=%s", asShell = false, workingDir = tempDir)
        assertEquals(0, logResult.resultCode)
        assertEquals("ripgrep: Add version 14.1.0", logResult.output.firstOrNull()?.trim())

        // Modify and commit update
        manifest.writeText("""{"version":"14.1.1"}""")
        val updateResult = service.commitManifest(tempDir, "ripgrep", isUpdate = true, version = "14.1.1")
        assertTrue(updateResult.isSuccess)

        val logResult2 = execute("git", "log", "-1", "--pretty=%s", asShell = false, workingDir = tempDir)
        assertEquals("ripgrep: Update to version 14.1.1", logResult2.output.firstOrNull()?.trim())
    }
}
