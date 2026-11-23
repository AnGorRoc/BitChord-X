package com.music.bitchord.data.listentogether.account

import kotlin.math.abs

/**
 * What a remote asked of the device that plays, held until that device's own report shows it done.
 *
 * A remote draws the other device's clock from its reports, and those reports run behind what the
 * person just did: one sent a moment before the command arrived still carries the old song, the old
 * position, the old play state. Taking every report as the truth is what made the bar jump back to
 * where it was, and taking none for a fixed while is what made it lie when a command was lost.
 *
 * So each thing asked is held as an intent: shown at once, kept while reports contradict it, let go
 * the moment a report agrees, sent once more if nothing has agreed for a while, and given up only
 * after that, when the reports are believed again. The rules are here, in one place, so the phone
 * and the computer reconcile the same way.
 *
 * [clock] is a monotonic millisecond clock; everything here is measured against it.
 */
class SeekIntent(private val clock: () -> Long) {
    private var targetMs = 0L
    private var mediaId: String? = null
    private var sentAt = NEVER
    private var resendable = false
    private var resent = false

    /**
     * The person moved the playhead to [positionMs] of [mediaId] (null: whatever is playing).
     * [resendable]: a seek command was sent and may be sent again; a skip or a queue hand-over
     * only expects its start and has nothing of its own to repeat.
     */
    fun expect(positionMs: Long, mediaId: String?, resendable: Boolean) {
        targetMs = positionMs.coerceAtLeast(0L)
        this.mediaId = mediaId
        sentAt = clock()
        this.resendable = resendable
        resent = false
    }

    val pending: Boolean get() = sentAt != NEVER && clock() - sentAt < GIVE_UP_MS

    /** The position last asked for, pending or not: what to show before any report has come. */
    val targetPositionMs: Long get() = targetMs

    /** Where the bar is while [pending]: the asked position, run on if the music plays. */
    fun positionMs(playing: Boolean): Long =
        targetMs + if (playing) (clock() - sentAt).coerceAtLeast(0L) else 0L

    /**
     * A report of [reportMediaId] at [reportPositionMs] (already aged to now) arrived. True when it
     * is to be believed; false when it predates the intent and its position must be ignored.
     *
     * [settled]: the device is in the state the person wants (playing when they want it playing),
     * not in the middle of opening a song. A seek seen to land while a song is still opening is
     * believed but not let go of: the song's start can still throw it away (a computer that opens
     * the stream after the seek arrived starts from the top), and then it has to be sent again.
     */
    fun accepts(reportMediaId: String, reportPositionMs: Long, playing: Boolean, settled: Boolean = true): Boolean {
        if (!pending) {
            clear()
            return true
        }
        val asked = mediaId
        // Another song than the one sought in: the device moved on, and the seek is moot.
        if (asked != null && reportMediaId != asked) {
            clear()
            return true
        }
        val landed = abs(reportPositionMs - positionMs(playing)) <= LANDED_TOLERANCE_MS
        if (landed && settled) clear()
        return landed
    }

    /**
     * The position to send again, once, when nothing has agreed for [RESEND_MS]; null otherwise. It is
     * where the bar is by now, so a repeat does not throw the music back to the moment of the tap.
     */
    fun resendPositionMs(playing: Boolean): Long? {
        if (!pending || !resendable || resent || clock() - sentAt < RESEND_MS) return null
        resent = true
        return positionMs(playing)
    }

    fun clear() {
        sentAt = NEVER
        mediaId = null
        resent = false
    }

    companion object {
        /** A report this close to where the bar is is the seek having landed (stream starts are not exact). */
        const val LANDED_TOLERANCE_MS = 2_500L

        /** With no report agreeing this long, the command is sent once more: it may have been lost. */
        const val RESEND_MS = 2_000L

        /** And after this the reports are believed again, whatever they say. */
        const val GIVE_UP_MS = 6_000L

        private const val NEVER = Long.MIN_VALUE / 2
    }
}

/** Play or pause asked of the device that plays; the same lifecycle as [SeekIntent]. */
class PlayIntent(private val clock: () -> Long) {
    private var wanted = false
    private var sentAt = NEVER
    private var resent = false

    fun expect(play: Boolean) {
        wanted = play
        sentAt = clock()
        resent = false
    }

    val pending: Boolean get() = sentAt != NEVER && clock() - sentAt < GIVE_UP_MS

    /** What the person asked for, while [pending]. */
    val wantsPlay: Boolean get() = wanted

    /** True when a report saying [playing] is to be believed. */
    fun accepts(playing: Boolean): Boolean {
        if (!pending) return true
        val agrees = playing == wanted
        if (agrees) sentAt = NEVER
        return agrees
    }

    /** Whether to send the press again, once, because no report has agreed with it. */
    fun shouldResend(): Boolean {
        if (!pending || resent || clock() - sentAt < SeekIntent.RESEND_MS) return false
        resent = true
        return true
    }

    fun clear() {
        sentAt = NEVER
        resent = false
    }

    private companion object {
        const val GIVE_UP_MS = SeekIntent.GIVE_UP_MS
        const val NEVER = Long.MIN_VALUE / 2
    }
}

/**
 * A skip made on the remote, which already shows the song it leads to: reports of other songs (the
 * one being left, or ones passed through on the way) are not news until the device gets there.
 */
class SkipIntent(private val clock: () -> Long) {
    var expectedId: String? = null
        private set
    private var sentAt = NEVER

    fun expect(mediaId: String?) {
        expectedId = mediaId
        sentAt = if (mediaId == null) NEVER else clock()
    }

    val pending: Boolean get() = expectedId != null && clock() - sentAt < WINDOW_MS

    /**
     * True when a report of [reportMediaId] is to be believed; reaching the song ends the skip. So does
     * a blend [headingTo] it: a device that mixes into the next song keeps the one it leaves for half
     * the blend, and that is the skip under way, shown as the device shows it, not a song passed by.
     */
    fun accepts(reportMediaId: String, headingTo: String? = null): Boolean {
        val expected = expectedId ?: return true
        if (reportMediaId == expected || headingTo == expected) {
            clear()
            return true
        }
        if (!pending) {
            clear()
            return true
        }
        return false
    }

    fun clear() {
        expectedId = null
        sentAt = NEVER
    }

    companion object {
        /** A computer has to find the next song's stream; long enough for that, short enough to recover. */
        const val WINDOW_MS = 4_000L
        private const val NEVER = Long.MIN_VALUE / 2
    }
}
