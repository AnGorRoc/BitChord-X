package com.music.bitchord.playback

/**
 * The rule that keeps AutoPlay's tail full, shared by the phone and the desktop so they cannot
 * drift apart again. Each platform still fetches and appends in its own way; this decides how many
 * to ask for, whether to go on, and which song seeds the next request.
 */
object AutoplayRefill {
    /** Most AutoPlay-suggested tracks kept queued ahead of the current one at once. */
    const val MAX_QUEUED = 10

    /** Pause before asking again after a short or empty batch. */
    const val RETRY_DELAY_MS = 2_000L

    /** Pause between asks once quick retries are spent and the song is still short of a full tail. */
    const val WATCHDOG_MS = 15_000L

    /** How many more suggestions the tail needs; zero or less means it is full. */
    fun missing(queuedAhead: Int): Int = MAX_QUEUED - queuedAhead

    /** Seeds tried back to back in one pass before waiting. */
    const val SEEDS_PER_PASS = 4

    /** Pause between seeds inside one pass. */
    const val NEXT_SEED_DELAY_MS = 300L

    /**
     * Queue indexes to seed the radio from, in order: the playing song, the last one queued (what
     * the station should carry on from), then the rest — earlier ones first, then later ones.
     * A radio seeded on music that is already queued (Recents, say) often offers nothing new, so a
     * different seed is what finds more.
     */
    fun seedOrder(currentIndex: Int, size: Int): List<Int> {
        if (size <= 0 || currentIndex !in 0 until size) return emptyList()
        return (listOf(currentIndex, size - 1) + (currentIndex - 1 downTo 0) + (currentIndex + 1 until size))
            .distinct()
    }
}
