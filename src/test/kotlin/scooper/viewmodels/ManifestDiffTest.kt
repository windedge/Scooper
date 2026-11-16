package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD tests for batch H1 (reply-language feature, S3 + C4): structured markers
 * replace string sentinels.
 *
 * 1. ManifestDiffEntry: typed diff entries with a display() rendering identical to
 *    the previous string form ("+ bin", "- notes", "~ key", "~ version: a -> b",
 *    "Initial manifest", "Manifest updated").
 * 2. synthesizeAssistExplanation dispatches on entry types, not string prefixes.
 * 3. extractUserHistory filters ask-user answer bubbles via the structured
 *    MessageKind marker (works for any bubble text, e.g. localized "Let AI decide").
 */
class ManifestDiffTest {

    // ---------------------------------------------------------------
    // ManifestDiffEntry.display(): byte-compatible with the old strings
    // ---------------------------------------------------------------

    @Test
    fun `display renders added keys with a plus prefix`() {
        assertEquals("+ shortcuts", ManifestDiffEntry.Added("shortcuts").display())
    }

    @Test
    fun `display renders removed keys with a minus prefix`() {
        assertEquals("- bin", ManifestDiffEntry.Removed("bin").display())
    }

    @Test
    fun `display renders modified keys with a tilde prefix`() {
        assertEquals("~ description", ManifestDiffEntry.Modified("description").display())
    }

    @Test
    fun `display renders version changes with the tilde arrow form`() {
        assertEquals(
            "~ version: 1.0.0 -> 1.1.0",
            ManifestDiffEntry.VersionChanged("1.0.0", "1.1.0").display(),
        )
    }

    @Test
    fun `display renders the initial manifest sentinel`() {
        assertEquals("Initial manifest", ManifestDiffEntry.Initial.display())
    }

    @Test
    fun `display renders the generic update sentinel`() {
        assertEquals("Manifest updated", ManifestDiffEntry.Generic.display())
    }

    // ---------------------------------------------------------------
    // computeManifestDiff returns typed entries
    // ---------------------------------------------------------------

    @Test
    fun `computeManifestDiff returns Initial for a blank previous manifest`() {
        val diff = computeManifestDiff("", """{"version":"1.0.0"}""")
        assertEquals(listOf<ManifestDiffEntry>(ManifestDiffEntry.Initial), diff)
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

    // ---------------------------------------------------------------
    // synthesizeAssistExplanation dispatches on entry types
    // ---------------------------------------------------------------

    @Test
    fun `synthesis combines removed added modified and version phrases`() {
        val diff = listOf(
            ManifestDiffEntry.Removed("bin"),
            ManifestDiffEntry.Added("shortcuts"),
            ManifestDiffEntry.Modified("description"),
            ManifestDiffEntry.VersionChanged("1.0.0", "1.1.0"),
        )
        assertEquals(
            "Removed bin, added shortcuts, updated description, updated version (1.0.0 -> 1.1.0). Validated and ready.",
            synthesizeAssistExplanation(diff),
        )
    }

    @Test
    fun `synthesis with only a removal starts capitalized`() {
        assertEquals(
            "Removed notes. Validated and ready.",
            synthesizeAssistExplanation(listOf(ManifestDiffEntry.Removed("notes"))),
        )
    }

    @Test
    fun `synthesis with only an addition starts capitalized`() {
        assertEquals(
            "Added shortcuts. Validated and ready.",
            synthesizeAssistExplanation(listOf(ManifestDiffEntry.Added("shortcuts"))),
        )
    }

    @Test
    fun `synthesis covers plain modified keys`() {
        assertEquals(
            "Updated description. Validated and ready.",
            synthesizeAssistExplanation(listOf(ManifestDiffEntry.Modified("description"))),
        )
    }

    @Test
    fun `synthesis with an empty diff reports no changes`() {
        assertEquals(
            "No manifest changes were required. Validated and ready.",
            synthesizeAssistExplanation(emptyList()),
        )
    }

    @Test
    fun `synthesis with a generic update reports a generic sentence`() {
        assertEquals(
            "Updated the manifest. Validated and ready.",
            synthesizeAssistExplanation(listOf(ManifestDiffEntry.Generic)),
        )
    }

    @Test
    fun `synthesis ignores Initial and VersionChanged renders arrow content only`() {
        val diff = listOf(
            ManifestDiffEntry.Initial,
            ManifestDiffEntry.VersionChanged("1.0.0", "1.1.0"),
        )
        assertEquals(
            "Updated version (1.0.0 -> 1.1.0). Validated and ready.",
            synthesizeAssistExplanation(diff),
        )
    }

    // ---------------------------------------------------------------
    // extractUserHistory: marker-based ask-user filtering (C4)
    // ---------------------------------------------------------------

    @Test
    fun `history filters answer bubbles by kind marker regardless of text`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "https://github.com/sharkdp/fd", note = "prefer portable"),
            ChatMessage(role = ChatRole.Agent, text = "Starting..."),
            // Answer bubbles carry arbitrary display text; the marker decides, not the string.
            ChatMessage(role = ChatRole.User, text = "Download confirmed", kind = MessageKind.ASK_USER_ANSWER),
            ChatMessage(role = ChatRole.User, text = "让 AI 决定", kind = MessageKind.ASK_USER_CANCEL),
            ChatMessage(role = ChatRole.User, text = "Question: user selected: 1. x64", kind = MessageKind.ASK_USER_ANSWER),
            ChatMessage(role = ChatRole.User, text = "Please remove notes"),
        )
        val history = extractUserHistory(messages)
        assertEquals(3, history.size)
        assertEquals("User: https://github.com/sharkdp/fd (Requirements: prefer portable)", history[0])
        assertEquals("Assistant: Starting...", history[1])
        assertEquals("User: Please remove notes", history[2])
    }

    @Test
    fun `history keeps plain user bubbles with no kind marker`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "remove notes"),
            ChatMessage(role = ChatRole.User, text = "Let AI decide"),
        )
        val history = extractUserHistory(messages)
        assertEquals(listOf("User: remove notes", "User: Let AI decide"), history)
    }

    @Test
    fun `history with blank text stays excluded even without a marker`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "  "),
            ChatMessage(role = ChatRole.User, text = "real instruction"),
        )
        val history = extractUserHistory(messages)
        assertEquals(listOf("User: real instruction"), history)
    }

    // ---------------------------------------------------------------
    // ChatMessage.kind default keeps existing constructors source-compatible
    // ---------------------------------------------------------------

    @Test
    fun `ChatMessage kind defaults to null`() {
        assertNull(ChatMessage(role = ChatRole.User, text = "hello").kind)
    }

    @Test
    fun `MessageKind enum has the two ask-user kinds`() {
        assertEquals(
            setOf(MessageKind.ASK_USER_ANSWER, MessageKind.ASK_USER_CANCEL),
            MessageKind.entries.toSet(),
        )
    }

    // ---------------------------------------------------------------
    // ManifestRevision.diffSummary is typed
    // ---------------------------------------------------------------

    @Test
    fun `ManifestRevision holds typed diff entries`() {
        val revision = ManifestRevision(
            revisionNumber = 1,
            source = RevisionSource.ASSIST,
            manifestJson = "{}",
            diffSummary = listOf(ManifestDiffEntry.Added("bin")),
        )
        assertEquals(listOf<ManifestDiffEntry>(ManifestDiffEntry.Added("bin")), revision.diffSummary)
        assertFalse(revision.diffSummary.isEmpty())
    }
}
