package scooper.ui.create

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for the post-completion composer lockup: after the
 * "Completed in ..." bubble appears the composer must accept input and the
 * Send button must enable as soon as text is typed.
 */
class ComposerInputUnblockTest {

    // ---------------------------------------------------------------
    // ref menu visibility: a stale token with the menu flag stuck open
    // keeps the key handler in the menu branch, which consumes Enter and
    // blocks typing; the extracted predicate must stay token && has
    // suggestions && open
    // ---------------------------------------------------------------

    @Test
    fun `menu stays hidden when dismissed even with an active token`() {
        assertFalse(isRefMenuVisible(hasActiveToken = true, hasSuggestions = true, menuOpen = false))
    }

    @Test
    fun `menu shows only with token, suggestions and open flag`() {
        assertTrue(isRefMenuVisible(hasActiveToken = true, hasSuggestions = true, menuOpen = true))
        assertFalse(isRefMenuVisible(hasActiveToken = true, hasSuggestions = false, menuOpen = true))
        assertFalse(isRefMenuVisible(hasActiveToken = false, hasSuggestions = true, menuOpen = true))
        assertFalse(isRefMenuVisible(hasActiveToken = false, hasSuggestions = false, menuOpen = false))
    }
}

