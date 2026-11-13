package com.scrcpy.remote

import com.scrcpy.remote.session.SoundRecordingSync
import com.scrcpy.remote.session.SoundRecordingSync.Result
import com.scrcpy.remote.session.SoundRecordingSync.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundRecordingSyncTest {
    private val frame = SoundRecordingSync.aacFrameUs(48_000)

    @Test fun anAacFrameIsOneThousandAndTwentyFourSamples() {
        assertEquals(21_333L, frame)
    }

    @Test fun aKeyFrameCoveredByBufferedSoundOpensBothTracksAtZero() {
        val sync = SoundRecordingSync(frame)
        // Sound for this moment usually arrives before the picture: its packets are buffered.
        for (pts in listOf(1_000_000L, 1_021_333L, 1_042_666L, 1_064_000L)) assertEquals(Result.Wait, audio(sync, pts))
        assertFalse(sync.isOpen)

        val opened = video(sync, 1_030_000, key = true) as Result.Write
        assertTrue("The file starts with this frame", opened.open)
        assertTrue(sync.isOpen)
        // The sound packet nearest the frame starts the sound track, so both tracks begin at zero
        // on every Android version; the older packet is left out.
        assertEquals(listOf(Track.VIDEO to 0L, Track.AUDIO to 0L, Track.AUDIO to 21_333L, Track.AUDIO to 42_667L),
            opened.samples.map { it.track to it.timeUs })
        assertTrue(opened.samples.first().keyFrame)

        assertEquals(listOf(Track.VIDEO to 33_333L), written(video(sync, 1_063_333, key = false)))
        assertEquals(listOf(Track.AUDIO to 64_000L), written(audio(sync, 1_085_333)))
        assertEquals(33_333L, sync.durationUs)
    }

    @Test fun thePictureWaitsForSoundThatHasNotArrivedYet() {
        val sync = SoundRecordingSync(frame)
        assertEquals(Result.Wait, audio(sync, 1_000_000))
        assertEquals("Held until the sound for it arrives", Result.Wait, video(sync, 1_050_000, key = true))
        assertEquals(Result.Wait, video(sync, 1_083_333, key = false))
        assertEquals("Still before the frame", Result.Wait, audio(sync, 1_021_333))

        val opened = audio(sync, 1_042_666) as Result.Write
        assertTrue(opened.open)
        assertEquals(listOf(Track.VIDEO to 0L, Track.VIDEO to 33_333L, Track.AUDIO to 0L),
            opened.samples.map { it.track to it.timeUs })
    }

    @Test fun soundThatBeginsAfterTheKeyFrameAsksForANewKeyFrame() {
        val sync = SoundRecordingSync(frame)
        assertEquals(Result.Wait, video(sync, 2_000_000, key = true))
        // Nothing covers the frame: written as it is, the sound would start early on Android 8,
        // which moves a track's first sample to zero.
        assertEquals(Result.NeedKeyFrame, audio(sync, 2_100_000))
        assertFalse(sync.isOpen)

        assertEquals(Result.Wait, audio(sync, 2_121_333))
        assertEquals("Only a key frame can open the file", Result.Wait, video(sync, 2_133_333, key = false))
        assertEquals(Result.Wait, audio(sync, 2_142_666))
        val opened = video(sync, 2_150_000, key = true) as Result.Write
        assertEquals(listOf(Track.VIDEO to 0L, Track.AUDIO to 0L), opened.samples.map { it.track to it.timeUs })
    }

    @Test fun soundBeforeTheStartIsNeverWrittenOnceOpen() {
        val sync = open()
        assertEquals("Older than the track's first sample", Result.Wait, audio(sync, 990_000))
        assertEquals(listOf(Track.AUDIO to 21_333L), written(audio(sync, 1_021_333)))
    }

    @Test fun eachTrackStaysStrictlyIncreasing() {
        val sync = open()
        assertEquals(listOf(Track.AUDIO to 21_333L), written(audio(sync, 1_021_333)))
        assertEquals(listOf(Track.AUDIO to 21_334L), written(audio(sync, 1_021_333)))
        assertEquals(listOf(Track.VIDEO to 33_333L), written(video(sync, 1_033_333, key = false)))
        assertEquals(listOf(Track.VIDEO to 33_334L), written(video(sync, 1_020_000, key = false)))
    }

    @Test fun aHoldThatOutlastsItsLimitGivesUpThatKeyFrame() {
        val sync = SoundRecordingSync(frame, holdLimitUs = 500_000)
        assertEquals(Result.Wait, video(sync, 1_000_000, key = true))
        assertEquals(Result.Wait, video(sync, 1_400_000, key = false))
        assertEquals(Result.NeedKeyFrame, video(sync, 1_600_000, key = false))
        // The held picture is gone: sound covering it no longer opens anything.
        assertEquals(Result.Wait, audio(sync, 1_000_000))
        assertFalse(sync.isOpen)
    }

    @Test fun aHeldPictureIsBoundedInBytes() {
        val sync = SoundRecordingSync(frame, maxHeldBytes = 1_000)
        assertEquals(Result.Wait, sync.onVideo(ByteArray(600), 600, 1_000_000, true))
        assertEquals(Result.NeedKeyFrame, sync.onVideo(ByteArray(600), 600, 1_033_333, false))
    }

    @Test fun aKeyFrameItsSoundCoversOpensHoweverLateItComes() {
        // The device's own next key frame can be ten seconds away; when it is the one that can
        // open the file, giving up on it for being late would leave nothing recorded at all.
        val sync = SoundRecordingSync(frame, giveUpUs = 3_000_000)
        assertEquals(Result.Wait, audio(sync, 1_030_000))
        assertEquals(Result.NeedKeyFrame, video(sync, 1_000_000, key = true))
        var pts = 1_030_000L + frame
        while (pts < 11_000_000) { audio(sync, pts); pts += frame }
        val opened = video(sync, 11_000_000, key = true)
        assertTrue("A key frame its sound covers is used: $opened", opened is Result.Write && opened.open)
    }

    @Test fun alignmentGivesUpOnceItHasTriedForItsTimeLimit() {
        val sync = SoundRecordingSync(frame, holdLimitUs = 500_000, giveUpUs = 3_000_000)
        assertEquals(Result.Wait, video(sync, 1_000_000, key = true))
        assertEquals(Result.NeedKeyFrame, video(sync, 1_600_000, key = false))
        assertEquals(Result.Wait, video(sync, 2_000_000, key = true))
        assertEquals(Result.NeedKeyFrame, video(sync, 2_600_000, key = false))
        assertEquals("More than three seconds of pictures without sound to match",
            Result.GiveUp, video(sync, 4_100_000, key = true))
        assertEquals("Final", Result.GiveUp, audio(sync, 4_100_000))
    }

    @Test fun thePrerollKeepsOnlyRecentSound() {
        val sync = SoundRecordingSync(frame, prerollUs = 100_000)
        var pts = 0L
        while (pts <= 1_000_000) { audio(sync, pts); pts += frame }
        // The packet covering this frame was older than the preroll keeps.
        assertEquals(Result.NeedKeyFrame, video(sync, 850_000, key = true))
        val opened = video(sync, 960_000, key = true) as Result.Write
        assertEquals(Track.AUDIO to 0L, opened.samples[1].track to opened.samples[1].timeUs)
    }

    @Test fun thePrerollIsBoundedInPackets() {
        val sync = SoundRecordingSync(frame, maxPrerollPackets = 4)
        for (i in 0 until 10) audio(sync, 1_000_000L + i * frame)
        // Only the last four packets remain, from 1_127_998 on, so none covers this frame.
        assertEquals(Result.NeedKeyFrame, video(sync, 1_100_000, key = true))
    }

    @Test fun aRestartWhileHoldingDropsTheHeldPicture() {
        val sync = SoundRecordingSync(frame)
        assertEquals(Result.Wait, video(sync, 1_000_000, key = true))
        sync.videoRestarted()
        assertEquals(Result.Wait, audio(sync, 995_000))
        assertFalse(sync.isOpen)
    }

    @Test fun onlyWhatIsKeptIsCopied() {
        val sync = SoundRecordingSync(frame)
        val early = ByteArray(8)
        audio(sync, 1_000_000, early)
        val key = ByteArray(100)
        val opened = sync.onVideo(key, key.size, 1_005_000, true) as Result.Write
        // Buffers come from a pool the reader reuses, so anything held has to be a copy...
        assertNotSame(early, opened.samples.single { it.track == Track.AUDIO }.data)
        // ...while what is written straight away is not copied at all.
        val frameData = ByteArray(100)
        assertSame(frameData, (sync.onVideo(frameData, 100, 1_038_333, false) as Result.Write).samples.single().data)
        val sound = ByteArray(8)
        assertSame(sound, (sync.onAudio(sound, 8, 1_021_333) as Result.Write).samples.single().data)
    }

    /** Opened at 1_000_000 with its first sound packet at the same time. */
    private fun open(): SoundRecordingSync {
        val sync = SoundRecordingSync(frame)
        audio(sync, 1_000_000)
        assertTrue((video(sync, 1_000_000, key = true) as Result.Write).open)
        return sync
    }

    private fun audio(sync: SoundRecordingSync, pts: Long, data: ByteArray = ByteArray(8)) =
        sync.onAudio(data, data.size, pts)

    private fun video(sync: SoundRecordingSync, pts: Long, key: Boolean) = sync.onVideo(ByteArray(100), 100, pts, key)

    private fun written(result: Result) = (result as Result.Write).also { assertFalse(it.open) }
        .samples.map { it.track to it.timeUs }
}
