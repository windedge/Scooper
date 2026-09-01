package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD tests for the deterministic explanation synthesis and the finished-bubble text
 * composition priority (architecture proposal 5.3 / 5.4):
 *
 * 1. Normal finish: engine explanation is the bubble text; when missing, a
 *    deterministic synthesis is used (refine diff based, or initial generation based).
 * 2. Conflict-only / finished-with-issues: the classified notice stays the main text
 *    and the explanation (when present) is appended as a trailing paragraph.
 */
class CreateViewModelExplanationTest {

    // ---------------------------------------------------------------
    // synthesizeRefineExplanation: diff-based synthesis
    // ---------------------------------------------------------------

    @Test
    fun `refine synthesis combines removed added and version change`() {
        val diff = listOf("- bin", "+ shortcuts", "~ version: 1.0.0 → 1.1.0")
        assertEquals(
            "Removed bin, added shortcuts, updated version (1.0.0 → 1.1.0). Validated and ready.",
            synthesizeRefineExplanation(diff),
        )
    }

    @Test
    fun `refine synthesis with only a removal starts capitalized`() {
        assertEquals(
            "Removed notes. Validated and ready.",
            synthesizeRefineExplanation(listOf("- notes")),
        )
    }

    @Test
    fun `refine synthesis with only an addition starts capitalized`() {
        assertEquals(
            "Added shortcuts. Validated and ready.",
            synthesizeRefineExplanation(listOf("+ shortcuts")),
        )
    }

    @Test
    fun `refine synthesis covers plain modified keys`() {
        assertEquals(
            "Updated description. Validated and ready.",
            synthesizeRefineExplanation(listOf("~ description")),
        )
    }

    @Test
    fun `refine synthesis groups multiple keys per change kind`() {
        val diff = listOf("- bin", "- notes", "+ shortcuts", "+ env_add_path")
        assertEquals(
            "Removed bin, notes, added shortcuts, env_add_path. Validated and ready.",
            synthesizeRefineExplanation(diff),
        )
    }

    @Test
    fun `refine synthesis with empty diff reports no changes`() {
        assertEquals(
            "No manifest changes were required. Validated and ready.",
            synthesizeRefineExplanation(emptyList()),
        )
    }

    @Test
    fun `refine synthesis with unparseable diff reports a generic update`() {
        assertEquals(
            "Updated the manifest. Validated and ready.",
            synthesizeRefineExplanation(listOf("Manifest updated")),
        )
    }

    // ---------------------------------------------------------------
    // synthesizeGenerateExplanation: manifest-field-based synthesis
    // ---------------------------------------------------------------

    @Test
    fun `generate synthesis describes portable zip with shortcut and persist counts`() {
        val manifest = """
            {
              "name": "fd",
              "version": "10.2.0",
              "url": "https://example.com/fd_10.2.0.zip",
              "shortcuts": [["fd.exe", "fd"]],
              "persist": ["plugins"]
            }
        """.trimIndent()
        assertEquals(
            "Generated manifest for fd 10.2.0 (portable zip). 1 shortcut, 1 persist entry. Validated and ready.",
            synthesizeGenerateExplanation(manifest, appName = null),
        )
    }

    @Test
    fun `generate synthesis describes msi installer with plural shortcuts`() {
        val manifest = """
            {
              "name": "app",
              "version": "1.0",
              "url": "https://example.com/setup.msi",
              "shortcuts": [["a.exe", "A"], ["b.exe", "B"]],
              "persist": ["data", "config"]
            }
        """.trimIndent()
        assertEquals(
            "Generated manifest for app 1.0 (MSI installer). 2 shortcuts, 2 persist entries. Validated and ready.",
            synthesizeGenerateExplanation(manifest, appName = null),
        )
    }

    @Test
    fun `generate synthesis describes plain exe installer`() {
        val manifest = """{"name":"app","version":"2.0","url":"https://example.com/setup.exe"}"""
        assertEquals(
            "Generated manifest for app 2.0 (installer). Validated and ready.",
            synthesizeGenerateExplanation(manifest, appName = null),
        )
    }

    @Test
    fun `generate synthesis falls back to appName when manifest has no name`() {
        val manifest = """{"version":"1.0","url":"https://example.com/app.zip"}"""
        assertEquals(
            "Generated manifest for fd 1.0 (portable zip). Validated and ready.",
            synthesizeGenerateExplanation(manifest, appName = "fd"),
        )
    }

    @Test
    fun `generate synthesis omits missing fields robustly`() {
        // no name, no version, no url, no shortcuts, no persist
        assertEquals(
            "Generated manifest (portable). Validated and ready.",
            synthesizeGenerateExplanation("{}", appName = null),
        )
    }

    @Test
    fun `generate synthesis survives invalid json input`() {
        assertEquals(
            "Generated manifest for app (portable). Validated and ready.",
            synthesizeGenerateExplanation("not json {{", appName = "app"),
        )
    }

