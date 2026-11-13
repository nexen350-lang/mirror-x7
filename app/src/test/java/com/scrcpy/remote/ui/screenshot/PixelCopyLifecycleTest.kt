package com.scrcpy.remote.ui.screenshot

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Test

class PixelCopyLifecycleTest {
    @Test
    fun cancellationOrTimeoutDoesNotRecycleBeforeNativeCopyCompletes() {
        var recycles = 0
        val lifecycle = PixelCopyLifecycle { recycles++ }
        lifecycle.release()
        lifecycle.release()
        assertEquals(0, recycles)
        lifecycle.finishCopy()
        lifecycle.finishCopy()
        assertEquals(1, recycles)
    }

    @Test
    fun completedCopyStaysAliveWhilePngEncoderOwnsIt() {
        var recycles = 0
        val lifecycle = PixelCopyLifecycle { recycles++ }
        lifecycle.finishCopy()
        assertEquals(0, recycles)
        lifecycle.release()
        lifecycle.release()
        assertEquals(1, recycles)
    }

    @Test
    fun callbackAndCancellationOnDifferentThreadsRecycleExactlyOnce() {
        repeat(100) {
            val recycles = AtomicInteger()
            val lifecycle = PixelCopyLifecycle { recycles.incrementAndGet() }
            val start = CountDownLatch(1)
            val callback = thread { start.await(); lifecycle.finishCopy() }
            val cancellation = thread { start.await(); lifecycle.release() }
            start.countDown()
            callback.join()
            cancellation.join()
            assertEquals(1, recycles.get())
        }
    }
}
