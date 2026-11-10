@file:Suppress("DEPRECATION")

package scooper.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.unit.dp
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.xml.sax.InputSource
import java.io.InputStream

private val logger: Logger = LoggerFactory.getLogger("ResourceUtils")

/**
 * Lock to serialize classpath resource reads.
 * Prevents [java.io.EOFException] caused by concurrent ZipFile access
 * when multiple Compose compositions read resources simultaneously.
 */
private val resourceLock = Any()

/** Silent placeholder used when a painter resource cannot be loaded or decoded. */
private val fallbackPainter: Painter = ColorPainter(Color.Transparent)

/** Blank 24dp vector used when an .xml icon resource fails to load. */
private fun fallbackImageVector(): ImageVector =
    ImageVector.Builder(
        name = "fallback",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).build()

/** 1x1 transparent bitmap used when an image resource fails to load. */
private fun fallbackImageBitmap(): ImageBitmap =
    ImageBitmap(1, 1).also { bmp ->
        Canvas(bmp).drawRect(0f, 0f, 1f, 1f, Paint().apply { color = Color.Transparent })
    }

/**
 * Loads a classpath resource and decodes it with [block].
 *
 * The resource stream is read directly from the classpath under [resourceLock]
 * so concurrent Compose compositions cannot trip concurrent ZipFile access.
 * Any failure (resource missing, jar rewritten mid-run, decode error) is
 * logged as a warning and [fallback] is returned instead of throwing into
 * the Compose composition, which would take down the whole UI tree.
 */
private inline fun <T> safeUseResource(resourcePath: String, fallback: () -> T, block: (InputStream) -> T): T =
    try {
        synchronized(resourceLock) {
            Thread.currentThread().contextClassLoader
                .getResourceAsStream(resourcePath)
                ?.use(block)
                ?: throw IllegalArgumentException("Resource not found: $resourcePath")
        }
    } catch (e: Exception) {
        logger.warn("Failed to load classpath resource '$resourcePath', using fallback", e)
        fallback()
    }

@Composable
fun rememberPainterResource(resourcePath: String): Painter =
    when (resourcePath.substringAfterLast(".")) {
        "svg" -> {
            val density = LocalDensity.current
            remember(resourcePath, density) {
                safeUseResource(resourcePath, { fallbackPainter }) { loadSvgPainter(it, density) }
            }
        }
        "xml" -> {
            val density = LocalDensity.current
            val image = remember(resourcePath, density) {
                safeUseResource(resourcePath, { fallbackImageVector() }) { loadXmlImageVector(InputSource(it), density) }
            }
            rememberVectorPainter(image)
        }
        else -> {
            val image = remember(resourcePath) {
                safeUseResource(resourcePath, { fallbackImageBitmap() }, ::loadImageBitmap)
            }
            BitmapPainter(image)
        }
    }
