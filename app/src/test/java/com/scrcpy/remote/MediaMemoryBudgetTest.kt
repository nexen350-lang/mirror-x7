package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.MediaMemoryBudget
import com.scrcpy.remote.scrcpy.MediaPacketLimits
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MediaMemoryBudgetTest {
    @Test fun everyAudioCodecUsesTheSmallerBudget() {
        for (codec in listOf(ScrcpyCodecs.AUDIO_OPUS, ScrcpyCodecs.AUDIO_AAC,
            ScrcpyCodecs.AUDIO_FLAC, ScrcpyCodecs.AUDIO_RAW)) {
            assertEquals(256 * 1024, MediaPacketLimits.maxPayloadBytes(codec))
            assertEquals(512 * 1024, MediaPacketLimits.ownedBytes(codec))
        }
        for (codec in listOf(ScrcpyCodecs.VIDEO_H264, ScrcpyCodecs.VIDEO_H265,
            ScrcpyCodecs.VIDEO_AV1, ScrcpyCodecs.VIDEO_VP8, ScrcpyCodecs.VIDEO_VP9, 0)) {
            assertEquals(4 * 1024 * 1024, MediaPacketLimits.maxPayloadBytes(codec))
            assertEquals(8 * 1024 * 1024, MediaPacketLimits.ownedBytes(codec))
        }
    }

    @Test fun reservationsWaitUntilTheirOwnersReleaseBytes() {
        val budget = MediaMemoryBudget(8)
        val first = budget.reserve(5)
        val started = CountDownLatch(1)
        val acquired = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val waiter = thread {
            try {
                started.countDown()
                budget.reserve(4).use {
                    acquired.countDown()
                    finish.await()
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertFalse(acquired.await(50, TimeUnit.MILLISECONDS))
            assertEquals(5, budget.usedBytes)
            first.close()
            first.close()
            assertTrue(acquired.await(2, TimeUnit.SECONDS))
            assertEquals(4, budget.usedBytes)
        } finally {
            first.close()
            finish.countDown()
            budget.close()
            waiter.join(2_000)
        }
        assertFalse(waiter.isAlive)
        assertNull(failure.get())
        assertEquals(0, budget.usedBytes)
        assertTrue(budget.peakBytes <= 8)
    }

    @Test fun closeWakesBlockedReservationsButDoesNotForgetLiveOwnership() {
        val budget = MediaMemoryBudget(8)
        val held = budget.reserve(8)
        val entered = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val waiter = thread {
            entered.countDown()
            try { budget.reserve(1).close() }
            catch (_: InterruptedException) { interrupted.set(true) }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            budget.close()
            waiter.join(2_000)
            assertFalse(waiter.isAlive)
            assertTrue(interrupted.get())
            assertEquals(8, budget.usedBytes)
            assertThrows(InterruptedException::class.java) { budget.reserve(0) }
        } finally { held.close(); budget.close(); waiter.interrupt(); waiter.join(2_000) }
        assertEquals(0, budget.usedBytes)
    }

    @Test fun anInterruptedWaiterDoesNotChargeOrFreeOtherOwnersBytes() {
        val budget = MediaMemoryBudget(3)
        val held = budget.reserve(3)
        val entered = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val waiter = thread {
            entered.countDown()
            try { budget.reserve(2).close() }
            catch (_: InterruptedException) { interrupted.set(true) }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            waiter.interrupt()
            waiter.join(2_000)
            assertFalse(waiter.isAlive)
            assertTrue(interrupted.get())
            assertEquals(3, budget.usedBytes)
            assertThrows(IllegalArgumentException::class.java) { budget.reserve(4) }
            assertThrows(IllegalArgumentException::class.java) { budget.reserve(-1) }
        } finally { held.close(); budget.close(); waiter.interrupt(); waiter.join(2_000) }
        assertEquals(0, budget.usedBytes)
    }
}
