package scooper.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Keyboard navigation rules for the composer model dropdown: the menu opens
 * on the selected entry (first row when absent, custom "use query" row
 * included in the index space), and Up/Down navigation clamps at the
 * boundaries instead of wrapping.
 */
class MenuIndexTest {

    // ---------------------------------------------------------------
    // initial index: the menu opens on the selected entry
    // ---------------------------------------------------------------

    @Test
    fun `initial index is the selected entry's index when present`() {
        val entries = listOf("a", "b", "c", "d")
        assertEquals(0, initialMenuHighlightIndex("a", entries))
        assertEquals(2, initialMenuHighlightIndex("c", entries))
        assertEquals(3, initialMenuHighlightIndex("d", entries))
    }

    @Test
    fun `initial index falls back to the first row when selected is absent`() {
        assertEquals(0, initialMenuHighlightIndex("missing", listOf("a", "b", "c")))
        assertEquals(0, initialMenuHighlightIndex("", listOf("a", "b")))
        assertEquals(0, initialMenuHighlightIndex("a", emptyList()))
    }

    @Test
    fun `initial index counts the custom use-query row as a normal row`() {
        val entries = listOf("use: gpt", "a", "b", "c")
        assertEquals(0, initialMenuHighlightIndex("use: gpt", entries))
        assertEquals(3, initialMenuHighlightIndex("c", entries))
    }

    // ---------------------------------------------------------------
    // clamp: keep the index inside 0 until size-1, unset only when empty
    // ---------------------------------------------------------------

    @Test
    fun `clamp keeps an in-range index unchanged`() {
        assertEquals(3, clampMenuIndex(3, 8))
        assertEquals(7, clampMenuIndex(7, 8))
    }

    @Test
    fun `clamp stops Up on the first row and Down on the last`() {
        assertEquals(0, clampMenuIndex(-1, 8))
        assertEquals(7, clampMenuIndex(8, 8))
    }

    @Test
    fun `clamp falls back to unset for an empty list`() {
        assertEquals(-1, clampMenuIndex(0, 0))
        assertEquals(-1, clampMenuIndex(2, 0))
    }
}
