package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scooper.service.AiErrorTranslator

/**
 * H2抽查测试: enqueue-frozen bubble copy must go through tr() at enqueue time
 * (Rule 9 exception: history bubbles are content snapshots). Under the default
 * English locale tr() returns the msgid unchanged, so these tests assert the
 * msgid wording, i.e. the gettext catalog entry that the translation batch fills.
 *
 * The "red" proof for this batch is structural: rg shows no bare-English
 * literal left in the covered functions (see docs), plus the const -> function
 * moves (PURE_IMAGE_INSTRUCTION / INVALID_URL_HINT) fail compilation until
 * every reference is migrated.
 */
class TrWrappingTest {

    // ---------------------------------------------------------------
    // display(): the symbolic-prefix entries carry tr() label templates
    // ---------------------------------------------------------------

    @Test
    fun `display keeps symbolic prefixes for key entries`() {
        assertEquals("+ bin", ManifestDiffEntry.Added("bin").display())
        assertEquals("- notes", ManifestDiffEntry.Removed("notes").display())
        assertEquals("~ description", ManifestDiffEntry.Modified("description").display())
    }

    @Test
    fun `display version label is the tr msgid template`() {
        assertEquals(
            "~ version: 1.0.0 → 1.1.0",
            ManifestDiffEntry.VersionChanged("1.0.0", "1.1.0").display(),
        )
    }

    @Test
    fun `display sentinel labels are tr msgids`() {
        assertEquals("Initial manifest", ManifestDiffEntry.Initial.display())
        assertEquals("Manifest updated", ManifestDiffEntry.Generic.display())
    }

    // ---------------------------------------------------------------
    // synthesized explanations: every segment is a tr msgid
    // ---------------------------------------------------------------

    @Test
    fun `assist synthesis segments are tr msgids`() {
        assertEquals(
            "Removed bin, added shortcuts, updated version (1.0.0 → 1.1.0). Validated and ready.",
            synthesizeAssistExplanation(
                listOf(
                    ManifestDiffEntry.Removed("bin"),
                    ManifestDiffEntry.Added("shortcuts"),
                    ManifestDiffEntry.VersionChanged("1.0.0", "1.1.0"),
                ),
            ),
        )
        assertEquals(
            "No manifest changes were required. Validated and ready.",
            synthesizeAssistExplanation(emptyList()),
        )
        assertEquals(
            "Updated the manifest. Validated and ready.",
            synthesizeAssistExplanation(listOf(ManifestDiffEntry.Generic)),
        )
    }

    @Test
    fun `generate synthesis segments are tr msgids`() {
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

    // ---------------------------------------------------------------
    // notices and fallbacks
    // ---------------------------------------------------------------

    @Test
    fun `finishedWithIssuesNotice uses the two tr msgids`() {
        assertEquals("Assist finished with issues", finishedWithIssuesNotice(isAssistScenario = true))
        assertEquals("Generation finished with issues", finishedWithIssuesNotice(isAssistScenario = false))
    }

    @Test
    fun `answeredBubbleText fallback is the tr msgid`() {
        assertEquals("Sorry, I could not produce an answer.", answeredBubbleText(null))
        assertEquals("Sorry, I could not produce an answer.", answeredBubbleText("  "))
    }

    // ---------------------------------------------------------------
    // AiErrorTranslator fallback detail
    // ---------------------------------------------------------------

    @Test
    fun `missing error detail is a tr msgid`() {
        val detail = AiErrorTranslator.translate(IllegalStateException()).detail
        assertTrue(detail.raw == null || detail.msgid != null, "empty detail must come from the tr msgid, not raw text")
        if (detail.msgid != null) {
            assertEquals("(no error detail)", detail.msgid)
        } else {
            // A cause-chain message was collected: the raw passthrough path, not the msgid.
            assertNotEquals("", detail.raw)
        }
    }

}
