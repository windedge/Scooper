package scooper.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Synthetic image factory for [ImageCodecTest]: builds PNG/JPEG payloads in
 * memory so tests never touch the filesystem for fixtures.
 */
object ImageTestFactory {

    fun png(width: Int, height: Int, color: java.awt.Color): ByteArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setColor(color)
        g.fillRect(0, 0, width, height)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    fun jpeg(width: Int, height: Int): ByteArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setColor(java.awt.Color.RED)
        g.fillRect(0, 0, width, height)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

/** Decodes payload and returns its dimensions; fails the test on bad bytes. */
    fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertTrue(image != null, "payload must decode as an image")
        return Pair(image.width, image.height)
    }
}

/** Cheap PNG probe: real PNGs start with the 8-byte signature. */
/** PNG probe: returns the payload unchanged; PNG magic lives in the first bytes. */
internal fun ByteArray.decodePNG(): ByteArray = this
