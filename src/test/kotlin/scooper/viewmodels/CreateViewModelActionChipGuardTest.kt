package scooper.viewmodels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * TDD tests for the review action chip guard in CreateViewModel
 * (chip clicks are dropped with a toast when no manifest existed or another
 * operation is running; an idle state returns null and applies the chip).
 */
class CreateViewModelActionChipGuardTest {

    // ---------------------------------------------------------------
    // applyActionChipBlockToast
    // ---------------------------------------------------------------

    @Test
    fun `idle state with manifest returns null and applies the chip`() {
        assertNull(applyActionChipBlockToast(manifestJson = "{}", running = false, testing = false))
    }

    @Test
    fun `blank manifest reports no manifest to fix`() {
        assertEquals(
            "No manifest to fix" to ToastType.ERROR,
            applyActionChipBlockToast(manifestJson = "", running = false, testing = false),
        )
        assertEquals(
            "No manifest to fix" to ToastType.ERROR,
            applyActionChipBlockToast(manifestJson = "   ", running = false, testing = false),
        )
    }

    @Test
    fun `running blocks the chip`() {
        assertEquals(
            "Another operation is running" to ToastType.INFO,
            applyActionChipBlockToast(manifestJson = "{}", running = true, testing = false),
        )
    }

    @Test
    fun `testing blocks the chip`() {
        assertEquals(
            "Another operation is running" to ToastType.INFO,
            applyActionChipBlockToast(manifestJson = "{}", running = false, testing = true),
        )
    }
}
