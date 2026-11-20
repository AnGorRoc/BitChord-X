package com.music.bitchord.playback.audio

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A short memory of what one player has handed to its audio output, stamped
 * with the track time each stretch of it belongs to.
 *
 * It exists for one job: telling where two renditions of the same track line
 * up by listening to what the two players actually produce, rather than by
 * decoding the files a second time on the side. A side decode cannot see what
 * the player does to the same bytes — the priming an AAC or MP3 decoder trims
 * or keeps, an Opus pre-skip, a seek landing on a frame boundary — and every
 * one of those is a shift between "this moment of the file" and "this moment
 * of the player's clock". What is recorded here is already on the player's
 * clock, so a lag measured between two taps is the lag between the two
 * players' positions, with nothing left to model.
 *
 * Kept as mono at roughly 12 kHz (box-averaged down by a whole factor), which
 * is all the alignment needs and keeps a ten-second memory near half a
 * megabyte. Always on: a swap that had to wait for the playing player's
 * history to build up would land seconds later, and the cost is a box filter
 * over each buffer.
 *
 * Written on the playback thread, read from anywhere.
 */
class PcmTap(private val capacityUs: Long = DEFAULT_CAPACITY_US) {

    /** One contiguous stretch of mono audio starting at [startUs] of track time. */
    class Chunk(val startUs: Long, val rateHz: Double, val samples: FloatArray) {
        val endUs: Long get() = startUs + (samples.size * 1_000_000.0 / rateHz).toLong()
    }

    private val lock = Any()
    private val chunks = ArrayDeque<Chunk>()

    // Playback-thread state: only touched from [configure]/[write]/[streamOffset].
    internal var encoding = C.ENCODING_INVALID
        private set
    internal var channels = 0
        private set
    internal var sampleRate = 0
        private set
    private var factor = 1
    internal var offsetUs = 0L
        private set

    /**
     * Track time the sink should move its content by, not yet carried out:
     * positive skips ahead, negative holds back. See [TappingAudioSink].
     */
    private val pendingShiftUs = AtomicLong(0L)

    /** Asks the sink to move this player's content by [deltaUs] at its next buffer. */
    fun requestShift(deltaUs: Long) {
        pendingShiftUs.addAndGet(deltaUs)
    }

    internal fun takeShift(): Long = pendingShiftUs.getAndSet(0L)

    /** Set by the sink while a requested move is still being carried out. */
    @Volatile
    internal var moving = false

    /** Whether a move asked for with [requestShift] has yet to finish. */
    fun isMoving(): Boolean = moving || pendingShiftUs.get() != 0L

    /** How many positions the sink has reported since [sinceNanos]. */
    fun positionsSince(sinceNanos: Long): Int = synchronized(positionLock) {
        var n = 0
        for (i in 0 until positionCount) if (positionNanos[i] >= sinceNanos) n++
        n
    }

    @Volatile
    private var resyncRequested = false

    /**
     * Asks the sink to re-anchor its clock on the next buffer's own timestamp.
     *
     * A sink counts the frames it was given from the first timestamp after a
     * flush and quietly absorbs any disagreement with the timestamps that
     * follow (Media3 lets up to 200 ms go). A decoder that discards samples
     * on a seek — Opus drops its pre-skip, 6.5 ms — leaves the playhead the
     * sink reports that far from the audio actually playing. Harmless for a
     * progress bar; a whole comb filter when two players are lined up on it.
     * Re-anchoring only moves the sink's bookkeeping, not the audio.
     */
    fun requestResync() {
        resyncRequested = true
    }

    internal fun takeResync(): Boolean {
        if (!resyncRequested) return false
        resyncRequested = false
        return true
    }

    // ── where the output is: the sink's own position, as the renderer asks for it ──

    private val positionLock = Any()
    private val positionNanos = LongArray(POSITION_SAMPLES)
    private val positionTrackUs = LongArray(POSITION_SAMPLES)
    private var positionCount = 0
    private var positionNext = 0

    /** What the sink reported as playing at [nanos] (System.nanoTime), in track time. */
    internal fun notePosition(nanos: Long, trackUs: Long) {
        synchronized(positionLock) {
            positionNanos[positionNext] = nanos
            positionTrackUs[positionNext] = trackUs
            positionNext = (positionNext + 1) % POSITION_SAMPLES
            if (positionCount < POSITION_SAMPLES) positionCount++
        }
    }

    /** Forgets the positions: after a flush they describe audio that will not play. */
    internal fun clearPositions() {
        synchronized(positionLock) { positionCount = 0 }
    }

    internal fun positionSamples(): Int = synchronized(positionLock) { positionCount }

