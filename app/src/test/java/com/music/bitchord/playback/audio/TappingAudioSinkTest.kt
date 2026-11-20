package com.music.bitchord.playback.audio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Moving a silent standby's content by exact sample counts, and what the tap records on the way. */
class TappingAudioSinkTest {

    private class Received(val timeUs: Long, val firstSample: Short, val frames: Int)

    /** Takes every buffer whole and remembers what it was given. */
    private class RecordingSink : AudioSink {
        val received = ArrayList<Received>()
        var discontinuities = 0
        var position = 0L

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            received += Received(presentationTimeUs, view.getShort(view.position()), buffer.remaining() / 4)
            buffer.position(buffer.limit())
            return true
        }

        override fun handleDiscontinuity() { discontinuities++ }
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = position
        override fun setListener(listener: AudioSink.Listener) {}
        override fun supportsFormat(format: Format): Boolean = true
        override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {}
        override fun play() {}
        override fun playToEndOfStream() {}
        override fun isEnded(): Boolean = false
        override fun hasPendingData(): Boolean = false
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
        override fun getSkipSilenceEnabled(): Boolean = false
        override fun setAudioAttributes(audioAttributes: AudioAttributes) {}
        override fun getAudioAttributes(): AudioAttributes? = null
        override fun setAudioSessionId(audioSessionId: Int) {}
        override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
        override fun getAudioTrackBufferSizeUs(): Long = 0L
        override fun enableTunnelingV21() {}
        override fun disableTunneling() {}
        override fun setVolume(volume: Float) {}
        override fun pause() {}
        override fun flush() {}
        override fun reset() {}
        override fun release() {}
    }

    private val rate = 48_000
    private val offsetUs = 1_000_000_000_000L

    private fun setUp(): Triple<TappingAudioSink, RecordingSink, PcmTap> {
        val delegate = RecordingSink()
        val tap = PcmTap()
        val sink = TappingAudioSink(delegate, tap)
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(rate)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
        sink.setOutputStreamOffsetUs(offsetUs)
        return Triple(sink, delegate, tap)
    }

    /**
     * Track time [startFrame] onwards, [frames] long, each frame's sample
     * value its own frame index (mod 32768) so what arrives says where it is from.
     */
    private fun buffer(startFrame: Int, frames: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = ((startFrame + i) % 32_768).toShort()
            b.putShort(v); b.putShort(v)
        }
        b.flip()
        return b
    }

    private fun timeOf(frame: Int) = offsetUs + frame * 1_000_000L / rate

    /** Feeds [count] buffers of [frames] from [startFrame], as a renderer would. */
    private fun feed(sink: TappingAudioSink, startFrame: Int, count: Int, frames: Int = 960) {
        for (k in 0 until count) {
            val start = startFrame + k * frames
            val b = buffer(start, frames)
            while (!sink.handleBuffer(b, timeOf(start), 1)) Unit
        }
    }

    @Test
    fun `passes audio through untouched and records it in track time`() {
        val (sink, delegate, tap) = setUp()
        feed(sink, startFrame = 48_000, count = 10)
        assertEquals(10, delegate.received.size)
        assertEquals(timeOf(48_000), delegate.received[0].timeUs)
        assertEquals(0, delegate.discontinuities)
        val recorded = tap.snapshot()
        assertEquals(1_000_000L, recorded.first().startUs)
        assertEquals(1_200_000L, recorded.last().endUs)
    }

    @Test
    fun `a small skip drops exactly that many frames and re-anchors the clock`() {
        val (sink, delegate, tap) = setUp()
        feed(sink, startFrame = 0, count = 5)
        tap.requestShift(10_000L) // 10 ms = 480 frames
        feed(sink, startFrame = 4_800, count = 3)
        val first = delegate.received[5]
        // The rest of the buffer, starting 480 frames in, at its own time.
        assertEquals((4_800 + 480).toShort(), first.firstSample)
        assertEquals(timeOf(4_800 + 480), first.timeUs)
        assertEquals(960 - 480, first.frames)
        assertEquals(1, delegate.discontinuities)
        assertFalse(tap.isMoving())
    }

    @Test
    fun `a long skip is taken in slices with audio passed between them`() {
        val (sink, delegate, tap) = setUp()
        feed(sink, startFrame = 0, count = 2)
        tap.requestShift(1_000_000L) // a whole second
        // Enough input to cover the second and what is passed between slices.
        feed(sink, startFrame = 1_920, count = 120)
        val passed = delegate.received.drop(2)
        // Dropped content shows up as jumps in what arrives; the jumps add up
        // to exactly the second asked for.
        var dropped = 0L
        var expected = timeOf(1_920)
        for (r in passed) {
            dropped += r.timeUs - expected
            expected = r.timeUs + r.frames * 1_000_000L / rate
        }
        assertEquals(1_000_000.0, dropped.toDouble(), 5.0)
        assertTrue("expected several slices, got ${delegate.discontinuities}", delegate.discontinuities >= 3)
        assertFalse(tap.isMoving())
    }

    @Test
    fun `holding back writes that much silence ahead of the audio`() {
        val (sink, delegate, tap) = setUp()
        feed(sink, startFrame = 0, count = 3)
        tap.requestShift(-5_000L) // 5 ms = 240 frames
        feed(sink, startFrame = 2_880, count = 2)
        val silence = delegate.received[3]
        val next = delegate.received[4]
        assertEquals(240, silence.frames)
        assertEquals(0.toShort(), silence.firstSample)
        assertEquals(timeOf(2_880) - 5_000L, silence.timeUs)
        assertEquals(timeOf(2_880), next.timeUs)
        assertEquals(2_880.toShort(), next.firstSample)
        assertEquals(1, delegate.discontinuities)
    }

    @Test
    fun `a resync request only re-anchors the clock`() {
        val (sink, delegate, tap) = setUp()
        feed(sink, startFrame = 0, count = 2)
        tap.requestResync()
        feed(sink, startFrame = 1_920, count = 2)
        assertEquals(1, delegate.discontinuities)
        assertEquals(4, delegate.received.size)
        assertEquals(timeOf(1_920), delegate.received[2].timeUs)
    }

    @Test
    fun `positions the renderer reads are kept in track time`() {
        val (sink, delegate, tap) = setUp()
        feed(sink, startFrame = 0, count = 1)
        val since = System.nanoTime()
        delegate.position = offsetUs + 2_500_000L
        repeat(3) { sink.getCurrentPositionUs(false) }
        val at = tap.playheadAtMs(System.nanoTime(), since, 1f)!!
        assertEquals(2_500.0, at, 5.0)
    }
}
