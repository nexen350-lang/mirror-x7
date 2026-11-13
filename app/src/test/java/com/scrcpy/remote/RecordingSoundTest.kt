package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.session.RecordingSound
import com.scrcpy.remote.session.SessionRecorder
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSoundTest {
    @Test fun soundSwitchedOffRecordsThePicture() {
        for (codec in AudioCodec.entries) {
            assertEquals(RecordingSound.PictureOnly(soundOff = true), RecordingSound.decide(false, false, codec))
        }
    }

    @Test fun soundThatIsNotRunningRecordsThePicture() {
        // Failed, or the device cannot send sound: there is nothing to reconnect for.
        for (codec in AudioCodec.entries) {
            assertEquals(RecordingSound.PictureOnly(soundOff = false), RecordingSound.decide(true, false, codec))
        }
    }

    @Test fun runningAacSoundIsRecorded() {
        assertEquals(RecordingSound.WithSound, RecordingSound.decide(true, true, AudioCodec.AAC))
    }

    @Test fun otherRunningSoundAsksBeforeAnythingChanges() {
        for (codec in listOf(AudioCodec.OPUS, AudioCodec.FLAC, AudioCodec.RAW)) {
            assertEquals(RecordingSound.NeedsAac(codec), RecordingSound.decide(true, true, codec))
        }
    }

    @Test fun aRecordingThatKeptNothingDoesNotSayItWasKept() {
        assertEquals(R.string.recording_stopped_sound, RecordingSound.stopNotice(SessionRecorder.AUDIO_STOPPED, kept = true))
        assertEquals("The sound stopped before the file opened",
            R.string.recording_sound_stopped_empty, RecordingSound.stopNotice(SessionRecorder.AUDIO_STOPPED, kept = false))
        assertEquals(R.string.recording_sound_timeline, RecordingSound.stopNotice(SessionRecorder.AUDIO_TIMELINE, kept = false))
        assertEquals(R.string.recording_sound_unsupported,
            RecordingSound.stopNotice(SessionRecorder.AUDIO_UNSUPPORTED, kept = false))
        assertEquals("Other reasons have their own notices", null,
            RecordingSound.stopNotice(SessionRecorder.SIZE_CHANGED, kept = true))
    }
}
