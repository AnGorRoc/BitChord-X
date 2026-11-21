package com.music.bitchord.data.feedback

/**
 * Makes a picture small enough to send before it is queued: at most [MAX_SIDE] pixels on its
 * longer side, PNG kept for screenshots (JPEG smears text) unless that stays large, photos as
 * JPEG. A 4 MB phone screenshot usually ends up a few hundred kilobytes. The server re-encodes
 * whatever arrives anyway, so this is about the person's data plan and upload time, not safety.
 *
 * Returns null for something that is not a picture it can read.
 */
fun interface FeedbackImageShrinker {
    fun shrink(bytes: ByteArray): PreparedFile?

    companion object {
        const val MAX_SIDE = 2560
        /** A PNG larger than this after scaling is sent as JPEG instead. */
        const val MAX_PNG_BYTES = 3 * 1024 * 1024
        const val JPEG_QUALITY = 85
        /** The server's limit for one upload. */
        const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024

        /** The picture type [bytes] start with, or null. */
        fun sniff(bytes: ByteArray): String? = when {
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }

        /** The size [w]×[h] scales to so neither side passes [MAX_SIDE]. */
        fun fit(w: Int, h: Int): Pair<Int, Int> {
            val longer = maxOf(w, h)
            if (longer <= MAX_SIDE) return w to h
            val scale = MAX_SIDE.toDouble() / longer
            return maxOf(1, Math.round(w * scale).toInt()) to maxOf(1, Math.round(h * scale).toInt())
        }
    }
}
