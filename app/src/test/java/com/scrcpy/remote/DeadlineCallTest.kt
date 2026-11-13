package com.scrcpy.remote

import com.scrcpy.remote.adb.DeadlineCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DeadlineCallTest {

    @Test fun aCallThatAnswersInTimeReturnsItsValue() {
        val value = DeadlineCall.within(2_000, "test", "too slow", dispose = { fail("disposed") }) {
            "answer"
        }
        assertEquals("answer", value)
    }

    @Test fun aCallThatBlocksPastItsDeadlineFailsInsteadOfHangingTheCaller() {
        val release = CountDownLatch(1)
        try {
            val started = System.nanoTime()
            val failure = assertThrows(IOException::class.java) {
                DeadlineCall.within(150, "test", "too slow", dispose = { release.countDown() }) {
                    release.await()
                    "late"
                }
            }
            assertEquals("too slow", failure.message)
            assertTrue("Deadline failures must remain distinguishable from refusal", failure is SocketTimeoutException)
            val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("Waited $waitedMillis ms", waitedMillis < 2_000)
        } finally {
            release.countDown()
        }
    }

    @Test fun aResultThatArrivesAfterTheDeadlineIsDisposedOfRatherThanLeaked() {
        // The point of the deadline is that the caller stops waiting, not that the work stops.
        // libadb's open parks in a wait that ignores interruption, so it goes on to produce a
        // stream that nothing else would ever close - modelled here by swallowing the interrupt.
        val release = CountDownLatch(1)
        val disposed = AtomicReference<String>()
        val handled = CountDownLatch(1)
        assertThrows(IOException::class.java) {
            DeadlineCall.within(100, "test", "too slow",
                dispose = { value: String -> disposed.set(value); handled.countDown() }) {
                while (release.count > 0L) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        // Deliberately not interruptible, like the native wait this stands in for.
                    }
                }
                "late"
            }
        }
        release.countDown()
        assertTrue("The late result was never disposed of", handled.await(2, TimeUnit.SECONDS))
        assertEquals("late", disposed.get())
    }

    @Test fun aFailureInsideTheCallReachesTheCaller() {
        val failure = assertThrows(IOException::class.java) {
            DeadlineCall.within(2_000, "test", "too slow", dispose = { fail("disposed") }) {
                throw IOException("device refused")
            }
        }
        assertEquals("device refused", failure.message)
    }

    @Test fun interruptingTheWaiterInterruptsTheWorkerAndDisposesItsLateResultExactlyOnce() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val producer = AtomicReference<Thread>()
        val producerInterrupted = AtomicBoolean()
        val callerFailure = AtomicReference<Throwable>()
        val disposed = AtomicReference<Any>()
        val disposalCount = AtomicInteger()
        val late = Any()
        val caller = Thread({
            try {
                DeadlineCall.within(60_000, "test-interrupted-open", "too slow", dispose = { value: Any ->
                    disposed.set(value)
                    disposalCount.incrementAndGet()
                }) {
                    producer.set(Thread.currentThread())
                    entered.countDown()
                    while (true) {
                        try {
                            release.await()
                            break
                        } catch (_: InterruptedException) {
                            producerInterrupted.set(true)
                        }
                    }
                    late
                }
            } catch (error: Throwable) {
                callerFailure.set(error)
            }
        }, "test-deadline-waiter").apply { isDaemon = true }
        try {
            caller.start()
            assertTrue("Producer did not start", entered.await(20, TimeUnit.SECONDS))
            caller.interrupt()
            caller.join(20_000)
            assertFalse("Interrupted caller stayed blocked", caller.isAlive)
            assertTrue(callerFailure.get().toString(), callerFailure.get() is InterruptedException)

            release.countDown()
            val worker = checkNotNull(producer.get())
            worker.join(20_000)
            assertFalse("Released producer stayed blocked", worker.isAlive)
            assertSame("Late stream was leaked", late, disposed.get())
            assertEquals(1, disposalCount.get())
            assertTrue("Cancellation never interrupted the producer", producerInterrupted.get())
        } finally {
            release.countDown()
            caller.interrupt()
            producer.get()?.interrupt()
            caller.join(20_000)
            producer.get()?.join(20_000)
            assertFalse("Caller survived test cleanup", caller.isAlive)
            assertFalse("Producer survived test cleanup", producer.get()?.isAlive == true)
        }
    }

    private fun fail(reason: String): Nothing = throw AssertionError(reason)
}
