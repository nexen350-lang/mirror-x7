package com.scrcpy.remote.session

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.AudioTimestamp
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer

internal class AudioTrackOutput(sampleRate: Int, channels: Int, encoding: Int) : PcmOutput {
    private val track: AudioTrack
    private val timestamp = AudioTimestamp()
    private val headCounter = FramePositionCounter()
    private val timestampCounter = FramePositionCounter()
    private var resetNanos = System.nanoTime()

    /** The current output, kept up to date by [routingListener] rather than asked for per sample. */
    @Volatile
    private var route: AudioDeviceInfo? = null

    private val routingListener = AudioRouting.OnRoutingChangedListener { routing ->
        route = routing.routedDevice
    }

    internal val playbackHeadFrames: Long get() = track.playbackHeadPosition.toLong() and 0xffffffffL

    init {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, mask, encoding)
        check(minimum > 0) { "AudioTrack cannot support the decoded format ($minimum)" }
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val created = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(encoding).setSampleRate(sampleRate)
                .setChannelMask(mask).build())
            .setBufferSizeInBytes(maxOf(minimum, sampleRate * channels * bytesPerSample / 50))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        var initialized = false
        try {
            check(created.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
            track = created
            initialized = true
        } finally {
            if (!initialized) created.release()
        }
        // The route is asked for on every presentation, which is about 200 times a second, and
        // each answer copies the device's whole list of audio ports under a process-wide lock —
        // the largest steady source of allocation in the app, for a value that changes only when
        // headphones or a speaker do. Android reports those changes, so it is read when they
        // happen instead.
        track.addOnRoutingChangedListener(routingListener, null)
        route = track.routedDevice
    }

    override fun play() = track.play()

    override fun setMuted(muted: Boolean) {
        if (track.setVolume(if (muted) 0f else 1f) != AudioTrack.SUCCESS) {
            throw IOException("Cannot set playback volume")
        }
    }

    override fun write(data: ByteArray, offset: Int, length: Int): Int =
        track.write(ByteBuffer.wrap(data, offset, length), length, AudioTrack.WRITE_BLOCKING)

    override fun presentation(): PcmPresentation {
        val now = System.nanoTime()
        val head = headCounter.update(playbackHeadFrames)
        val measured = track.getTimestamp(timestamp) && timestamp.nanoTime >= resetNanos &&
            now - timestamp.nanoTime in -500_000_000L..500_000_000L
        return PcmPresentation(
            if (measured) timestampCounter.update(timestamp.framePosition) else head,
            if (measured) timestamp.nanoTime else now,
            measured, track.underrunCount, route?.type, route?.id,
        )
    }

    override fun reset() {
        track.pause()
        track.flush()
        headCounter.reset()
        timestampCounter.reset()
        resetNanos = System.nanoTime()
        track.play()
    }

    override fun close() {
        runCatching { track.removeOnRoutingChangedListener(routingListener) }
        try {
            track.pause()
            track.flush()
        } catch (e: IllegalStateException) {
            Log.w("AudioPlayer", "Audio track already stopped", e)
        } finally {
            track.release()
        }
    }

    /**
     * Makes a blocking write return without waiting for the sink to drain.
     *
     * `AudioTrack.write` in blocking mode is a native call that `Thread.interrupt` cannot break
     * out of, so a worker inside one when the output stalls would never reach its own cleanup.
     * Pausing and flushing from the closing thread is what releases it.
     */
    override fun unblockWriter() {
        runCatching {
            track.pause()
            track.flush()
        }.onFailure { Log.w("AudioPlayer", "Could not unblock the audio writer", it) }
    }
}
