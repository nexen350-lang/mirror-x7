package com.scrcpy.remote

import com.scrcpy.remote.adb.AdbSync
import com.scrcpy.remote.adb.SyncChannel
import com.scrcpy.remote.adb.withInactivityDeadline
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/**
 * The ADB `sync:` framing used to deploy the scrcpy server.
 *
 * Worth pinning down because a malformed push corrupts the server jar silently: the failure would
 * surface much later as an unexplained session start failure.
 */
class AdbSyncTest {

    /** Stand-in for the ADB transport, capturing what we write and replaying a canned response. */
    private class FakeChannel(response: ByteArray) : SyncChannel {
        val written = ByteArrayOutputStream()
        override val output: OutputStream = written
        override val input: InputStream = ByteArrayInputStream(response)
        override fun close() = Unit
    }

    private fun okay(): ByteArray = header("OKAY", 0)

    private fun header(id: String, value: Int): ByteArray =
        ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII))
            .putInt(value)
            .array()

    private fun readId(bytes: ByteArray, offset: Int) =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun readLe(bytes: ByteArray, offset: Int) =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    @Test
    fun sendsPathModeThenDataThenDone() {
        val stream = FakeChannel(okay())
        val content = ByteArray(10) { it.toByte() }

        AdbSync.push({ stream }, content, "/data/local/tmp/server.jar")

        val out = stream.written.toByteArray()
        // SEND header, then "path,mode".
        assertEquals("SEND", readId(out, 0))
        val pathLength = readLe(out, 4)
        val pathAndMode = String(out, 8, pathLength, Charsets.UTF_8)
        assertEquals("/data/local/tmp/server.jar,420", pathAndMode)

        // 420 decimal is 0644 octal, which is what adbd parses it as.
        assertEquals("644", 420.toString(8))

        var offset = 8 + pathLength
        assertEquals("DATA", readId(out, offset))
        assertEquals(content.size, readLe(out, offset + 4))
        offset += 8
        assertArrayEquals(content, out.copyOfRange(offset, offset + content.size))
        offset += content.size

        assertEquals("DONE", readId(out, offset))
    }

    @Test
    fun splitsLargePayloadsIntoChunks() {
        val stream = FakeChannel(okay())
        // Two full chunks plus a remainder; adbd rejects DATA blocks over 64k.
        val content = ByteArray(64 * 1024 * 2 + 5)

        AdbSync.push({ stream }, content, "/tmp/x")

        val out = stream.written.toByteArray()
        var offset = 8 + readLe(out, 4)
        val chunkSizes = mutableListOf<Int>()
        while (readId(out, offset) == "DATA") {
            val size = readLe(out, offset + 4)
            chunkSizes += size
            offset += 8 + size
        }

        assertEquals(listOf(65536, 65536, 5), chunkSizes)
        assertEquals("DONE", readId(out, offset))
        assertTrue(chunkSizes.all { it <= 65536 })
    }

    @Test(expected = IOException::class)
    fun failResponseIsReportedAsAnError() {
        val message = "no such directory".toByteArray(Charsets.UTF_8)
        val response = header("FAIL", message.size) + message
        AdbSync.push({ FakeChannel(response) }, ByteArray(4), "/bad/path")
    }

    @Test(expected = IOException::class)
    fun unexpectedResponseIsRejected() {
        AdbSync.push({ FakeChannel(header("WHAT", 0)) }, ByteArray(4), "/tmp/x")
    }

    @Test
    fun statParsesModeSizeAndTime() {
        val response = header("STAT", 0b1000_000_110_100_100) +
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(733706).putInt(1234).array()

        val stat = AdbSync.stat({ FakeChannel(response) }, "/data/local/tmp/server.jar")

        assertTrue(stat.exists)
        assertEquals(733706, stat.size)
        assertEquals(1234, stat.modifiedAt)
    }

    @Test
    fun statReportsMissingFiles() {
        // adbd answers with a zeroed stat rather than an error when the path is absent.
        val response = header("STAT", 0) + ByteArray(8)

        val stat = AdbSync.stat({ FakeChannel(response) }, "/nope")

        assertTrue(!stat.exists)
        assertEquals(0, stat.size)
    }

    @Test
    fun interruptedExistenceProbesDoNotBecomeUnknownDestinations() {
        for (error in listOf(
            InterruptedException("Interrupted while opening"),
            InterruptedIOException("Transfer cancelled"),
            CancellationException("Cancelled while registering"),
        )) {
            val failure = assertThrows(error.javaClass) {
                AdbSync.exists({ throw error }, "/sdcard/Download/notes.txt")
            }
            assertSame(error, failure)
        }
    }

    @Test
    fun anOrdinaryExistenceProbeFailureStillMeansUnknown() {
        for (error in listOf(IOException("Device stopped answering"), SocketTimeoutException("Read timed out"))) {
            assertNull(AdbSync.exists({ throw error }, "/sdcard/Download/notes.txt"))
        }
    }

    /** A device that accepted the sync request and then stopped answering. */
    private class StalledChannel : SyncChannel {
        val closed = java.util.concurrent.CountDownLatch(1)
        override val output: OutputStream = ByteArrayOutputStream()
        override val input: InputStream = object : InputStream() {
            override fun read(): Int = read(ByteArray(1), 0, 1)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                closed.await()
                throw IOException("Stream closed")
            }
        }
        override fun close() = closed.countDown()
    }

    @Test(timeout = 10_000)
    fun aSyncRequestTheDeviceStopsAnsweringEndsWithATimeout() {
        val stalled = StalledChannel()
        val started = System.nanoTime()
        assertThrows(SocketTimeoutException::class.java) {
            AdbSync.stat({ stalled.withInactivityDeadline(200) }, "/sdcard/Download/notes.txt")
        }
        assertEquals("The stalled channel is closed, not leaked", 0L, stalled.closed.count)
        assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(5))
    }

    @Test(timeout = 10_000)
    fun steadyProgressIsNotMistakenForAStall() {
        // Each 250 ms read is far below the 2 s deadline (headroom for a GC/scheduling pause under a
        // full build) while the 16 reads (~4 s) together exceed it.
        val reply = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .put("STAT".toByteArray(Charsets.US_ASCII)).putInt(0x81a4).putInt(42).putInt(7).array()
        val slow = object : SyncChannel {
            override val output: OutputStream = ByteArrayOutputStream()
            override val input: InputStream = object : InputStream() {
                var position = 0
                override fun read(): Int {
                    Thread.sleep(250)
                    return if (position < reply.size) reply[position++].toInt() and 0xff else -1
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    val value = read()
                    if (value < 0) return -1
                    b[off] = value.toByte()
                    return 1
                }
            }
            override fun close() = Unit
        }
        val started = System.nanoTime()
        val stat = AdbSync.stat({ slow.withInactivityDeadline(2_000) }, "/x")
        assertEquals(42, stat.size)
        assertTrue("The whole response must exceed one inactivity deadline",
            System.nanoTime() - started > java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2_000))
    }

    @Test
    fun aConnectionLostAfterDoneLeavesTheResultUnconfirmed() {
        // adbd has the whole file once it reads DONE; only its OKAY is missing.
        val stream = FakeChannel(ByteArray(0))
        val error = assertThrows(IOException::class.java) {
            AdbSync.push({ stream }, ByteArray(10), "/sdcard/Download/a.bin")
        }
        assertTrue(error.toString(), error is AdbSync.SyncCommitUnconfirmedException)
        assertEquals("DONE", readId(stream.written.toByteArray(), stream.written.size() - 8))
    }

    @Test
    fun aRefusalOrALossBeforeDoneIsStillADefiniteFailure() {
        val message = "disk full".toByteArray()
        val refused = FakeChannel(header("FAIL", message.size) + message)
        val failure = assertThrows(IOException::class.java) { AdbSync.push({ refused }, ByteArray(10), "/x") }
        assertFalse(failure is AdbSync.SyncCommitUnconfirmedException)

        val broken = object : SyncChannel {
            override val output: OutputStream = object : OutputStream() {
                var bytes = 0
                override fun write(b: Int) { if (++bytes > 20) throw IOException("Broken pipe") }
            }
            override val input: InputStream = ByteArrayInputStream(okay())
            override fun close() = Unit
        }
        val lost = assertThrows(IOException::class.java) { AdbSync.push({ broken }, ByteArray(10), "/x") }
        assertFalse(lost is AdbSync.SyncCommitUnconfirmedException)
    }

    @Test
    fun theCommitIsAnnouncedJustBeforeDoneAndNeverByAnAbandonedTransfer() {
        // The announcement is what lets a session that ends mid-transfer say whether the device
        // may have the file: before DONE adbd discards a partial one, after it the outcome is open.
        val stream = FakeChannel(okay())
        val writtenAtCommit = mutableListOf<Int>()
        AdbSync.push({ stream }, ByteArrayInputStream(ByteArray(10)), 10, "/sdcard/Download/a.bin",
            onCommit = { writtenAtCommit += stream.written.size() })
        val out = stream.written.toByteArray()
        assertEquals("Announced exactly once", 1, writtenAtCommit.size)
        assertEquals("Everything but DONE was written first", out.size - 8, writtenAtCommit.single())
        assertEquals("DONE", readId(out, writtenAtCommit.single()))

        var emptyCommits = 0
        AdbSync.push({ FakeChannel(okay()) }, ByteArrayInputStream(ByteArray(0)), 0, "/x",
            onCommit = { emptyCommits++ })
        assertEquals("An empty file is committed by DONE alone", 1, emptyCommits)

        var abandonedCommits = 0
        assertThrows(IOException::class.java) {
            AdbSync.push({ FakeChannel(okay()) }, ByteArrayInputStream(ByteArray(11)), 10, "/x",
                onCommit = { abandonedCommits++ })
        }
        var cancelled = false
        assertThrows(InterruptedIOException::class.java) {
            AdbSync.push({ FakeChannel(okay()) }, ByteArrayInputStream(ByteArray(10)), 10, "/x",
                onProgress = { cancelled = true }, isCancelled = { cancelled }, onCommit = { abandonedCommits++ })
        }
        assertEquals("A transfer abandoned before DONE never claims a commit", 0, abandonedCommits)
    }
}
