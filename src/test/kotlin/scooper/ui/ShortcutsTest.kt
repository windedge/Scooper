package scooper.ui

import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShortcutsTest {

    @Test
    fun `Ctrl+F matches FocusSearch`() {
        assertEquals(ShortcutAction.FocusSearch, matchShortcutKey(Key.F, ctrl = true))
    }

    @Test
    fun `Ctrl+R matches RefreshScoop`() {
        assertEquals(ShortcutAction.RefreshScoop, matchShortcutKey(Key.R, ctrl = true))
    }

    @Test
    fun `Ctrl+Shift+R matches ReloadApps`() {
        assertEquals(ShortcutAction.ReloadApps, matchShortcutKey(Key.R, ctrl = true, shift = true))
    }

    @Test
    fun `F5 matches RefreshScoop`() {
        assertEquals(ShortcutAction.RefreshScoop, matchShortcutKey(Key.F5))
    }

    @Test
    fun `Ctrl+1 matches NavDiscover`() {
        assertEquals(ShortcutAction.NavDiscover, matchShortcutKey(Key.One, ctrl = true))
    }

    @Test
    fun `Ctrl+2 matches NavInstalled`() {
        assertEquals(ShortcutAction.NavInstalled, matchShortcutKey(Key.Two, ctrl = true))
    }

    @Test
    fun `Ctrl+3 matches NavBuckets`() {
        assertEquals(ShortcutAction.NavBuckets, matchShortcutKey(Key.Three, ctrl = true))
    }

    @Test
    fun `Ctrl+4 matches NavCleanup`() {
        assertEquals(ShortcutAction.NavCleanup, matchShortcutKey(Key.Four, ctrl = true))
    }

    @Test
    fun `Ctrl+Comma matches OpenSettings`() {
        assertEquals(ShortcutAction.OpenSettings, matchShortcutKey(Key.Comma, ctrl = true))
    }

    @Test
    fun `Ctrl+L matches OpenOutput`() {
        assertEquals(ShortcutAction.OpenOutput, matchShortcutKey(Key.L, ctrl = true))
    }

    @Test
    fun `Ctrl+Grave matches OpenOutput`() {
        assertEquals(ShortcutAction.OpenOutput, matchShortcutKey(Key.Grave, ctrl = true))
    }

    @Test
    fun `Escape matches GoBack`() {
        assertEquals(ShortcutAction.GoBack, matchShortcutKey(Key.Escape))
    }

    @Test
    fun `plain key without modifiers returns null`() {
        assertNull(matchShortcutKey(Key.A))
        assertNull(matchShortcutKey(Key.F))
        assertNull(matchShortcutKey(Key.R))
        assertNull(matchShortcutKey(Key.L))
    }

    @Test
    fun `Ctrl+Shift+R does not match RefreshScoop`() {
        assertNotEquals(ShortcutAction.RefreshScoop, matchShortcutKey(Key.R, ctrl = true, shift = true))
    }
}
