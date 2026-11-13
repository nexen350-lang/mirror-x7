package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AvcParameterSetsTest {

    private val fourByte = byteArrayOf(0, 0, 0, 1)
    private val threeByte = byteArrayOf(0, 0, 1)

    /** `type` is the low five bits of the first byte after the start code. */
    private fun nal(startCode: ByteArray, type: Int, body: ByteArray = byteArrayOf(9, 9)) =
        startCode + byteArrayOf((0x60 or type).toByte()) + body

    @Test fun theSequenceAndPictureSetsAreSeparated() {
        // The device sends both in one buffer; a muxer needs them apart, and given only the
        // first writes a file with no usable track.
        val sps = nal(fourByte, 7, byteArrayOf(1, 2, 3))
        val pps = nal(fourByte, 8, byteArrayOf(4, 5))
        val split = ScrcpyCodecs.avcParameterSets(sps + pps)
        assertNotNull(split)
        assertArrayEquals(sps, split!!.first)
        assertArrayEquals(pps, split.second)
    }

    @Test fun threeByteStartCodesAreUnderstoodToo() {
        val sps = nal(threeByte, 7)
        val pps = nal(threeByte, 8)
        val split = ScrcpyCodecs.avcParameterSets(sps + pps)
        assertNotNull(split)
        assertArrayEquals(sps, split!!.first)
        assertArrayEquals(pps, split.second)
    }

    @Test fun mixedStartCodeLengthsAreHandled() {
        val sps = nal(fourByte, 7, byteArrayOf(1))
        val pps = nal(threeByte, 8, byteArrayOf(2))
        val split = ScrcpyCodecs.avcParameterSets(sps + pps)
        assertNotNull(split)
        assertArrayEquals(sps, split!!.first)
        assertArrayEquals(pps, split.second)
    }

    @Test fun anythingElseInTheBufferIsIgnored() {
        // Some devices prepend an access unit delimiter or add SEI alongside the parameter sets.
        val delimiter = nal(fourByte, 9, byteArrayOf(0x10))
        val sps = nal(fourByte, 7)
        val sei = nal(fourByte, 6, byteArrayOf(1, 2))
        val pps = nal(fourByte, 8)
        val split = ScrcpyCodecs.avcParameterSets(delimiter + sps + sei + pps)
        assertNotNull(split)
        assertArrayEquals(sps, split!!.first)
        assertArrayEquals(pps, split.second)
    }

    @Test fun theFirstOfEachSetWins() {
        val sps = nal(fourByte, 7, byteArrayOf(1))
        val laterSps = nal(fourByte, 7, byteArrayOf(2))
        val pps = nal(fourByte, 8, byteArrayOf(3))
        val split = ScrcpyCodecs.avcParameterSets(sps + pps + laterSps)
        assertArrayEquals(sps, split!!.first)
    }

    @Test fun aConfigurationMissingEitherSetIsRefused() {
        // Refused rather than guessed at, so the caller can fall back instead of writing a file
        // that cannot be opened.
        assertNull(ScrcpyCodecs.avcParameterSets(nal(fourByte, 7)))
        assertNull(ScrcpyCodecs.avcParameterSets(nal(fourByte, 8)))
        assertNull(ScrcpyCodecs.avcParameterSets(ByteArray(0)))
        assertNull(ScrcpyCodecs.avcParameterSets(byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun aTruncatedUnitDoesNotReadPastTheEnd() {
        // A start code with no type byte after it must not throw.
        assertNull(ScrcpyCodecs.avcParameterSets(fourByte))
        assertEquals(null, ScrcpyCodecs.avcParameterSets(byteArrayOf(0, 0, 1)))
    }
}
