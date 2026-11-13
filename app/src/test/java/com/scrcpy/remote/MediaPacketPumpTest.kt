package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.MediaInputException
import com.scrcpy.remote.scrcpy.MediaPacketLimits
import com.scrcpy.remote.scrcpy.MediaProtocolException
import com.scrcpy.remote.scrcpy.PacketSource
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import com.scrcpy.remote.scrcpy.StreamPacket
import com.scrcpy.remote.session.FrameLatencyMeter
import com.scrcpy.remote.session.LatencyStats
import com.scrcpy.remote.session.MediaPacketPump
import com.scrcpy.remote.session.VideoInputPacer
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MediaPacketPumpTest {
    private fun frame(value: Int) = StreamPacket.Media(0, false, true, byteArrayOf(value.toByte()), 1)

    @Test fun aFrameFinishingInAQuietStreamIsNotHeldBackByTheIdleWait() {
        // The backoff exists so a session with nothing to show stops waking up. But the wait is
        // picked from the quiet-round count, so a round that had just handed back a frame used to
        // pick the idle wait anyway and sit on the next frame for the whole of it.
        val source = object : PacketSource {
            override val codecId = 0
            override fun readPacket(): StreamPacket {
                CountDownLatch(1).await()
                error("unreachable")
            }
        }
        val produceNow = java.util.concurrent.atomic.AtomicBoolean(false)
        val unsetNanos = Long.MIN_VALUE
        val producedAt = AtomicLong(unsetNanos)
        val nextDrainAt = AtomicLong(unsetNanos)
        val measured = CountDownLatch(1)
        val pump = MediaPacketPump(source,
            consume = { _, _ -> true },
            drain = {
                val now = System.nanoTime()
                if (producedAt.get() != unsetNanos && nextDrainAt.compareAndSet(unsetNanos, now)) measured.countDown()
                // Reports one frame, on the first round after the stream has gone quiet.
                if (produceNow.compareAndSet(true, false)) { producedAt.set(now); true } else false
            },
            release = {}, onError = {}, name = "test-quiet")
        try {
            pump.start()
            // Long enough for the quiet-round count to climb past the active threshold, so the
            // wait in force when the frame appears is the idle one.
            // The property is that the drain after a frame is NOT delayed by the 50 ms idle wait.
            // A scheduler stall can only inflate a gap, so any attempt showing a short gap proves
            // it; only if every attempt is slow is the product at fault.
            var bestGap = Long.MAX_VALUE
            for (attempt in 1..5) {
                producedAt.set(unsetNanos)
                nextDrainAt.set(unsetNanos)
                Thread.sleep(400)
                producedAt.set(unsetNanos)
                nextDrainAt.set(unsetNanos)
                produceNow.set(true)
                val deadline = System.nanoTime() + 20_000_000_000L
                while (nextDrainAt.get() == unsetNanos && System.nanoTime() < deadline) Thread.sleep(1)
                assertTrue("No drain followed the produced frame", nextDrainAt.get() != unsetNanos)
                bestGap = minOf(bestGap, (nextDrainAt.get() - producedAt.get()) / 1_000_000)
                if (bestGap < 30) break
            }
            assertTrue(
                "The next drain came $bestGap ms after a frame was produced",
                bestGap < 30,
            )
        } finally { pump.close(); assertTrue(pump.awaitStopped(2_000)) }
    }

    @Test fun outputDrainsEvenWhenTheNetworkHasNoNextPacket() {
        val submitted = CountDownLatch(1)
        val allowOutput = CountDownLatch(1)
        val rendered = CountDownLatch(1)
        val source = object : PacketSource {
            override val codecId = 0
            var first = true
            override fun readPacket(): StreamPacket {
                if (first) { first = false; return frame(7) }
                CountDownLatch(1).await()
                error("unreachable")
            }
        }
        val errors = AtomicReference<Exception>()
        val pump = MediaPacketPump(source,
            consume = { _, _ -> submitted.countDown(); true },
            drain = {
                val ready = submitted.count == 0L && allowOutput.count == 0L
                if (ready) rendered.countDown()
                ready
            },
            release = {}, onError = { errors.set(it) }, name = "test-video")
        try {
            pump.start()
            assertTrue(submitted.await(20, TimeUnit.SECONDS))
            allowOutput.countDown()
            assertTrue("No second network packet should be necessary", rendered.await(20, TimeUnit.SECONDS))
            assertNull(errors.get())
        } finally { pump.close(); assertTrue(pump.awaitStopped(2_000)) }
    }

    @Test fun anUnavailableInputBufferDoesNotDiscardThePacket() {
        val attempts = AtomicInteger()
        val accepted = CountDownLatch(1)
        val source = object : PacketSource {
            override val codecId = 0
            var first = true
            override fun readPacket(): StreamPacket {
                if (first) { first = false; return frame(42) }
                CountDownLatch(1).await()
                error("unreachable")
            }
        }
        val errors = AtomicReference<Exception>()
        val pump = MediaPacketPump(source, consume = { packet, _ ->
            assertEquals(42, (packet as StreamPacket.Media).data[0].toInt())
            if (attempts.incrementAndGet() >= 3) { accepted.countDown(); true } else false
        }, drain = { false }, release = {}, onError = { errors.set(it) }, name = "test-input")
        try {
            pump.start()
            assertTrue(accepted.await(20, TimeUnit.SECONDS))
            assertEquals(3, attempts.get())
            assertNull(errors.get())
        } finally { pump.close(); assertTrue(pump.awaitStopped(2_000)) }
    }

    @Test fun receiptTimingSurvivesAFullSynchronizedInputQueue() {
        val meter = FrameLatencyMeter()
        val capacity = VideoInputPacer.MAX_QUEUED_PACKETS
        val receivedCount = capacity + 2 // queued packets, pending input, and the reader's next copy
        val filled = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val allowInput = AtomicBoolean()
        val result = AtomicReference<LatencyStats>()
        val failure = AtomicReference<Exception>()
        val receipts = Collections.synchronizedList(mutableListOf<Long>())
        val receiptBounds = AtomicReference<LongRange>()
        val source = object : PacketSource {
            override val codecId = ScrcpyCodecs.VIDEO_H264
            var next = 0
            var firstReadAt = 0L
            override fun readPacket(): StreamPacket {
                if (next == 0) firstReadAt = System.nanoTime()
                if (next == 1) receiptBounds.set(firstReadAt..System.nanoTime())
                if (next == receivedCount) {
                    CountDownLatch(1).await()
                    error("unreachable")
                }
                val pts = (next++).toLong()
                if (next == receivedCount) filled.countDown()
                return StreamPacket.Media(pts, false, true, byteArrayOf(1), 1)
            }
        }
        val pump = MediaPacketPump(
            source,
            consume = { packet, receivedNanos ->
                packet as StreamPacket.Media
                if (packet.pts == 0L) receipts += receivedNanos
                if (packet.pts == 0L && allowInput.get()) {
                    val queuedAt = receivedNanos + 100_000_000
                    val renderedAt = receivedNanos + 110_000_000
                    meter.arrived(packet.pts, queuedAt, receivedNanos)
                    meter.rendered(packet.pts, renderedAt)
                    result.set(meter.snapshot(renderedAt))
                    accepted.countDown()
                    true
                } else false
            },
            drain = { false }, release = {}, onError = failure::set,
            name = "test-receipt-budget", maxOwnedBytes = receivedCount, maxQueuedPackets = capacity,
        )
        try {
            pump.start()
            assertTrue("The supported queue must reach its full ownership bound", filled.await(20, TimeUnit.SECONDS))
            allowInput.set(true)
            assertTrue(accepted.await(20, TimeUnit.SECONDS))
            assertNull(failure.get())
            val stats = checkNotNull(result.get())
            assertEquals("A full queue must not restart timing at decoder acceptance", 110, stats.medianMillis)
            assertEquals(100, stats.intakeP95Millis)
            assertEquals("Input retries must retain the packet's original receipt", 1, receipts.toSet().size)
            assertTrue("Receipt must be stamped on intake, before the following source read",
                receipts.first() in checkNotNull(receiptBounds.get()))
            assertTrue(pump.peakOwnedBytes <= receivedCount)
        } finally { pump.close(); assertTrue(pump.awaitStopped(2_000)) }
    }

    @Test fun readerCopiesReusablePayloadBeforeCrossingThreads() {
        val shared = byteArrayOf(0)
        val secondRead = CountDownLatch(1)
        val firstConsumed = CountDownLatch(1)
        val captured = AtomicInteger()
        val source = object : PacketSource {
            override val codecId = 0
            var count = 0
            override fun readPacket(): StreamPacket {
                when (++count) {
                    1 -> { shared[0] = 1; return StreamPacket.Media(0, false, true, shared, 1) }
                    2 -> { shared[0] = 2; secondRead.countDown(); return StreamPacket.Media(1, false, false, shared, 1) }
                    else -> { CountDownLatch(1).await(); error("unreachable") }
                }
            }
        }
        val pump = MediaPacketPump(source, consume = { value, _ ->
            val packet = value as StreamPacket.Media
            if (packet.pts == 0L) {
                assertTrue(secondRead.await(20, TimeUnit.SECONDS))
                captured.set(packet.data[0].toInt())
                firstConsumed.countDown()
            }
            true
        }, drain = { false }, release = {}, onError = { throw AssertionError(it) }, name = "test-buffer")
        try {
            pump.start()
            assertTrue(firstConsumed.await(20, TimeUnit.SECONDS))
            assertEquals(1, captured.get())
        } finally { pump.close(); assertTrue(pump.awaitStopped(2_000)) }
    }

    @Test fun pendingAndQueuedCopiesShareOneByteBudgetWithoutDroppingInputs() {
        val thirdRead = CountDownLatch(1)
        val allowInput = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val accepted = Collections.synchronizedList(mutableListOf<Int>())
        val source = object : PacketSource {
            override val codecId = ScrcpyCodecs.VIDEO_H264
            val reusable = ByteArray(4)
            var count = 0
            override fun readPacket(): StreamPacket {
                if (++count > 6) throw EOFException("done")
                reusable.fill(count.toByte())
                if (count == 3) thirdRead.countDown()
                return StreamPacket.Media(count.toLong(), false, false, reusable, reusable.size)
            }
        }
        val errors = AtomicReference<Exception>()
        val pump = MediaPacketPump(source, consume = { packet, _ ->
            if (allowInput.count != 0L) false
            else {
                accepted += (packet as StreamPacket.Media).data[0].toInt()
                true
            }
        }, drain = { false }, release = {}, onError = { errors.set(it); ended.countDown() },
            name = "test-budget", maxOwnedBytes = 8)
        try {
            pump.start()
            assertTrue(thirdRead.await(20, TimeUnit.SECONDS))
            assertEquals("The pending input is still owned, not just queued packets", 8, pump.ownedBytes)
            assertEquals(8, pump.peakOwnedBytes)
            allowInput.countDown()
            assertTrue(ended.await(20, TimeUnit.SECONDS))
            assertTrue(pump.awaitStopped(2_000))
            assertEquals((1..6).toList(), accepted)
            assertTrue(errors.get() is EOFException)
            assertEquals(0, pump.ownedBytes)
        } finally { pump.close(); assertTrue(pump.awaitStopped(2_000)) }
    }

    @Test fun shutdownReleasesReaderQueueAndPendingOwnership() {
        // Exercise byte-budget and item-capacity blocking for live and synchronized video queues.
        for ((limit, capacity) in listOf(8 to 4, 64 to 4, 8 to 64, 512 to 64)) {
            val enoughReads = CountDownLatch(if (limit == 8) 3 else capacity + 2)
            val releases = AtomicInteger()
            val errors = AtomicReference<Exception>()
            val source = object : PacketSource {
                override val codecId = ScrcpyCodecs.VIDEO_H264
                override fun readPacket(): StreamPacket {
                    enoughReads.countDown()
                    return StreamPacket.Media(0, false, false, ByteArray(4), 4)
                }
            }
            val pump = MediaPacketPump(source, consume = { _, _ -> false }, drain = { false },
                release = { releases.incrementAndGet() }, onError = { errors.set(it) },
                name = "test-shutdown-$limit-$capacity", maxOwnedBytes = limit, maxQueuedPackets = capacity)
            try {
                pump.start()
                assertTrue(enoughReads.await(20, TimeUnit.SECONDS))
            } finally { pump.close(); assertTrue(pump.awaitStopped(2_000)) }
            assertEquals(0, pump.ownedBytes)
            assertTrue(pump.peakOwnedBytes <= limit)
            assertEquals(1, releases.get())
            assertNull(errors.get())
        }
    }

    @Test fun decoderFailureReleasesReservationsEvenWithABlockedReader() {
        val thirdRead = CountDownLatch(3)
        val failure = IllegalStateException("decoder failed")
        val observed = AtomicReference<Exception>()
        val source = object : PacketSource {
            override val codecId = ScrcpyCodecs.VIDEO_H264
            override fun readPacket(): StreamPacket {
                thirdRead.countDown()
                return StreamPacket.Media(0, false, false, ByteArray(4), 4)
            }
        }
        val pump = MediaPacketPump(source, consume = { _, _ ->
            assertTrue(thirdRead.await(20, TimeUnit.SECONDS))
            throw failure
        }, drain = { false }, release = {}, onError = { observed.set(it) },
            name = "test-failure", maxOwnedBytes = 8)
        try {
            pump.start()
            assertTrue(pump.awaitStopped(2_000))
            assertSame(failure, observed.get())
            assertEquals(0, pump.ownedBytes)
        } finally { pump.close(); assertTrue(pump.awaitStopped(2_000)) }
    }

    @Test fun invalidLengthsAreRejectedBeforeCopyingOrReserving() {
        for (length in listOf(-1, 2, MediaPacketLimits.AUDIO_PAYLOAD_BYTES + 1)) {
            val failure = AtomicReference<Exception>()
            val source = object : PacketSource {
                override val codecId = ScrcpyCodecs.AUDIO_RAW
                override fun readPacket() = StreamPacket.Media(0, true, false, ByteArray(1), length)
            }
            val pump = MediaPacketPump(source, consume = { _, _ -> fail("Invalid payload was submitted"); true },
                drain = { false }, release = {}, onError = { failure.set(it) }, name = "test-invalid")
            try {
                pump.start()
                assertTrue(pump.awaitStopped(2_000))
                assertTrue(failure.get() is MediaProtocolException)
                assertEquals(0, pump.peakOwnedBytes)
                assertEquals(0, pump.ownedBytes)
            } finally { pump.close(); assertTrue(pump.awaitStopped(2_000)) }
        }
    }

    @Test fun decoderInputTimeoutHasANonretryableTypeAndReleasesItsPacket() {
        val failure = AtomicReference<Exception>()
        val source = object : PacketSource {
            override val codecId = ScrcpyCodecs.VIDEO_H264
            var first = true
            override fun readPacket(): StreamPacket {
                if (first) { first = false; return frame(1) }
                CountDownLatch(1).await()
                error("unreachable")
            }
        }
        val pump = MediaPacketPump(source, consume = { _, _ -> false }, drain = { false }, release = {},
            onError = { failure.set(it) }, name = "test-timeout", inputTimeoutMillis = 20)
        try {
            pump.start()
            assertTrue(pump.awaitStopped(2_000))
            assertTrue(failure.get() is MediaInputException)
            assertEquals(0, pump.ownedBytes)
        } finally { pump.close(); assertTrue(pump.awaitStopped(2_000)) }
    }

    @Test fun anUninterruptibleSourceCannotPublishAfterShutdown() {
        val reading = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val failure = AtomicReference<Exception>()
        val source = object : PacketSource {
            override val codecId = ScrcpyCodecs.VIDEO_H264
            var first = true
            override fun readPacket(): StreamPacket {
                if (first) { first = false; return frame(1) }
                reading.countDown()
                var interrupted = false
                while (true) {
                    try { finishRead.await(); break }
                    catch (_: InterruptedException) { interrupted = true }
                }
                if (interrupted) Thread.currentThread().interrupt()
                return StreamPacket.Media(1, false, false, ByteArray(64), 64)
            }
        }
        val pump = MediaPacketPump(source, consume = { _, _ -> true }, drain = { false }, release = {},
            onError = { failure.set(it) }, name = "test-late-source")
        try {
            pump.start()
            assertTrue(reading.await(20, TimeUnit.SECONDS))
            pump.close()
            assertFalse("The session still needs to close its blocking source", pump.awaitStopped(10))
            finishRead.countDown()
            assertTrue(pump.awaitStopped(2_000))
            assertEquals(1, pump.peakOwnedBytes)
            assertEquals(0, pump.ownedBytes)
            assertNull(failure.get())
        } finally {
            finishRead.countDown()
            pump.close()
            assertTrue(pump.awaitStopped(2_000))
        }
    }

    @Test fun readerThrowableIsReportedAsAnException() {
        val failure = AtomicReference<Exception>()
        val source = object : PacketSource {
            override val codecId = ScrcpyCodecs.AUDIO_RAW
            override fun readPacket(): StreamPacket = throw LinkageError("boom")
        }
        val pump = MediaPacketPump(source, consume = { _, _ -> true }, drain = { false }, release = {},
            onError = { failure.set(it) }, name = "test-reader-throwable")
        try {
            pump.start()
            assertTrue(pump.awaitStopped(2_000))
            assertTrue(failure.get().cause is LinkageError)
        } finally { pump.close() }
    }

    @Test fun decoderThrowableIsReportedAsAnException() {
        val failure = AtomicReference<Exception>()
        val source = object : PacketSource {
            override val codecId = ScrcpyCodecs.AUDIO_RAW
            override fun readPacket(): StreamPacket {
                Thread.sleep(5)
                return StreamPacket.Media(0, true, false, ByteArray(2), 2)
            }
        }
        val pump = MediaPacketPump(source, consume = { _, _ -> throw LinkageError("decode") }, drain = { false },
            release = {}, onError = { failure.set(it) }, name = "test-decoder-throwable")
        try {
            pump.start()
            assertTrue(pump.awaitStopped(2_000))
            assertTrue(failure.get().cause is LinkageError)
        } finally { pump.close() }
    }
}
