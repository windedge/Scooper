package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Typed key-level output of [computeManifestDiff]. Display and synthesis wording is covered elsewhere. */
class ManifestDiffTypingTest {

    @Test
    fun `computeManifestDiff returns empty for a blank previous manifest`() {
        val diff = computeManifestDiff("", """{"version":"1.0.0"}""")
        assertTrue(diff.isEmpty())
    }

    @Test
    fun `computeManifestDiff returns empty for a blank new manifest`() {
        val diff = computeManifestDiff("""{"version":"1.0.0"}""", "")
        assertTrue(diff.isEmpty())
    }

    @Test
    fun `computeManifestDiff returns empty for identical manifests`() {
        val json = """{"version":"1.0.0"}"""
        assertTrue(computeManifestDiff(json, json).isEmpty())
    }

    @Test
    fun `computeManifestDiff returns Generic when either side is unparseable`() {
        assertEquals(
            listOf<ManifestDiffEntry>(ManifestDiffEntry.Generic),
            computeManifestDiff("not json {{", """{"version":"1.0.0"}"""),
        )
        assertEquals(
            listOf<ManifestDiffEntry>(ManifestDiffEntry.Generic),
            computeManifestDiff("""{"version":"1.0.0"}""", "not json {{"),
        )
    }

    @Test
    fun `computeManifestDiff types added removed and modified keys`() {
        val oldJson = """{"version":"1.0.0","description":"test","bin":["app.exe"],"notes":"some notes"}"""
        val newJson = """{"version":"1.1.0","description":"test","shortcuts":[["app.exe","App"]]}"""
        val diff = computeManifestDiff(oldJson, newJson)
        assertTrue(diff.contains(ManifestDiffEntry.Removed("bin")))
        assertTrue(diff.contains(ManifestDiffEntry.Removed("notes")))
        assertTrue(diff.contains(ManifestDiffEntry.Added("shortcuts")))
        assertTrue(diff.contains(ManifestDiffEntry.VersionChanged("1.0.0", "1.1.0")))
        assertEquals(4, diff.size)
    }

    @Test
    fun `computeManifestDiff types a non-primitive version change as Modified`() {
        val oldJson = """{"version":["1.0.0"]}"""
        val newJson = """{"version":["1.1.0"]}"""
        val diff = computeManifestDiff(oldJson, newJson)
        assertTrue(diff.contains(ManifestDiffEntry.Modified("version")))
    }

    @Test
    fun `computeManifestDiff sorts keys deterministically`() {
        val oldJson = """{"url":"a"}"""
        val newJson = """{"bin":["x"],"url":"a","version":"2"}"""
        val diff = computeManifestDiff(oldJson, newJson)
        assertEquals(listOf(ManifestDiffEntry.Added("bin"), ManifestDiffEntry.Added("version")), diff)
    }
}
