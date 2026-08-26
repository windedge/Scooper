package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD tests for the SaveResult card support in CreateViewModel: the [gitCommitCommand] /
 * [buildSaveGitHint] / [classifySaveOutcome] pure functions and the [SaveResult] /
 * [SaveGitHint] data classes that drive the SaveResultPanel.
 *
 * The command is meant to be copied and run by the user in cmd.exe on Windows, so
 * quoting follows cmd rules: the bucket path is ALWAYS double-quoted (backslashes stay
 * literal because cmd has no backslash escape inside double quotes, and `&` inside the
 * quotes is not a command separator), `&&` chains the two git invocations, and app names
 * are already validated to [A-Za-z0-9._-] by the save dialog so no escaping is needed.
 *
 * TODO: the onSave dialog-clear reduce (saveResult = null when reopening the save dialog,
 * see CreateViewModel.onSave) is not covered by an Orbit/ContainerHost test because the
 * existing test suite has no mock infrastructure for ScoopAiBridge / ScoopClient /
 * AppsRepository / GitHistoryService. Add such a ViewModel-level test when a mock harness
 * is available.
 */
class CreateViewModelSaveResultTest {

    // ---------------------------------------------------------------
    // gitCommitCommand: cmd.exe quoting rules
    // ---------------------------------------------------------------

    @Test
    fun `git commit command always quotes the path even without spaces`() {
        assertEquals(
            "git -C \"C:/scoop/buckets/main\" add -- bucket/ripgrep.json && " +
                "git -C \"C:/scoop/buckets/main\" commit -m \"ripgrep: Add version 14.1.0\"",
            gitCommitCommand("C:/scoop/buckets/main", "ripgrep", version = "14.1.0"),
        )
    }

    @Test
    fun `git commit command quotes a path containing spaces`() {
        val command = gitCommitCommand("C:/Users/My Name/scoop/buckets/main", "fd", version = "10.2.0")
        assertTrue(command.startsWith("git -C \"C:/Users/My Name/scoop/buckets/main\" add"), command)
        assertTrue(command.contains("commit -m \"fd: Add version 10.2.0\""), command)
    }

    @Test
    fun `git commit command keeps backslashes as-is inside the quotes`() {
        val command = gitCommitCommand("C:\\Users\\xujl\\scoop\\buckets\\main", "fd")
        assertTrue(
            command.startsWith("git -C \"C:\\Users\\xujl\\scoop\\buckets\\main\" add"),
            command,
        )
        assertTrue(command.contains(" -C \"C:\\Users\\xujl\\scoop\\buckets\\main\" commit"), command)
        assertTrue(command.contains("commit -m \"fd: Add manifest\""), command)
    }

    @Test
    fun `git commit command quotes a path containing an ampersand`() {
        val command = gitCommitCommand("C:/scoop/one&two/buckets/main", "fd")
        // `&` inside double quotes is not a cmd command separator, so the && chain survives.
        assertTrue(command.startsWith("git -C \"C:/scoop/one&two/buckets/main\" add"), command)
        assertTrue(command.contains(" -C \"C:/scoop/one&two/buckets/main\" commit"), command)
    }

    @Test
    fun `git commit command quotes a trailing-slash path too`() {
        val command = gitCommitCommand("C:/scoop/buckets/main/", "fd")
        assertTrue(command.startsWith("git -C \"C:/scoop/buckets/main/\" add"), command)
    }

    // ---------------------------------------------------------------
    // gitCommitCommand: app name handling (legal APP_NAME_REGEX charset only)
    // ---------------------------------------------------------------

    @Test
    fun `git commit command handles legal app-name chars digits dot underscore hyphen`() {
        val command = gitCommitCommand("C:/scoop/buckets/main", "my.app_2-tool")
        assertTrue(command.contains("bucket/my.app_2-tool.json"), command)
        assertTrue(command.contains("-m \"my.app_2-tool: Add manifest\""), command)
    }

    // ---------------------------------------------------------------
    // gitCommitCommand: structure
    // ---------------------------------------------------------------

    @Test
    fun `git commit command contains exactly one -- separator before the pathspec`() {
        val command = gitCommitCommand("C:/scoop/buckets/main", "ripgrep")
        assertEquals(1, command.split(" -- ").size - 1, command)
        assertTrue(command.contains(" add -- bucket/ripgrep.json && git -C"), command)
    }

    @Test
    fun `git commit command chains add and commit with &&`() {
        val command = gitCommitCommand("C:/scoop/buckets/main", "fd")
        val add = "git -C \"C:/scoop/buckets/main\" add -- bucket/fd.json"
        val commit = "git -C \"C:/scoop/buckets/main\" commit -m \"fd: Add manifest\""
        assertEquals("$add && $commit", command)
    }

    // ---------------------------------------------------------------
    // buildSaveGitHint
    // ---------------------------------------------------------------

    @Test
    fun `buildSaveGitHint returns null for a non-git bucket`() {
        assertNull(
            buildSaveGitHint(
                isGitBucket = false,
                bucketDirPath = "C:/scoop/buckets/main",
                appName = "ripgrep",
            ),
        )
    }

