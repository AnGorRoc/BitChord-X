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

    /**
     * Which earlier song seeds the next request. A radio that has nothing new left for this song is
     * asked again from the one before it, then the one before that, wrapping back to the current
     * song once the queue's start is passed.
     */
    fun nextSeedBack(currentIndex: Int, seedBack: Int): Int =
        if (currentIndex - (seedBack + 1) >= 0) seedBack + 1 else 0
}
