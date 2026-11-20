package com.music.bitchord.playback.smart

import com.music.bitchord.playback.audio.PcmTap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Where two players playing the same recording line up, read from what each
 * of them has actually produced — see [PcmTap] for why that and not the files.
 *
 * The playing player's tap holds the last several seconds it rendered; the
 * incoming one, started silently somewhere near the right moment, holds the
 * couple of seconds since it started. The incoming stretch is slid along the
 * playing one and the shift where they agree best is the answer:
 *
 *  1. **Coarse**, on loudness envelopes (1 ms hops), across the whole search
 *     range. Envelopes survive a different codec, bitrate or even master,
 *     where waveforms may not.
 *  2. **Fine**, on the waveforms themselves (8 kHz), a few milliseconds
 *     either side of the coarse answer, interpolated between samples. Two
 *     encodings of one master agree to well under a millisecond here; when the
 *     waveforms don't agree (a different master), the coarse figure stands.
 *
 * Music repeats itself — a loop, a four-on-the-floor kick — so a strong peak
 * is not enough: the runner-up a beat away must be clearly weaker, or the
 * answer is withheld as ambiguous rather than risk locking a bar off.
 *
 * Pure arithmetic over the taps' contents, so it is tested without a player.
 */
object LiveAlignment {

    const val GRID_HZ = 8_000.0
    private const val ENV_HOP = 8 // samples of the grid: 1 ms
    private const val ENV_WIN = 16 // 2 ms
    private const val FINE_RADIUS_MS = 4.0

    /** The weakest coarse match believed. */
    const val MIN_CONFIDENCE = 0.6

    /** How much clearer than the best rival a peak must be. */
    const val MIN_MARGIN = 0.04

    /** Rival peaks closer than this to the best are the same peak. */
    private const val RIVAL_EXCLUSION_MS = 40.0

    /** The waveform match below which the fine pass is not trusted. */
    const val MIN_FINE_CONFIDENCE = 0.5

    sealed interface Result {
        /**
         * The incoming player at track time `t + offsetMs` plays what the
         * playing one plays at `t`.
         */
        data class Aligned(
            val offsetMs: Double,
            val confidence: Double,
            val margin: Double,
            val fine: Boolean,
        ) : Result

        /** Not enough audio on one side yet, or not enough of it overlapping. */
        data object NeedMore : Result

        /** Plenty of audio and no convincing single match. */
        data class Unsure(val confidence: Double, val margin: Double) : Result
    }

    /** A signal laid on the shared grid: sample `i` is at [startUs] + i / [GRID_HZ]. */
    class Grid(val startUs: Double, val samples: FloatArray)

    /**
     * Searches offsets within [guessMs] ± [radiusMs].
     *
     * [minIncomingMs] is how much incoming audio there must be before a match
     * is attempted at all; less than that and a short phrase can match in
     * several places.
     */
    fun measure(
        playing: List<PcmTap.Chunk>,
        incoming: List<PcmTap.Chunk>,
        guessMs: Double,
        radiusMs: Double,
        minIncomingMs: Double = 2_500.0,
    ): Result {
        val a = toGrid(playing) ?: return Result.NeedMore
        val b = toGrid(incoming) ?: return Result.NeedMore
        return measureGrids(a, b, guessMs, radiusMs, minIncomingMs)
    }

    internal fun measureGrids(
        a: Grid,
        b: Grid,
        guessMs: Double,
        radiusMs: Double,
        minIncomingMs: Double = 2_500.0,
    ): Result {
        val envA = envelope(a.samples)
        val envB = envelope(b.samples)
        if (envA.isEmpty() || envB.isEmpty()) return Result.NeedMore
        val hopMs = ENV_HOP / GRID_HZ * 1000.0
        val startDiffMs = (b.startUs - a.startUs) / 1000.0

        // Incoming envelope frame k (track time b.start + k·hop) meets playing
        // frame k + s; the offset that pairing stands for is startDiff - s·hop.
        val lowShift = floor((startDiffMs - (guessMs + radiusMs)) / hopMs).toInt()
        val highShift = floor((startDiffMs - (guessMs - radiusMs)) / hopMs).toInt() + 1

        // Only the incoming frames every candidate shift can see: comparing a
        // shift that has its whole stretch against one that is still waiting
        // for the playing player to get there picks the wrong one.
        val kStart = max(0, -lowShift)
        val kEnd = min(envB.size, envA.size - highShift)
        if ((kEnd - kStart) * hopMs < minIncomingMs) return Result.NeedMore

        val scores = DoubleArray(highShift - lowShift + 1)
        for (s in lowShift..highShift) {
            scores[s - lowShift] = ncc(envB, envA, s, kStart, kEnd) ?: Double.NaN
        }

        var best = -1
        for (i in scores.indices) if (!scores[i].isNaN() && (best < 0 || scores[i] > scores[best])) best = i
        if (best < 0) return Result.Unsure(0.0, 0.0)
        val bestScore = scores[best]
        val exclusion = (RIVAL_EXCLUSION_MS / hopMs).roundToInt()
        var rival = -1.0
        for (i in scores.indices) {
            if (scores[i].isNaN() || abs(i - best) <= exclusion) continue
            if (isLocalPeak(scores, i) && scores[i] > rival) rival = scores[i]
        }
        val margin = if (rival < 0) 1.0 else bestScore - rival
        if (bestScore < MIN_CONFIDENCE || margin < MIN_MARGIN) return Result.Unsure(bestScore, margin)

        // Sub-hop position of the coarse peak.
        val coarseShift = lowShift + best + parabolic(scores, best)
        val coarseOffsetMs = startDiffMs - coarseShift * hopMs

        val fine = refine(a, b, coarseOffsetMs)
        return if (fine != null && fine.second >= MIN_FINE_CONFIDENCE) {
            Result.Aligned(fine.first, bestScore, margin, fine = true)
        } else {
            Result.Aligned(coarseOffsetMs, bestScore, margin, fine = false)
        }
    }

