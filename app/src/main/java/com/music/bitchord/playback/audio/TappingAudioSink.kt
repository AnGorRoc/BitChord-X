package com.music.bitchord.playback.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hands each decoded buffer to [tap] on its way into [delegate], and can move
 * the player's content by an exact number of samples on request.
 *
 * Outermost, so it sees the decoder's output in the renderer's own timestamps
 * whichever path the delegate then sends it down (the DSP chain, bit-perfect).
 * The renderer offers the same buffer again until the sink has taken all of
 * it, so a buffer is looked at the first time it is offered only.
 *
 * **Moving the content** ([PcmTap.requestShift]) is how a silent standby is put
 * on the exact sample of the playing copy for a quality swap. Forward, the
 * frames are dropped before they reach the output; back, silence is written
 * ahead of them. Either way the output keeps running — no seek, so no new
 * AudioTrack, so nothing for the playing track to stutter over — and the
 * delegate is told of the discontinuity so the sink re-anchors its clock on
 * the next buffer's timestamp: the position the player reports afterwards is
 * the content's own, not off by what was moved.
 *
 * The positions the renderer reads from the sink are noted in [tap] too, with
 * the time they were read, for [PcmTap.playheadAtMs] — and read here as well
 * whenever the renderer has stopped asking.
 */
@OptIn(UnstableApi::class)
internal class TappingAudioSink(
    delegate: AudioSink,
    private val tap: PcmTap,
) : ForwardingAudioSink(delegate) {

    private var lastPositionNanos = 0L
    private var lastBuffer: ByteBuffer? = null
    private var lastTimeUs = C.TIME_UNSET

    /** The timestamp the current buffer is passed on with, once moved. */
    private var passTimeUs = C.TIME_UNSET

    private var dropFrames = 0L
    private var sliceLeft = 0L
    private var passLeft = 0L
    private var resyncPending = false

    /** A move has been carried out and the audio after it not yet passed on. */
    private var landPending = false
    private var silence: ByteBuffer? = null
    private var silenceTimeUs = C.TIME_UNSET

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        tap.configure(format.pcmEncoding, format.channelCount, format.sampleRate)
        forget()
        super.configure(audioSinkConfig)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        tap.streamOffset(outputStreamOffsetUs)
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        // The renderer only asks for the position while the player's clock is
        // the audio's; a silent standby whose file is read to the end can run
        // on the player's own clock for seconds at a time and never ask. It is
        // asked here instead, on the same thread, between buffers.
        if (System.nanoTime() - lastPositionNanos > SELF_POSITION_GAP_NANOS) getCurrentPositionUs(false)
        if (buffer !== lastBuffer || presentationTimeUs != lastTimeUs) {
            lastBuffer = buffer
            lastTimeUs = presentationTimeUs
            passTimeUs = presentationTimeUs
            if (!takeShift(buffer, presentationTimeUs)) {
                // The whole buffer was dropped.
                tap.moving = true
                return true
            }
            // The first audio to follow a finished move.
            if (landPending && dropFrames == 0L) {
                landPending = false
                tap.shiftLandsAtUs = passTimeUs - tap.offsetUs
            }
            try {
                tap.write(buffer, passTimeUs)
            } catch (oom: OutOfMemoryError) {
                tap.clear()
            }
        }
        silence?.let { pending ->
            val taken = super.handleBuffer(pending, silenceTimeUs, 0)
            if (!taken || pending.hasRemaining()) return false
            silence = null
        }
        tap.moving = dropFrames > 0L
        return super.handleBuffer(buffer, passTimeUs, encodedAccessUnitCount)
    }

    /**
     * Carries out any shift [tap] has been asked for, on the buffer just
     * offered. False when that buffer is dropped whole.
     */
    private fun takeShift(buffer: ByteBuffer, timeUs: Long): Boolean {
        val bytesPerSample = PcmTap.bytesPerSample(tap.encoding)
        val frameBytes = bytesPerSample * tap.channels
        val rate = tap.sampleRate
        val requested = tap.takeShift()
        if (frameBytes <= 0 || rate <= 0 || timeUs == C.TIME_UNSET) {
            if (tap.takeResync()) super.handleDiscontinuity()
            return true
        }
        if (requested > 0) {
            dropFrames += requested * rate / 1_000_000L
            landPending = true
        } else if (requested < 0 && tap.encoding != C.ENCODING_PCM_8BIT) {
            val frames = (-requested * rate / 1_000_000L).toInt()
            if (frames > 0) {
                val boundedFrames = minOf(frames, rate * 2)
                val bytes = try {
                    ByteBuffer.allocateDirect(boundedFrames * frameBytes).order(ByteOrder.nativeOrder())
                } catch (oom: OutOfMemoryError) {
                    null
                }
                if (bytes != null) {
                    silence = bytes // zeroed: silence for every signed encoding
                    silenceTimeUs = timeUs - boundedFrames * 1_000_000L / rate
                    landPending = true
                    super.handleDiscontinuity()
                    tap.moving = true
                }
            }
        }
        // A skip is taken a slice at a time with a little audio passed between
        // slices. Dropped whole, seconds of content go to the decoder as fast
        // as it can run while the output plays out what it had queued; on a
        // slow decoder (AAC on a modest phone, hi-res FLAC) that queue runs
        // dry first, and an output that underran reports its playhead with a
        // stall correction that was seen tens of milliseconds wrong.
        if (dropFrames > 0L && sliceLeft == 0L && passLeft <= 0L) {
            sliceLeft = minOf(dropFrames, rate * DROP_SLICE_MS / 1000L)
        }
        if (sliceLeft > 0L) {
            val frames = (buffer.remaining() / frameBytes).toLong()
            if (frames <= sliceLeft) {
                sliceLeft -= frames
                dropFrames -= frames
                buffer.position(buffer.limit())
                resyncPending = true
                if (sliceLeft == 0L && dropFrames > 0L) passLeft = rate * PASS_BETWEEN_SLICES_MS / 1000L
                return false
            }
            buffer.position(buffer.position() + (sliceLeft * frameBytes).toInt())
            passTimeUs = timeUs + sliceLeft * 1_000_000L / rate
            dropFrames -= sliceLeft
            sliceLeft = 0L
            resyncPending = true
            passLeft = if (dropFrames > 0L) rate * PASS_BETWEEN_SLICES_MS / 1000L else 0L
        }
        if (passLeft > 0L) passLeft -= buffer.remaining() / frameBytes
        if (tap.takeResync()) {
            resyncPending = true
            // Re-anchoring lands where it is written, like a move.
            landPending = true
        }
        if (resyncPending) {
            resyncPending = false
            super.handleDiscontinuity()
        }
        return true
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (position != AudioSink.CURRENT_POSITION_NOT_SET) {
            lastPositionNanos = System.nanoTime()
            tap.notePosition(lastPositionNanos, position - tap.offsetUs)
        }
        return position
    }

    override fun flush() {
        forget()
        tap.clear()
        tap.clearPositions()
        super.flush()
    }

    override fun reset() {
        forget()
        tap.clear()
        tap.clearPositions()
        super.reset()
    }

    private companion object {
        const val DROP_SLICE_MS = 250L
        const val PASS_BETWEEN_SLICES_MS = 40L
        const val SELF_POSITION_GAP_NANOS = 5_000_000L
    }

    private fun forget() {
        lastBuffer = null
        lastTimeUs = C.TIME_UNSET
        passTimeUs = C.TIME_UNSET
        dropFrames = 0L
        sliceLeft = 0L
        passLeft = 0L
        resyncPending = false
        landPending = false
        silence = null
        tap.takeShift()
        tap.moving = false
    }
}
