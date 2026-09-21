package scooper.util

import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.ImageOutputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

/** A compressed image ready for a multimodal AI request. */
data class EncodedImage(val bytes: ByteArray, val mimeType: String)

/**
 * Pure geometry: proportionally scale so the long edge is at most
 * [maxEdge]; sizes already within the limit pass through. Never returns
 * a zero dimension (rounds up to 1).
 *
 * Top-level so tests in the same package can call it unqualified.
 */
internal fun scaledSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= maxEdge) return Pair(width, height)
    val scale = maxEdge.toDouble() / longest
    val w = maxOf(1, Math.round(width * scale).toInt())
    val h = maxOf(1, Math.round(height * scale).toInt())
    return Pair(w, h)
}

/**
 * Pure JVM-side image preparation for multimodal AI requests. WebP is not
 * supported here because JVM ImageIO has no built-in WebP reader; callers
 * should convert or reject such files upstream.
 */
object ImageCodec {

    /** Hard ceiling on the encoded payload sent to the model. */
    private const val DEFAULT_MAX_BYTES = 4 * 1024 * 1024
    private const val JPEG_QUALITY = 0.92f

    /**
     * Load an image file and compress it for an AI request: downscale beyond
     * [maxEdge] and re-encode to fit [maxBytes]. PNG/JPEG only.
     */
    fun loadAndCompress(
        file: File,
        maxEdge: Int = 1568,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): Result<EncodedImage> {
        val image = try {
            ImageIO.read(file)
        } catch (_: Exception) {
            null
        } ?: return Result.failure(IllegalArgumentException("Unsupported or corrupt image: ${file.name}"))
        return encode(image, file.name, maxEdge, maxBytes)
    }

    /**
     * Compress raw image bytes (e.g. from the clipboard) with the same
     * policy as [loadAndCompress]. PNG/JPEG only.
     */
    fun compressBytes(
        bytes: ByteArray,
        mimeType: String,
        maxEdge: Int = 1568,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): Result<EncodedImage> {
        val image = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            null
        } ?: return Result.failure(IllegalArgumentException("Unsupported or corrupt image"))
        val isJpegSource = mimeType.equals("image/jpeg", ignoreCase = true)
        return encode(image, null, maxEdge, maxBytes, isJpegSource)
    }

    private fun encode(
        image: BufferedImage,
        sourceName: String?,
        maxEdge: Int,
        maxBytes: Int,
        forceJpeg: Boolean = false,
    ): Result<EncodedImage> {
        val (w, h) = scaledSize(image.width, image.height, maxEdge)
        val scaled = if (w != image.width || h != image.height) {
            val target = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g: Graphics2D = target.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            // Paint white first so transparent PNG areas do not turn black in JPEG.
            g.color = Color.WHITE
            g.fillRect(0, 0, w, h)
            g.drawImage(image, 0, 0, w, h, null)
            g.dispose()
            target
        } else {
            image
        }

        val wantJpeg = forceJpeg || sourceName?.lowercase()?.endsWith(".jpg") == true ||
            sourceName?.lowercase()?.endsWith(".jpeg") == true

        val pngBytes = if (!wantJpeg) toPngBytes(scaled) else null
        if (pngBytes != null && pngBytes.size <= maxBytes) {
            return Result.success(EncodedImage(pngBytes, "image/png"))
        }

        val jpegBytes = toJpegBytes(scaled)
            ?: return Result.failure(IllegalStateException("Failed to encode image"))
        if (jpegBytes.size > maxBytes) {
            return Result.failure(IllegalArgumentException("Image too large after compression (${jpegBytes.size} bytes)"))
        }
        return Result.success(EncodedImage(jpegBytes, "image/jpeg"))
    }

    private fun toPngBytes(image: BufferedImage): ByteArray? = try {
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        out.toByteArray()
    } catch (_: Exception) {
        null
    }

    /** Hand-rolled write param: ImageIO.write has no quality control. */
    private fun toJpegBytes(image: BufferedImage): ByteArray? = try {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.getDefaultWriteParam()
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT)
        param.setCompressionType("JPEG")
        param.setCompressionQuality(JPEG_QUALITY)
        val out = ByteArrayOutputStream()
        val stream: ImageOutputStream = MemoryCacheImageOutputStream(out)
        writer.output = stream
        writer.write(null, IIOImage(image, null, null), param)
        writer.dispose()
        stream.close()
        out.toByteArray()
    } catch (_: Exception) {
        null
    }
}
