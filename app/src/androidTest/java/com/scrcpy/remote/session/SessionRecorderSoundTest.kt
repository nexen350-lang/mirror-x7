package com.scrcpy.remote.session

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recordings with sound, read back with a real extractor.
 *
 * Android's MP4 writer only takes AAC beside the picture, and Android 8's moves a track's first
 * sample to zero, so these check what a player will actually read, on every API level.
 */
@RunWith(AndroidJUnit4::class)
class SessionRecorderSoundTest {
    private val cache: File get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test fun soundAndPictureStartTogetherWhenTheSoundArrivesFirst() {
        val video = EncodedMedia.h264(count = 16)
        val sound = EncodedMedia.aac(seconds = 1.5)
        val videoBase = 10_000_000L
        // Sound starts 30 ms before the picture: the packet nearest the key frame starts it.
        val soundBase = videoBase - 30_000
        val half = SoundRecordingSync.aacFrameUs(48_000) / 2
        val startPts = sound.frames.map { soundBase + it.ptsUs }.first { it >= videoBase - half }
        record(withSound = true) { recorder ->
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, EncodedMedia.WIDTH, EncodedMedia.HEIGHT, video.config)
            recorder.onAudioFormat(sound.config)
            // Sound for each moment arrives 60 ms before the picture for it.
            feed(recorder, video, videoBase, sound, soundBase, soundLeadUs = 60_000)
        }.use { file ->
            val tracks = read(file.file)
            assertEquals(listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_AUDIO_AAC).toSet(), tracks.keys)
            val pictureTimes = tracks.getValue(MediaFormat.MIMETYPE_VIDEO_AVC)
            val soundTimes = tracks.getValue(MediaFormat.MIMETYPE_AUDIO_AAC)
            assertTimes(video.frames.map { it.ptsUs }, pictureTimes)
            // Every packet from the start on, each at its own distance from the start.
            val expected = sound.frames.map { soundBase + it.ptsUs }.filter { it >= startPts }.map { it - startPts }
            assertTimes(expected, soundTimes)
            assertTrue("The sound starts within half a packet of the picture",
                kotlin.math.abs(startPts - videoBase) <= half)
            assertTrue(file.recorder.hasSound)
        }
    }

    @Test fun thePictureWaitsForSoundThatArrivesAfterIt() {
        val video = EncodedMedia.h264(count = 16)
        val sound = EncodedMedia.aac(seconds = 1.5)
        val base = 20_000_000L
        record(withSound = true) { recorder ->
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, EncodedMedia.WIDTH, EncodedMedia.HEIGHT, video.config)
            recorder.onAudioFormat(sound.config)
            // The sound for each moment arrives 150 ms after the picture for it.
            feed(recorder, video, base, sound, base, soundLeadUs = -150_000)
        }.use { file ->
            val tracks = read(file.file)
            assertTimes(video.frames.map { it.ptsUs }, tracks.getValue(MediaFormat.MIMETYPE_VIDEO_AVC),
                "No picture is lost while it waits for its sound")
            assertTimes(sound.frames.map { it.ptsUs }, tracks.getValue(MediaFormat.MIMETYPE_AUDIO_AAC))
        }
    }

    @Test fun theSoundTrackIsDescribedByTheDevicesConfiguration() {
        val video = EncodedMedia.h264(count = 16)
        val sound = EncodedMedia.aac(seconds = 1.5)
        record(withSound = true) { recorder ->
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, EncodedMedia.WIDTH, EncodedMedia.HEIGHT, video.config)
            recorder.onAudioFormat(sound.config)
            feed(recorder, video, 0, sound, 0, soundLeadUs = 20_000)
        }.use { file ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.file.absolutePath)
                val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                    .single { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
                assertEquals(48_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            } finally {
                extractor.release()
            }
        }
    }

    @Test fun soundThatStopsEndsTheRecordingAndKeepsWhatItHas() {
        val video = EncodedMedia.h264(count = 16)
        val sound = EncodedMedia.aac(seconds = 1.5)
        val stopped = AtomicInteger()
        record(withSound = true, onStopped = { stopped.incrementAndGet() }) { recorder ->
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, EncodedMedia.WIDTH, EncodedMedia.HEIGHT, video.config)
            recorder.onAudioFormat(sound.config)
            feed(recorder, video, 0, sound, 0, soundLeadUs = 20_000, stopAtUs = 500_000)
            recorder.onAudioStopped()
            // What arrives afterwards belongs to no recording.
            feed(recorder, video, 0, sound, 0, soundLeadUs = 20_000, fromUs = 500_000)
        }.use { file ->
            assertEquals(SessionRecorder.AUDIO_STOPPED, file.recorder.error)
            assertEquals("Told once", 1, stopped.get())
            val tracks = read(file.file)
            assertEquals(2, tracks.size)
            assertTrue("Only the picture before the sound stopped",
                tracks.getValue(MediaFormat.MIMETYPE_VIDEO_AVC).all { it < 500_000 })
        }
    }

    @Test fun soundThatNeverLinesUpEndsTheRecordingWithItsReason() {
        val video = EncodedMedia.h264(count = 16)
        val sound = EncodedMedia.aac(seconds = 0.2)
        val stopped = AtomicInteger()
        val asked = AtomicInteger()
        val file = File(cache, "sound-misaligned-${System.nanoTime()}.mp4")
        val recorder = SessionRecorder(file, withSound = true)
        recorder.onStopped = { stopped.incrementAndGet() }
        recorder.onKeyFrameNeeded = { asked.incrementAndGet() }
        try {
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, EncodedMedia.WIDTH, EncodedMedia.HEIGHT, video.config)
            recorder.onAudioFormat(sound.config)
            // The sound's clock is ten seconds ahead of the picture's.
            sound.frames.forEach { recorder.onAudioSample(it.data, it.data.size, 10_000_000 + it.ptsUs) }
            val key = video.frames.first()
            for (attempt in 0..8) recorder.onSample(key.data, key.data.size, attempt * 500_000L, true)
            assertTrue("A new key frame was asked for", asked.get() > 0)
            assertEquals(SessionRecorder.AUDIO_TIMELINE, recorder.error)
            assertEquals(1, stopped.get())
            assertNull("Nothing was written, so nothing is offered", recorder.finish())
            assertFalse(file.exists())
        } finally {
            recorder.discard()
        }
    }

    @Test fun aKeyFrameIsAskedForWhenTheSoundFormatComesAfterThePicture() {
        val video = EncodedMedia.h264(count = 16)
        val sound = EncodedMedia.aac(seconds = 0.2)
        val asked = AtomicInteger()
        val file = File(cache, "sound-late-format-${System.nanoTime()}.mp4")
        val recorder = SessionRecorder(file, withSound = true)
        recorder.onKeyFrameNeeded = { asked.incrementAndGet() }
        try {
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, EncodedMedia.WIDTH, EncodedMedia.HEIGHT, video.config)
            val key = video.frames.first()
            recorder.onSample(key.data, key.data.size, key.ptsUs, true)
            assertEquals(0, asked.get())
            recorder.onAudioFormat(sound.config)
            assertEquals("The key frame that went past had no sound to open with", 1, asked.get())
        } finally {
            recorder.discard()
        }
    }

    @Test fun aSoundConfigurationThatCannotDescribeATrackEndsTheRecording() {
        val file = File(cache, "sound-bad-config-${System.nanoTime()}.mp4")
        val recorder = SessionRecorder(file, withSound = true)
        try {
            recorder.onAudioFormat(byteArrayOf(0x11))
            assertEquals(SessionRecorder.AUDIO_UNSUPPORTED, recorder.error)
        } finally {
            recorder.discard()
        }
    }

    @Test fun aRecordingWithoutSoundTakesNoneOfIt() {
        val video = EncodedMedia.h264(count = 16)
        val sound = EncodedMedia.aac(seconds = 1.0)
        record(withSound = false) { recorder ->
            recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, EncodedMedia.WIDTH, EncodedMedia.HEIGHT, video.config)
            recorder.onAudioFormat(sound.config)
            feed(recorder, video, 0, sound, 0, soundLeadUs = 20_000)
            recorder.onAudioStopped()
        }.use { file ->
            assertNull(file.recorder.error)
            assertFalse(file.recorder.hasSound)
            assertEquals(setOf(MediaFormat.MIMETYPE_VIDEO_AVC), read(file.file).keys)
        }
    }

    private class Recorded(val recorder: SessionRecorder, val file: File) : AutoCloseable {
        override fun close() { file.delete() }
    }

    /** MP4 keeps times in a track timescale, so they come back rounded by a few microseconds. */
    private fun assertTimes(expected: List<Long>, actual: List<Long>, message: String = "Sample times") {
        assertEquals("$message: count", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { index, (want, got) ->
            assertTrue("$message: sample $index at $got, expected $want", kotlin.math.abs(got - want) <= 1_000)
        }
    }

    private fun record(
        withSound: Boolean,
        onStopped: () -> Unit = {},
        block: (SessionRecorder) -> Unit,
    ): Recorded {
        val destination = File(cache, "sound-${System.nanoTime()}.mp4")
        val recorder = SessionRecorder(destination, withSound)
        recorder.onStopped = onStopped
        block(recorder)
        val saved = recorder.finish()
        assertNotNull("Nothing was kept: ${recorder.error}", saved)
        return Recorded(recorder, saved!!)
    }

    /**
     * Offers both streams in the order they would arrive: each packet arrives at its own time
     * plus a delay, the sound [soundLeadUs] earlier than the picture.
     */
    private fun feed(
        recorder: SessionRecorder,
        video: EncodedMedia.Stream,
        videoBase: Long,
        sound: EncodedMedia.Stream,
        soundBase: Long,
        soundLeadUs: Long,
        fromUs: Long = Long.MIN_VALUE,
        stopAtUs: Long = Long.MAX_VALUE,
    ) {
        data class Arrival(val at: Long, val video: Boolean, val frame: EncodedMedia.Frame)
        val arrivals = video.frames.map { Arrival(it.ptsUs, true, it) } +
            sound.frames.map { Arrival(soundBase - videoBase + it.ptsUs - soundLeadUs, false, it) }
        for (arrival in arrivals.sortedBy { it.at }) {
            if (arrival.at < fromUs || arrival.at >= stopAtUs) continue
            val frame = arrival.frame
            if (arrival.video) recorder.onSample(frame.data, frame.data.size, videoBase + frame.ptsUs, frame.keyFrame)
            else recorder.onAudioSample(frame.data, frame.data.size, soundBase + frame.ptsUs)
        }
    }

    /** Each track's sample times, by mime type, read the way a player reads them. */
    private fun read(file: File): Map<String, List<Long>> {
        val probe = MediaExtractor()
        val count = try {
            probe.setDataSource(file.absolutePath)
            probe.trackCount
        } finally {
            probe.release()
        }
        val buffer = ByteBuffer.allocate(512 * 1024)
        return (0 until count).associate { index ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                extractor.selectTrack(index)
                val times = mutableListOf<Long>()
                while (extractor.readSampleData(buffer, 0) >= 0) {
                    times += extractor.sampleTime
                    extractor.advance()
                }
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)!! to times
            } finally {
                extractor.release()
            }
        }
    }
}
