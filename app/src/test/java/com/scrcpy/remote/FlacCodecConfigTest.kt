package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FlacCodecConfigTest {

    /** A STREAMINFO block is always 34 bytes; its contents do not matter here. */
    private fun streamInfo(size: Int = 34) = ByteArray(size) { (it + 1).toByte() }

    @Test fun theMarkerAndBlockHeaderArePutBackInFrontOfStreamInfo() {
        val restored = ScrcpyCodecs.flacCodecConfig(streamInfo())
        assertEquals(42, restored.size)
        assertEquals("fLaC", String(restored, 0, 4, Charsets.US_ASCII))
        // Last metadata block, type 0 (STREAMINFO), then its 24 bit length: 34 == 0x22.
        assertEquals(0x80, restored[4].toInt() and 0xFF)
        assertEquals(0x00, restored[5].toInt() and 0xFF)
        assertEquals(0x00, restored[6].toInt() and 0xFF)
        assertEquals(0x22, restored[7].toInt() and 0xFF)
        assertArrayEquals(streamInfo(), restored.copyOfRange(8, restored.size))
    }

    @Test fun aConfigurationThatAlreadyCarriesTheMarkerIsLeftAlone() {
        // Android's own encoder produces this form, and a device could too. Prepending a second
        // marker would break a stream that was already correct.
        val complete = "fLaC".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22) + streamInfo()
        assertSame(complete, ScrcpyCodecs.flacCodecConfig(complete))
    }

    @Test fun theLengthIsWrittenAsThreeBigEndianBytes() {
        val long = ScrcpyCodecs.flacCodecConfig(streamInfo(0x012345))
        assertEquals(0x01, long[5].toInt() and 0xFF)
        assertEquals(0x23, long[6].toInt() and 0xFF)
        assertEquals(0x45, long[7].toInt() and 0xFF)
    }

    @Test fun somethingTooShortToCarryAMarkerIsStillFramed() {
        // Never expected from a device, but it must not read past the end deciding that.
        val tiny = byteArrayOf(1, 2)
        val framed = ScrcpyCodecs.flacCodecConfig(tiny)
        assertEquals("fLaC", String(framed, 0, 4, Charsets.US_ASCII))
        assertEquals(2, framed[7].toInt() and 0xFF)
        assertEquals(10, framed.size)
    }

    @Test fun anEmptyConfigurationDoesNotThrow() {
        assertEquals(8, ScrcpyCodecs.flacCodecConfig(ByteArray(0)).size)
    }
}
