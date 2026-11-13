package com.scrcpy.remote

import com.scrcpy.remote.session.PcmPlaybackBuffer
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PcmPlaybackBufferTest {
    @Test fun normalPacketsAreCopiedAndWrappedWithoutChangingSampleOrder() {
        val buffer = PcmPlaybackBuffer(2, 4, 8)
        val source = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertTrue(buffer.offer(source, source.size))
        source.fill(0)
        val first = ByteArray(4)
        assertEquals(4, buffer.poll(first, 0))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), first)
        assertTrue(buffer.offer(ByteArray(12) { (it + 7).toByte() }, 12))
        val rest = ByteArray(14)
        assertEquals(14, buffer.poll(rest, 0))
        assertArrayEquals(ByteArray(14) { (it + 5).toByte() }, rest)
        assertEquals(0L, buffer.droppedFrames)
    }

    @Test fun backlogHysteresisKeepsNewestSamplesOnlyAfterMaximumIsExceeded() {
        val buffer = PcmPlaybackBuffer(2, 4, 8)
        buffer.offer(ByteArray(16) { it.toByte() }, 16)
        assertEquals(8, buffer.queuedFrames)
        assertEquals(0L, buffer.droppedFrames)
        buffer.offer(byteArrayOf(16, 17), 2)
        assertEquals(4, buffer.queuedFrames)
        assertEquals(5L, buffer.droppedFrames)
        val latest = ByteArray(8)
        assertEquals(8, buffer.poll(latest, 0))
        assertArrayEquals(ByteArray(8) { (it + 10).toByte() }, latest)
    }

    @Test fun packetLargerThanCapacityRetainsItsNewestFrameAlignedTail() {
        val buffer = PcmPlaybackBuffer(8, 4, 8)
        val packet = ByteArray(800) { it.toByte() }
        assertTrue(buffer.offer(packet, packet.size))
        assertEquals(96L, buffer.droppedFrames)
        val tail = ByteArray(32)
        assertEquals(32, buffer.poll(tail, 0))
        assertArrayEquals(packet.takeLast(32).toByteArray(), tail)
    }

    @Test fun randomizedWrapAndOverflowMatchAFrameQueueForMonoStereoAndFloat() {
        for (frameSize in listOf(2, 4, 8)) {
            val buffer = PcmPlaybackBuffer(frameSize, 20, 40)
            val expected = ArrayDeque<ByteArray>()
            val random = Random(719)
            var dropped = 0L
            repeat(1_000) {
                if (random.nextBoolean()) {
                    val count = random.nextInt(70)
                    val bytes = ByteArray(count * frameSize).also(random::nextBytes)
                    for (frame in 0 until count) {
                        expected.addLast(bytes.copyOfRange(frame * frameSize, (frame + 1) * frameSize))
                    }
                    if (expected.size > 40) {
                        while (expected.size > 20) { expected.removeFirst(); dropped++ }
                    }
                    assertTrue(buffer.offer(bytes, bytes.size))
                } else {
                    val result = ByteArray((1 + random.nextInt(20)) * frameSize)
                    val count = buffer.poll(result, 0)
                    repeat(count / frameSize) { frame ->
                        assertArrayEquals(expected.removeFirst(), result.copyOfRange(frame * frameSize, (frame + 1) * frameSize))
                    }
                }
                assertEquals(expected.size, buffer.queuedFrames)
                assertEquals(dropped, buffer.droppedFrames)
            }
        }
    }

    @Test fun discardingQueuedAudioWakesARecoveryRefillWait() {
        // After an underrun the player waits to refill a whole target before resuming. Unmuting
        // resets the timeline by discarding the queue; that reset must apply now, not after the
        // refill wait, or the mute change and the reset are both late by the whole wait.
        val buffer = PcmPlaybackBuffer(4, 10, 20)
        buffer.offer(ByteArray(8), 8)
        val waiting = CountDownLatch(1)
        val returned = CountDownLatch(1)
        var polled = -1
        val reader = Thread {
            waiting.countDown()
            polled = buffer.pollChunk(ByteArray(40), 5_000, minimumFrames = 10).length
            returned.countDown()
        }
        reader.start()
        assertTrue(waiting.await(1, TimeUnit.SECONDS))
        Thread.sleep(100)
        buffer.discardQueued()
        assertTrue("A discard must end the refill wait", returned.await(1, TimeUnit.SECONDS))
        reader.join(1_000)
        assertEquals("Discarded audio must not be played", 0, polled)
    }

    @Test fun closingWakesWaitingPlaybackAndRejectsLateProducer() {
        val buffer = PcmPlaybackBuffer(4, 10, 20)
        val stopped = CountDownLatch(1)
        val reader = Thread {
            assertEquals(0, buffer.poll(ByteArray(4), 5_000))
            stopped.countDown()
        }
        reader.start()
        buffer.close()
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
        reader.join(1_000)
        assertFalse(buffer.offer(ByteArray(4), 4))
    }

    @Test fun invalidFrameAlignmentIsRejected() {
        val buffer = PcmPlaybackBuffer(4, 10, 20)
        assertThrows(IllegalArgumentException::class.java) { buffer.offer(ByteArray(5), 5) }
        assertThrows(IllegalArgumentException::class.java) { buffer.offer(ByteArray(4), 8) }
        assertThrows(IllegalArgumentException::class.java) { buffer.poll(ByteArray(3), 0) }
    }
}
