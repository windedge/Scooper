package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * H3 TDD: review action chips carry only a stable id plus the English assist
 * prompt; the visible label resolves from the id through tr() at render time.
 * Under the pinned en test locale tr() returns the msgid unchanged, so these
 * tests assert the gettext msgids the translation batch fills.
 */
class ReviewActionChipLabelTest {

    // ---------------------------------------------------------------
    // label per stable id (render-time lookup)
    // ---------------------------------------------------------------

    @Test
    fun `every chip id maps to its msgid label`() {
        val expected = mapOf(
            "fix_json" to "Fix JSON syntax",
            "fix_name_conflict" to "Fix name conflict",
            "fix_persist" to "Fix persist paths",
            "fix_license" to "Fix license",
            "fix_extract_dir" to "Fix extract_dir",
            "fix_description" to "Improve description",
            "fix_autoupdate" to "Fix autoupdate/checkver",
        )
        expected.forEach { (id, msgid) ->
            assertEquals(msgid, ReviewActionChip(id = id, prompt = "x").labelText(), "label for id $id")
        }
    }

    @Test
    fun `unknown id falls back to the raw id`() {
        assertEquals("custom_chip", ReviewActionChip(id = "custom_chip", prompt = "x").labelText())
    }

    // ---------------------------------------------------------------
    // extraction emits only stable ids, prompts stay English
    // ---------------------------------------------------------------

    @Test
    fun `extraction emits stable ids for every keyword family`() {
        val issues = listOf(
            "Conflict: app name 'micro' already exists in official bucket",
            "Illegal persist path: C:\\data must be relative",
            "License bare GPL-3.0 should use explicit suffix",
            "extract_dir strips too many levels",
            "Description is 163 characters, should be under 100",
            "checkver regex is unreliable, autoupdate broken",
        )
        val chips = extractReviewActionChips(issues = issues, invalidJson = true)
        assertEquals(
            listOf(
                "fix_json",
                "fix_name_conflict",
                "fix_persist",
                "fix_license",
                "fix_extract_dir",
                "fix_description",
                "fix_autoupdate",
            ),
            chips.map { it.id },
        )
        assertTrue(chips.all { it.prompt.isNotBlank() }, "each chip keeps its English assist prompt")
    }

    // ---------------------------------------------------------------
    // ask-user bubble text (enqueue-time tr, Rule 9 exception)
    // ---------------------------------------------------------------

    @Test
    fun `download confirm bubbles resolve through tr msgids`() {
        assertEquals("Download confirmed", resolveAskUserBubbleText("yes", isDownloadPrompt = true))
        assertEquals("Download confirmed", resolveAskUserBubbleText("Proceed with download", isDownloadPrompt = true))
        assertEquals("Download skipped", resolveAskUserBubbleText("no", isDownloadPrompt = true))
        assertEquals("Download skipped", resolveAskUserBubbleText("Skip this download", isDownloadPrompt = true))
        assertEquals("custom text", resolveAskUserBubbleText("custom text", isDownloadPrompt = true))
        assertEquals("raw answer", resolveAskUserBubbleText("raw answer", isDownloadPrompt = false))
    }
}
