package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Review action chips carry their identity as an enum; the visible label
 * resolves from the enum msgid through tr() at render time. Under the pinned
 * en test locale tr() returns the msgid unchanged, so these tests assert the
 * gettext msgids the translation batch fills.
 */
class ReviewActionChipLabelTest {

    @Test
    fun `chip labels resolve from the action msgid`() {
        assertEquals("Fix JSON syntax", ReviewActionChip(ReviewAction.FIX_JSON).labelText())
        assertEquals("Improve description", ReviewActionChip(ReviewAction.FIX_DESCRIPTION).labelText())
        assertEquals(
            "Fix autoupdate/checkver",
            ReviewActionChip(ReviewAction.FIX_AUTOUPDATE).labelText(),
        )
    }

    @Test
    fun `extraction emits every action with its English assist prompt`() {
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
                ReviewAction.FIX_JSON,
                ReviewAction.FIX_NAME_CONFLICT,
                ReviewAction.FIX_PERSIST,
                ReviewAction.FIX_LICENSE,
                ReviewAction.FIX_EXTRACT_DIR,
                ReviewAction.FIX_DESCRIPTION,
                ReviewAction.FIX_AUTOUPDATE,
            ),
            chips.map { it.action },
        )
        assertTrue(chips.all { it.action.promptMsgid.isNotBlank() }, "each chip keeps its assist prompt msgid")
    }

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
