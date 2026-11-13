package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import com.scrcpy.remote.scrcpy.ScrcpyCodecs.AacAudioConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AacAudioConfigTest {
    private fun parse(vararg bytes: Int) = ScrcpyCodecs.aacAudioConfig(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test fun whatTheDeviceSendsForItsDefaultSound() {
        // AAC-LC, 48 kHz, stereo: what scrcpy-server encodes.
        assertEquals(AacAudioConfig(48_000, 2), parse(0x11, 0x90))
    }

    @Test fun otherRatesAndChannelCounts() {
        assertEquals(AacAudioConfig(44_100, 2), parse(0x12, 0x10))
        assertEquals(AacAudioConfig(48_000, 1), parse(0x11, 0x88))
    }

    @Test fun anExplicitSampleRate() {
        // Frequency index 15 is followed by the rate itself in 24 bits.
        assertEquals(AacAudioConfig(48_000, 2), parse(0x17, 0x80, 0x5D, 0xC0, 0x10))
    }

    @Test fun whatAnMp4TrackCannotBeDescribedFrom() {
        assertNull("Too short", parse(0x11))
        assertNull("Empty", parse())
        assertNull("Channels given by a program configuration element", parse(0x11, 0x80))
        assertNull("Reserved frequency index", parse(0x16, 0x90))
        assertNull("Explicit rate cut short", parse(0x17, 0x80, 0x5D))
    }
}
