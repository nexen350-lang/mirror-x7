package com.scrcpy.remote.session

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.scrcpy.remote.scrcpy.PacketSource
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import com.scrcpy.remote.scrcpy.StreamPacket
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Decode and playback are independent. A bounded PCM queue catches up after stalls without
 * losing compressed packets or delaying video/touch to match an accumulated audio backlog.
 */
class AudioPlayer(
    reader: PacketSource,
    private val onError: (Exception) -> Unit,
    private val onPlaying: () -> Unit,
    private val clock: AudioPresentationClock = AudioPresentationClock(),
    private val bufferMillis: Int = 0,
) {
    private val active = AtomicBoolean(true)
    private val muted = AtomicBoolean(false)
    private val callbackLock = Any()
    private val mime = ScrcpyCodecs.audioMimeType(reader.codecId)
    private var codec: MediaCodec? = null
    private var codecBlockFrames = 0
    @Volatile private var playback: PcmPlayback? = null
    private var lastDropLogNanos = 0L
    private var pcm = ByteArray(8192)
    private val info = MediaCodec.BufferInfo()
    private val pump = MediaPacketPump(
        reader, { packet, _ -> consume(packet) }, ::drain, ::release, ::reportFailure, "scrcpy-audio")

    /**
     * A recording asked for by the main thread, picked up by the audio worker.
     *
     * Handed over rather than set directly for the same reason as the video decoder's: the
     * configuration a recording must be primed with is worker state, and the packet that carried
     * it has usually gone past long before the user presses Record.
     */
    private val requestedRecorder = AtomicReference<SoundRecording?>(null)
    private var recorder: SoundRecording? = null
    private var config: ByteArray? = null

    /** AAC is the only sound Android's MP4 writer takes, so nothing else is ever offered. */
    private val recordable = mime == MediaFormat.MIMETYPE_AUDIO_AAC

    fun setMuted(value: Boolean) {
        muted.set(value)
        clock.setAudible(!value)
    }

    /** Copies the encoded sound to [value] as it arrives, or stops when null. */
    internal fun setRecorder(value: SoundRecording?) = requestedRecorder.set(value)
    fun resetTimeline() { playback?.resetTimeline() }
    fun start() = pump.start()
    fun stop() {
        synchronized(callbackLock) { active.set(false) }
        playback?.close()
        pump.close()
    }
    fun awaitStopped(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        val pumpStopped = pump.awaitStopped(timeoutMillis)
        val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
        return (playback?.awaitStopped(remaining) ?: true) && pumpStopped
    }

    internal fun playbackStats(): PcmPlaybackStats? = playback?.stats()

    private fun reportFailure(error: Exception): Unit = synchronized(callbackLock) {
        if (active.getAndSet(false)) {
            // Before the session hears of it: a recording that was to have sound ends here,
            // keeping what it has, rather than going on silent.
            requestedRecorder.get()?.onAudioStopped()
            playback?.close()
            pump.close()
            onError(error)
        }
    }

    private fun reportPlaying(): Unit = synchronized(callbackLock) {
        if (active.get()) onPlaying()
    }

    /** Picks up a recorder the main thread asked for, and primes it with the configuration in force. */
    private fun updateRecorder() {
        val requested = requestedRecorder.get()
        if (requested === recorder) return
        recorder = requested
        if (requested != null && recordable) config?.let { requested.onAudioFormat(it) }
    }

    private fun consume(packet: StreamPacket): Boolean {
        updateRecorder()
        if (packet !is StreamPacket.Media) throw IOException("Unexpected audio session packet")
        if (mime == null) {
            if (playback == null) createPlayback(48_000, 2, AudioFormat.ENCODING_PCM_16BIT)
            writePcm(packet.data, packet.length, packet.pts)
            return true
        }
        if (packet.isConfig) {
            val payload = packet.copyOfPayload()
            createCodec(payload)
            config = payload
            if (recordable) recorder?.onAudioFormat(payload)
            return true
        }
        val decoder = codec ?: throw IOException("Audio frame arrived before codec configuration")
        val index = decoder.dequeueInputBuffer(0)
        if (index < 0) return false
        val input = checkNotNull(decoder.getInputBuffer(index)) { "Missing audio input buffer" }
        check(packet.length <= input.capacity()) { "Encoded audio packet exceeds decoder capacity" }
        input.clear()
        input.put(packet.data, 0, packet.length)
        decoder.queueInputBuffer(index, 0, packet.length, packet.pts, 0)
        // Recorded once, when the packet is taken: the pump offers the same packet again while
        // the decoder has no input buffer free. Taken from the stream itself, before playback
        // drops anything to catch up, so the recording is every packet the device sent.
        if (recordable) recorder?.onAudioSample(packet.data, packet.length, packet.pts)
        return true
    }

    private fun drain(): Boolean {
        val decoder = codec ?: return false
        var produced = false
        while (active.get()) {
            when (val index = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!active.get()) return produced
                    val format = decoder.outputFormat
                    val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else AudioFormat.ENCODING_PCM_16BIT
                    createPlayback(
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), encoding,
                    )
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                MediaCodec.INFO_TRY_AGAIN_LATER -> return produced
                else -> {
                    check(index >= 0) { "Unexpected audio decoder output result $index" }
                    produced = true
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val output = checkNotNull(decoder.getOutputBuffer(index))
                            if (pcm.size < info.size) pcm = ByteArray(info.size)
                            output.position(info.offset)
                            output.get(pcm, 0, info.size)
                            writePcm(pcm, info.size, info.presentationTimeUs)
                        }
                    } finally {
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
        return produced
    }

    private fun writePcm(data: ByteArray, length: Int, ptsUs: Long) {
        val output = playback ?: throw IOException("Audio output format has not been received")
        val previousDropped = output.stats().droppedFrames
        if (!output.offer(data, length, ptsUs) && active.get()) throw IOException("Audio playback stopped accepting PCM")
        val stats = output.stats()
        val now = System.nanoTime()
        if (stats.droppedFrames > previousDropped && now - lastDropLogNanos > 1_000_000_000L) {
            lastDropLogNanos = now
            Log.i("AudioPlayer", "Audio caught up after backlog: $stats")
        }
    }

    private fun createCodec(config: ByteArray) {
        releaseCodec()
        val codecConfig =
            if (mime == MediaFormat.MIMETYPE_AUDIO_FLAC) ScrcpyCodecs.flacCodecConfig(config) else config
        val blockFrames = if (mime == MediaFormat.MIMETYPE_AUDIO_FLAC) flacBlockFrames(codecConfig) else 0
        val format = MediaFormat.createAudioFormat(checkNotNull(mime), 48_000, 2).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(codecConfig))
            if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS) {
                require(config.size >= 19 &&
                    String(config, 0, 8, Charsets.US_ASCII) == "OpusHead") { "Invalid Opus header" }
                val preSkip = (config[10].toInt() and 255) or ((config[11].toInt() and 255) shl 8)
                setByteBuffer("csd-1", longBuffer(preSkip * 1_000_000_000L / 48_000))
                setByteBuffer("csd-2", longBuffer(80_000_000L))
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val created = MediaCodec.createDecoderByType(checkNotNull(mime))
        try {
            created.configure(format, null, null, 0)
            created.start()
            codecBlockFrames = blockFrames
            codec = created
        } catch (e: Exception) {
            created.release()
            throw e
        }
    }

    private fun longBuffer(value: Long): ByteBuffer =
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }

    private fun flacBlockFrames(config: ByteArray): Int {
        // The normalized CSD starts with fLaC and a four-byte metadata header, then STREAMINFO.
        require(config.size >= 42 && (config[4].toInt() and 0x7f) == 0) { "Invalid FLAC STREAMINFO" }
        val length = ((config[5].toInt() and 255) shl 16) or
            ((config[6].toInt() and 255) shl 8) or (config[7].toInt() and 255)
        require(length >= 34 && length <= config.size - 8) { "Invalid FLAC STREAMINFO length" }
        val minimum = ((config[8].toInt() and 255) shl 8) or (config[9].toInt() and 255)
        val maximum = ((config[10].toInt() and 255) shl 8) or (config[11].toInt() and 255)
        require(minimum >= 16 && maximum >= minimum) { "Invalid FLAC block size" }
        return maximum
    }

    private fun createPlayback(sampleRate: Int, channels: Int, encoding: Int) {
        if (!active.get()) return
        require(sampleRate > 0 && channels in 1..2) { "Unsupported audio format $sampleRate Hz / $channels channels" }
        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            "Unsupported decoded PCM encoding $encoding"
        }
        playback?.let {
            it.close()
            if (!it.awaitStopped(1_000)) throw IOException("Previous audio output did not stop")
        }
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        require(bufferMillis == 0 || bufferMillis in 20..250) { "Unsupported audio buffer: $bufferMillis ms" }
        if (!active.get()) return
        clock.reset()
        playback = PcmPlayback(
            sampleRate, channels * bytesPerSample,
            bufferMillis.takeIf { it != 0 } ?: if (mime == MediaFormat.MIMETYPE_AUDIO_FLAC) 120 else 50,
            outputFactory = { AudioTrackOutput(sampleRate, channels, encoding) },
            muted = muted::get,
            onPlaying = ::reportPlaying,
            onError = ::reportFailure,
            clock = clock,
            transition = PcmTransition(sampleRate, channels, encoding == AudioFormat.ENCODING_PCM_FLOAT),
            codecBlockFrames = codecBlockFrames,
        ).also { it.start() }
    }

    private fun releaseCodec() {
        val decoder = codec ?: return
        codec = null
        try { decoder.stop() }
        catch (e: IllegalStateException) { Log.w("AudioPlayer", "Audio decoder already stopped", e) }
        finally { decoder.release() }
    }

    private fun release() {
        try { releaseCodec() } finally { playback?.close() }
    }
}
