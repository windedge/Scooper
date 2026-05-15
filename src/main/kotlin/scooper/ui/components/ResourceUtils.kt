@file:Suppress("DEPRECATION")

package scooper.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.loadXmlImageVector
import org.xml.sax.InputSource
import java.io.InputStream

/**
 * Lock to serialize classpath resource reads.
 * Prevents [java.io.EOFException] caused by concurrent ZipFile access
 * when multiple Compose compositions read resources simultaneously.
 */
private val resourceLock = Any()

private fun <T> safeUseResource(resourcePath: String, block: (InputStream) -> T): T =
    synchronized(resourceLock) {
        Thread.currentThread().contextClassLoader
            .getResourceAsStream(resourcePath)
            ?.use(block)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")
    }

@Composable
fun rememberPainterResource(resourcePath: String): Painter =
    when (resourcePath.substringAfterLast(".")) {
        "svg" -> {
            val density = LocalDensity.current
            remember(resourcePath, density) {
                safeUseResource(resourcePath) { loadSvgPainter(it, density) }
            }
        }
        "xml" -> {
            val density = LocalDensity.current
            val image = remember(resourcePath, density) {
                safeUseResource(resourcePath) { loadXmlImageVector(InputSource(it), density) }
            }
            rememberVectorPainter(image)
        }
        else -> {
            val image = remember(resourcePath) {
                safeUseResource(resourcePath, ::loadImageBitmap)
            }
            BitmapPainter(image)
        }
    }
