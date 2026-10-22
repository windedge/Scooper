package scooper.ui.components

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Panel width rule for the plain ExposedDropdownMenu popup: never narrower
 * than the anchor field, never narrower than the longest item text plus the
 * content reserve, so display names stay on a single line.
 */
class DropdownPanelWidthTest {

    // ---------------------------------------------------------------
    // Long display names must fit on one line
    // ---------------------------------------------------------------

    @Test
    fun `expands a narrow anchor so the longest item fits`() {
        assertEquals(170.dp, dropdownPanelWidth(anchorWidth = 160.dp, longestItemWidth = 130.dp))
    }

    @Test
    fun `panel width is the longest text plus the horizontal reserve`() {
        assertEquals(
            200.dp + DROPDOWN_PANEL_TEXT_RESERVE,
            dropdownPanelWidth(anchorWidth = 0.dp, longestItemWidth = 200.dp),
        )
    }

    // ---------------------------------------------------------------
    // Wide anchors keep their size
    // ---------------------------------------------------------------

    @Test
    fun `keeps a wide anchor width`() {
        assertEquals(400.dp, dropdownPanelWidth(anchorWidth = 400.dp, longestItemWidth = 130.dp))
    }

    @Test
    fun `empty item list keeps the anchor width`() {
        assertEquals(220.dp, dropdownPanelWidth(anchorWidth = 220.dp, longestItemWidth = 0.dp))
    }
}
