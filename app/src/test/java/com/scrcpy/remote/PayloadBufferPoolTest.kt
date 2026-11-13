package com.scrcpy.remote

import com.scrcpy.remote.session.PayloadBufferPool
import com.scrcpy.remote.session.PumpPollPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PayloadBufferPoolTest {

    @Test fun aRecycledBufferIsHandedOutAgainRatherThanAllocated() {
        // The point of the pool: one array per packet at 8 Mbit/s is about a megabyte a second of
        // short-lived garbage, and collections show up as dropped frames.
        val pool = PayloadBufferPool(1 shl 20)
        val first = pool.acquire(1000)
        pool.recycle(first)
        assertSame(first, pool.acquire(1000))
    }

    @Test fun aBufferStillOutIsNeverHandedToASecondCaller() {
        val pool = PayloadBufferPool(1 shl 20)
        val first = pool.acquire(1000)
        val second = pool.acquire(1000)
        assertNotSame(first, second)
        pool.recycle(first)
        val third = pool.acquire(1000)
        assertSame(first, third)
        assertNotSame(second, third)
    }

    @Test fun aBufferIsNeverMuchLargerThanThePacketItCarries() {
        // Rounding to the next power of two wasted up to half of a large buffer. The ownership
        // budget charges the payload rather than the buffer, so that waste would have been
        // memory held outside anything that bounds it.
        val pool = PayloadBufferPool(1 shl 25)
        for (length in listOf(4096, 4097, 100_000, 1 shl 20, (1 shl 20) + 1, 4 * 1024 * 1024)) {
            val buffer = pool.acquire(length)
            assertTrue("$length -> ${buffer.size}", buffer.size >= length)
            assertTrue("waste for $length was ${buffer.size - length}",
                buffer.size - length <= 8 * 1024)
        }
    }

    @Test fun capacitiesAreRoundedSoSimilarSizesShareABuffer() {
        val pool = PayloadBufferPool(1 shl 20)
        val buffer = pool.acquire(5000)
        assertTrue("${buffer.size}", buffer.size >= 5000)
        pool.recycle(buffer)
        // A slightly different packet size reuses it instead of allocating its own.
        assertSame(buffer, pool.acquire(4800))
    }

    @Test fun aBufferTooSmallIsNotHandedOut() {
        val pool = PayloadBufferPool(1 shl 20)
        val small = pool.acquire(16)
        pool.recycle(small)
        val large = pool.acquire(100_000)
        assertNotSame(small, large)
        assertTrue("${large.size}", large.size >= 100_000)
    }

    @Test fun aLargeBufferIsNeverHandedToASmallPacket() {
        // Reusing any buffer that merely fits meant a recycled 4 MiB array could carry a few
        // hundred bytes. The ownership budget charges the payload, not the array, so that
        // oversize was memory outside everything meant to bound it.
        val pool = PayloadBufferPool(1 shl 22)
        val large = pool.acquire(200_000)
        pool.recycle(large)
        val small = pool.acquire(1_000)
        assertNotSame(large, small)
        assertTrue("${small.size}", small.size - 1_000 <= 8 * 1024)
        // The large buffer stays available for a packet that actually needs it.
        assertSame(large, pool.acquire(200_000))
    }

    @Test fun thePoolRefusesToGrowPastItsLimit() {
        // It exists to stop churn, not to become a second copy of the ownership budget.
        val limit = 32 * 1024
        val pool = PayloadBufferPool(limit)
        val buffers = List(8) { pool.acquire(8 * 1024) }
        buffers.forEach { pool.recycle(it) }
        assertTrue("${pool.retainedBytes}", pool.retainedBytes <= limit)
    }

    @Test fun clearingReleasesEverythingHeld() {
        val pool = PayloadBufferPool(1 shl 20)
        pool.recycle(pool.acquire(1000))
        assertTrue(pool.size > 0)
        pool.clear()
        assertEquals(0, pool.size)
        assertEquals(0, pool.retainedBytes)
    }

    @Test fun concurrentUseNeverLendsOneBufferTwice() {
        // The reader acquires and the codec worker recycles, so the two really do race.
        val pool = PayloadBufferPool(1 shl 20)
        val threads = 4
        val perThread = 500
        val start = CountDownLatch(1)
        val clashes = java.util.concurrent.atomic.AtomicInteger()
        val live = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<ByteArray, Boolean>())
        val workers = List(threads) {
            Thread {
                start.await()
                repeat(perThread) {
                    val buffer = pool.acquire(1024)
                    // Identity, not equality: two different arrays of the same bytes are fine,
                    // the same array in two hands at once is not.
                    if (!live.add(buffer)) clashes.incrementAndGet()
                    Thread.yield()
                    live.remove(buffer)
                    pool.recycle(buffer)
                }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }
        assertEquals("A buffer was lent to two callers at once", 0, clashes.get())
    }

    @Test fun theWaitShortensWhilePacketsArriveAndBacksOffOnceTheyStop() {
        // A single fixed wait has to choose between holding a decoded frame back and waking an
        // idle session continuously; this is what lets it avoid choosing.
        assertEquals(PumpPollPolicy.ACTIVE_MILLIS, PumpPollPolicy.timeoutMillis(0))
        assertEquals(PumpPollPolicy.ACTIVE_MILLIS,
            PumpPollPolicy.timeoutMillis(PumpPollPolicy.ACTIVE_ROUNDS - 1))
        assertEquals(PumpPollPolicy.IDLE_MILLIS,
            PumpPollPolicy.timeoutMillis(PumpPollPolicy.ACTIVE_ROUNDS))
        assertEquals(PumpPollPolicy.IDLE_MILLIS, PumpPollPolicy.timeoutMillis(10_000))
    }

    @Test fun theActiveWindowOutlastsAFrameIntervalAtLowFrameRates() {
        // At 20 fps packets are 50 ms apart. If that counted as idle, the idle wait would be
        // added to every single frame, which is the opposite of the intent.
        val activeWindowMillis = PumpPollPolicy.ACTIVE_MILLIS * PumpPollPolicy.ACTIVE_ROUNDS
        assertTrue("$activeWindowMillis ms", activeWindowMillis >= 50)
        assertTrue(PumpPollPolicy.ACTIVE_MILLIS < PumpPollPolicy.IDLE_MILLIS)
    }
}