    /** The waveform pass: (offsetMs, correlation), or null without overlap. */
    private fun refine(a: Grid, b: Grid, aroundMs: Double): Pair<Double, Double>? {
        val sampleMs = 1000.0 / GRID_HZ
        val startDiffMs = (b.startUs - a.startUs) / 1000.0
        val centre = (startDiffMs - aroundMs) / sampleMs
        val radius = (FINE_RADIUS_MS / sampleMs).toInt()
        val low = floor(centre).toInt() - radius
        val high = floor(centre).toInt() + radius + 1
        val kStart = max(0, -low)
        val kEnd = min(b.samples.size, a.samples.size - high)
        if (kEnd - kStart < GRID_HZ) return null
        val scores = DoubleArray(high - low + 1)
        for (s in low..high) scores[s - low] = ncc(b.samples, a.samples, s, kStart, kEnd) ?: Double.NaN
        var best = -1
        for (i in scores.indices) if (!scores[i].isNaN() && (best < 0 || scores[i] > scores[best])) best = i
        if (best < 0) return null
        // A peak on the edge of the window is not a peak.
        if (best == 0 || best == scores.lastIndex) return null
        val shift = low + best + parabolic(scores, best)
        return (startDiffMs - shift * sampleMs) to scores[best]
    }

    /**
     * Normalised correlation of x[kStart until kEnd] against y shifted so
     * that x[k] meets y[k + shift]. The caller keeps every index in range.
     */
    private fun ncc(x: FloatArray, y: FloatArray, shift: Int, kStart: Int, kEnd: Int): Double? {
        val n = kEnd - kStart
        if (n <= 1) return null
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (k in kStart until kEnd) {
            val xv = x[k].toDouble()
            val yv = y[k + shift].toDouble()
            sx += xv; sy += yv
            sxx += xv * xv; syy += yv * yv
            sxy += xv * yv
        }
        val cov = sxy - sx * sy / n
        val vx = sxx - sx * sx / n
        val vy = syy - sy * sy / n
        val den = sqrt(vx * vy)
        return if (den <= 1e-12) null else cov / den
    }

    private fun isLocalPeak(s: DoubleArray, i: Int): Boolean {
        val left = if (i > 0) s[i - 1] else Double.NaN
        val right = if (i < s.lastIndex) s[i + 1] else Double.NaN
        return (left.isNaN() || s[i] >= left) && (right.isNaN() || s[i] >= right)
    }

    /** Vertex of the parabola through the peak and its neighbours, in samples from [i]. */
    private fun parabolic(s: DoubleArray, i: Int): Double {
        if (i <= 0 || i >= s.lastIndex) return 0.0
        val l = s[i - 1]
        val c = s[i]
        val r = s[i + 1]
        if (l.isNaN() || r.isNaN()) return 0.0
        val den = l - 2 * c + r
        if (abs(den) < 1e-12) return 0.0
        return (0.5 * (l - r) / den).coerceIn(-0.5, 0.5)
    }

    private fun envelope(x: FloatArray): FloatArray {
        val frames = (x.size - ENV_WIN) / ENV_HOP
        if (frames <= 0) return FloatArray(0)
        val env = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0.0
            val start = f * ENV_HOP
            for (j in start until start + ENV_WIN) sum += x[j] * x[j]
            env[f] = sqrt(sum / ENV_WIN).toFloat()
        }
        return env
    }

    /**
     * Lays the newest contiguous run of [chunks] on the [GRID_HZ] grid by
     * linear interpolation, so two taps at different native rates (44.1 and
     * 48 kHz, say) are compared sample for sample in real time.
     */
    fun toGrid(chunks: List<PcmTap.Chunk>): Grid? {
        if (chunks.isEmpty()) return null
        val startUs = chunks.first().startUs.toDouble()
        val endUs = chunks.last().endUs.toDouble()
        val count = ((endUs - startUs) / 1_000_000.0 * GRID_HZ).toInt()
        if (count <= ENV_WIN * 4) return null
        val out = FloatArray(count)
        var ci = 0
        for (i in 0 until count) {
            val tUs = startUs + i * 1_000_000.0 / GRID_HZ
            while (ci < chunks.lastIndex && tUs >= chunks[ci + 1].startUs) ci++
            val c = chunks[ci]
            val pos = (tUs - c.startUs) / 1_000_000.0 * c.rateHz
            val j = floor(pos).toInt()
            out[i] = when {
                j < 0 -> c.samples.firstOrNull() ?: 0f
                j >= c.samples.lastIndex -> {
                    // Between this chunk's last sample and the next one's first.
                    val next = chunks.getOrNull(ci + 1)?.samples?.firstOrNull()
                    val last = c.samples.lastOrNull() ?: 0f
                    if (next == null || j > c.samples.lastIndex) last else last + (next - last) * (pos - j).toFloat()
                }
                else -> c.samples[j] + (c.samples[j + 1] - c.samples[j]) * (pos - j).toFloat()
            }
        }
        return Grid(startUs, out)
    }

    /** Rounded for logs and for the players, which take whole milliseconds. */
    fun Result.Aligned.offsetWholeMs(): Long = offsetMs.roundToLong()
}
