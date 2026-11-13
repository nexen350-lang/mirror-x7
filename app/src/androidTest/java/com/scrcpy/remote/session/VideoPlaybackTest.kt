package com.scrcpy.remote.session

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.scrcpy.PacketSource
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import com.scrcpy.remote.scrcpy.StreamPacket
import com.scrcpy.remote.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class VideoPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun synchronizedBurstStreamDecodesAndReachesTheRealSurface() = exercise(synchronized = true)
    @Test fun lowLatencyBurstStreamStillCatchesUpAndReachesTheRealSurface() = exercise(synchronized = false)

    @Test fun theFirstFrameIsReportedOnceAndOnlyOnceItHasBeenDecoded() {
        // The connection checkup counts video as started from this report, so a decoder that
        // announced its output format and then produced nothing must not have reported it.
        val fixture = encodeFixture(frameCount = 3)
        val source = Source()
        val failure = AtomicReference<Exception>()
        val decodedWhenReported = LinkedBlockingQueue<Long>()
        lateinit var view: SurfaceView
        compose.runOnUiThread {
            view = SurfaceView(compose.activity)
            compose.activity.setContentView(view, ViewGroup.LayoutParams(640, 360))
        }
        await("Surface creation") { view.holder.surface.isValid }
        lateinit var decoder: VideoDecoder
        decoder = VideoDecoder(source, { _, _, _ -> }, failure::set, {},
            onFirstFrame = { decodedWhenReported.put(decoder.frameStats().decodedFrames) })
        try {
            decoder.start(view.holder.surface)
            source.packets.put(StreamPacket.Session(WIDTH, HEIGHT, false))
            source.packets.put(fixture.first)
            fixture.second.forEach(source.packets::put)
            await("First decoded frame") { failure.get() != null || decoder.frameStats().decodedFrames >= 1 }
            assertNull(failure.get())
            val decoded = requireNotNull(decodedWhenReported.poll(3, TimeUnit.SECONDS)) { "The first frame was never reported" }
            assertTrue("Reported with $decoded frames decoded", decoded >= 1)
            SystemClock.sleep(300)
            assertTrue("Reported more than once: $decodedWhenReported", decodedWhenReported.isEmpty())
        } finally {
            decoder.stop()
            assertTrue(decoder.awaitStopped(3_000))
        }
    }

    @Test fun configurationReadAheadRetainsTheKeyframesFullIntakeTime() {
        assumeTrue("The single-frame fixture requires low-latency decoding", Build.VERSION.SDK_INT >= 30)
        val fixture = encodeFixture(frameCount = 1)
        val source = Source()
        val headerEntered = CountDownLatch(1)
        val releaseHeader = CountDownLatch(1)
        val failure = AtomicReference<Exception>()
        lateinit var view: SurfaceView
        compose.runOnUiThread {
            view = SurfaceView(compose.activity)
            compose.activity.setContentView(view, ViewGroup.LayoutParams(640, 360))
        }
        await("Surface creation") { view.holder.surface.isValid }
        val decoder = VideoDecoder(source, { _, _, _ ->
            headerEntered.countDown()
            check(releaseHeader.await(10, TimeUnit.SECONDS))
        }, failure::set, {})
        try {
            decoder.start(view.holder.surface)
            source.packets.put(StreamPacket.Session(WIDTH, HEIGHT, false))
            assertTrue(headerEntered.await(3, TimeUnit.SECONDS))
            source.packets.put(fixture.first)
            source.packets.put(fixture.second.single())
            // Entering the next read means the keyframe's receive stamp and ownership copy
            // have both completed, while its preceding configuration is still queued.
            await("Keyframe read-ahead") { source.readCalls.get() >= 4 }
            val heldAt = System.nanoTime()
            SystemClock.sleep(100)
            val heldMillis = ((System.nanoTime() - heldAt) / 1_000_000).toInt()
            releaseHeader.countDown()
            await("Rendered keyframe timing") { failure.get() != null || decoder.latencyStats().hasSamples }
            assertNull(failure.get())
            val stats = decoder.latencyStats()
            assertEquals(1, stats.samples)
            assertTrue("Configuration discarded a known $heldMillis ms intake wait: $stats",
                stats.intakeP95Millis >= heldMillis)
        } finally {
            releaseHeader.countDown()
            decoder.stop()
            assertTrue(decoder.awaitStopped(3_000))
        }
    }

    private fun exercise(synchronized: Boolean) {
        val fixture = encodeFixture()
        val source = Source()
        lateinit var view: SurfaceView
        compose.runOnUiThread {
            view = SurfaceView(compose.activity)
            compose.activity.setContentView(view, ViewGroup.LayoutParams(640, 360))
        }
        await("Surface creation") { view.holder.surface.isValid }
        val clock = AudioPresentationClock(synchronized)
        val failure = AtomicReference<Exception>()
        val dimensions = AtomicInteger()
        val decoder = VideoDecoder(source, { width, height, _ ->
            assertEquals(WIDTH, width)
            assertEquals(HEIGHT, height)
            dimensions.incrementAndGet()
        }, failure::set, {}, clock)
        val running = AtomicBoolean(true)
        val base = System.nanoTime()
        clock.record(0, 48_000 * 8, ORIGIN_US - 250_000, 48_000)
        val ticker = Thread {
            while (running.get()) {
                val now = System.nanoTime()
                clock.observe(PcmPresentation((now - base) * 48_000 / 1_000_000_000, now, true, 0),
                    PcmPlaybackStats(0, 48_000L * 8, 0), now)
                SystemClock.sleep(5)
            }
        }
        try {
            ticker.start()
            decoder.start(view.holder.surface)
            source.packets.put(StreamPacket.Session(WIDTH, HEIGHT, false))
            source.packets.put(fixture.first)
            for ((index, packet) in fixture.second.withIndex()) {
                if (index % 4 == 0) {
                    val due = base + index * 1_000_000_000L / FPS
                    val wait = (due - System.nanoTime()) / 1_000_000
                    if (wait > 0) SystemClock.sleep(wait)
                }
                source.packets.put(packet)
            }
            await("stream consumption") {
                failure.get() != null ||
                    decoder.frameStats().receivedFrames >= fixture.second.size.toLong()
            }
            await("decode completion") {
                failure.get() != null || decoder.frameStats().decodedFrames >= expectedDecodedFrames()
            }
            var stats = decoder.frameStats()
            await("render callbacks and output release") {
                stats = decoder.frameStats()
                failure.get() != null || (stats.surfaceFrames > 0 &&
                    stats.presentedFrames + stats.skippedFrames == stats.decodedFrames)
            }
            assertNull(failure.get())
            // More tail frames can arrive immediately; assert the sample that passed the wait.
            assertEquals(fixture.second.size.toLong(), stats.receivedFrames)
            assertTrue("Decoder retained too many frames: $stats",
                stats.decodedFrames >= expectedDecodedFrames())
            assertEquals(stats.decodedFrames, stats.presentedFrames + stats.skippedFrames)
            assertTrue(stats.presentedFrames > 0)
            assertTrue("Surface timestamps must use the client's monotonic clock", stats.surfaceFps > 0)
            assertEquals(1, dimensions.get())
            Log.i("VideoPlaybackTest", "sync=$synchronized input=$FPS fps, $stats")
            decoder.setSurface(null)
            source.packets.put(fixture.second.last())
            await("hidden stream consumption") { decoder.frameStats().receivedFrames == fixture.second.size + 1L }
            assertNull(failure.get())
        } finally {
            Log.i("VideoPlaybackTest", "Final sync=$synchronized ${decoder.frameStats()}; error=${failure.get()}")
            running.set(false)
            ticker.join(2_000)
            decoder.stop()
            assertTrue("Video workers did not stop", decoder.awaitStopped(3_000))
        }
    }

    private class Source : PacketSource {
        override val codecId = ScrcpyCodecs.VIDEO_H264
        val packets = LinkedBlockingQueue<StreamPacket>()
        val readCalls = AtomicInteger()
        override fun readPacket(): StreamPacket {
            readCalls.incrementAndGet()
            return packets.take()
        }
    }

    private fun encodeFixture(frameCount: Int = FRAME_COUNT): Pair<StreamPacket.Media, List<StreamPacket.Media>> {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_BIT_RATE, 600_000)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        var started = false
        var config: ByteArray? = null
        val frames = mutableListOf<StreamPacket.Media>()
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            started = true
            val info = MediaCodec.BufferInfo()
            val yuv = ByteArray(WIDTH * HEIGHT * 3 / 2) { 128.toByte() }
            var next = 0
            var inputEnded = false
            var finished = false
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (!finished && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val index = encoder.dequeueInputBuffer(1_000)
                    if (index >= 0) {
                        val input = checkNotNull(encoder.getInputBuffer(index))
                        input.clear()
                        if (next == frameCount) {
                            encoder.queueInputBuffer(index, 0, 0, ORIGIN_US + next * 1_000_000L / FPS,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            yuv.fill((16 + next % 200).toByte(), 0, WIDTH * HEIGHT)
                            input.put(yuv)
                            encoder.queueInputBuffer(index, 0, yuv.size, ORIGIN_US + next * 1_000_000L / FPS, 0)
                            next++
                        }
                    }
                }
                when (val index = encoder.dequeueOutputBuffer(info, 1_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = encoder.outputFormat
                        config = listOfNotNull(output.getByteBuffer("csd-0"), output.getByteBuffer("csd-1"))
                            .flatMap { bytes -> ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) }.toList() }
                            .toByteArray()
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        check(index >= 0)
                        try {
                            if (info.size > 0) {
                                val data = ByteArray(info.size)
                                checkNotNull(encoder.getOutputBuffer(index)).apply { position(info.offset); get(data) }
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config = data
                                else frames += StreamPacket.Media(info.presentationTimeUs, false,
                                    info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, data, data.size)
                            }
                            finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { encoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
            assertTrue("Video fixture encoder did not finish", finished)
            val header = checkNotNull(config)
            assertEquals(frameCount, frames.size)
            return StreamPacket.Media(0, true, false, header, header.size) to frames
        } finally {
            if (started) encoder.stop()
            encoder.release()
        }
    }

    /**
     * Frames the decoder must have emitted once the whole fixture has been fed.
     *
     * `MediaFormat.KEY_LOW_LATENCY` only exists from API 30. Without it a decoder keeps a small
     * reorder window, so the last frames stay inside the codec until end of stream. That is
     * normal decoder behavior rather than frame loss, and the accounting assertions still have to
     * balance exactly. Where the low-latency path does exist — which is where the frame-retention
     * fix matters — every fed frame is still required to come out.
     */
    private fun expectedDecodedFrames(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) FRAME_COUNT.toLong()
        else FRAME_COUNT - MAX_DECODER_OUTPUT_DELAY

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + AWAIT_TIMEOUT_MILLIS
        while (!predicate()) {
            if (SystemClock.elapsedRealtime() >= deadline) fail("Timed out: $description")
            SystemClock.sleep(10)
        }
    }

    private companion object {
        const val WIDTH = 256
        const val HEIGHT = 144
        const val FPS = 60
        const val FRAME_COUNT = 180
        const val ORIGIN_US = 10_000_000L

        /** Output delay tolerated from a decoder that cannot be put into low-latency mode. */
        const val MAX_DECODER_OUTPUT_DELAY = 8L

        /**
         * A liveness guard, not a throughput assertion. The assertions check frame accounting,
         * not decode speed, so this must be generous enough for a software decoder rendering into
         * a software-rasterized surface, where 60 FPS is not achievable.
         */
        const val AWAIT_TIMEOUT_MILLIS = 20_000L
    }
}
