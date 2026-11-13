package com.scrcpy.remote

import com.scrcpy.remote.adb.SyncChannel
import com.scrcpy.remote.scrcpy.ScrcpySession
import com.scrcpy.remote.session.ResourceHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ScrcpyFileTransferTest {
    private class ReplyChannel(reply: ByteArray) : SyncChannel {
        val written = ByteArrayOutputStream()
        val closes = AtomicInteger()
        override val input = reply.inputStream()
        override val output = written
        override fun close() { closes.incrementAndGet() }
    }

    private class BlockingProbe : SyncChannel {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        private val released = CountDownLatch(1)
        val written = ByteArrayOutputStream()
        override val output = written
        override val input = object : InputStream() {
            override fun read(): Int = throw AssertionError("Expected a bulk read")

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                while (true) {
                    try {
                        released.await()
                        break
                    } catch (_: InterruptedException) {
                        // A libadb read is released by closing its channel, not its caller's job.
                    }
                }
                throw IOException("Stream closed.")
            }
        }

        override fun close() {
            closes.incrementAndGet()
            closed.countDown()
            released.countDown()
        }

        fun releaseForCleanup() = released.countDown()
    }

    @Test fun cancellingDuringDestinationStatClosesTheProbeAndStopsTheTransfer() {
        val probe = BlockingProbe()
        val owner = ResourceHandoff<Closeable>()
        val cancelled = AtomicBoolean()
        val opened = AtomicInteger()
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val worker = Thread({
            try {
                ScrcpySession.pushFile(
                    openSync = {
                        if (opened.incrementAndGet() != 1) throw IOException("Unexpected later probe")
                        probe
                    },
                    source = byteArrayOf(1).inputStream(),
                    size = 1,
                    displayName = "notes.txt",
                    isCancelled = cancelled::get,
                    onChannelOpen = owner::offer,
                    onChannelClosed = owner::release,
                )
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }, "test-preflight-cancellation").apply { isDaemon = true }
        try {
            worker.start()
            assertTrue("STAT was not reached", probe.entered.await(2, TimeUnit.SECONDS))
            cancelled.set(true)
            owner.close()
            assertTrue("Cancellation did not close the STAT channel", probe.closed.await(2, TimeUnit.SECONDS))
            assertTrue("Cancelled transfer stayed blocked", finished.await(2, TimeUnit.SECONDS))
            assertTrue(failure.get().toString(), failure.get() is InterruptedIOException)
            assertEquals("Cancellation must not start another probe or a push", 1, opened.get())
            assertEquals("The probe must be closed exactly once", 1, probe.closes.get())
            assertEquals("STAT", String(probe.written.toByteArray(), 0, 4, Charsets.US_ASCII))
        } finally {
            cancelled.set(true)
            owner.close()
            probe.releaseForCleanup()
            worker.interrupt()
            worker.join(2_000)
            assertFalse("Probe worker survived test cleanup", worker.isAlive)
        }
    }

    @Test fun anAlreadyCancelledTransferDoesNotOpenAnyChannel() {
        val channels = listOf(ReplyChannel(stat(0)), ReplyChannel(frame("OKAY", 0))).iterator()
        var opened = 0
        assertThrows(InterruptedIOException::class.java) {
            ScrcpySession.pushFile(
                openSync = { opened++; channels.next() },
                source = byteArrayOf(1).inputStream(),
                size = 1,
                displayName = "notes.txt",
                isCancelled = { true },
            )
        }
        assertEquals("Cancellation must be checked before the first STAT", 0, opened)
    }

    @Test fun aProbeReturnedAfterCancellationIsClosedOnceWithoutWritingStat() {
        val probe = ReplyChannel(stat(0))
        val push = ReplyChannel(frame("OKAY", 0))
        val owner = ResourceHandoff<Closeable>()
        var cancelled = false
        var opened = 0
        try {
            assertThrows(InterruptedIOException::class.java) {
                ScrcpySession.pushFile(
                    openSync = {
                        opened++
                        if (opened == 1) {
                            cancelled = true
                            owner.close()
                            probe
                        } else push
                    },
                    source = byteArrayOf(1).inputStream(),
                    size = 1,
                    displayName = "notes.txt",
                    isCancelled = { cancelled },
                    onChannelOpen = owner::offer,
                    onChannelClosed = owner::release,
                )
            }
            assertEquals("Cancellation after opening must not start a push", 1, opened)
            assertEquals("The late probe must be closed exactly once", 1, probe.closes.get())
            assertEquals("A cancelled late open must not issue STAT", 0, probe.written.size())
            assertEquals(0, push.written.size())
        } finally {
            owner.close()
        }
    }

    @Test fun anOccupiedDestinationStillSelectsTheNextNameAndPushesSuccessfully() {
        val channels = listOf(
            ReplyChannel(stat(0b1000_000_110_100_100)),
            ReplyChannel(stat(0)),
            ReplyChannel(frame("OKAY", 0)),
        )
        val pending = channels.iterator()
        val owner = ResourceHandoff<Closeable>()
        try {
            val path = ScrcpySession.pushFile(
                openSync = { pending.next() },
                source = byteArrayOf(1, 2, 3).inputStream(),
                size = 3,
                displayName = "notes.txt",
                onChannelOpen = owner::offer,
                onChannelClosed = owner::release,
            )
            assertEquals("/sdcard/Download/notes (1).txt", path)
            assertEquals(listOf("STAT", "STAT", "SEND"), channels.map {
                String(it.written.toByteArray(), 0, 4, Charsets.US_ASCII)
            })
            assertFalse(pending.hasNext())
        } finally {
            owner.close()
        }
        assertTrue(channels.all { it.closes.get() == 1 })
    }

    @Test fun cancelledRegistrationClosesTheNewChannelAndStopsProbing() {
        val probe = ReplyChannel(stat(0))
        val expected = CancellationException("Cancelled while registering the channel")
        var offered: Closeable? = null
        val released = mutableListOf<Closeable>()
        var opened = 0
        val failure = assertThrows(CancellationException::class.java) {
            ScrcpySession.pushFile(
                openSync = { opened++; probe },
                source = byteArrayOf(1).inputStream(),
                size = 1,
                displayName = "notes.txt",
                onChannelOpen = { offered = it; throw expected },
                onChannelClosed = { released += it },
            )
        }
        assertSame(expected, failure)
        assertEquals(1, opened)
        assertEquals(1, probe.closes.get())
        assertEquals(0, probe.written.size())
        assertEquals(1, released.size)
        assertSame("Release must name the same wrapper that was offered", offered, released.single())
    }

    private fun stat(mode: Int) = frame("STAT", mode) + ByteArray(8)

    private fun frame(id: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII)).putInt(value).array()
}
