package com.music.bitchord.data.listentogether

import com.music.bitchord.data.listentogether.account.PlayIntent
import com.music.bitchord.data.listentogether.account.SeekIntent
import com.music.bitchord.data.listentogether.account.SkipIntent

/**
 * What this device asked of the party and has not yet seen the party do.
 *
 * The server is the judge in a party: every control it applies bumps [PartyPlayback.seq] and comes
 * back to everybody as a state frame. Between the press and that echo the party's state still
 * describes the world before the press, and two things went wrong in that gap:
 *
 *  - **A control could vanish.** One sent while the socket was down was dropped without a word, and
 *    one sent into a socket that was dying went with it. The press did nothing, and the device that
 *    made it was put back where it had been once its wait for the echo ran out.
 *  - **A remote showed the past.** With Play here off, the bar, the play button and the song are
 *    the party's state, so a seek drew the bar back to where it was until the echo came, and two
 *    quick presses of "forward 10 s" both started from the same old position.
 *
 * So, the same way a Connect remote holds what it asked of the device that plays (see [SeekIntent]):
 * each control is kept until the party's sequence number shows it applied, sent again once if nothing
 * has applied it for [SeekIntent.RESEND_MS] (or as soon as a dropped socket is back), and given up
 * after [SeekIntent.GIVE_UP_MS]. And what it asked for is what a remote shows until then.
 *
 * [T] is the client's own form of a control to send again. [clock] is a monotonic millisecond clock.
 */
class PartyIntents<T : Any>(private val clock: () -> Long) {

    private val seek = SeekIntent(clock)
    private val press = PlayIntent(clock)
    private val skip = SkipIntent(clock)

    // ---------------------------------------------------------- what to show --

    /** A seek to [positionMs] of the track that is playing ([trackId]), or of the one a skip leads to. */
    fun expectSeek(positionMs: Long, trackId: String?) {
        seek.expect(positionMs, skip.expectedId?.takeIf { skip.pending } ?: trackId, resendable = false)
    }

    fun expectPress(play: Boolean) = press.expect(play)

    /** A move to [trackId] from its start; what next, previous and choosing a song all do. */
    fun expectTrack(trackId: String?, positionMs: Long = 0L, playing: Boolean = true) {
        if (trackId == null) return
        skip.expect(trackId)
        seek.expect(positionMs, trackId, resendable = false)
        press.expect(playing)
    }

    /** The track a skip is heading to, while it is under way. */
    val skippingTo: String? get() = skip.expectedId?.takeIf { skip.pending }

    /**
     * The party's state changed or was re-told: [trackId] at [positionMs] (on this device's clock),
     * [playing]. Each intent the state agrees with is let go; a state that predates one is ignored.
     */
    fun onPlayback(trackId: String?, positionMs: Long?, playing: Boolean) {
        if (trackId == null) {
            clearShown()
            return
        }
        // Still the song being left, or one passed through on the way: says nothing yet.
        if (!skip.accepts(trackId)) return
        press.accepts(playing)
        if (positionMs != null) seek.accepts(trackId, positionMs, shownPlaying(playing))
    }

    fun shownPlaying(partyPlaying: Boolean): Boolean = if (press.pending) press.wantsPlay else partyPlaying

    fun shownPositionMs(partyPositionMs: Long?, partyPlaying: Boolean): Long? =
        if (seek.pending) seek.positionMs(shownPlaying(partyPlaying)) else partyPositionMs

    fun shownTrackId(partyTrackId: String?): String? = skippingTo ?: partyTrackId

    // ------------------------------------------------------ what to send again --

    private class Sent<T>(val control: T, val atMs: Long)

    private val unanswered = ArrayList<Sent<T>>()

    /** The party's seq before the oldest of [unanswered] was sent. */
    private var baseSeq = 0L
    private var resent = false

    /**
     * A playback control went out (or should have: [resendAs] is kept either way) while the party
     * stood at [partySeq]. Null [resendAs] is a control that cannot safely be repeated; it is still
     * counted, so the ones after it are matched to the right echoes.
     */
    fun sent(resendAs: T?, partySeq: Long) {
        dropIfStale()
        if (unanswered.isEmpty()) {
            baseSeq = partySeq
            resent = false
        }
        @Suppress("UNCHECKED_CAST")
        unanswered += Sent(resendAs ?: NOTHING as T, clock())
    }

    /**
     * The party is at [partySeq]. Every control it applied since [baseSeq] answers one of ours, in
     * order — a control somebody else made answers one too, which is right: theirs came later and
     * stands over ours anyway.
     */
    fun answeredUpTo(partySeq: Long) {
        val applied = (partySeq - baseSeq).coerceIn(0L, unanswered.size.toLong()).toInt()
        if (applied == 0) return
        repeat(applied) { unanswered.removeAt(0) }
        baseSeq += applied
    }

    /** Controls nothing has answered for [SeekIntent.RESEND_MS], to send once more; empty otherwise. */
    fun dueAgain(): List<Pair<T, Long>> {
        dropIfStale()
        if (resent || unanswered.isEmpty()) return emptyList()
        if (clock() - unanswered.last().atMs < SeekIntent.RESEND_MS) return emptyList()
        resent = true
        return repeatable()
    }

    /**
     * The socket is back and the party stands at [partySeq]. What it has not applied went out into
     * the dead socket or never went at all, so it is sent now — if it is still recent enough to be
     * what the person wants. Counted from here as a fresh send, and not repeated again.
     */
    fun dueAfterReconnect(partySeq: Long): List<Pair<T, Long>> {
        answeredUpTo(partySeq)
        dropIfStale()
        if (unanswered.isEmpty()) return emptyList()
        baseSeq = partySeq
        resent = true
        return repeatable()
    }

    /** The server refused a control: nothing of what is held is going to happen. */
    fun refused() {
        unanswered.clear()
        clearShown()
    }

    fun clear() {
        unanswered.clear()
        resent = false
        clearShown()
    }

    /** Each repeatable control with how long ago it was first sent, oldest first. */
    private fun repeatable(): List<Pair<T, Long>> {
        val now = clock()
        return unanswered.filter { it.control !== NOTHING }.map { it.control to now - it.atMs }
    }

    private fun dropIfStale() {
        val last = unanswered.lastOrNull() ?: return
        if (clock() - last.atMs >= SeekIntent.GIVE_UP_MS) {
            unanswered.clear()
            resent = false
        }
    }

    private fun clearShown() {
        seek.clear()
        press.clear()
        skip.clear()
    }

    private companion object {
        val NOTHING = Any()
    }
}

/** The track [delta] steps from where the party is, or from where a skip already under way leads. */
fun partyStepTarget(queue: PartyQueue, playback: PartyPlayback, from: String?, delta: Int): PartyTrack? {
    val at = partyQueueIndexOf(queue, playback, from ?: playback.track?.videoId)
    if (at < 0) return null
    return queue.items.getOrNull(at + delta)
}
