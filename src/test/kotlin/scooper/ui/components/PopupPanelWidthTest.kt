package scooper.ui.components

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Panel width rule for the model dropdown popup: use the caller-provided
 * minWidth (a tracked anchor width or a fixed floor), fall back while it is
 * unknown, clamp to maxWidth. Long entries ellipsize instead of stretching
 * the panel.
 */
class PopupPanelWidthTest {

    // ---------------------------------------------------------------
    // Known width (tracked anchor or fixed floor)
    // ---------------------------------------------------------------

    @Test
    fun `uses the given width when available`() {
        assertEquals(300.dp, popupPanelWidth(300.dp, 220.dp, 480.dp))
        assertEquals(220.dp, popupPanelWidth(220.dp, 220.dp, 480.dp))
        assertEquals(360.dp, popupPanelWidth(360.dp, 220.dp, 480.dp))
    }

    @Test
    fun `settings page uses its fixed compact floor`() {
        assertEquals(320.dp, popupPanelWidth(320.dp, 220.dp, 360.dp))
    }

    // ---------------------------------------------------------------
    // Unknown width
    // ---------------------------------------------------------------

    @Test
    fun `falls back to the default width when the width is zero`() {
        assertEquals(220.dp, popupPanelWidth(0.dp, 220.dp, 480.dp))
    }

    @Test
    fun `treats a negative width as unknown`() {
        assertEquals(220.dp, popupPanelWidth((-5).dp, 220.dp, 480.dp))
    }

    // ---------------------------------------------------------------
    // Upper bound
    // ---------------------------------------------------------------

    @Test
    fun `clamps a very wide tracked anchor to maxWidth`() {
        assertEquals(480.dp, popupPanelWidth(900.dp, 220.dp, 480.dp))
    }

    @Test
    fun `maxWidth also caps the fallback`() {
        assertEquals(300.dp, popupPanelWidth(0.dp, 500.dp, 300.dp))
    }

    @Test
    fun `maxWidth caps the settings page floor`() {
        assertEquals(300.dp, popupPanelWidth(320.dp, 220.dp, 300.dp))
    }
}
