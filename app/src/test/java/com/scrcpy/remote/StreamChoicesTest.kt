package com.scrcpy.remote

import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.DeviceEncoder
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.scrcpy.VideoCodec
import com.scrcpy.remote.session.CodecSupport
import com.scrcpy.remote.session.DeviceEncodersState
import com.scrcpy.remote.session.SessionOverrides
import com.scrcpy.remote.session.StreamChoice
import com.scrcpy.remote.session.StreamChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Picture & sound changes the session's codecs and server together, for that session only, and
 * narrows its choices with the device's own encoder list when one was read.
 */
class StreamChoicesTest {
    private fun listed(vararg codecs: String) = DeviceEncodersState(
        loaded = true, encoders = codecs.map { DeviceEncoder(it, "c2.test.$it.encoder") })

    @Test fun aCodecIsOnTheDeviceOnlyWhenItsEncoderListNamesIt() {
        val device = listed("h264", "h265", "opus", "aac")
        assertEquals(CodecSupport.ON_DEVICE, StreamChoices.videoSupport(VideoCodec.H264, device))
        assertEquals(CodecSupport.ON_DEVICE, StreamChoices.videoSupport(VideoCodec.H265, device))
        assertEquals(CodecSupport.NOT_ON_DEVICE, StreamChoices.videoSupport(VideoCodec.AV1, device))
        assertEquals(CodecSupport.ON_DEVICE, StreamChoices.audioSupport(AudioCodec.AAC, device))
        assertEquals(CodecSupport.NOT_ON_DEVICE, StreamChoices.audioSupport(AudioCodec.FLAC, device))
    }

    @Test fun rawSoundNeedsNoEncoder() {
        // The server sends PCM as it captures it, so no list can say it is missing.
        for (state in listOf(listed(), DeviceEncodersState(), DeviceEncodersState(error = "refused"))) {
            assertEquals(CodecSupport.ON_DEVICE, StreamChoices.audioSupport(AudioCodec.RAW, state))
        }
    }

    @Test fun aListNotReadPromisesNothing() {
        // Not checked is not the same as available: the sheet says so instead of an empty success.
        for (state in listOf(DeviceEncodersState(), DeviceEncodersState(loading = true),
            DeviceEncodersState(error = "The device did not answer"))) {
            assertEquals(CodecSupport.NOT_CHECKED, StreamChoices.videoSupport(VideoCodec.H265, state))
            assertEquals(CodecSupport.NOT_CHECKED, StreamChoices.audioSupport(AudioCodec.AAC, state))
        }
    }

    @Test fun aChoiceRunsAsSessionOnlySwitchesOnTopOfTheProfile() {
        val profile = MirrorSettings(videoCodec = VideoCodec.H264, videoEncoder = "c2.qti.avc.encoder",
            audioCodec = AudioCodec.OPUS, serverVersion = ScrcpyServerVersion.V5_0)
        val choice = StreamChoice(VideoCodec.H265, AudioCodec.AAC, ScrcpyServerVersion.DEFAULT)
        val overrides = StreamChoices.overridesFor(SessionOverrides.EMPTY, choice, profile)
        val applied = overrides.applyTo(profile)
        assertEquals(choice, StreamChoice.of(applied))
        assertNull("An encoder listed for H.264 cannot run H.265", applied.videoEncoder)
        assertEquals(StreamChoice(VideoCodec.H264, AudioCodec.OPUS, ScrcpyServerVersion.V5_0), StreamChoice.of(profile))
    }

    @Test fun choosingTheProfilesOwnValuesClearsTheSwitches() {
        val profile = MirrorSettings()
        val switched = StreamChoices.overridesFor(SessionOverrides.EMPTY,
            StreamChoice(VideoCodec.VP9, AudioCodec.FLAC, ScrcpyServerVersion.DEFAULT), profile)
        // Back to what the device's settings say: nothing of the session's own is left in effect.
        assertEquals(SessionOverrides.EMPTY, StreamChoices.overridesFor(switched, StreamChoice.of(profile), profile))
    }

    @Test fun otherSessionChoicesSurviveASwitch() {
        val held = SessionOverrides(camera = SessionOverrides.Choice(CameraCapture(id = "1")), audioDup = false)
        val switched = StreamChoices.overridesFor(held,
            StreamChoice(VideoCodec.VP9, AudioCodec.OPUS, ScrcpyServerVersion.V5_0), MirrorSettings())
        assertEquals(held.camera, switched.camera)
        assertEquals(false, switched.audioDup)
    }
}
