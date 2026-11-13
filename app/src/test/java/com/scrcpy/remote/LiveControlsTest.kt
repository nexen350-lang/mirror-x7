package com.scrcpy.remote

import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.session.LiveTyping
import com.scrcpy.remote.session.LiveTyping.Edit
import com.scrcpy.remote.session.SessionOverrides
import com.scrcpy.remote.session.VideoFrameMeter
import com.scrcpy.remote.ui.RemoteKey
import com.scrcpy.remote.ui.formatKbps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveControlsTest {

    @Test fun keysAreNeverMergedWithTheEditsAroundThem() {
        val tab = Edit.Key(61)
        val edits = listOf(Edit.Insert("a"), tab, Edit.Insert("b"), Edit.Move(-1), tab, Edit.Move(1))
        assertEquals(edits, LiveTyping.coalesce(edits))
    }

    @Test fun cursorKeysRestartTheLiveFieldButEscDoesNot() {
        assertFalse(RemoteKey.ESC.movesCursor)
        assertTrue(RemoteKey.entries.filter { it != RemoteKey.ESC }.all { it.movesCursor })
        assertTrue(RemoteKey.SELECT_ALL.metaState and android.view.KeyEvent.META_CTRL_ON != 0)
    }

    @Test fun aSessionAudioSourceOverridesTheSavedOne() {
        val saved = MirrorSettings(audioSource = AudioSource.PLAYBACK, audioDup = true)
        val run = SessionOverrides(audioSource = AudioSource.MIC_CAMCORDER).applyTo(saved)
        assertEquals(AudioSource.MIC_CAMCORDER, run.audioSource)
        // Duplication belongs to playback capture alone.
        assertFalse(run.audioDup)
        val back = SessionOverrides(audioSource = AudioSource.PLAYBACK).applyTo(saved)
        assertTrue(back.audioDup)
    }

    @Test fun theMeterReportsKilobitsOverTheLastSecond() {
        var now = 0L
        val meter = VideoFrameMeter { now }
        repeat(10) { meter.received(12_500); now += 100_000_000 } // 125 kB over 1 s
        assertEquals(1000, meter.snapshot().receivedKbps)
        now += 2_000_000_000
        assertEquals(0, meter.snapshot().receivedKbps)
    }

    @Test fun bitratesReadNaturally() {
        assertEquals("850 kb/s", formatKbps(850))
        assertEquals("8.2 Mb/s", formatKbps(8_200))
    }

    /** Verbatim server error output from scrcpy 5.0 on a locked Android 11 device. */
    @Test fun aLockedAndroid11DeviceIsRecognisedFromTheServersOwnWords() {
        val output = "[server] ERROR: Failed to start audio capture\n" +
            "[server] ERROR: On Android 11, audio capture must be started in the foreground, make sure that the device is unlocked when starting scrcpy.\n"
        assertTrue(com.scrcpy.remote.session.AudioRefusal.lockedDevice(output))
        assertFalse(com.scrcpy.remote.session.AudioRefusal.lockedDevice("[server] ERROR: Failed to start audio capture\n"))
    }
}
