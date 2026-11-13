package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.PlaybackDuplication
import com.scrcpy.remote.scrcpy.ScrcpyOptions
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 13 refuses the scrcpy 5.0 server's playback capture that keeps sound playing on the
 * device: its rule names voice calls, and Android 13's shell lacks the permission to capture them
 * while they still play. The session reads the device's Android version only when it asks for that
 * capture, so the screen can say why there is no sound and offer what works.
 */
class PlaybackDuplicationTest {
    private val keptOnDevice = ScrcpyOptions(audioSource = AudioSource.PLAYBACK, audioDup = true)

    @Test fun onlyPlaybackCaptureKeptOnTheDeviceAsksForTheAndroidVersion() {
        assertTrue(PlaybackDuplication.needsTargetSdk(keptOnDevice))
        // Every other session starts exactly as before, with no extra question for the device.
        assertFalse(PlaybackDuplication.needsTargetSdk(keptOnDevice.copy(audioDup = false)))
        assertFalse(PlaybackDuplication.needsTargetSdk(keptOnDevice.copy(audio = false)))
        // Duplication is only sent with playback capture, so it changes nothing for other sources.
        assertFalse(PlaybackDuplication.needsTargetSdk(keptOnDevice.copy(audioSource = AudioSource.OUTPUT)))
        assertFalse(PlaybackDuplication.needsTargetSdk(keptOnDevice.copy(audioSource = AudioSource.MIC)))
    }

    @Test fun androidThirteenRefusesSoundKeptOnTheDevice() {
        assertTrue(PlaybackDuplication.refused(33, AudioSource.PLAYBACK, audioDup = true))
    }

    @Test fun androidFourteenAndNewerShareIt() {
        for (sdk in listOf(34, 35, 36, 37)) {
            assertFalse("API $sdk", PlaybackDuplication.refused(sdk, AudioSource.PLAYBACK, audioDup = true))
        }
    }

    @Test fun otherFailuresKeepTheGeneralExplanation() {
        // Without duplication Android 13 captures, so a failure there has another cause.
        assertFalse(PlaybackDuplication.refused(33, AudioSource.PLAYBACK, audioDup = false))
        assertFalse(PlaybackDuplication.refused(33, AudioSource.OUTPUT, audioDup = true))
        // Before Android 13 there is no playback capture at all, which its setting already says.
        assertFalse(PlaybackDuplication.refused(32, AudioSource.PLAYBACK, audioDup = true))
        // A version that could not be read proves nothing.
        assertFalse(PlaybackDuplication.refused(null, AudioSource.PLAYBACK, audioDup = true))
    }

    @Test fun theVersionIsReadFromGetpropOutput() {
        assertEquals(33, PlaybackDuplication.parseSdk("33\n"))
        assertEquals(36, PlaybackDuplication.parseSdk(" 36\r\n"))
        assertNull(PlaybackDuplication.parseSdk(""))
        assertNull(PlaybackDuplication.parseSdk("error: closed"))
        assertNull(PlaybackDuplication.parseSdk("0"))
    }

}
