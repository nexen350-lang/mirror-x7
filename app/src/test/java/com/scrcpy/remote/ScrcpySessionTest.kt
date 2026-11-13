package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.ScrcpyServerRefusedException
import com.scrcpy.remote.scrcpy.ScrcpySession
import com.scrcpy.remote.session.ConnectionRecoveryPolicy
import com.scrcpy.remote.session.StartupResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ScrcpySessionTest {
    @Test
    fun closingTransportFirstReleasesABlockedWriterBeforeLogicalStreamsClose() {
        assertTransportClosesBeforeBlockedStreams { stream, shell, transport ->
            ScrcpySession.closeTransportResources(listOf(stream), shell, transport)
        }
    }

    @Test
    fun failedOrCancelledStartupDisconnectsBeforeClosingBlockedLogicalStreams() {
        assertTransportClosesBeforeBlockedStreams { stream, shell, transport ->
            val startup = StartupResources()
            startup.trackTransport(transport)
            startup.track(shell)
            startup.track(stream)
            startup.close()
        }
    }

    private fun assertTransportClosesBeforeBlockedStreams(
        close: (Closeable, Closeable, Closeable) -> Unit,
    ) {
        val writeLock = ReentrantLock()
        val writerEntered = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val firstClose = AtomicReference<String>()
        val failure = AtomicReference<Throwable>()
        val order = mutableListOf<String>()

        fun closing(name: String) {
            order += name
            if (firstClose.compareAndSet(null, name)) closeAttempted.countDown()
        }

        val stream = Closeable {
            closing("stream")
            writeLock.withLock { }
        }
        val shell = Closeable { closing("shell") }
        val transport = Closeable {
            closing("transport")
            disconnected.countDown()
        }
        val writer = Thread({
            try {
                writeLock.withLock {
                    writerEntered.countDown()
                    // A blocked socket write holds libadb's lock and ignores thread interruption.
                    while (true) {
                        try {
                            disconnected.await()
                            break
                        } catch (_: InterruptedException) {
                            // Only physical transport closure releases this simulated I/O.
                        }
                    }
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }, "test-blocked-adb-write").apply { isDaemon = true }
        val closer = Thread({
            try {
                close(stream, shell, transport)
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            } finally {
                closeFinished.countDown()
            }
        }, "test-session-close").apply { isDaemon = true }
        try {
            writer.start()
            assertTrue("Writer did not acquire the transport lock", writerEntered.await(10, TimeUnit.SECONDS))
            closer.start()
            assertTrue("Resource cleanup did not start", closeAttempted.await(10, TimeUnit.SECONDS))
            assertEquals("Logical close would wait behind the blocked writer", "transport", firstClose.get())
            assertTrue("Transport closure did not release cleanup", closeFinished.await(10, TimeUnit.SECONDS))
            writer.join(10_000)
            closer.join(10_000)
            assertNull(failure.get())
            assertEquals(listOf("transport", "stream", "shell"), order)
        } finally {
            disconnected.countDown()
            writer.interrupt()
            closer.interrupt()
            writer.join(10_000)
            closer.join(10_000)
            assertFalse("Writer survived test cleanup", writer.isAlive)
            assertFalse("Closer survived test cleanup", closer.isAlive)
        }
    }

    @Test
    fun anAlreadyAnnotatedSocketTimeoutStillClassifiesTheServerRefusal() {
        val log = "[server] ERROR: Camera mirroring is not supported before Android 12"
        val heading = ScrcpySession.SERVER_OUTPUT_HEADING
        val original = IOException(
            "Timed out opening the video socket\n\n$heading\n$log",
            ConnectException("Stream open actively rejected by remote peer."),
        )

        val reported = withServerLog(original, log)

        assertTrue("Server refusal retained a retryable transport type", reported is ScrcpyServerRefusedException)
        assertSame(original, reported.cause)
        assertEquals("Diagnostic output was duplicated", 2, checkNotNull(reported.message).split(heading).size)
        assertEquals(ConnectionRecoveryPolicy.FailureKind.INVALID_INPUT, ConnectionRecoveryPolicy.classify(reported))
        assertTrue(
            ConnectionRecoveryPolicy.onFailure(ConnectionRecoveryPolicy.State(), reported, enabled = true)
                is ConnectionRecoveryPolicy.Decision.Stop,
        )
    }

    @Test
    fun anUnannotatedServerRefusalKeepsItsCauseAndDiagnostic() {
        val original = IOException("Stream closed.")
        val log = "[server] ERROR: Could not create display"

        val reported = withServerLog(original, log)

        assertTrue(reported is ScrcpyServerRefusedException)
        assertSame(original, reported.cause)
        assertEquals(
            "Stream closed.\n\n${ScrcpySession.SERVER_OUTPUT_HEADING}\n$log",
            reported.message,
        )
        assertEquals(ConnectionRecoveryPolicy.FailureKind.INVALID_INPUT, ConnectionRecoveryPolicy.classify(reported))
    }

    @Test
    fun alreadyAnnotatedInformationalOutputDoesNotTurnANetworkFailureIntoARefusal() {
        val log = "[server] INFO: Device: example"
        val original = java.net.SocketTimeoutException(
            "Timed out opening the video socket\n\n${ScrcpySession.SERVER_OUTPUT_HEADING}\n$log",
        ).apply { initCause(ConnectException("Connection refused")) }

        val reported = withServerLog(original, log)

        assertSame(original, reported)
        assertEquals(
            ConnectionRecoveryPolicy.FailureKind.NOT_RESPONDING,
            ConnectionRecoveryPolicy.classify(reported),
        )
    }

    @Test(timeout = 10_000)
    fun aRefusalTheServerWritesAsItsSocketsCloseIsStillReported() {
        // The server writes why it refused an option and exits, and adbd may close the video
        // socket before it relays that output. Closing the transport then lost the reason.
        val transportClosed = CountDownLatch(1)
        val startup = StartupResources()
        startup.trackTransport(Closeable { transportClosed.countDown() })
        val log = StringBuilder("[server] INFO: Device: example\n")
        val refusal = "[server] ERROR: Video encoder 'missing' for h264 not found"
        val reader = Thread({
            // The server writes its refusal while the transport is still open. Decide on the
            // transport's state at this instant instead of on a timed wait, which a stalled
            // scheduler could expire before the startup code even ran.
            if (transportClosed.count > 0L) synchronized(log) { log.appendLine(refusal) }
        }, "test-server-log").apply { isDaemon = true; start() }

        val reported = ScrcpySession.startupFailure(
            IOException("Stream closed."), startup, log, reader, graceMillis = 5_000)

        assertTrue("The server's refusal was lost: ${reported.message}", reported is ScrcpyServerRefusedException)
        assertTrue(reported.message, checkNotNull(reported.message).contains(refusal))
        assertEquals("The transport was kept open", 0L, transportClosed.count)
        reader.join(2_000)
    }

    @Test(timeout = 10_000)
    fun aCancelledStartDoesNotWaitForTheServer() {
        val startup = StartupResources()
        val finish = CountDownLatch(1)
        // A server still running, with nothing more to say, while the user has already gone.
        val reader = Thread({ finish.await() }, "test-server-log").apply { isDaemon = true; start() }
        try {
            startup.close()
            val started = System.nanoTime()

            ScrcpySession.startupFailure(
                InterruptedIOException("Connection cancelled"), startup, StringBuilder(), reader, graceMillis = 5_000)

            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue("A cancelled start waited $elapsedMillis ms for the server", elapsedMillis < 4_000)
        } finally {
            finish.countDown()
            reader.join(2_000)
        }
    }

    @Test(timeout = 10_000)
    fun aServerThatKeepsRunningHoldsAFailedStartOnlyBriefly() {
        val released = CountDownLatch(1)
        val startup = StartupResources()
        startup.trackTransport(Closeable { released.countDown() })
        // A server that neither exits nor writes: its output ends only with the transport.
        val reader = Thread({ released.await() }, "test-server-log").apply { isDaemon = true; start() }
        val original = java.net.SocketTimeoutException("Timed out opening the video socket")
        val started = System.nanoTime()

        val reported = ScrcpySession.startupFailure(
            original, startup, StringBuilder("[server] INFO: Device: example\n"), reader, graceMillis = 300)

        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("A failed start waited $elapsedMillis ms", elapsedMillis < 8_000)
        assertEquals("The transport was not released", 0L, released.count)
        reader.join(2_000)
        assertFalse("The server's output outlived its transport", reader.isAlive)
        assertFalse("Informational output became a refusal", reported is ScrcpyServerRefusedException)
    }

    @Test(timeout = 10_000)
    fun aStartInterruptedWhileWaitingStopsAndStaysInterrupted() {
        val startup = StartupResources()
        val finish = CountDownLatch(1)
        val reader = Thread({ finish.await() }, "test-server-log").apply { isDaemon = true; start() }
        val outcome = AtomicReference<Pair<Long, Boolean>>()
        val worker = Thread({
            val started = System.nanoTime()
            ScrcpySession.startupFailure(
                IOException("Stream closed."), startup, StringBuilder(), reader, graceMillis = 5_000)
            outcome.set((System.nanoTime() - started) / 1_000_000 to Thread.currentThread().isInterrupted)
        }, "test-startup").apply { isDaemon = true; start() }
        try {
            val until = System.nanoTime() + 10_000_000_000L
            while (worker.state != Thread.State.TIMED_WAITING && worker.isAlive && System.nanoTime() < until) {
                Thread.sleep(10)
            }
            assertEquals("The start never waited for the server's output", Thread.State.TIMED_WAITING, worker.state)
            worker.interrupt()
            worker.join(8_000)
            val (elapsedMillis, interrupted) = checkNotNull(outcome.get()) { "The start was still waiting" }
            assertTrue("An interrupted start waited $elapsedMillis ms", elapsedMillis < 4_000)
            assertTrue("The interrupt was swallowed", interrupted)
        } finally {
            finish.countDown()
            reader.join(2_000)
        }
    }

    private fun withServerLog(error: Exception, log: String): Exception {
        val method = ScrcpySession.Companion::class.java.getDeclaredMethod(
            "withServerLog", Exception::class.java, StringBuilder::class.java,
        ).apply { isAccessible = true }
        return method.invoke(ScrcpySession.Companion, error, StringBuilder(log)) as Exception
    }

    @Test
    fun closeContinuesPastAFailingResource() {
        val closed = mutableListOf<String>()
        ScrcpySession.closeTransportResources(
            listOf(Closeable { closed += "s1"; throw IllegalStateException("x") }, Closeable { closed += "s2" }),
            Closeable { closed += "shell" },
            Closeable { closed += "conn"; throw Error("y") },
        )
        assertEquals(listOf("conn", "s1", "s2", "shell"), closed)
    }

    @Test
    fun handshakeEofIsTransientAndWrongByteIsNot() {
        val eof = org.junit.Assert.assertThrows(java.io.EOFException::class.java) {
            ScrcpySession.readDummyByte(java.io.ByteArrayInputStream(ByteArray(0)))
        }
        assertEquals(ConnectionRecoveryPolicy.FailureKind.TRANSIENT_TRANSPORT, ConnectionRecoveryPolicy.classify(eof))
        org.junit.Assert.assertThrows(IOException::class.java) {
            ScrcpySession.readDummyByte(java.io.ByteArrayInputStream(byteArrayOf(7)))
        }
        ScrcpySession.readDummyByte(java.io.ByteArrayInputStream(byteArrayOf(0)))
    }

    @Test
    fun sentFileReportsScanRequestNotIndexing() {
        val sent = ScrcpySession.SentFile("/sdcard/a", true)
        assertTrue(sent.scanRequested)
    }
}