    /**
     * Where the output's playhead was at [t0Nanos], in milliseconds of track
     * time, judged from every position the sink reported since [sinceNanos]
     * (each carried back to [t0Nanos] at [speed], then the median). These are
     * read where the sink computes them — on the playback thread, against the
     * output's own timestamps — with none of the player-level estimation a
     * `currentPosition` read goes through. Null with fewer than [minSamples].
     */
    fun playheadAtMs(t0Nanos: Long, sinceNanos: Long, speed: Float, minSamples: Int = 3): Double? {
        val values = ArrayList<Double>()
        synchronized(positionLock) {
            for (i in 0 until positionCount) {
                val n = positionNanos[i]
                if (n < sinceNanos) continue
                values += positionTrackUs[i] / 1000.0 - (n - t0Nanos) / 1_000_000.0 * speed
            }
        }
        if (values.size < minSamples) return null
        values.sort()
        return values[values.size / 2]
    }

    /** Takes the decoded format; anything that is not linear PCM is not recorded. */
    fun configure(pcmEncoding: Int, channelCount: Int, sampleRateHz: Int) {
        // The same format configured again (a decoder announcing it twice)
        // continues what was recorded.
        if (pcmEncoding == encoding && channelCount == channels && sampleRateHz == sampleRate) return
        encoding = pcmEncoding
        channels = channelCount
        sampleRate = sampleRateHz
        factor = max(1, (sampleRateHz / TARGET_RATE_HZ.toDouble()).roundToInt())
        clear()
    }

    /** The renderer's stream offset, subtracted to turn buffer timestamps into track time. */
    fun streamOffset(outputStreamOffsetUs: Long) {
        offsetUs = outputStreamOffsetUs
    }

    /** Whatever was recorded no longer continues: a seek, a flush, a new track. */
    fun clear() {
        synchronized(lock) { chunks.clear() }
    }

    /**
     * Records [buffer] from its current position without consuming it.
     * [presentationTimeUs] is the renderer timestamp the sink was given.
     */
    fun write(buffer: ByteBuffer, presentationTimeUs: Long) {
        if (presentationTimeUs == C.TIME_UNSET) return
        val bytesPerSample = bytesPerSample(encoding)
        if (bytesPerSample == 0 || channels <= 0 || sampleRate <= 0) return
        val frameBytes = bytesPerSample * channels
        val frames = buffer.remaining() / frameBytes
        val outCount = frames / factor
        if (outCount <= 0) return
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = view.position()
        val out = FloatArray(outCount)
        val norm = 1f / (channels * factor)
        for (o in 0 until outCount) {
            var sum = 0f
            for (f in 0 until factor) {
                val frameAt = base + (o * factor + f) * frameBytes
                for (c in 0 until channels) {
                    sum += sampleAt(view, frameAt + c * bytesPerSample, encoding)
                }
            }
            out[o] = sum * norm
        }
        val chunk = Chunk(
            startUs = presentationTimeUs - offsetUs,
            rateHz = sampleRate.toDouble() / factor,
            samples = out,
        )
        synchronized(lock) {
            // A timestamp that steps backwards is a seek the flush has not
            // reached yet; what came before it no longer continues.
            val last = chunks.lastOrNull()
            if (last != null && (chunk.startUs < last.startUs || chunk.startUs - last.endUs > GAP_TOLERANCE_US)) {
                chunks.clear()
            }
            chunks.addLast(chunk)
            while (chunks.size > 1 && chunk.endUs - chunks.first().startUs > capacityUs) chunks.removeFirst()
        }
    }

    /** A copy of what is held, oldest first. */
    fun snapshot(): List<Chunk> = synchronized(lock) { chunks.toList() }

    companion object {
        const val DEFAULT_CAPACITY_US = 12_000_000L
        const val TARGET_RATE_HZ = 12_000
        private const val POSITION_SAMPLES = 512

        /** A hole in the timestamps wider than this ends the stretch held. */
        private const val GAP_TOLERANCE_US = 30_000L

        fun bytesPerSample(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }

        private fun sampleAt(view: ByteBuffer, at: Int, encoding: Int): Float = when (encoding) {
            C.ENCODING_PCM_16BIT -> view.getShort(at) / 32768f
            C.ENCODING_PCM_FLOAT -> view.getFloat(at)
            C.ENCODING_PCM_8BIT -> ((view.get(at).toInt() and 0xFF) - 128) / 128f
            C.ENCODING_PCM_24BIT -> {
                val v = (view.get(at).toInt() and 0xFF) or
                    ((view.get(at + 1).toInt() and 0xFF) shl 8) or
                    (view.get(at + 2).toInt() shl 16)
                v / 8_388_608f
            }
            C.ENCODING_PCM_32BIT -> view.getInt(at) / 2_147_483_648f
            else -> 0f
        }
    }
}
