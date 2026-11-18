package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import scooper.util.Strings
import java.util.Locale

/**
 * TDD for review-fix batch A' item 1: the ReviewAction prompt must be stored
 * as a msgid and localized through tr() when the chip is clicked, so a zh_CN
 * session sends the engine the Chinese instruction instead of the English
 * msgid. The bubble label is already rendered through labelText().
 *
 * The test swaps Strings to the zh_CN catalog around the assertion and restores
 * the English instance afterwards; the gradle test JVM is pinned to en_US.
 */
class ReviewActionPromptLocalizationTest {

    @Test
    fun `prompt resolves from the msgid through tr`() {
        val prompt = ReviewAction.FIX_DESCRIPTION.promptMsgid
        assertEquals(
            ReviewAction.FIX_DESCRIPTION.promptMsgid,
            ReviewActionChip(ReviewAction.FIX_DESCRIPTION).prompt(),
            "prompt() must resolve the stored msgid through tr()",
        )
        assertEquals(prompt, ReviewAction.FIX_DESCRIPTION.promptMsgid)
    }

    @Test
    fun `prompt is localized under the zh_CN locale`() {
        val english = Strings.current
        val chinese = scooper.util.loadLocale(Locale.SIMPLIFIED_CHINESE)
        try {
            Strings.update(Locale.SIMPLIFIED_CHINESE)
            // loadLocale keeps the current instance on failure, so pin the check:
            // the zh_CN catalog must translate the description chip prompt.
            val localized = ReviewActionChip(ReviewAction.FIX_DESCRIPTION).prompt()
            org.junit.jupiter.api.Assertions.assertNotEquals(
                ReviewAction.FIX_DESCRIPTION.promptMsgid,
                localized,
                "zh_CN catalog must contain a translation for the chip prompt msgid",
            )
            assertEquals(
                chinese.tr(ReviewAction.FIX_DESCRIPTION.promptMsgid),
                localized,
            )
        } finally {
            Strings.update(Locale.ENGLISH)
            assertEquals(english.locale.toLanguageTag(), Strings.current.locale.toLanguageTag())
        }
    }
}
