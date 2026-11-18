package scooper.ui.components

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Panel width rule for the model dropdown popup: use the shared minWidth as
 * a floor, fall back while it is unknown, clamp to the shared maxWidth.
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
    fun `model dropdowns use the shared width bounds`() {
        assertEquals(320.dp, MODEL_DROPDOWN_MIN_WIDTH_DP.dp)
        assertEquals(480.dp, MODEL_DROPDOWN_MAX_WIDTH_DP.dp)
        assertEquals(
            320.dp,
            popupPanelWidth(
                MODEL_DROPDOWN_MIN_WIDTH_DP.dp,
                220.dp,
                MODEL_DROPDOWN_MAX_WIDTH_DP.dp,
            ),
        )
        assertEquals(
            400.dp,
            popupPanelWidth(400.dp, 220.dp, MODEL_DROPDOWN_MAX_WIDTH_DP.dp),
        )
        assertEquals(
            480.dp,
            popupPanelWidth(900.dp, 220.dp, MODEL_DROPDOWN_MAX_WIDTH_DP.dp),
        )
    }

    @Test
    fun `settings trigger has a fixed width independent of selection`() {
        // Trigger width is applied through Compose modifiers, so only the shared
        // constant can be asserted directly here.
        assertEquals(360.dp, MODEL_TRIGGER_FIXED_WIDTH_DP.dp)
        assertEquals(
            (MODEL_DROPDOWN_MIN_WIDTH_DP + 40).dp,
            MODEL_TRIGGER_FIXED_WIDTH_DP.dp,
        )
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
