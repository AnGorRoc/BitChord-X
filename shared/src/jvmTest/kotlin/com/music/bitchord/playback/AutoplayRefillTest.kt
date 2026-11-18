package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoplayRefillTest {
    @Test
    fun seedsStartWithCurrentThenLastQueuedThenTheRest() {
        assertEquals(listOf(2, 5, 1, 0, 3, 4), AutoplayRefill.seedOrder(2, 6))
    }

    @Test
    fun aSingleSongSeedsOnlyItself() {
        assertEquals(listOf(0), AutoplayRefill.seedOrder(0, 1))
    }

    @Test
    fun noSeedsWithoutAValidCurrent() {
        assertEquals(emptyList(), AutoplayRefill.seedOrder(-1, 3))
        assertEquals(emptyList(), AutoplayRefill.seedOrder(0, 0))
    }

    @Test
    fun missingCountsWhatIsLeftToFill() {
        assertEquals(3, AutoplayRefill.missing(AutoplayRefill.MAX_QUEUED - 3))
    }
}
