package scooper.util

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard as AwtClipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalComposeUiApi::class)
class ClipboardRetryTest {

    // --------------------------------------------------------------- retry read

    @Test
    fun `retries while getter throws or returns null then succeeds`() {
        var calls = 0
        val getter: () -> String? = {
            calls++
            when {
                calls <= 2 -> throw IllegalStateException("openClipboard failed")
                calls == 3 -> null
                else -> "pasted"
            }
        }
        assertEquals("pasted", readClipboardTextWithRetry(attempts = 8, delayMillis = 1, getter = getter))
        assertEquals(4, calls)
    }

    @Test
    fun `returns null when getter keeps failing`() {
        var calls = 0
        val getter: () -> String? = {
            calls++
            throw IllegalStateException("openClipboard failed")
        }
        assertNull(readClipboardTextWithRetry(attempts = 3, delayMillis = 1, getter = getter))
        assertEquals(4, calls)
    }

    @Test
    fun `returns empty string when clipboard holds empty text`() {
        val getter: () -> String? = { "" }
        assertEquals("", readClipboardTextWithRetry(attempts = 1, delayMillis = 1, getter = getter))
    }

    // --------------------------------------------------------------- writeWithRetry

    /** AWT clipboard stub whose setContents fails for the first [failTimes] calls. */
    private class FakeAwtClipboard(private var failTimes: Int) : AwtClipboard("fake") {
        var calls = 0
        private var stored: String? = null

        override fun setContents(contents: Transferable?, owner: java.awt.datatransfer.ClipboardOwner?) {
            calls++
            if (calls <= failTimes) throw IllegalStateException("openClipboard failed")
            stored = contents?.getTransferData(DataFlavor.stringFlavor) as? String
        }

        override fun getContents(requestor: Any?): Transferable? = StringSelection(stored ?: "")
    }

    @Test
    fun `writeWithRetry retries while clipboard is locked then succeeds`() {
        val fake = FakeAwtClipboard(failTimes = 2)
        writeWithRetry(fake, StringSelection("retry-text"), attempts = 8, delayMillis = 1)
        assertEquals(3, fake.calls)
        assertEquals("retry-text", readClipboardTextWithRetry(attempts = 1, delayMillis = 1) {
            fake.getContents(null)?.getTransferData(DataFlavor.stringFlavor) as? String
        })
    }

    @Test
    fun `writeWithRetry gives up silently after attempts exhausted`() {
        val fake = FakeAwtClipboard(failTimes = Int.MAX_VALUE)
        runCatching { writeWithRetry(fake, StringSelection("never"), attempts = 3, delayMillis = 1) }
        // attempts (3) + final unconditional attempt = 4 calls
        assertEquals(4, fake.calls)
    }

    // --------------------------------------------------------------- RetryingAwtClipboard

    @Test
    fun `setClipEntry writes and clears the system clipboard`() = kotlinx.coroutines.runBlocking {
        val awt = Toolkit.getDefaultToolkit().systemClipboard
        val clipboard = RetryingAwtClipboard(attempts = 4, delayMillis = 10)
        val text = "scooper-test-${System.nanoTime()}"

        clipboard.setClipEntry(ClipEntry(StringSelection(text)))
        assertEquals(text, readClipboardTextWithRetry(attempts = 4, delayMillis = 10) {
            awt.getContents(null)?.getTransferData(DataFlavor.stringFlavor) as? String
        })

        clipboard.setClipEntry(null)
        assertEquals("", readClipboardTextWithRetry(attempts = 4, delayMillis = 10) {
            awt.getContents(null)?.getTransferData(DataFlavor.stringFlavor) as? String
        })
    }
}
