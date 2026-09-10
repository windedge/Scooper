package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD tests for [resolveDefaultSaveBucket]: the pure selection rule deciding
 * which bucket the save dialog preselects. Priority: current selection, then
 * the last used bucket from Settings, then "main", then the first bucket.
 */
class CreateViewModelSaveBucketMemoryTest {

    private val buckets = listOf("main", "extras", "games")

    // ---------------------------------------------------------------
    // current selection still valid wins
    // ---------------------------------------------------------------

    @Test
    fun `current selection wins when it is still in the bucket list`() {
        val result = resolveDefaultSaveBucket(
            current = "extras",
            lastUsed = "games",
            buckets = buckets,
        )
        assertEquals("extras", result)
    }

    @Test
    fun `lastUsed wins when current selection is empty`() {
        val result = resolveDefaultSaveBucket(
            current = "",
            lastUsed = "games",
            buckets = buckets,
        )
        assertEquals("games", result)
    }

    // ---------------------------------------------------------------
    // both invalid falls back to main
    // ---------------------------------------------------------------

    @Test
    fun `falls back to main when neither current nor lastUsed matches`() {
        val result = resolveDefaultSaveBucket(
            current = "deleted-bucket",
            lastUsed = "removed-bucket",
            buckets = buckets,
        )
        assertEquals("main", result)
    }

    // ---------------------------------------------------------------
    // no main bucket takes the first entry
    // ---------------------------------------------------------------

    @Test
    fun `uses the first bucket when main is missing`() {
        val result = resolveDefaultSaveBucket(
            current = "",
            lastUsed = "",
            buckets = listOf("games", "nirsoft"),
        )
        assertEquals("games", result)
    }

    // ---------------------------------------------------------------
    // lastUsed pointing at a deleted bucket is ignored
    // ---------------------------------------------------------------

    @Test
    fun `lastUsed pointing at a deleted bucket is ignored`() {
        val result = resolveDefaultSaveBucket(
            current = "",
            lastUsed = "gone",
            buckets = buckets,
        )
        assertEquals("main", result)
    }

    // ---------------------------------------------------------------
    // commit-to-git checkbox default combines bucket type and the
    // remembered checkbox choice
    // ---------------------------------------------------------------

    @Test
    fun `git bucket keeps the checkbox checked when last preference was true`() {
        assertTrue(resolveDefaultCommitToGit(isGitBucket = true, lastPreference = true))
    }

    @Test
    fun `git bucket leaves the checkbox unchecked when last preference was false`() {
        assertFalse(resolveDefaultCommitToGit(isGitBucket = true, lastPreference = false))
    }

    @Test
    fun `non git bucket never preselects commit regardless of preference`() {
        assertFalse(resolveDefaultCommitToGit(isGitBucket = false, lastPreference = true))
    }
}
