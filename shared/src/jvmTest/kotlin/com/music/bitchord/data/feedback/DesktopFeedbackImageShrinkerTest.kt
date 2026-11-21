package com.music.bitchord.data.feedback

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class DesktopFeedbackImageShrinkerTest {
    private fun encode(w: Int, h: Int, format: String): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { color = Color(30, 120, 200); fillRect(0, 0, w / 2, h); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(img, format, it) }.toByteArray()
    }

    @Test
    fun largeScreenshotIsScaledAndStaysPng() {
        val out = assertNotNull(DesktopFeedbackImageShrinker.shrink(encode(5000, 1000, "png")))
        assertEquals("image/png", out.mime)
        val img = ImageIO.read(ByteArrayInputStream(out.bytes))
        assertEquals(2560, img.width)
        assertEquals(512, img.height)
    }

    @Test
    fun smallPictureGoesUntouched() {
        val bytes = encode(800, 600, "jpg")
        val out = assertNotNull(DesktopFeedbackImageShrinker.shrink(bytes))
        assertSame(bytes, out.bytes)
        assertEquals("image/jpeg", out.mime)
    }

    @Test
    fun notAPicture() {
        assertNull(DesktopFeedbackImageShrinker.shrink("<svg/>".toByteArray()))
        assertNull(DesktopFeedbackImageShrinker.shrink(ByteArray(0)))
    }
}
