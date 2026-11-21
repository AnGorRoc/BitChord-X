package com.music.bitchord.data.feedback

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** [FeedbackImageShrinker] for the desktop, on ImageIO. */
object DesktopFeedbackImageShrinker : FeedbackImageShrinker {
    override fun shrink(bytes: ByteArray): PreparedFile? {
        val type = FeedbackImageShrinker.sniff(bytes) ?: return null
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
        if (image == null) {
            // ImageIO has no WebP reader; the server does, so a WebP goes as it is when it fits.
            return if (type == "image/webp" && bytes.size <= FeedbackImageShrinker.MAX_UPLOAD_BYTES) PreparedFile(bytes, type) else null
        }
        val (w, h) = FeedbackImageShrinker.fit(image.width, image.height)
        val scaled = w != image.width || h != image.height
        // Small enough and already a type the server takes: no need to touch it.
        if (!scaled && type != "image/webp" && bytes.size <= FeedbackImageShrinker.MAX_PNG_BYTES) return PreparedFile(bytes, type)

        val img = if (scaled) scale(image, w, h) else image
        if (type == "image/png") {
            val png = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
            if (png.size <= FeedbackImageShrinker.MAX_PNG_BYTES) return PreparedFile(png, "image/png")
        }
        return PreparedFile(jpeg(img), "image/jpeg")
    }

    private fun scale(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val type = if (src.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val dst = BufferedImage(w, h, type)
        dst.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(src, 0, 0, w, h, null)
            dispose()
        }
        return dst
    }

    private fun jpeg(src: BufferedImage): ByteArray {
        // JPEG has no alpha: put the picture on white first, or ImageIO writes nonsense colours.
        val rgb = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            color = Color.WHITE
            fillRect(0, 0, src.width, src.height)
            drawImage(src, 0, 0, null)
            dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = FeedbackImageShrinker.JPEG_QUALITY / 100f
            }
            writer.write(null, IIOImage(rgb, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }
}
