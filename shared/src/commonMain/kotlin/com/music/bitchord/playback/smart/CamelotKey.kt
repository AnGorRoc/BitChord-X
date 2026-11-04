package com.music.bitchord.playback.smart

import kotlin.math.min

/**
 * Representation of musical keys mapped to the Camelot EasyMix System (Circle of Fifths)
 * for harmonic DJ mixing.
 *
 * Each key is mapped to a Camelot wheel position 1..12 and mode (A for minor, B for major).
 * Compatible transitions:
 * - Exact key match (e.g. 8A -> 8A, 8B -> 8B)
 * - Relative major / minor swap (e.g. 8A -> 8B, 8B -> 8A)
 * - Circle of fifths step (+1 or -1 hour, e.g. 8A -> 9A or 8A -> 7A)
 * - Diagonal relative step (+1 or -1 hour in opposite mode, e.g. 8A -> 9B or 8A -> 7B)
 */
data class CamelotKey(val number: Int, val isMinor: Boolean) {
    val code: String get() = "$number${if (isMinor) "A" else "B"}"

    /**
     * Returns true if two keys can be blended together harmoniously without musical dissonance.
     * Distance of 0 (same key or relative key) or 1 (adjacent hour on Camelot wheel) are compatible.
     * Distance >= 2 (e.g. tritone clash at distance 6) is a severe harmonic clash.
     */
    fun isCompatibleWith(other: CamelotKey): Boolean {
        val diff = (number - other.number + 12) % 12
        val distance = min(diff, (12 - diff) % 12)
        return distance <= 1
    }

    companion object {
        private val MAJOR_MAP = mapOf(
            "B" to 1,
            "F♯" to 2, "F#" to 2, "G♭" to 2, "Gb" to 2,
            "D♭" to 3, "Db" to 3, "C♯" to 3, "C#" to 3,
            "A♭" to 4, "Ab" to 4, "G♯" to 4, "G#" to 4,
            "E♭" to 5, "Eb" to 5, "D♯" to 5, "D#" to 5,
            "B♭" to 6, "Bb" to 6, "A♯" to 6, "A#" to 6,
            "F" to 7,
            "C" to 8,
            "G" to 9,
            "D" to 10,
            "A" to 11,
            "E" to 12,
        )

        private val MINOR_MAP = mapOf(
            "A♭" to 1, "Ab" to 1, "G♯" to 1, "G#" to 1,
            "E♭" to 2, "Eb" to 2, "D♯" to 2, "D#" to 2,
            "B♭" to 3, "Bb" to 3, "A♯" to 3, "A#" to 3,
            "F" to 4,
            "C" to 5,
            "G" to 6,
            "D" to 7,
            "A" to 8,
            "E" to 9,
            "B" to 10,
            "F♯" to 11, "F#" to 11, "G♭" to 11, "Gb" to 11,
            "D♭" to 12, "Db" to 12, "C♯" to 12, "C#" to 12,
        )

        /**
         * Parses a key string emitted by the analyzer (e.g. "C major", "A minor", "F♯ minor", "Bb major", "8A").
         */
        fun parse(key: String): CamelotKey? {
            val trimmed = key.trim()
            if (trimmed.isBlank()) return null

            // If already in Camelot notation (e.g. "8A", "11B")
            val camelotRegex = Regex("""^(\d{1,2})([aAbB])$""")
            camelotRegex.matchEntire(trimmed)?.let { match ->
                val num = match.groupValues[1].toIntOrNull() ?: return null
                if (num in 1..12) {
                    val isMin = match.groupValues[2].equals("A", ignoreCase = true)
                    return CamelotKey(num, isMin)
                }
            }

            val parts = trimmed.split(' ')
            val root = parts.firstOrNull() ?: return null
            val isMinor = parts.getOrNull(1)?.lowercase()?.startsWith("min") == true
            val number = if (isMinor) MINOR_MAP[root] else MAJOR_MAP[root]
            return number?.let { CamelotKey(it, isMinor) }
        }
    }
}
