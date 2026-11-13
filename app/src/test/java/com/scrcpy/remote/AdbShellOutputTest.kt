package com.scrcpy.remote

import com.scrcpy.remote.adb.AdbShellTimeoutException
import com.scrcpy.remote.adb.isAdbStreamEof
import com.scrcpy.remote.adb.readShellOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

class AdbShellOutputTest {

    /** Emits [content], then ends the way libadb does: by throwing rather than returning -1. */
    private class ThrowingEofStream(
        content: ByteArray,
        private val endWith: IOException = IOException("Stream closed."),
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(content)
        override fun read(): Int = throw UnsupportedOperationException("Unused")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = delegate.read(b, off, len)
            if (read < 0) throw endWith
            return read
        }
    }

    @Test fun aThrownEndOfStreamKeepsTheOutputItAlreadyProduced() {
        // The original implementation used readBytes(), so this exception discarded everything the
        // command had printed and every shell call failed.
        assertEquals("hello", readShellOutput(ThrowingEofStream("hello".toByteArray())))
    }

    @Test fun aNormalMinusOneEndOfStreamAlsoWorks() {
        assertEquals("hello", readShellOutput(ByteArrayInputStream("hello".toByteArray())))
    }

    @Test fun outputLongerThanOneBufferIsReassembledInOrder() {
        val payload = (1..5000).joinToString(",")
        assertEquals(
            payload,
            readShellOutput(ThrowingEofStream(payload.toByteArray()), bufferBytes = 64),
        )
    }

    @Test fun utf8SplitAcrossChunkBoundariesIsNotCorrupted() {
        // Decoding per chunk would mangle any multi-byte character straddling a read boundary.
        val payload = "가".repeat(400) + "é漢"
        assertEquals(
            payload,
            readShellOutput(ThrowingEofStream(payload.toByteArray()), bufferBytes = 7),
        )
    }

    @Test fun aGenuineFailureIsReportedRatherThanTreatedAsTheEnd() {
        val failure = assertThrows(IOException::class.java) {
            readShellOutput(ThrowingEofStream("part".toByteArray(), IOException("Connection reset")))
        }
        assertEquals("Connection reset", failure.message)
    }

    @Test fun anExpiredDeadlineIsReportedInsteadOfReturningPartialOutput() {
        // The deadline is enforced by closing the stream, so its failure arrives as an IOException
        // that would otherwise look exactly like a clean end of stream.
        assertThrows(AdbShellTimeoutException::class.java) {
            readShellOutput(
                ThrowingEofStream("partial".toByteArray()),
                hasExpired = { true },
            )
        }
    }

    @Test fun outputIsCappedSoOneCommandCannotExhaustMemory() {
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill('x'.code.toByte(), off, off + len)
                return len
            }
        }
        val failure = assertThrows(IOException::class.java) {
            readShellOutput(endless, maxBytes = 4096, bufferBytes = 512)
        }
        assertTrue(failure.message, failure.message!!.contains("exceeded 4096"))
    }

    @Test fun emptyOutputIsAnEmptyString() {
        assertEquals("", readShellOutput(ThrowingEofStream(ByteArray(0))))
    }

    @Test fun theEndOfStreamSignalIsMatchedExactlyAndNotByShape() {
        assertTrue(isAdbStreamEof(IOException("Stream closed.")))
        assertFalse("A different message is a real failure", isAdbStreamEof(IOException("Stream closed")))
        assertFalse("A subclass carries more meaning", isAdbStreamEof(FileNotFoundException("Stream closed.")))
        assertFalse(isAdbStreamEof(IOException()))
    }
}
