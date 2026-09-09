package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// ---------------------------------------------------------------
// ignoredNamesForConflictCheck: the pure exclusion set forwarded to
// the scoop-ai engine so its verify step does not re-flag the manifest
// the current session itself just saved as "conflicts with an existing
// manifest". Only a real saved name excludes; blank/null exclude nothing.
// ---------------------------------------------------------------
class CreateViewModelIgnoredNamesTest {

    @Test
    fun `saved app name becomes the exclusion set`() {
        assertEquals(setOf("sanad-agent"), ignoredNamesForConflictCheck("sanad-agent"))
    }

    @Test
    fun `blank app name yields an empty exclusion set`() {
        assertTrue(ignoredNamesForConflictCheck("").isEmpty())
        assertTrue(ignoredNamesForConflictCheck("   ").isEmpty())
    }

    @Test
    fun `null app name yields an empty exclusion set`() {
        assertTrue(ignoredNamesForConflictCheck(null).isEmpty())
    }
}
