package com.scrcpy.remote

import com.scrcpy.remote.session.PcmOutput
import com.scrcpy.remote.session.PcmPlayback
import com.scrcpy.remote.session.PcmPresentation
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PcmPlaybackTest {
    private open class Output : PcmOutput {
        val samples = ByteArrayOutputStream()
        val muteChanges = CopyOnWriteArrayList<Boolean>()
        val threads = CopyOnWriteArrayList<String>()
        var plays = 0
        var closes = 0
        override fun play() { threads += Thread.currentThread().name; plays++ }
        override fun setMuted(muted: Boolean) { threads += Thread.currentThread().name; muteChanges += muted }
        override fun write(data: ByteArray, offset: Int, length: Int): Int {
            threads += Thread.currentThread().name
            val count = minOf(length, 4)
            samples.write(data, offset, count)
            return count
        }
        override fun close() { threads += Thread.currentThread().name; closes++ }
    }

    @Test fun firstPcmPlaysImmediatelyWithoutWaitingToFillTheBuffer() {
        val output = Output()
        val playing = CountDownLatch(1)
        val errors = AtomicReference<Exception>()
        val playback = PcmPlayback(1_000, 2, 50, { output }, { false }, { playing.countDown() }, errors::set)
        try {
            playback.start()
            playback.offer(byteArrayOf(42, 43), 2)
            assertTrue(playing.await(2, TimeUnit.SECONDS))
            assertEquals(1, output.plays)
            assertArrayEquals(byteArrayOf(42, 43), output.samples.toByteArray())
            assertNull(errors.get())
        } finally { playback.close(); assertTrue(playback.awaitStopped(2_000)) }
        assertEquals(1, output.closes)
        assertEquals(setOf("scrcpy-audio-playback"), output.threads.toSet())
    }

    @Test fun blockedOutputCannotBlockDecodeAndOldBacklogIsNotReplayed() {
        val blocked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val firstWrite = AtomicBoolean(true)
        val output = object : Output() {
            override fun write(data: ByteArray, offset: Int, length: Int): Int {
                if (firstWrite.getAndSet(false)) {
                    blocked.countDown()
                    check(resume.await(2, TimeUnit.SECONDS))
                }
                return super.write(data, offset, length)
            }
        }
        val errors = AtomicReference<Exception>()
        val playback = PcmPlayback(1_000, 2, 50, { output }, { false }, {}, errors::set)
        try {
            playback.start()
            playback.offer(frames(0, 20), 40)
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            // Simulate a 300 ms network burst while the audio device is still blocked.
            assertTrue(playback.offer(frames(20, 300), 600))
            assertEquals(50, playback.stats().queuedFrames)
            assertEquals(260L, playback.stats().droppedFrames)
            resume.countDown()
            await { playback.stats().writtenFrames == 60L }
            playback.close()
            assertTrue(playback.awaitStopped(2_000))
            assertArrayEquals(frames(0, 10) + frames(270, 50), output.samples.toByteArray())
            assertNull(errors.get())
        } finally { resume.countDown(); playback.close(); assertTrue(playback.awaitStopped(2_000)) }
    }

    @Test fun lowBufferPreservesANormalBlockWhileItsPredecessorIsPartlyConsumed() {
        val blockFrames = 4_096
        val first = ByteArray(blockFrames * 4) { (it % 127).toByte() }
        val second = ByteArray(blockFrames * 4) { (127 - it % 127).toByte() }
        val partlyConsumed = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val output = object : Output() {
            var calls = 0
            override fun write(data: ByteArray, offset: Int, length: Int): Int {
                if (++calls == 3) {
                    partlyConsumed.countDown()
                    check(resume.await(2, TimeUnit.SECONDS))
                }
                samples.write(data, offset, length)
                return length
            }
        }
        val errors = AtomicReference<Exception>()
        val playback = PcmPlayback(48_000, 4, 20, { output }, { false }, {}, errors::set,
            codecBlockFrames = blockFrames)
        try {
            playback.start()
            assertTrue(playback.offer(first, first.size))
            assertTrue(partlyConsumed.await(2, TimeUnit.SECONDS))
            assertTrue(playback.stats().writtenFrames > 0)
            assertTrue(playback.stats().queuedFrames in 1 until blockFrames)
            assertTrue(playback.offer(second, second.size))
            assertEquals("A partly consumed predecessor does not turn the next block into backlog",
                0L, playback.stats().droppedFrames)
            assertTrue(playback.stats().queuedFrames <= blockFrames * 2)
            resume.countDown()
            await { playback.stats().writtenFrames == blockFrames * 2L }
            assertArrayEquals(first + second, output.samples.toByteArray())
            assertNull(errors.get())
        } finally { resume.countDown(); playback.close(); assertTrue(playback.awaitStopped(2_000)) }
    }

    @Test fun codecBlockFloorStillBoundsBacklogAndRetainsOneWholeBlock() {
        val blockFrames = 4_096
        val block = ByteArray(blockFrames * 4) { (it % 127).toByte() }
        val latest = ByteArray(block.size) { (127 - it % 127).toByte() }
        val output = object : Output() {
            override fun write(data: ByteArray, offset: Int, length: Int): Int {
                samples.write(data, offset, length)
                return length
            }
        }
        val errors = AtomicReference<Exception>()
        val playback = PcmPlayback(48_000, 4, 20, { output }, { false }, {}, errors::set,
            codecBlockFrames = blockFrames)
        try {
            assertTrue(playback.offer(block, block.size))
            assertTrue(playback.offer(block, block.size))
            assertEquals(blockFrames * 2, playback.stats().queuedFrames)
            assertEquals(0L, playback.stats().droppedFrames)
            assertTrue(playback.offer(latest, latest.size))
            assertEquals(blockFrames, playback.stats().queuedFrames)
            assertEquals(blockFrames * 2L, playback.stats().droppedFrames)
            playback.start()
            await { playback.stats().writtenFrames == blockFrames.toLong() }
            assertArrayEquals(latest, output.samples.toByteArray())
            assertNull(errors.get())
        } finally { playback.close(); assertTrue(playback.awaitStopped(2_000)) }
    }

    @Test fun codecBlockCapacityCannotExceedTheFormatsUnsignedFrameBound() {
        for (invalid in listOf(-1, 0x10000)) {
            assertThrows(IllegalArgumentException::class.java) {
                PcmPlayback(48_000, 4, 20, { Output() }, { false }, {}, {}, codecBlockFrames = invalid)
            }
        }
    }

    @Test fun mutingKeepsAudioAdvancingAndDoesNotRestartOutput() {
        val output = Output()
        val muted = AtomicBoolean(false)
        val errors = AtomicReference<Exception>()
        val playback = PcmPlayback(1_000, 2, 50, { output }, muted::get, {}, errors::set)
        try {
            playback.start()
            playback.offer(frames(0, 20), 40)
            await { playback.stats().writtenFrames == 20L }
            muted.set(true)
            await { output.muteChanges.lastOrNull() == true }
            playback.offer(frames(20, 20), 40)
            await { playback.stats().writtenFrames == 40L }
            muted.set(false)
            await { output.muteChanges.lastOrNull() == false }
            playback.close()
            assertTrue(playback.awaitStopped(2_000))
            assertEquals(1, output.plays)
            assertArrayEquals(frames(0, 40), output.samples.toByteArray())
            assertNull(errors.get())
        } finally { playback.close(); assertTrue(playback.awaitStopped(2_000)) }
    }

    @Test fun zeroNegativeAndUnalignedWritesFailOnceAndReleaseOutput() {
        for (result in listOf(0, -6, 1, 20)) {
            val output = object : Output() {
                override fun write(data: ByteArray, offset: Int, length: Int) = result
            }
            val errors = CopyOnWriteArrayList<Exception>()
            val playback = PcmPlayback(1_000, 2, 50, { output }, { false }, { fail("Invalid output reported playing") }, { errors += it })
            playback.start()
            playback.offer(ByteArray(4), 4)
            assertTrue(playback.awaitStopped(2_000))
            assertEquals(1, errors.size)
            assertTrue(errors.single() is IOException)
            assertEquals(1, output.closes)
        }
    }

    @Test fun cancelledBeforeStartDoesNotCreateAnAudioDevice() {
        val factories = AtomicInteger()
        val playback = PcmPlayback(1_000, 2, 50, { factories.incrementAndGet(); Output() }, { false }, {}, {})
        playback.close()
        playback.start()
        assertTrue(playback.awaitStopped(2_000))
        assertEquals(0, factories.get())
        assertFalse(playback.offer(ByteArray(4), 4))
    }

    @Test fun creationFailureIsReportedAndStopsAcceptingSamples() {
        val error = AtomicReference<Exception>()
        val playback = PcmPlayback(1_000, 2, 50, { throw IllegalStateException("unsupported track") }, { false }, {}, error::set)
        playback.start()
        assertTrue(playback.awaitStopped(2_000))
        assertEquals("unsupported track", error.get().message)
        assertFalse(playback.offer(ByteArray(4), 4))
    }

    @Test fun measuredUnderrunRebuffersBrieflyButNeverWaitsForeverForAFullQueue() {
        val underruns = AtomicInteger()
        val recoveryObserved = CountDownLatch(1)
        val beginRecovery = CountDownLatch(1)
        val resumed = CountDownLatch(1)
        val resumedAt = AtomicLong()
        val first = CountDownLatch(1)
        val output = object : Output() {
            override fun presentation(): PcmPresentation {
                if (underruns.get() > 0 && recoveryObserved.count > 0L) {
                    recoveryObserved.countDown()
                    // Start the recovery deadline only after the test has queued its short packet.
                    // Otherwise a busy test thread can miss the entire recovery window.
                    check(beginRecovery.await(2, TimeUnit.SECONDS))
                }
                return PcmPresentation(0, System.nanoTime(), false, underruns.get())
            }
            override fun write(data: ByteArray, offset: Int, length: Int): Int {
                if (first.count == 0L) { resumedAt.compareAndSet(0L, System.nanoTime()); resumed.countDown() }
                else first.countDown()
                return super.write(data, offset, length)
            }
        }
        val errors = AtomicReference<Exception>()
        val playback = PcmPlayback(1_000, 2, 50, { output }, { false }, {}, errors::set)
        try {
            playback.start()
            playback.offer(ByteArray(2), 2)
            assertTrue(first.await(2, TimeUnit.SECONDS))
            underruns.incrementAndGet()
            assertTrue(recoveryObserved.await(2, TimeUnit.SECONDS))
            playback.offer(ByteArray(2), 2)
            val t0 = System.nanoTime()
            beginRecovery.countDown()
            assertTrue("A short stream must not wait forever for 50 ms of PCM", resumed.await(5, TimeUnit.SECONDS))
            assertTrue("Underrun recovery should briefly wait for more PCM", resumedAt.get() - t0 >= 40_000_000L)
            assertNull(errors.get())
        } finally { beginRecovery.countDown(); playback.close(); assertTrue(playback.awaitStopped(2_000)) }
    }

    @Test fun routeResetDiscardsQueuedPcmAndFlushesOnTheOutputOwnerThread() {
        val firstWrite = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val resets = AtomicInteger()
        val output = object : Output() {
            override fun write(data: ByteArray, offset: Int, length: Int): Int {
                if (firstWrite.count > 0) {
                    firstWrite.countDown()
                    check(resume.await(2, TimeUnit.SECONDS))
                }
                return super.write(data, offset, length)
            }
            override fun reset() {
                threads += Thread.currentThread().name
                resets.incrementAndGet()
            }
        }
        val errors = AtomicReference<Exception>()
        val playback = PcmPlayback(1_000, 2, 50, { output }, { false }, {}, errors::set)
        try {
            playback.start()
            playback.offer(frames(0, 10), 20)
            assertTrue(firstWrite.await(2, TimeUnit.SECONDS))
            playback.offer(frames(10, 50), 100)
            playback.resetTimeline()
            assertEquals(0, playback.stats().queuedFrames)
            resume.countDown()
            await { resets.get() == 1 }
            playback.offer(frames(60, 10), 20)
            await { playback.stats().writtenFrames == 20L }
            playback.close()
            assertTrue(playback.awaitStopped(2_000))
            assertArrayEquals(frames(0, 10) + frames(60, 10), output.samples.toByteArray())
            assertEquals(setOf("scrcpy-audio-playback"), output.threads.toSet())
            assertNull(errors.get())
        } finally { resume.countDown(); playback.close(); assertTrue(playback.awaitStopped(2_000)) }
    }

    @Test fun switchingBetweenAlreadyConnectedOutputsFlushesWithoutADeviceAddedEvent() {
        val routeId = AtomicInteger(1)
        val reset = CountDownLatch(1)
        val initialRoute = CountDownLatch(1)
        val output = object : Output() {
            override fun presentation(): PcmPresentation {
                initialRoute.countDown()
                return PcmPresentation(0, System.nanoTime(), false, 0, outputRouteId = routeId.get())
            }
            override fun reset() { reset.countDown() }
        }
        val error = AtomicReference<Exception>()
        val playback = PcmPlayback(1_000, 2, 50, { output }, { false }, {}, error::set)
        try {
            playback.start()
            playback.offer(ByteArray(20), 20)
            assertTrue(initialRoute.await(2, TimeUnit.SECONDS))
            await { playback.stats().writtenFrames == 10L }
            routeId.set(2)
            assertTrue(reset.await(2, TimeUnit.SECONDS))
            playback.offer(ByteArray(20), 20)
            await { playback.stats().writtenFrames == 20L }
            assertNull(error.get())
        } finally { playback.close(); assertTrue(playback.awaitStopped(2_000)) }
    }

    private fun frames(start: Int, count: Int) = ByteArray(count * 2) { index ->
        val value = start + index / 2
        if (index % 2 == 0) value.toByte() else (value ushr 8).toByte()
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Timed out waiting for PCM playback", predicate())
    }

    @Test fun unexpectedRuntimeFailureIsReported() {
        val error = AtomicReference<Exception>()
        val playback = PcmPlayback(1_000, 2, 50, { throw ArrayStoreException("odd") }, { false }, {}, error::set)
        playback.start()
        assertTrue(playback.awaitStopped(2_000))
        assertEquals("odd", error.get().message)
    }
}
