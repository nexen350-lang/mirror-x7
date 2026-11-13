package com.scrcpy.remote

import com.scrcpy.remote.session.PcmWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class PcmWriterTest {
    @Test fun partialWritesRetainEveryByte() {
        val input = ByteArray(64) { it.toByte() }
        val received = ByteArrayOutputStream()
        PcmWriter.writeFully(input, input.size, { true }) { data, offset, count ->
            minOf(count, 5).also { received.write(data, offset, it) }
        }
        assertArrayEquals(input, received.toByteArray())
    }
    @Test(expected = IOException::class) fun deadTrackIsReported() {
        PcmWriter.writeFully(ByteArray(4), 4, { true }) { _, _, _ -> -6 }
    }
    @Test(expected = IOException::class) fun stalledBlockingWriteIsReported() {
        PcmWriter.writeFully(ByteArray(4), 4, { true }) { _, _, _ -> 0 }
    }
    @Test fun cancellationStopsFurtherWrites() {
        var active = true
        var calls = 0
        PcmWriter.writeFully(ByteArray(64), 64, { active }) { _, _, _ -> calls++; active = false; 4 }
        assertEquals(1, calls)
    }
}
