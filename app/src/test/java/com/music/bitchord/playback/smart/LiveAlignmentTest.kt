package com.music.bitchord.playback.smart

import com.music.bitchord.playback.audio.PcmTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Lining two taps up, with signals whose true offset is known. */
class LiveAlignmentTest {

    /**
     * Something music-like at time [t] seconds: irregular note onsets with
     * decaying tones and a little noise, the same function for both
     * "renditions" so a shift between them is exact.
     */
    private class Song(seed: Int) {
        private val rng = Random(seed)
        private val onsets = generateSequence(0.0) { it + 0.08 + rng.nextDouble() * 0.35 }
            .takeWhile { it < 60.0 }.toList().toDoubleArray()
        private val freqs = DoubleArray(onsets.size) { 110.0 * Math.pow(2.0, rng.nextInt(0, 36) / 12.0) }
        private val amps = DoubleArray(onsets.size) { 0.2 + rng.nextDouble() * 0.6 }

        fun at(t: Double): Double {
            var v = 0.0
            // Only the notes that can still be ringing.
            var i = onsets.binarySearch(t).let { if (it < 0) -it - 2 else it }
            var n = 0
            while (i >= 0 && n < 8) {
                val dt = t - onsets[i]
                if (dt >= 0) v += amps[i] * exp(-dt * 6) * sin(2 * PI * freqs[i] * dt)
                i--; n++
            }
            return v * 0.4
        }
    }

    /**
     * A tap's worth of [song] as a player would hold it: native [rateHz]
     * reduced like [PcmTap] does, in buffers of [bufferMs], starting at track
     * time [fromSec] of this rendition, where the rendition plays the song
     * shifted by [shiftSec] (rendition time = song time + shift).
     */
    private fun tap(
        song: Song,
        rateHz: Int,
        fromSec: Double,
        seconds: Double,
        shiftSec: Double,
        noise: Double = 0.0,
        seed: Int = 1,
        bufferMs: Int = 23,
    ): List<PcmTap.Chunk> {
        val rng = Random(seed)
        val factor = maxOf(1, Math.round(rateHz / PcmTap.TARGET_RATE_HZ.toDouble()).toInt())
        val rate = rateHz.toDouble() / factor
        val perBuffer = (rate * bufferMs / 1000).toInt()
        val chunks = ArrayList<PcmTap.Chunk>()
        var i = 0L
        val total = (seconds * rate).toLong()
        while (i < total) {
            val n = minOf(perBuffer.toLong(), total - i).toInt()
            val start = fromSec + i / rate
            val samples = FloatArray(n) { k ->
                val t = start + k / rate - shiftSec
                (song.at(t) + if (noise > 0) rng.nextDouble(-noise, noise) else 0.0).toFloat()
            }
            chunks += PcmTap.Chunk((start * 1_000_000).toLong(), rate, samples)
            i += n
        }
        return chunks
    }

    private fun aligned(result: LiveAlignment.Result): LiveAlignment.Result.Aligned {
        assertTrue("expected a match, got $result", result is LiveAlignment.Result.Aligned)
        return result as LiveAlignment.Result.Aligned
    }

    @Test
    fun `finds the shift between two renditions at different rates to within a fraction of a millisecond`() {
        val song = Song(7)
        // The better copy has 1.2345 s more lead-in: its t = song t + 1.2345.
        val playing = tap(song, 48_000, fromSec = 20.0, seconds = 9.0, shiftSec = 0.0)
        val incoming = tap(song, 44_100, fromSec = 26.0, seconds = 3.0, shiftSec = 1.2345, noise = 0.01)
        val result = aligned(LiveAlignment.measure(playing, incoming, guessMs = 1_000.0, radiusMs = 1_000.0))
        assertTrue(result.fine)
        assertEquals(1234.5, result.offsetMs, 0.3)
    }

    @Test
    fun `a negative shift and no guess at all`() {
        val song = Song(11)
        val playing = tap(song, 44_100, fromSec = 40.0, seconds = 10.0, shiftSec = 0.0)
        val incoming = tap(song, 48_000, fromSec = 44.0, seconds = 3.0, shiftSec = -0.4567, noise = 0.02, seed = 3)
        val result = aligned(LiveAlignment.measure(playing, incoming, guessMs = 0.0, radiusMs = 2_000.0))
        assertEquals(-456.7, result.offsetMs, 0.3)
    }

    @Test
    fun `waits while the playing player has not reached what the incoming one plays`() {
        val song = Song(5)
        // The incoming player is ahead in the song: the playing tap ends
        // before the incoming stretch (shifted back) does.
        val playing = tap(song, 48_000, fromSec = 10.0, seconds = 4.0, shiftSec = 0.0)
        val incoming = tap(song, 48_000, fromSec = 13.0, seconds = 3.0, shiftSec = 0.0)
        assertEquals(
            LiveAlignment.Result.NeedMore,
            LiveAlignment.measure(playing, incoming, guessMs = 0.0, radiusMs = 1_000.0),
        )
    }

    @Test
    fun `too little incoming audio is not matched`() {
        val song = Song(5)
        val playing = tap(song, 48_000, fromSec = 10.0, seconds = 8.0, shiftSec = 0.0)
        val incoming = tap(song, 48_000, fromSec = 14.0, seconds = 1.0, shiftSec = 0.0)
        assertEquals(
            LiveAlignment.Result.NeedMore,
            LiveAlignment.measure(playing, incoming, guessMs = 0.0, radiusMs = 500.0),
        )
    }

    @Test
    fun `a strictly repeating loop is not trusted`() {
        // A bar of 500 ms repeated exactly: every bar matches equally well.
        val bar = Song(9)
        val loop = object {
            fun at(t: Double) = bar.at(((t % 0.5) + 0.5) % 0.5 + 1.0)
        }
        fun loopTap(from: Double, secs: Double, shift: Double): List<PcmTap.Chunk> {
            val rate = 12_000.0
            val n = (secs * rate).toInt()
            return listOf(
                PcmTap.Chunk((from * 1e6).toLong(), rate, FloatArray(n) { k -> loop.at(from + k / rate - shift).toFloat() }),
            )
        }
        val result = LiveAlignment.measure(
            loopTap(10.0, 8.0, 0.0),
            loopTap(13.0, 3.0, 0.1),
            guessMs = 0.0,
            radiusMs = 1_500.0,
        )
        assertTrue("expected Unsure, got $result", result is LiveAlignment.Result.Unsure)
    }

    @Test
    fun `a different master still lines up on its envelope`() {
        val song = Song(21)
        val playing = tap(song, 48_000, fromSec = 30.0, seconds = 9.0, shiftSec = 0.0)
        // Heavily noisy: the waveform no longer agrees, the loudness still does.
        val incoming = tap(song, 44_100, fromSec = 35.0, seconds = 3.5, shiftSec = 0.25, noise = 0.25, seed = 9)
        val result = aligned(LiveAlignment.measure(playing, incoming, guessMs = 0.0, radiusMs = 1_000.0))
        assertEquals(250.0, result.offsetMs, 2.0)
    }
}
