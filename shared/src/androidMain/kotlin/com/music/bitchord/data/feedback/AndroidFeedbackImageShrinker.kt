package com.music.bitchord.data.feedback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * [FeedbackImageShrinker] for the phone. Every picture is decoded and written again, which also
 * applies the camera's EXIF rotation: the server drops EXIF, so a photo that relied on the flag
 * would otherwise arrive on its side.
 */
object AndroidFeedbackImageShrinker : FeedbackImageShrinker {
    override fun shrink(bytes: ByteArray): PreparedFile? {
        val type = FeedbackImageShrinker.sniff(bytes) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Decode at the smallest power-of-two reduction that still covers MAX_SIDE, so a 50 MP
        // photo never sits in memory at full size.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= FeedbackImageShrinker.MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null

        val (w, h) = FeedbackImageShrinker.fit(decoded.width, decoded.height)
        var bitmap = if (w != decoded.width || h != decoded.height) {
            Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        bitmap = rotated(bitmap, orientation(bytes))

        try {
            if (type == "image/png") {
                val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                if (png.size <= FeedbackImageShrinker.MAX_PNG_BYTES) return PreparedFile(png, "image/png")
            }
            val opaque = if (bitmap.hasAlpha()) onWhite(bitmap) else bitmap
            val jpeg = ByteArrayOutputStream().also { opaque.compress(Bitmap.CompressFormat.JPEG, FeedbackImageShrinker.JPEG_QUALITY, it) }.toByteArray()
            if (opaque !== bitmap) opaque.recycle()
            return PreparedFile(jpeg, "image/jpeg")
        } finally {
            bitmap.recycle()
        }
    }

    private fun orientation(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun rotated(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    private fun onWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return out
    }
}
