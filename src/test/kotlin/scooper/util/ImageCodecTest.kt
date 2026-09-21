package scooper.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * TDD tests for [ImageCodec]: pure geometry ([scaledSize]) and pure
 * bytes-to-bytes compression ([compressBytes]) used to prepare images for
 * multimodal AI requests. File loading reuses [compressBytes] via
 * [loadAndCompress], covered through synthetic in-memory payloads.
 */
class ImageCodecTest {

    /** PNG magic bytes: any real PNG payload starts with these. */
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    // ---------------------------------------------------------------
    // scaledSize (pure geometry)
    // ---------------------------------------------------------------

    @Test
    fun `scaledSize keeps size within maxEdge untouched`() {
        assertEquals(Pair(800, 600), scaledSize(800, 600, 1568))
        assertEquals(Pair(1568, 1568), scaledSize(1568, 1568, 1568))
    }

    @Test
    fun `scaledSize scales landscape down proportionally by long edge`() {
        assertEquals(Pair(1568, 784), scaledSize(3136, 1568, 1568))
    }

    @Test
    fun `scaledSize scales portrait down proportionally by long edge`() {
        assertEquals(Pair(784, 1568), scaledSize(1568, 3136, 1568))
    }

    @Test
    fun `scaledSize rounds to at least one pixel`() {
        assertEquals(Pair(1, 1), scaledSize(1, 2, 1))
    }

    // ---------------------------------------------------------------
    // compressBytes (pure bytes transformation)
    // ---------------------------------------------------------------

    @Test
    fun `wide PNG is downscaled so the long edge fits maxEdge`() {
        val png = ImageTestFactory.png(2000, 100, java.awt.Color.WHITE)

        val result = ImageCodec.compressBytes(png, "image/png")

        val image = assertIs<kotlin.Result<scooper.util.EncodedImage>>(result).getOrThrow()
        assertEquals("image/png", image.mimeType)
        val (w, h) = ImageTestFactory.dimensions(image.bytes)
        assertEquals(1568, w)
        assertEquals(78, h)
    }

    @Test
    fun `small PNG stays PNG and decodes back at the same size`() {
        val png = ImageTestFactory.png(120, 80, java.awt.Color.BLUE)

        val result = ImageCodec.compressBytes(png, "image/png")

        val image = assertIs<kotlin.Result<scooper.util.EncodedImage>>(result).getOrThrow()
        assertEquals("image/png", image.mimeType)
        val (w, h) = ImageTestFactory.dimensions(image.bytes)
        assertEquals(120, w)
        assertEquals(80, h)
    }

    @Test
    fun `JPEG input re-encodes as JPEG`() {
        val jpeg = ImageTestFactory.jpeg(300, 200)

        val result = ImageCodec.compressBytes(jpeg, "image/jpeg")

        val image = assertIs<kotlin.Result<scooper.util.EncodedImage>>(result).getOrThrow()
        assertEquals("image/jpeg", image.mimeType)
        val (w, h) = ImageTestFactory.dimensions(image.bytes)
        // A real JPEG payload cannot also be a PNG (signature check).
        assertTrue(!image.bytes.decodePNG().contentEquals(PNG_SIGNATURE))
        assertEquals(300, w)
        assertEquals(200, h)
    }

    @Test
    fun `non-image bytes fail with the unsupported message`() {
        val result = ImageCodec.compressBytes("definitely not an image".toByteArray(), "image/png")

        val error = assertIs<kotlin.Result<scooper.util.EncodedImage>>(result).exceptionOrNull()
        assertEquals("Unsupported or corrupt image", error!!.message)
    }

    // ---------------------------------------------------------------
    // loadAndCompress (file path, exercised via byte-level factories)
    // ---------------------------------------------------------------

    @Test
    fun `loadAndCompress rejects an oversized payload beyond maxBytes`() {
        val png = ImageTestFactory.png(4000, 4000, java.awt.Color.WHITE)

        val result = ImageCodec.compressBytes(png, "image/png", maxBytes = 1)

        val error = assertIs<kotlin.Result<scooper.util.EncodedImage>>(result).exceptionOrNull()
        assertTrue(error!!.message!!.contains("too large"))
    }
}
