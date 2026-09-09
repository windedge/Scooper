package scooper.util

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.asAwtTransferable
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard as AwtClipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the system clipboard, retrying while another process holds it open
 * (transient on Windows: WClipboard.openClipboard throws IllegalStateException).
 */
fun readClipboardTextWithRetry(
    attempts: Int = 8,
    delayMillis: Long = 40,
    getter: () -> String? = {
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
        contents?.getTransferData(DataFlavor.stringFlavor) as? String
    },
): String? = runCatching {
    repeat(attempts) {
        val text = try {
            getter()
        } catch (_: Exception) {
            // getContents may throw IllegalStateException, getTransferData may throw
            // UnsupportedFlavorException or IOException; all transient here, retry
            null
        }
        if (text != null) return text
        Thread.sleep(delayMillis)
    }
    try {
        getter()
    } catch (_: Exception) {
        // final attempt failed: treat as no clipboard content
        null
    }
}.getOrNull()

/**
 * Writes to the system clipboard with the same retry loop as
 * [readClipboardTextWithRetry], but the final attempt's failure propagates so
 * callers still observe a hard failure after retries are exhausted.
 */
internal fun writeWithRetry(
    clipboard: AwtClipboard,
    contents: Transferable,
    attempts: Int = 8,
    delayMillis: Long = 40,
) {
    repeat(attempts) {
        try {
            clipboard.setContents(contents, null)
            return
        } catch (_: Exception) {
            // Windows clipboard is transiently locked by other processes, retry
        }
        Thread.sleep(delayMillis)
    }
    clipboard.setContents(contents, null)
}

// Windows clipboard locks are transient; retries ride out competing open/close cycles.
@OptIn(ExperimentalComposeUiApi::class)
class RetryingAwtClipboard(
    private val attempts: Int = 8,
    private val delayMillis: Long = 40,
) : Clipboard {
    private val awtClipboard: AwtClipboard? by lazy {
        try {
            Toolkit.getDefaultToolkit().systemClipboard
        } catch (_: HeadlessException) {
            null
        }
    }

    override suspend fun getClipEntry(): ClipEntry? {
        val text = withContext(Dispatchers.IO) {
            readClipboardTextWithRetry(attempts, delayMillis) {
                awtClipboard?.getContents(null)?.getTransferData(DataFlavor.stringFlavor) as? String
            }
        }
        return text?.let { ClipEntry(StringSelection(it)) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val clipboard = awtClipboard ?: return
        val contents = clipEntry?.asAwtTransferable ?: StringSelection("")
        withContext(Dispatchers.IO) { writeWithRetry(clipboard, contents, attempts, delayMillis) }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override val nativeClipboard: Any
        get() = awtClipboard ?: NoClipboard
}

private object NoClipboard
