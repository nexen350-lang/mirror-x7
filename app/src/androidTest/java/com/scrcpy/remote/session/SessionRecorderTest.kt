package com.scrcpy.remote.session

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.scrcpy.PacketSource
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import com.scrcpy.remote.scrcpy.StreamPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Records an encoded stream and reads the result back with a real extractor.
 *
 * The frames are copied into the container without being re-encoded, so the only way to know the
 * file is usable is to open it again and check the track is there and the samples come back.
 */
@RunWith(AndroidJUnit4::class)
class SessionRecorderTest {

    private val cache: File get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    /** Encodes real input; VP9's optional configuration is deliberately omitted by its tests. */
    private fun encodedFrames(
        count: Int = 12,
        mime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    ): Pair<ByteArray, List<Triple<ByteArray, Long, Boolean>>> {
        val width = 176
        val height = 144
        assumeTrue("No local encoder for $mime", MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .any { it.isEncoder && it.supportedTypes.contains(mime) })
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 400_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        var config: ByteArray? = null
        val frames = mutableListOf<Triple<ByteArray, Long, Boolean>>()
        val info = MediaCodec.BufferInfo()
        var fed = 0
        var inputEnded = false
        val deadline = SystemClock.elapsedRealtime() + 10_000
        try {
            while (frames.size < count && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(20_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        if (fed < count + 4) {
                            buffer.put(ByteArray(width * height * 3 / 2) { (it + fed).toByte() })
                            codec.queueInputBuffer(index, 0, buffer.position(), fed * 66_666L, 0)
                            fed++
                        } else {
                            codec.queueInputBuffer(index, 0, 0, fed * 66_666L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 20_000)
                if (out < 0) continue
                val buffer = codec.getOutputBuffer(out)!!
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = bytes
                } else if (info.size > 0) {
                    frames += Triple(bytes, info.presentationTimeUs,
                        info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                }
                codec.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        assertEquals("Encoder did not finish $mime fixture", count, frames.size)
        check(mime == MediaFormat.MIMETYPE_VIDEO_VP9 || config != null) { "Encoder produced no codec configuration" }
        return (config ?: byteArrayOf()) to frames
    }

    @Test fun configFreeVp9RecordingDoesNotWaitForever() = exerciseConfigFreeVp9(lateAttachment = false)

    @Test fun configFreeVp9RecordingCanAttachAfterTheSessionHeader() = exerciseConfigFreeVp9(lateAttachment = true)

    private fun exerciseConfigFreeVp9(lateAttachment: Boolean) {
        val frames = encodedFrames(mime = MediaFormat.MIMETYPE_VIDEO_VP9).second
        assertTrue("The recording fixture must start with a keyframe", frames.first().third)
        val file = File(cache, "recorder-vp9-${System.nanoTime()}.mp4")
        val recorder = SessionRecorder(file)
        val stopped = AtomicInteger()
        recorder.onStopped = { stopped.incrementAndGet() }
        var decoder: VideoDecoder? = null
        try {
            if (lateAttachment) {
                val packets = LinkedBlockingQueue<StreamPacket>()
                val source = object : PacketSource {
                    override val codecId = ScrcpyCodecs.VIDEO_VP9
                    override fun readPacket(): StreamPacket = packets.take()
                }
                val headers = AtomicInteger()
                val initialHeader = CountDownLatch(1)
                val samplesTaken = CountDownLatch(1)
                val failure = AtomicReference<Exception>()
                val video = VideoDecoder(source, { _, _, _ ->
                    if (headers.incrementAndGet() == 1) initialHeader.countDown()
                    else samplesTaken.countDown()
                }, failure::set, {})
                decoder = video
                video.start()
                packets.put(StreamPacket.Session(176, 144, false))
                assertTrue(initialHeader.await(3, TimeUnit.SECONDS))
                video.setRecorder(recorder)
                frames.forEach { (bytes, pts, key) ->
                    packets.put(StreamPacket.Media(pts, false, key, bytes, bytes.size))
                }
                // A same-size header is ordered after every sample on the codec worker.
                packets.put(StreamPacket.Session(176, 144, false))
                assertTrue("Frames were not consumed: ${failure.get()}", samplesTaken.await(3, TimeUnit.SECONDS))
                assertNull(failure.get())
                video.stop()
                assertTrue(video.awaitStopped(3_000))
            } else {
                recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_VP9, 176, 144, null)
                frames.forEach { (bytes, pts, key) -> recorder.onSample(bytes, bytes.size, pts, key) }
            }
            val saved = recorder.finish()
            if (recorder.error == SessionRecorder.UNSUPPORTED_CODEC) {
                assertEquals("An unsupported container must be reported once", 1, stopped.get())
                assertNull(saved)
                assertEquals(0L, recorder.frameCount)
            } else {
                assertNull("Recording failed: ${recorder.error}", recorder.error)
                assertNotNull("Optional CSD must not leave recording silently waiting", saved)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(saved!!.absolutePath)
                    assertEquals(1, extractor.trackCount)
                    assertEquals(MediaFormat.MIMETYPE_VIDEO_VP9,
                        extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
                    extractor.selectTrack(0)
                    val buffer = ByteBuffer.allocate(256 * 1024)
                    var count = 0
                    while (extractor.readSampleData(buffer, 0) >= 0) {
                        count++
                        extractor.advance()
                    }
                    assertEquals(frames.size, count)
                } finally { extractor.release() }
            }
        } finally {
            decoder?.let { it.stop(); assertTrue(it.awaitStopped(3_000)) }
            recorder.discard()
            file.delete()
        }
    }

    @Test fun h264AndH265StillWaitForRequiredCodecConfiguration() {
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            val file = File(cache, "recorder-needs-csd-${System.nanoTime()}.mp4")
            val recorder = SessionRecorder(file)
            try {
                recorder.onFormat(mime, 176, 144, null)
                // The guard must run before a muxer sees this deliberately unconfigured sample.
                recorder.onSample(byteArrayOf(1), 1, 0, true)
                assertEquals("$mime must wait for its parameter sets", 0L, recorder.frameCount)
                assertNull(recorder.error)
                assertNull(recorder.finish())
            } finally { recorder.discard(); file.delete() }
        }
    }

    @Test fun addingConfigurationToAnOpenConfigFreeTrackEndsThatTrack() {
        val frames = encodedFrames(mime = MediaFormat.MIMETYPE_VIDEO_VP9).second
        val file = File(cache, "recorder-vp9-new-csd-${System.nanoTime()}.mp4")
        val recorder = SessionRecorder(file)
        try {
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_VP9, 176, 144, null)
            frames.take(6).forEach { (bytes, pts, key) -> recorder.onSample(bytes, bytes.size, pts, key) }
            if (recorder.error == SessionRecorder.UNSUPPORTED_CODEC) {
                assertNull(recorder.finish())
                return
            }
            assertNull(recorder.error)
            assertTrue(recorder.isRecording)
            val written = recorder.frameCount
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_VP9, 176, 144, null)
            assertTrue("A same-format header still continues the track", recorder.isRecording)
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_VP9, 176, 144, byteArrayOf(1, 1, 0))
            frames.drop(6).forEach { (bytes, pts, key) -> recorder.onSample(bytes, bytes.size, pts, key) }
            assertEquals(SessionRecorder.STREAM_CHANGED, recorder.error)
            assertEquals(written, recorder.frameCount)
            assertNotNull(recorder.finish())
        } finally { recorder.discard(); file.delete() }
    }

    @Test fun aRecordingOpensAgainAsAPlayableTrack() {
        val (config, frames) = encodedFrames()
        val file = File(cache, "recorder-playable-${System.nanoTime()}.mp4")
        val recorder = SessionRecorder(file)
        try {
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 176, 144, config)
            frames.forEach { (bytes, pts, key) -> recorder.onSample(bytes, bytes.size, pts, key) }
            val saved = recorder.finish()

            assertNotNull("Nothing was written", saved)
            assertTrue("The recording is empty", saved!!.length() > 0)
            assertNull("Recording reported a failure: ${recorder.error}", recorder.error)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(saved.absolutePath)
                assertEquals(1, extractor.trackCount)
                val track = extractor.getTrackFormat(0)
                assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, track.getString(MediaFormat.KEY_MIME))
                assertEquals(176, track.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(144, track.getInteger(MediaFormat.KEY_HEIGHT))

                extractor.selectTrack(0)
                val buffer = ByteBuffer.allocate(256 * 1024)
                var read = 0
                var previousTime = -1L
                while (extractor.readSampleData(buffer, 0) >= 0) {
                    // A muxed track must come back in increasing order for a player to seek it.
                    assertTrue("Sample times went backwards", extractor.sampleTime > previousTime)
                    previousTime = extractor.sampleTime
                    read++
                    extractor.advance()
                }
                assertEquals("Every offered frame should be in the file", frames.size, read)
            } finally {
                extractor.release()
            }
        } finally {
            file.delete()
        }
    }

    @Test fun aRecordingWaitsForAKeyFrameBeforeItOpens() {
        // Opening on anything else gives a decoder nothing to start from, so the file would begin
        // as a broken picture.
        val (config, frames) = encodedFrames()
        val file = File(cache, "recorder-keyframe-${System.nanoTime()}.mp4")
        val recorder = SessionRecorder(file)
        try {
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 176, 144, config)
            val nonKey = frames.first { !it.third }
            recorder.onSample(nonKey.first, nonKey.first.size, nonKey.second, false)
            assertEquals(0L, recorder.frameCount)
            assertNull("A file should not exist yet", file.takeIf { it.isFile && it.length() > 0 })

            val key = frames.first { it.third }
            recorder.onSample(key.first, key.first.size, key.second, true)
            assertEquals(1L, recorder.frameCount)
        } finally {
            recorder.discard()
            file.delete()
        }
    }

    @Test fun aCaptureRestartAtTheSameSizeDoesNotEndTheRecording() {
        // Returning from Home, or from a file picker, recreates the surface and asks the device to
        // restart capture, which sends a fresh session packet at the very same size. Treating that
        // like a resize silently ended the recording while the panel still said "Recording", and
        // then blamed the device for changing a size it had not changed.
        val (config, frames) = encodedFrames()
        val file = File(cache, "recorder-restart-${System.nanoTime()}.mp4")
        val recorder = SessionRecorder(file)
        try {
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 176, 144, config)
            frames.take(6).forEach { (bytes, pts, key) -> recorder.onSample(bytes, bytes.size, pts, key) }
            val written = recorder.frameCount
            assertTrue("Expected frames before the restart", written > 0)

            // Exactly what a same-size capture restart delivers.
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 176, 144, null)
            frames.drop(6).forEach { (bytes, pts, key) -> recorder.onSample(bytes, bytes.size, pts, key) }

            assertNull("A restart is not a failure: ${recorder.error}", recorder.error)
            assertTrue("Recording should have continued past the restart",
                recorder.frameCount > written)
            assertNotNull(recorder.finish())
        } finally {
            file.delete()
        }
    }

    @Test fun aRestartWithDifferentParameterSetsEndsTheRecordingRatherThanCorruptIt() {
        // A restart usually re-uses the parameter sets, and carrying on is then exactly right.
        // When the new ones differ, they cannot be carried on with: an MP4 track describes its
        // stream once, in a header written when the track is created, so every frame after such a
        // restart would be decoded against the wrong SPS/PPS. That damage is invisible until
        // playback, where the recording plays and then turns to blocks or stops. Ending the file
        // keeps what was definitely good.
        val (config, frames) = encodedFrames()
        val file = File(cache, "recorder-newsps-${System.nanoTime()}.mp4")
        val recorder = SessionRecorder(file)
        try {
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 176, 144, config)
            frames.take(6).forEach { (bytes, pts, key) -> recorder.onSample(bytes, bytes.size, pts, key) }
            val written = recorder.frameCount
            assertTrue("Expected frames before the restart", written > 0)

            // The session packet, then a configuration that is not the one already in the header.
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 176, 144, null)
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 176, 144, differentParameterSets(config))
            frames.drop(6).forEach { (bytes, pts, key) -> recorder.onSample(bytes, bytes.size, pts, key) }

            assertEquals("Frames described by the new sets must not join the old track",
                written, recorder.frameCount)
            assertEquals(SessionRecorder.STREAM_CHANGED, recorder.error)
            assertNotNull("What was captured beforehand is still worth keeping", recorder.finish())
        } finally {
            file.delete()
        }
    }

    /**
     * The same configuration with one altered SPS byte, standing in for an encoder that comes back
     * from a restart describing its stream differently.
     */
    private fun differentParameterSets(config: ByteArray): ByteArray {
        val changed = config.copyOf()
        // level_idc: the third byte of the SPS payload, which follows the 4-byte start code and
        // the NAL header. Changing it leaves the structure intact and the bytes unequal.
        val index = 4 + 1 + 2
        changed[index] = (changed[index] + 1).toByte()
        return changed
    }

    @Test fun aCaptureSizeChangeEndsTheRecordingAndSaysSo() {
        val (config, frames) = encodedFrames()
        val file = File(cache, "recorder-resize-${System.nanoTime()}.mp4")
        val recorder = SessionRecorder(file)
        try {
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 176, 144, config)
            frames.take(6).forEach { (bytes, pts, key) -> recorder.onSample(bytes, bytes.size, pts, key) }
            val written = recorder.frameCount
            assertTrue("Expected some frames before the change", written > 0)

            // What the decoder does when the device rotates or folds.
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 144, 176, null)
            frames.drop(6).forEach { (bytes, pts, key) -> recorder.onSample(bytes, bytes.size, pts, key) }

            assertEquals("Frames after the change must not be written", written, recorder.frameCount)
            assertEquals(SessionRecorder.SIZE_CHANGED, recorder.error)
            assertNotNull("What was captured before the change is still kept", recorder.finish())
        } finally {
            file.delete()
        }
    }

    @Test fun framesArrivingAfterTheFileIsFinishedAreIgnored() {
        // They keep coming on the codec worker after the user stops, and must not start a second,
        // headless recording nor be written into a closed one.
        val (config, frames) = encodedFrames()
        val file = File(cache, "recorder-late-${System.nanoTime()}.mp4")
        val recorder = SessionRecorder(file)
        try {
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 176, 144, config)
            frames.take(4).forEach { (bytes, pts, key) -> recorder.onSample(bytes, bytes.size, pts, key) }
            val saved = recorder.finish()
            val countAtFinish = recorder.frameCount

            frames.drop(4).forEach { (bytes, pts, key) -> recorder.onSample(bytes, bytes.size, pts, key) }
            assertEquals(countAtFinish, recorder.frameCount)
            assertTrue("The finished file should not have grown",
                saved == null || saved.length() > 0)
        } finally {
            file.delete()
        }
    }
}
