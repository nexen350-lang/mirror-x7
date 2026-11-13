package com.scrcpy.remote.session

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.scrcpy.remote.scrcpy.PacketSource
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import com.scrcpy.remote.scrcpy.StreamPacket
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

data class DecodedVideoFormat(
    val width: Int,
    val height: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int,
    val captureGeneration: Long,
) {
    val displayWidth: Int get() = cropRight - cropLeft + 1
    val displayHeight: Int get() = cropBottom - cropTop + 1
}

/** All MediaCodec operations run on the pump's codec worker, including surface changes. */
class VideoDecoder(
    reader: PacketSource,
    private val onVideoSizeChanged: (Int, Int, Long) -> Unit,
    onError: (Exception) -> Unit,
    private val onDecodedFormat: (DecodedVideoFormat) -> Unit,
    private val clock: AudioPresentationClock = AudioPresentationClock(),
    /** Codec-worker header acknowledgment, not confirmation that a new frame has rendered. */
    private val onCaptureSession: ((Int, Int, Long, Boolean) -> Unit)? = null,
    /** Once, on the codec worker, when this decoder first produces a decoded frame. */
    private val onFirstFrame: () -> Unit = {},
) {
    private data class Target(val surface: Surface?, val revision: Long)

    private var firstFrameReported = false

    private val target = AtomicReference(Target(null, 0))
    private var appliedTarget = target.get()
    private val mimeType = ScrcpyCodecs.videoMimeType(reader.codecId)
    private val synchronizedPlayback = clock.diagnostics().synchronized
    private var codec: MediaCodec? = null
    private val outputInfo = MediaCodec.BufferInfo()
    private var codecConfig: ByteArray? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var captureGeneration = 0L
    private var needsKeyFrame = true
    private val meter = VideoFrameMeter()
    private val latency = FrameLatencyMeter()

    /** Set once a decoder rejects the low-latency request, so it is not retried every restart. */
    private var lowLatencyRefused = false
    @Volatile private var codecEpoch = 0L

    /**
     * A recorder asked for by the main thread, picked up by the codec worker.
     *
     * Handed over rather than set directly because the format it has to be primed with — the size
     * and the codec configuration — is worker state. A recording started part way through a
     * session would otherwise never see the configuration packet that already went past, and
     * would sit waiting for one that only arrives on the next capture change.
     */
    private val requestedRecorder = AtomicReference<SessionRecorder?>(null)
    private var recorder: SessionRecorder? = null

    /** Starts writing the frames this decoder receives to [value], or stops when null. */
    internal fun setRecorder(value: SessionRecorder?) = requestedRecorder.set(value)
    private val observedReader = object : PacketSource {
        override val codecId = reader.codecId
        override fun readPacket(): StreamPacket = reader.readPacket().also {
            if (it is StreamPacket.Media && !it.isConfig) {
                meter.received(it.length)
            }
        }
    }
    private val pump = MediaPacketPump(
        observedReader, ::consume, ::drain, ::releaseCodec, onError, "scrcpy-video",
        maxQueuedPackets = if (synchronizedPlayback) VideoInputPacer.MAX_QUEUED_PACKETS else 4,
    )

    fun setSurface(surface: Surface?) {
        target.updateAndGet { previous ->
            if (previous.surface === surface) previous else Target(surface, previous.revision + 1)
        }
    }

    fun start(surface: Surface? = null) {
        setSurface(surface)
        pump.start()
    }

    fun stop() = pump.close()
    fun awaitStopped(timeoutMillis: Long): Boolean = pump.awaitStopped(timeoutMillis)
    fun frameStats(): VideoFrameStats = meter.snapshot()

    /** How long frames are taking from receipt to reaching the surface. */
    fun latencyStats(): LatencyStats = latency.snapshot(System.nanoTime())

    private fun updateSurface() {
        val next = target.get()
        if (next.revision != appliedTarget.revision) {
            releaseCodec()
            appliedTarget = next
            needsKeyFrame = true
        }
    }

    /** Picks up a recorder the main thread asked for, and primes it with the format in force. */
    private fun updateRecorder() {
        val requested = requestedRecorder.get()
        if (requested === recorder) return
        recorder = requested
        val config = codecConfig
        if (requested != null && videoWidth > 0 && videoHeight > 0) {
            requested.onFormat(mimeType, videoWidth, videoHeight, config)
        }
    }

    private fun consume(packet: StreamPacket, receivedNanos: Long): Boolean {
        updateSurface()
        updateRecorder()
        if (packet is StreamPacket.Session) {
            releaseCodec()
            videoWidth = packet.width
            videoHeight = packet.height
            codecConfig = null
            needsKeyFrame = true
            captureGeneration++
            // A recording cannot follow a change of capture size: one MP4 track keeps the
            // dimensions it was opened with, so the file ends here and says why.
            recorder?.onFormat(mimeType, videoWidth, videoHeight, null)
            onVideoSizeChanged(videoWidth, videoHeight, captureGeneration)
            onCaptureSession?.invoke(videoWidth, videoHeight, captureGeneration, packet.clientResized)
            return true
        }
        packet as StreamPacket.Media
        if (packet.isConfig) {
            codecConfig = packet.copyOfPayload()
            recorder?.onFormat(mimeType, videoWidth, videoHeight, codecConfig)
            releaseCodec()
            needsKeyFrame = true
            return true
        }
        // Recorded exactly once, when the packet is actually taken. The pump retries the same
        // packet every couple of milliseconds while the decoder has no free input buffer, or
        // while synchronised playback is holding video back to match the sound — so recording it
        // before those checks wrote the same frame dozens of times, which bloated the file and
        // left repeated P-frames decoding against the wrong reference.
        val taken = consumeMedia(packet, receivedNanos)
        if (taken) recorder?.onSample(packet.data, packet.length, packet.pts, packet.isKeyFrame)
        return taken
    }

    /** Whether [packet] was consumed; false asks the pump to offer the same one again. */
    private fun consumeMedia(packet: StreamPacket.Media, receivedNanos: Long): Boolean {
        val surface = appliedTarget.surface
        // Continue reading while hidden so the ADB stream does not accumulate stale video. Still
        // counts as taken, so a recording made with the app in the background gets every frame.
        if (surface == null || !surface.isValid) return true
        if (videoWidth == 0 || videoHeight == 0) throw IOException("Video frame before capture metadata")
        if (needsKeyFrame && !packet.isKeyFrame) return true
        val active = codec ?: try {
            createCodec(surface).also { codec = it }
        } catch (e: Exception) {
            // The surface is checked above, but configuring the codec is a native call that can
            // still be running when the view replaces or releases it, and it then throws. That is
            // an ordinary surface transition, not a broken stream: reporting it as a decoder
            // failure stopped video for the rest of the session over a routine swap. The next
            // round picks up whatever target has since been published.
            val current = target.get()
            if (current.revision != appliedTarget.revision || current.surface !== surface ||
                !surface.isValid
            ) return true
            throw e
        }
        if (synchronizedPlayback) {
            val now = System.nanoTime()
            if (!VideoInputPacer.ready(clock.videoPresentation(packet.pts, now), now)) return false
        }
        val index = active.dequeueInputBuffer(0)
        if (index < 0) return false
        val buffer = checkNotNull(active.getInputBuffer(index)) { "Missing decoder input buffer" }
        check(packet.length <= buffer.capacity()) { "Encoded video packet exceeds decoder input capacity" }
        buffer.clear()
        buffer.put(packet.data, 0, packet.length)
        active.queueInputBuffer(index, 0, packet.length, packet.pts, 0)
        latency.arrived(packet.pts, System.nanoTime(), receivedNanos, codecEpoch)
        needsKeyFrame = false
        return true
    }

    private fun drain(): Boolean {
        updateSurface()
        val active = codec ?: return false
        var produced = false
        val batch = VideoOutputBatch(
            presentation = { pts -> clock.videoPresentation(pts, System.nanoTime()) },
            release = { index, presentation ->
                val current = target.get()
                if (current.revision != appliedTarget.revision || current.surface?.isValid != true) {
                    // Counted, because the frame was decoded and then deliberately not shown.
                    // Leaving it out makes decoded frames outnumber presented plus skipped, which
                    // is exactly the balance the frame accounting is there to let anyone check.
                    active.releaseOutputBuffer(index, false)
                    meter.skipped()
                } else {
                    try {
                        when (presentation) {
                            null -> { active.releaseOutputBuffer(index, false); meter.skipped() }
                            VideoPresentation.Immediate -> {
                                // The boolean overload inherits the remote PTS as a surface timestamp.
                                active.releaseOutputBuffer(index, System.nanoTime())
                                meter.presented()
                            }
                            is VideoPresentation.At -> { active.releaseOutputBuffer(index, presentation.nanoTime); meter.presented() }
                        }
                    } catch (e: IllegalStateException) {
                        // The view frees its surface as soon as surfaceDestroyed returns, which
                        // can land between the check above and this release. Pressing Home at
                        // that moment threw here and was treated as a dead codec, ending the
                        // session — including its background audio — over an ordinary surface
                        // change. Not released again: the failed call may already have done it.
                        if (target.get().revision == appliedTarget.revision &&
                            target.get().surface?.isValid == true
                        ) throw e
                        meter.skipped()
                    }
                }
            },
        )
        while (true) {
            when (val index = active.dequeueOutputBuffer(outputInfo, 0)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> reportFormat(active.outputFormat)
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                MediaCodec.INFO_TRY_AGAIN_LATER -> break
                else -> {
                    check(index >= 0) { "Unexpected video decoder output result $index" }
                    produced = true
                    if (outputInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        active.releaseOutputBuffer(index, false)
                    } else {
                        meter.decoded()
                        // Reported from a real frame, not the format change before it: a decoder
                        // can announce its output format and still fail to produce anything.
                        if (!firstFrameReported) {
                            firstFrameReported = true
                            onFirstFrame()
                        }
                        batch.offer(index, outputInfo.presentationTimeUs)
                    }
                }
            }
        }
        batch.finish()
        return produced
    }

    private fun createCodec(surface: Surface): MediaCodec {
        // Asked for first, and again without it if the codec refuses. The key was previously set
        // only where the decoder advertised the feature, but plenty of vendor decoders honour it
        // without advertising, and on those the decoder holds whole frames — worth far more than
        // any buffering this client controls. A decoder that rejects the key throws at configure,
        // which is recoverable as long as the attempt is made on its own codec instance.
        if (Build.VERSION.SDK_INT >= 30 && !lowLatencyRefused) {
            runCatching { return createCodec(surface, lowLatency = true) }
                .onFailure {
                    // A surface swapped mid-configure (PiP, zoom) also lands here: that says
                    // nothing about the decoder, so it is passed on as the surface transition.
                    if (!lowLatencyRefusalApplies(surface.isValid, target.get().surface === surface)) throw it
                    Log.w(TAG, "Decoder would not accept the low-latency request", it)
                    lowLatencyRefused = true
                }
        }
        return createCodec(surface, lowLatency = false)
    }

    private fun createCodec(surface: Surface, lowLatency: Boolean): MediaCodec {
        val created = MediaCodec.createDecoderByType(mimeType)
        try {
            val format = MediaFormat.createVideoFormat(mimeType, videoWidth, videoHeight).apply {
                codecConfig?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (lowLatency && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
            created.configure(format, surface, null, 0)
            val epoch = ++codecEpoch
            created.setOnFrameRenderedListener({ _, presentationTimeUs, nanoTime ->
                if (codecEpoch == epoch) {
                    meter.rendered(nanoTime)
                    // The presentation timestamp was being discarded. It is the only thing that
                    // ties a rendered frame back to the packet it came from, and without it the
                    // app could count frames but never say how long any of them took.
                    latency.rendered(presentationTimeUs, nanoTime, epoch)
                }
            }, Handler(Looper.getMainLooper()))
            created.start()
            return created
        } catch (e: Exception) {
            created.release()
            throw e
        }
    }

    private fun reportFormat(format: MediaFormat) {
        fun integer(key: String, default: Int) = if (format.containsKey(key)) format.getInteger(key) else default
        val width = integer(MediaFormat.KEY_WIDTH, videoWidth)
        val height = integer(MediaFormat.KEY_HEIGHT, videoHeight)
        val decoded = DecodedVideoFormat(
            width, height, integer("crop-left", 0), integer("crop-top", 0),
            integer("crop-right", width - 1), integer("crop-bottom", height - 1),
            captureGeneration,
        )
        require(decoded.cropLeft >= 0 && decoded.cropTop >= 0 &&
            decoded.cropRight < width && decoded.cropBottom < height &&
            decoded.displayWidth > 0 && decoded.displayHeight > 0) { "Invalid decoder crop rectangle" }
        Log.i(TAG, "Decoded format: $decoded; session=${videoWidth}x$videoHeight")
        onDecodedFormat(decoded)
    }

    private fun releaseCodec() {
        codecEpoch++
        // Only submitted frames belong to the retiring codec. Packets still in the pump keep
        // their receipt times, and late callbacks carry their old epoch even when PTS repeat.
        latency.reset()
        val active = codec ?: return
        codec = null
        try {
            active.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Video decoder was already stopped", e)
        } finally {
            active.release()
        }
    }

    private companion object { const val TAG = "VideoDecoder" }
}