    @Test
    fun `generate synthesis counts single string persist as one entry`() {
        val manifest = """{"name":"app","version":"1.0","url":"https://x/app.7z","persist":"data"}"""
        assertEquals(
            "Generated manifest for app 1.0 (portable 7z archive). 1 persist entry. Validated and ready.",
            synthesizeGenerateExplanation(manifest, appName = null),
        )
    }

    @Test
    fun `generate synthesis reads strategy from the architecture block when top-level url is missing`() {
        val manifest = """
            {
              "version": "1.0",
              "architecture": {
                "64bit": {
                  "url": "https://example.com/app_1.0.zip",
                  "hash": "abc"
                }
              }
            }
        """.trimIndent()
        assertEquals(
            "Generated manifest 1.0 (portable zip). Validated and ready.",
            synthesizeGenerateExplanation(manifest, appName = null),
        )
    }

    @Test
    fun `generate synthesis falls back to portable when neither top-level nor architecture url exists`() {
        val manifest = """{"version":"1.0","architecture":{"64bit":{"hash":"abc"}}}"""
        assertEquals(
            "Generated manifest 1.0 (portable). Validated and ready.",
            synthesizeGenerateExplanation(manifest, appName = null),
        )
    }

    // ---------------------------------------------------------------
    // composeFinishedBubbleText: 5.3 priority
    // ---------------------------------------------------------------

    private val conflictText = "Done with notice: App name 'app' conflicts with an existing local manifest. See card below."
    private val issuesText = "Generation finished with issues. See card below for details."
    private val fallbackText = "Generated manifest for app 1.0 (portable zip). Validated and ready."

    @Test
    fun `normal finish prefers the engine explanation over the synthesized fallback`() {
        val text = composeFinishedBubbleText(
            explanation = "Removed notes per your request.",
            isConflictOnly = false,
            finishedWithIssues = false,
            conflictText = conflictText,
            issuesText = issuesText,
            fallbackText = fallbackText,
        )
        assertEquals("Removed notes per your request.", text)
    }

    @Test
    fun `normal finish falls back to the synthesized text when explanation is missing`() {
        val text = composeFinishedBubbleText(
            explanation = null,
            isConflictOnly = false,
            finishedWithIssues = false,
            conflictText = conflictText,
            issuesText = issuesText,
            fallbackText = fallbackText,
        )
        assertEquals(fallbackText, text)
    }

    @Test
    fun `normal finish treats a blank explanation as missing`() {
        val text = composeFinishedBubbleText(
            explanation = "   ",
            isConflictOnly = false,
            finishedWithIssues = false,
            conflictText = conflictText,
            issuesText = issuesText,
            fallbackText = fallbackText,
        )
        assertEquals(fallbackText, text)
    }

    @Test
    fun `conflict-only keeps the classified text as main and appends the explanation`() {
        val text = composeFinishedBubbleText(
            explanation = "The name collides with an existing package.",
            isConflictOnly = true,
            finishedWithIssues = true,
            conflictText = conflictText,
            issuesText = issuesText,
            fallbackText = fallbackText,
        )
        assertTrue(text.startsWith(conflictText), "classified notice must stay the main text")
        assertTrue(text.contains("\n\nThe name collides with an existing package."))
    }

    @Test
    fun `conflict-only without explanation keeps the classified text only`() {
        val text = composeFinishedBubbleText(
            explanation = null,
            isConflictOnly = true,
            finishedWithIssues = true,
            conflictText = conflictText,
            issuesText = issuesText,
            fallbackText = fallbackText,
        )
        assertEquals(conflictText, text)
    }

    @Test
    fun `finished with issues appends the explanation after the issues text`() {
        val text = composeFinishedBubbleText(
            explanation = "Validation still flags the checkver regex.",
            isConflictOnly = false,
            finishedWithIssues = true,
            conflictText = conflictText,
            issuesText = issuesText,
            fallbackText = fallbackText,
        )
        assertTrue(text.startsWith(issuesText), "issues notice must stay the main text")
        assertTrue(text.contains("\n\nValidation still flags the checkver regex."))
    }

    @Test
    fun `finished with issues without explanation keeps the issues text only`() {
        val text = composeFinishedBubbleText(
            explanation = null,
            isConflictOnly = false,
            finishedWithIssues = true,
            conflictText = conflictText,
            issuesText = issuesText,
            fallbackText = fallbackText,
        )
        assertEquals(issuesText, text)
    }

    // ---------------------------------------------------------------
    // isRefineScenario helper shared with the composition logic
    // ---------------------------------------------------------------

    @Test
    fun `isRefineScenario requires both a refine phase and existing revisions`() {
        val revisions = listOf(
            ManifestRevision(revisionNumber = 1, source = RevisionSource.INITIAL_GENERATE, manifestJson = "{}"),
        )
        assertTrue(isRefineScenario(currentPhase = "refine", revisions = revisions))
        assertFalse(isRefineScenario(currentPhase = "generate", revisions = revisions), "initial generate is not a refine")
        assertFalse(isRefineScenario(currentPhase = "refine", revisions = emptyList()), "no revisions means no previous manifest to diff")
        assertFalse(isRefineScenario(currentPhase = null, revisions = revisions))
    }
}
