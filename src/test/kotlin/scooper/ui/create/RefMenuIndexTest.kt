package scooper.ui.create

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression tests for the Enter-does-nothing-on-first-press bug: the menu
 * opened with refMenuIndex = -1 and the Enter branch required the index to be
 * in range, so Enter was swallowed without applying a suggestion until an
 * arrow key first set the index. The index must start at the first item and
 * arrow navigation must clamp at the boundaries.
 */
class RefMenuIndexTest {

    // ---------------------------------------------------------------
    // initial index: the menu opens with the first row highlighted so a
    // bare Enter applies a suggestion immediately
    // ---------------------------------------------------------------

    @Test
    fun `initial ref menu index highlights the first item`() {
        assertEquals(0, initialRefMenuIndex())
    }

    // ---------------------------------------------------------------
    // clamp: keeps the index inside 0 until size-1 (or -1 only when the
    // list is empty) so Up and Down navigation stays predictable
    // ---------------------------------------------------------------

    @Test
    fun `clamp maps a stale unset index onto the first item`() {
        assertEquals(0, clampRefMenuIndex(-1, 8))
        assertEquals(0, clampRefMenuIndex(Int.MIN_VALUE, 3))
    }

    @Test
    fun `clamp keeps an in-range index unchanged`() {
        assertEquals(3, clampRefMenuIndex(3, 8))
        assertEquals(7, clampRefMenuIndex(7, 8))
    }

    @Test
    fun `clamp stops Up on the first item and Down on the last`() {
        assertEquals(0, clampRefMenuIndex(0, 8))
        assertEquals(7, clampRefMenuIndex(8, 8))
    }

    @Test
    fun `clamp falls back to unset for an empty list`() {
        assertEquals(-1, clampRefMenuIndex(0, 0))
        assertEquals(-1, clampRefMenuIndex(-1, 0))
    }
}
