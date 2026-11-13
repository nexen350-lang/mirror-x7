package com.scrcpy.remote

import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.CaptureOrientation
import com.scrcpy.remote.scrcpy.VideoCodec
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.session.DesktopSize
import com.scrcpy.remote.session.SessionDisplay
import com.scrcpy.remote.session.SessionOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOverridesTest {

    @Test fun nothingChosenChangesNothing() {
        val settings = MirrorSettings(videoEncoder = "from.profile", audioEncoder = "audio.profile")
        assertEquals(settings, SessionOverrides.EMPTY.applyTo(settings))
        assertTrue(SessionOverrides.EMPTY.isEmpty)
    }

    @Test fun aChosenEncoderReplacesTheProfileOne() {
        val overrides = SessionOverrides(videoEncoder = SessionOverrides.Choice("c2.chosen.encoder"))
        val applied = overrides.applyTo(MirrorSettings(videoEncoder = "from.profile"))
        assertEquals("c2.chosen.encoder", applied.videoEncoder)
        assertFalse(overrides.isEmpty)
    }

    @Test fun choosingTheDeviceDefaultIsItselfAChoice() {
        // "Let the device choose" has the value null, which a bare nullable could not tell apart
        // from having chosen nothing — and the profile's encoder would then survive a choice made
        // specifically to get rid of it.
        val overrides = SessionOverrides(videoEncoder = SessionOverrides.Choice(null))
        assertNull(overrides.applyTo(MirrorSettings(videoEncoder = "from.profile")).videoEncoder)
        assertFalse(overrides.isEmpty)
    }

    @Test fun videoAndAudioAreIndependent() {
        val overrides = SessionOverrides(audioEncoder = SessionOverrides.Choice("audio.chosen"))
        val applied = overrides.applyTo(
            MirrorSettings(videoEncoder = "video.profile", audioEncoder = "audio.profile"))
        assertEquals("video.profile", applied.videoEncoder)
        assertEquals("audio.chosen", applied.audioEncoder)
    }

    @Test fun anEncoderIsDroppedWhenItsCodecChanges() {
        // The device refuses a mismatched codec/encoder pair, and the user sees only a session
        // that will not start.
        val overrides = SessionOverrides(
            videoEncoder = SessionOverrides.Choice("c2.avc.encoder"),
            audioEncoder = SessionOverrides.Choice("c2.opus.encoder"),
        )
        val before = MirrorSettings(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.OPUS)
        val after = before.copy(videoCodec = VideoCodec.H265)

        val kept = overrides.clearStaleEncoders(before, after)
        assertNull("The video encoder belonged to H.264", kept.videoEncoder)
        assertEquals("Audio was untouched", "c2.opus.encoder", kept.audioEncoder?.value)
    }

    @Test fun anOrdinaryReconnectKeepsTheChoice() {
        // Applying an encoder *is* a reconnect, so a reconnect that drops it would undo the
        // choice by the very act of taking effect.
        val overrides = SessionOverrides(videoEncoder = SessionOverrides.Choice("c2.avc.encoder"))
        val settings = MirrorSettings(videoCodec = VideoCodec.H264)
        assertEquals(overrides, overrides.clearStaleEncoders(settings, settings.copy(maxFps = 30)))
    }

    @Test fun withNoPreviousSettingsNothingIsDropped() {
        val overrides = SessionOverrides(videoEncoder = SessionOverrides.Choice("c2.avc.encoder"))
        assertEquals(overrides, overrides.clearStaleEncoders(null, MirrorSettings()))
    }

    @Test fun aSessionCanSwitchToAacSoundWithoutItsProfile() {
        // Recording with sound needs AAC; the switch is for this session, and the profile's Opus
        // encoder cannot encode AAC, so the device would refuse the pair.
        val overrides = SessionOverrides(audioCodec = AudioCodec.AAC)
        val profile = MirrorSettings(audioCodec = AudioCodec.OPUS, audioEncoder = "c2.android.opus.encoder")
        val applied = overrides.applyTo(profile)
        assertEquals(AudioCodec.AAC, applied.audioCodec)
        assertNull(applied.audioEncoder)
        assertFalse(overrides.isEmpty)
    }

    @Test fun aProfileAlreadyOnAacKeepsItsEncoder() {
        val profile = MirrorSettings(audioCodec = AudioCodec.AAC, audioEncoder = "c2.android.aac.encoder")
        assertEquals(profile, SessionOverrides(audioCodec = AudioCodec.AAC).applyTo(profile))
    }

    @Test fun anEncoderChosenForTheSessionCodecApplies() {
        val overrides = SessionOverrides(
            audioCodec = AudioCodec.AAC, audioEncoder = SessionOverrides.Choice("c2.android.aac.encoder"))
        val applied = overrides.applyTo(MirrorSettings(audioCodec = AudioCodec.OPUS, audioEncoder = "c2.android.opus.encoder"))
        assertEquals(AudioCodec.AAC, applied.audioCodec)
        assertEquals("c2.android.aac.encoder", applied.audioEncoder)
    }

    @Test fun aSoundCodecSavedAfterTheSessionSwitchTakesOver() {
        // Changed on purpose after "Reconnect and record": that choice wins, and because it is
        // what the session would now run, the screen can say the saved settings differ.
        val overrides = SessionOverrides(audioCodec = AudioCodec.AAC, audioCodecFrom = AudioCodec.OPUS)
        assertEquals(AudioCodec.AAC, overrides.applyTo(MirrorSettings(audioCodec = AudioCodec.OPUS)).audioCodec)
        val edited = overrides.applyTo(MirrorSettings(audioCodec = AudioCodec.FLAC, audioEncoder = "c2.android.flac.encoder"))
        assertEquals(AudioCodec.FLAC, edited.audioCodec)
        assertEquals("c2.android.flac.encoder", edited.audioEncoder)
    }

    @Test fun aSwitchTheProfileHasMovedOnFromIsDroppedOnReconnect() {
        val overrides = SessionOverrides(audioCodec = AudioCodec.AAC, audioCodecFrom = AudioCodec.OPUS,
            audioEncoder = SessionOverrides.Choice("c2.android.aac.encoder"))
        val running = MirrorSettings(audioCodec = AudioCodec.AAC, audioEncoder = "c2.android.aac.encoder")
        val kept = overrides.clearStaleEncoders(running, MirrorSettings(audioCodec = AudioCodec.FLAC))
        assertNull(kept.audioCodec)
        assertNull("The encoder belonged to the AAC switch", kept.audioEncoder)
        assertEquals("While the profile still says Opus the switch stays",
            overrides, overrides.clearStaleEncoders(running, MirrorSettings(audioCodec = AudioCodec.OPUS)))
    }

    @Test fun reconnectingKeepsAnEncoderChosenForTheSessionCodec() {
        // The profile still says Opus, but the session runs AAC: a reconnect is not a codec change.
        val overrides = SessionOverrides(
            audioCodec = AudioCodec.AAC, audioEncoder = SessionOverrides.Choice("c2.android.aac.encoder"))
        val running = MirrorSettings(audioCodec = AudioCodec.AAC, audioEncoder = "c2.android.aac.encoder")
        assertEquals(overrides, overrides.clearStaleEncoders(running, MirrorSettings(audioCodec = AudioCodec.OPUS)))
    }

    @Test fun aPhysicalDisplayChoiceReplacesBothCameraAndVirtualCaptureWithoutSavingThem() {
        val original = MirrorSettings(camera = CameraCapture(id = "0"), virtualDisplay = "1280x720")
        val applied = SessionOverrides(display = SessionDisplay.Physical(3)).applyTo(original)
        assertEquals(3, applied.displayId)
        assertNull(applied.camera)
        assertNull(applied.virtualDisplay)
        assertEquals("0", original.camera?.id)
        assertEquals("1280x720", original.virtualDisplay)
    }

    @Test fun primaryDisplayZeroIsAnExplicitSessionChoice() {
        val applied = SessionOverrides(display = SessionDisplay.Physical(0))
            .applyTo(MirrorSettings(displayId = 3))
        assertEquals(0, applied.displayId)
    }

    @Test fun anAdaptiveDesktopKeepsStreamingPreferencesButNotPhysicalScreenBehavior() {
        val original = MirrorSettings(
            maxSize = 1600, maxFps = 45, camera = CameraCapture(id = "1"), displayId = 2,
            captureOrientation = CaptureOrientation.LOCK_90,
            turnScreenOff = true, powerOffOnClose = true,
        )
        val applied = SessionOverrides(
            display = SessionDisplay.Desktop(DesktopSize(720, 1600), 280),
        ).applyTo(original)
        assertEquals("720x1600/280", applied.virtualDisplay)
        assertEquals(0, applied.displayId)
        assertNull(applied.camera)
        assertEquals(CaptureOrientation.FOLLOW_DEVICE, applied.captureOrientation)
        assertFalse(applied.turnScreenOff)
        assertFalse(applied.powerOffOnClose)
        assertEquals(1600, applied.maxSize)
        assertEquals(45, applied.maxFps)
        assertTrue(original.turnScreenOff)
    }

    @Test fun aCameraCanTemporarilyReplaceTheDesktopAndReturningRestoresIt() {
        val desktop = SessionOverrides(display = SessionDisplay.Desktop(DesktopSize(720, 1600), 280))
        val camera = desktop.copy(camera = SessionOverrides.Choice(CameraCapture(id = "1")))
        val cameraSettings = camera.applyTo(MirrorSettings(captureOrientation = CaptureOrientation.LOCK_90))
        assertEquals("1", cameraSettings.camera?.id)
        assertEquals(CaptureOrientation.LOCK_90, cameraSettings.captureOrientation)
        assertNull(cameraSettings.virtualDisplay)
        val screen = camera.copy(camera = SessionOverrides.Choice(null)).applyTo(MirrorSettings())
        assertNull(screen.camera)
        assertEquals("720x1600/280", screen.virtualDisplay)
    }

    @Test fun soundKeptOnTheDeviceCanBeMovedHereForTheSessionOnly() {
        // Android 13 refuses to send sound it keeps playing on the device. Hearing it here instead
        // is chosen for the session in front of the user; the saved device keeps its setting.
        val profile = MirrorSettings(audioSource = AudioSource.PLAYBACK, audioDup = true)
        val overrides = SessionOverrides(audioDup = false)
        val applied = overrides.applyTo(profile)
        assertFalse(applied.audioDup)
        assertEquals(AudioSource.PLAYBACK, applied.audioSource)
        assertTrue(profile.audioDup)
        assertFalse(overrides.isEmpty)
        // Taking effect is a reconnect, which must not undo it.
        assertFalse(overrides.clearStaleEncoders(applied, profile).applyTo(profile).audioDup)
    }

    @Test fun aVideoCodecChosenForTheSessionAppliesUntilTheProfileChoosesAgain() {
        val profile = MirrorSettings(videoCodec = VideoCodec.H264, videoEncoder = "c2.qti.avc.encoder")
        val overrides = SessionOverrides(videoCodec = VideoCodec.H265, videoCodecFrom = VideoCodec.H264)
        val applied = overrides.applyTo(profile)
        assertEquals(VideoCodec.H265, applied.videoCodec)
        assertNull("The profile's encoder belongs to H.264", applied.videoEncoder)
        assertEquals(VideoCodec.H265, overrides.clearStaleEncoders(applied, profile).applyTo(profile).videoCodec)
        // A codec saved on purpose afterwards wins, and the switch goes.
        val saved = profile.copy(videoCodec = VideoCodec.VP9, videoEncoder = null)
        val dropped = overrides.clearStaleEncoders(applied, saved)
        assertNull(dropped.videoCodec)
        assertEquals(VideoCodec.VP9, dropped.applyTo(saved).videoCodec)
    }

    @Test fun anEncoderChosenForTheSessionGoesWhenTheSessionSwitchesCodec() {
        // The encoder was picked from the H.264 list; the device refuses it for anything else.
        val running = MirrorSettings(videoCodec = VideoCodec.H264, videoEncoder = "c2.qti.avc.encoder")
        val overrides = SessionOverrides(videoEncoder = SessionOverrides.Choice("c2.qti.avc.encoder"),
            videoCodec = VideoCodec.H265, videoCodecFrom = VideoCodec.H264)
        val next = overrides.clearStaleEncoders(running, MirrorSettings(videoCodec = VideoCodec.H264))
        assertNull(next.videoEncoder)
        assertEquals(VideoCodec.H265, next.videoCodec)
    }

    @Test fun recordingWithSoundAfterASoundSwitchStillReconnectsWithAac() {
        // Picture & sound moved this session from the profile's Opus to FLAC, which an MP4 cannot hold.
        val profile = MirrorSettings(audioCodec = AudioCodec.OPUS, audioEncoder = "c2.android.opus.encoder")
        val switched = SessionOverrides(audioCodec = AudioCodec.FLAC, audioCodecFrom = AudioCodec.OPUS)
        val running = switched.applyTo(profile)
        assertEquals(AudioCodec.FLAC, running.audioCodec)
        // The reconnect for the recording runs AAC, without the Opus encoder.
        val reconnect = switched.withAacForRecording(profile).clearStaleEncoders(running, profile)
        assertEquals(AudioCodec.AAC, reconnect.applyTo(profile).audioCodec)
        assertNull(reconnect.applyTo(profile).audioEncoder)
        // A codec saved on purpose afterwards still wins, as for every session switch.
        val saved = profile.copy(audioCodec = AudioCodec.RAW, audioEncoder = null)
        assertEquals(AudioCodec.RAW, reconnect.clearStaleEncoders(reconnect.applyTo(profile), saved).applyTo(saved).audioCodec)
        // A profile already on AAC needs no switch: one chosen away from it is simply dropped.
        val aacProfile = MirrorSettings(audioCodec = AudioCodec.AAC)
        val away = SessionOverrides(audioCodec = AudioCodec.OPUS, audioCodecFrom = AudioCodec.AAC)
        val back = away.withAacForRecording(aacProfile)
        assertNull(back.audioCodec)
        assertEquals(AudioCodec.AAC, back.clearStaleEncoders(away.applyTo(aacProfile), aacProfile).applyTo(aacProfile).audioCodec)
    }
}