    @Test
    fun `buildSaveGitHint builds command and bucketDir for a git bucket`() {
        val hint = buildSaveGitHint(
            isGitBucket = true,
            bucketDirPath = "C:/scoop/buckets/main",
            appName = "fd",
        )
        assertEquals(SaveGitHint("C:/scoop/buckets/main", gitCommitCommand("C:/scoop/buckets/main", "fd")), hint)
    }

    // ---------------------------------------------------------------
    // classifySaveOutcome: C2 best-effort indexing rules
    // ---------------------------------------------------------------

    @Test
    fun `classifySaveOutcome with write ok and index ok returns success with hint`() {
        val hint = SaveGitHint(bucketDir = "C:/scoop/buckets/main", command = "git add")
        val r = classifySaveOutcome(
            writeOk = true,
            indexOk = true,
            gitCommitted = false,
            gitHint = hint,
            appName = "fd",
            bucket = "main",
            targetPath = "p",
            overwritten = false,
        )
        assertTrue(r.success)
        assertTrue(r.indexedOk)
        assertEquals(false, r.gitCommitted)
        assertEquals(hint, r.gitHint)
    }

    @Test
    fun `classifySaveOutcome with git committed true sets flag and clears git hint`() {
        val hint = SaveGitHint(bucketDir = "C:/scoop/buckets/main", command = "git add")
        val r = classifySaveOutcome(
            writeOk = true,
            indexOk = true,
            gitCommitted = true,
            gitHint = hint,
            appName = "fd",
            bucket = "main",
            targetPath = "p",
            overwritten = false,
        )
        assertTrue(r.success)
        assertTrue(r.indexedOk)
        assertTrue(r.gitCommitted)
        assertNull(r.gitHint)
    }

    @Test
    fun `classifySaveOutcome degrades to saved-not-indexed when indexing threw`() {
        // C2: after a successful write an indexing / git-hint exception must NOT fail the
        // save; the card reports indexedOk=false and drops the git hint instead.
        val r = classifySaveOutcome(
            writeOk = true,
            indexOk = false,
            gitCommitted = false,
            gitHint = null,
            appName = "fd",
            bucket = "main",
            targetPath = "p",
            overwritten = false,
        )
        assertTrue(r.success)
        assertEquals(false, r.indexedOk)
        assertEquals(false, r.gitCommitted)
        assertNull(r.gitHint)
    }

    @Test
    fun `classifySaveOutcome treats write failure as the only fatal failure`() {
        // A failed write is not a success and never carries indexing state / a hint;
        // the caller shows an error toast and keeps the dialog open (no card rendered).
        val r = classifySaveOutcome(
            writeOk = false,
            indexOk = true,
            gitCommitted = true,
            gitHint = SaveGitHint(bucketDir = "d", command = "g"),
            appName = "fd",
            bucket = "main",
            targetPath = "p",
            overwritten = false,
        )
        assertEquals(false, r.success)
        assertEquals(false, r.indexedOk)
        assertEquals(false, r.gitCommitted)
        assertNull(r.gitHint)
    }

    // ---------------------------------------------------------------
    // SaveResult / SaveGitHint data classes
    // ---------------------------------------------------------------

    @Test
    fun `SaveResult defaults - gitHint is null when omitted`() {
        val r = SaveResult(
            success = true,
            appName = "app",
            bucket = "main",
            targetPath = "C:/scoop/buckets/main/bucket/app.json",
            overwritten = false,
            indexedOk = true,
        )
        assertNull(r.gitHint)
        assertTrue(r.success)
        assertTrue(r.indexedOk)
    }

    @Test
    fun `SaveResult carries git hint and overwritten flag`() {
        val hint = SaveGitHint(bucketDir = "C:/scoop/buckets/main", command = "git add")
        val r = SaveResult(
            success = true,
            appName = "app",
            bucket = "main",
            targetPath = "p",
            overwritten = true,
            indexedOk = false,
            gitHint = hint,
        )
        assertEquals(hint, r.gitHint)
        assertTrue(r.overwritten)
        assertEquals(false, r.indexedOk)
    }

    // ---------------------------------------------------------------
    // isNameConflictOnly
    // ---------------------------------------------------------------

    @Test
    fun `isNameConflictOnly returns true when all issues are name conflict notices`() {
        val issues = listOf(
            "App name 'minke' conflicts with an existing manifest in a local Scoop bucket. Choose a different name.",
        )
        assertTrue(isNameConflictOnly(issues, invalidJson = false))
    }

    @Test
    fun `isNameConflictOnly returns false when invalidJson is true or issues contain other errors`() {
        val conflictIssues = listOf(
            "App name 'minke' conflicts with an existing manifest in a local Scoop bucket.",
        )
        // Invalid JSON overrides conflict notice
        assertEquals(false, isNameConflictOnly(conflictIssues, invalidJson = true))

        val mixedIssues = listOf(
            "App name 'minke' conflicts with an existing manifest in a local Scoop bucket.",
            "Schema error: checkver is missing",
        )
        assertEquals(false, isNameConflictOnly(mixedIssues, invalidJson = false))

        // Empty issues
        assertEquals(false, isNameConflictOnly(emptyList(), invalidJson = false))
    }
}
