package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.ControlMessage
import com.scrcpy.remote.scrcpy.ControlStalledException
import com.scrcpy.remote.scrcpy.ScrcpyController
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ScrcpyControllerTest {
    private class Gate(private val interruptible: Boolean = true) {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun await() {
            entered.countDown()
            var interrupted = false
            try {
                while (true) {
                    try {
                        released.await()
                        return
                    } catch (error: InterruptedException) {
                        if (interruptible) throw error
                        interrupted = true
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }

        fun awaitEntered() = assertTrue("Writer did not reach gate", entered.await(5, TimeUnit.SECONDS))
        fun open() = released.countDown()
    }

    private class RecordingStream(
        private val writeGate: Gate? = null,
        private val flushGate: Gate? = null,
    ) : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        private val flushSizes = ArrayList<Int>()
        val successfulFlushes = AtomicInteger()
        val closes = AtomicInteger()

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            writeGate?.await()
            synchronized(bytes) { bytes.write(b, off, len) }
        }

        override fun flush() {
            flushGate?.await()
            synchronized(bytes) { flushSizes.add(bytes.size()) }
            successfulFlushes.incrementAndGet()
        }

        override fun close() {
            closes.incrementAndGet()
        }

        fun snapshot(): ByteArray = synchronized(bytes) { bytes.toByteArray() }
        fun flushedSizes(): List<Int> = synchronized(bytes) { flushSizes.toList() }
    }

    private fun touch(action: Int, pointer: Long = 0, x: Int = 0) = ControlMessage.InjectTouchEvent(
        action = action,
        pointerId = pointer,
        x = x,
        y = x,
        screenWidth = 1080,
        screenHeight = 2400,
        pressure = if (action == 1) 0f else 1f,
    )

    private fun assertWire(expected: List<ControlMessage>, actual: ByteArray) {
        val bytes = ByteArrayOutputStream()
        expected.forEach { bytes.write(it.toByteArray()) }
        assertArrayEquals(bytes.toByteArray(), actual)
    }

    @Test
    fun writesMessagesInOrder() {
        val stream = RecordingStream()
        val controller = ScrcpyController(stream)
        try {
            val messages = listOf(ControlMessage.expandNotificationPanel(), ControlMessage.collapsePanels())
            messages.forEach { assertTrue(controller.send(it)) }
            assertTrue(controller.flushPending(5000))
            assertWire(messages, stream.snapshot())
        } finally {
            controller.stop()
        }
    }

    @Test
    fun emptyQueueDoesNotCompleteBarrierWhileWriteIsInFlight() {
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val controller = ScrcpyController(stream)
        try {
            val message = touch(0)
            assertTrue(controller.send(message))
            gate.awaitEntered()
            assertEquals(0, stream.snapshot().size)
            assertFalse(controller.flushPending(0))
            gate.open()
            assertTrue(controller.flushPending(5000))
            assertTrue(stream.successfulFlushes.get() > 0)
            assertWire(listOf(message), stream.snapshot())
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun barrierAndTouchCallbackWaitForSuccessfulOutputFlush() {
        val gate = Gate()
        val stream = RecordingStream(flushGate = gate)
        val callbackDone = CountDownLatch(1)
        val callbackWasFlushed = AtomicBoolean()
        val callbackThread = AtomicReference<Thread>()
        val written = AtomicReference<ControlMessage.InjectTouchEvent>()
        val controller = ScrcpyController(stream, onTouchWritten = {
            callbackWasFlushed.set(stream.successfulFlushes.get() > 0)
            callbackThread.set(Thread.currentThread())
            written.set(it)
            callbackDone.countDown()
        })
        try {
            val message = touch(0)
            assertTrue(controller.send(message))
            gate.awaitEntered()
            assertEquals(32, stream.snapshot().size)
            assertFalse(controller.flushPending(0))
            assertEquals(1L, callbackDone.count)
            gate.open()
            assertTrue(controller.flushPending(5000))
            assertTrue(callbackDone.await(5, TimeUnit.SECONDS))
            assertTrue(callbackWasFlushed.get())
            assertEquals("scrcpy-controller", callbackThread.get().name)
            assertSame(message, written.get())
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun contiguousMovesCoalesceButDownMoveUpAndPointerReuseStayOrdered() {
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val controller = ScrcpyController(stream)
        try {
            val down = touch(0)
            assertTrue(controller.send(down))
            gate.awaitEntered()
            repeat(200) { assertTrue(controller.send(touch(2, x = it))) }
            val up = touch(1, x = 199)
            val nextDown = touch(0, x = 300)
            val nextMove = touch(2, x = 301)
            val nextUp = touch(1, x = 301)
            listOf(up, nextDown, nextMove, nextUp).forEach { assertTrue(controller.send(it)) }
            gate.open()
            assertTrue(controller.flushPending(5000))
            assertWire(listOf(down, touch(2, x = 199), up, nextDown, nextMove, nextUp), stream.snapshot())
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun alternatingFingersKeepEachFingersLatestSampleInTheOrderTheyArrived() {
        // Two fingers move together, so their moves alternate. Every sample of both was kept while
        // the writer was held up, which filled the queue within seconds and ended the session.
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val controller = ScrcpyController(stream)
        try {
            val first = touch(0)
            assertTrue(controller.send(first))
            gate.awaitEntered()
            val moves = (0 until 100).map { touch(2, pointer = (it % 2).toLong(), x = it) }
            moves.forEach { assertTrue(controller.send(it)) }
            gate.open()
            assertTrue(controller.flushPending(5000))
            assertWire(listOf(first, moves[98], moves[99]), stream.snapshot())
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun aLongStallDuringATwoFingerGestureNeverFillsTheQueue() {
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val failures = AtomicInteger()
        val controller = ScrcpyController(stream, onFailure = { failures.incrementAndGet() })
        try {
            val first = touch(0)
            assertTrue(controller.send(first))
            gate.awaitEntered()
            // Several times the queue's size: seconds of a pinch at 120 Hz with the network stalled.
            repeat(5_000) { assertTrue("Move $it", controller.send(touch(2, pointer = (it % 2).toLong(), x = it))) }
            assertEquals(0, failures.get())
            gate.open()
            assertTrue(controller.flushPending(5000))
            assertWire(listOf(first, touch(2, pointer = 0, x = 4_998), touch(2, pointer = 1, x = 4_999)), stream.snapshot())
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun aFingersMovesNeverMergeAcrossAnotherFingerGoingDownOrUp() {
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val controller = ScrcpyController(stream)
        try {
            val first = touch(0)
            assertTrue(controller.send(first))
            gate.awaitEntered()
            val messages = listOf(
                touch(2, pointer = 0, x = 1),
                touch(0, pointer = 1, x = 5),
                touch(2, pointer = 0, x = 2),
                touch(1, pointer = 1, x = 5),
                touch(2, pointer = 0, x = 3),
            )
            messages.forEach { assertTrue(controller.send(it)) }
            gate.open()
            assertTrue(controller.flushPending(5000))
            assertWire(listOf(first) + messages, stream.snapshot())
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun movesDoNotCoalesceAcrossBarriersOrIncompatibleGeometryAndButtons() {
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val controller = ScrcpyController(stream)
        try {
            val first = touch(0)
            val beforeBarrier = touch(2, x = 1)
            val afterBarrier = touch(2, x = 2)
            val resized = touch(2, x = 3).copy(screenWidth = 900)
            val buttons = resized.copy(x = 4, buttons = 1)
            val actionButton = buttons.copy(x = 5, actionButton = 1)
            assertTrue(controller.send(first))
            gate.awaitEntered()
            assertTrue(controller.send(beforeBarrier))
            assertFalse(controller.flushPending(0))
            val rest = listOf(afterBarrier, resized, buttons, actionButton)
            rest.forEach { assertTrue(controller.send(it)) }
            gate.open()
            assertTrue(controller.flushPending(5000))
            assertWire(listOf(first, beforeBarrier) + rest, stream.snapshot())
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun adjacentQueuedResizesKeepOnlyTheLatestDimensions() {
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val controller = ScrcpyController(stream)
        try {
            val first = ControlMessage.expandNotificationPanel()
            assertTrue(controller.send(first))
            gate.awaitEntered()
            repeat(200) { assertTrue(controller.send(ControlMessage.ResizeDisplay(640 + it, 480 + it))) }
            gate.open()
            assertTrue(controller.flushPending(5000))
            assertWire(listOf(first, ControlMessage.ResizeDisplay(839, 679)), stream.snapshot())
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun resizesNeverCoalesceAcrossOtherControlMessages() {
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val controller = ScrcpyController(stream)
        try {
            val first = ControlMessage.expandNotificationPanel()
            assertTrue(controller.send(first))
            gate.awaitEntered()
            val messages = listOf(
                ControlMessage.ResizeDisplay(640, 480),
                touch(0),
                ControlMessage.ResizeDisplay(800, 600),
                touch(2, x = 20),
                ControlMessage.ResizeDisplay(960, 720),
                ControlMessage.InjectKeycode(0, 66),
                ControlMessage.ResizeDisplay(1024, 768),
                ControlMessage.InjectScrollEvent(10, 20, 1080, 2400, 1f, -1f),
                ControlMessage.ResizeDisplay(1152, 864),
                ControlMessage.SetClipboard(1, "text", true),
                ControlMessage.ResizeDisplay(1280, 960),
                ControlMessage.rotateDevice(),
                ControlMessage.ResizeDisplay(1440, 1080),
                touch(1, x = 20),
            )
            messages.forEach { assertTrue(controller.send(it)) }
            gate.open()
            assertTrue(controller.flushPending(5000))
            assertWire(listOf(first) + messages, stream.snapshot())
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun aTimedOutFlushBarrierProtectsItsQueuedResizeFromLaterReplacements() {
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val controller = ScrcpyController(stream)
        try {
            val first = ControlMessage.expandNotificationPanel()
            val beforeBarrier = ControlMessage.ResizeDisplay(640, 480)
            val latest = ControlMessage.ResizeDisplay(1280, 960)
            assertTrue(controller.send(first))
            gate.awaitEntered()
            assertTrue(controller.send(beforeBarrier))
            assertFalse(controller.flushPending(0))
            assertTrue(controller.send(ControlMessage.ResizeDisplay(800, 600)))
            assertTrue(controller.send(latest))
            gate.open()
            assertTrue(controller.flushPending(5000))
            assertWire(listOf(first, beforeBarrier, latest), stream.snapshot())
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun anInFlightResizeIsKeptWhileItsQueuedSuccessorsCoalesce() {
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val controller = ScrcpyController(stream)
        try {
            val inFlight = ControlMessage.ResizeDisplay(640, 480)
            val latest = ControlMessage.ResizeDisplay(1280, 960)
            assertTrue(controller.send(inFlight))
            gate.awaitEntered()
            assertTrue(controller.send(ControlMessage.ResizeDisplay(800, 600)))
            assertTrue(controller.send(latest))
            assertFalse(controller.flushPending(0))
            gate.open()
            assertTrue(controller.flushPending(5000))
            assertWire(listOf(inFlight, latest), stream.snapshot())
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun gracefulCloseFlushesTheLatestAcceptedResizeAndRejectsNewResizes() {
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val controller = ScrcpyController(stream)
        try {
            val first = ControlMessage.expandNotificationPanel()
            val latest = ControlMessage.ResizeDisplay(1280, 960)
            assertTrue(controller.send(first))
            gate.awaitEntered()
            assertTrue(controller.send(ControlMessage.ResizeDisplay(640, 480)))
            assertTrue(controller.send(ControlMessage.ResizeDisplay(800, 600)))
            assertTrue(controller.send(latest))
            gate.open()
            assertTrue(controller.closeGracefully(5000))
            assertFalse(controller.send(ControlMessage.ResizeDisplay(1920, 1080)))
            assertWire(listOf(first, latest), stream.snapshot())
            assertEquals(0, stream.closes.get())
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun aResizeBurstDoesNotOverflowTheControlBacklog() {
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val failures = AtomicInteger()
        val controller = ScrcpyController(stream, onFailure = { failures.incrementAndGet() })
        try {
            val first = ControlMessage.expandNotificationPanel()
            assertTrue(controller.send(first))
            gate.awaitEntered()
            repeat(2048) {
                assertTrue("Resize $it overflowed", controller.send(ControlMessage.ResizeDisplay(640 + it, 480 + it)))
            }
            gate.open()
            assertTrue(controller.flushPending(5000))
            assertWire(listOf(first, ControlMessage.ResizeDisplay(2687, 2527)), stream.snapshot())
            assertEquals(0, failures.get())
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun invalidResizeDimensionsAreRejectedBeforeAcceptanceWithoutAbortingControl() {
        val stream = RecordingStream()
        val failures = AtomicInteger()
        val controller = ScrcpyController(stream, onFailure = { failures.incrementAndGet() })
        try {
            for (invalid in listOf(Int.MIN_VALUE, -1, 0, 65536, Int.MAX_VALUE)) {
                for ((width, height) in listOf(invalid to 960, 1280 to invalid)) {
                    assertThrows(IllegalArgumentException::class.java) {
                        controller.send(ControlMessage.ResizeDisplay(width, height))
                    }
                }
            }
            val valid = ControlMessage.ResizeDisplay(1280, 960)
            assertTrue(controller.send(valid))
            assertTrue(controller.flushPending(5000))
            assertWire(listOf(valid), stream.snapshot())
            assertEquals(0, failures.get())
        } finally {
            controller.stop()
        }
    }

    @Test
    fun scrollDeltasKeepTheirSumAndOrderEvenBeyondSingleMessageRange() {
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val controller = ScrcpyController(stream)
        try {
            val first = ControlMessage.expandNotificationPanel()
            assertTrue(controller.send(first))
            gate.awaitEntered()
            val scrolls = (0 until 100).map {
                ControlMessage.InjectScrollEvent(
                    x = it,
                    y = 20,
                    screenWidth = 1080,
                    screenHeight = 2400,
                    hScroll = if (it % 2 == 0) 1f else -0.5f,
                    vScroll = 1f,
                )
            }
            scrolls.forEach { assertTrue(controller.send(it)) }
            gate.open()
            assertTrue(controller.flushPending(5000))
            val bytes = stream.snapshot()
            assertWire(listOf(first) + scrolls, bytes)
            val wire = ByteBuffer.wrap(bytes)
            val horizontal = scrolls.indices.sumOf { wire.getShort(1 + it * 21 + 13).toInt() } / 2048f
            val vertical = scrolls.indices.sumOf { wire.getShort(1 + it * 21 + 15).toInt() } / 2048f
            assertEquals(25f, horizontal, 0f)
            assertEquals(100f, vertical, 0f)
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun saturationExplicitlyFailsInsteadOfDroppingCriticalTransitions() {
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val failures = AtomicInteger()
        val failure = AtomicReference<Throwable>()
        val failureThread = AtomicReference<Thread>()
        val controller = ScrcpyController(stream, onFailure = {
            failure.set(it)
            failureThread.set(Thread.currentThread())
            failures.incrementAndGet()
        })
        try {
            assertTrue(controller.send(touch(0)))
            gate.awaitEntered()
            repeat(1024) { assertTrue("Rejected transition $it", controller.send(touch(it % 2))) }
            assertFalse(controller.send(touch(1)))
            assertEquals(1, failures.get())
            assertTrue(failure.get() is IOException)
            // Reported as a stall, so the session is recovered like a connection that stopped answering.
            assertTrue(failure.get() is ControlStalledException)
            assertTrue(failure.get().message!!.contains("1024"))
            assertSame(Thread.currentThread(), failureThread.get())
            assertFalse(controller.send(touch(0)))
            assertFalse(controller.flushPending(0))
            controller.stop()
            assertEquals(1, failures.get())
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun sustainedBacklogFlushesInBoundedBursts() {
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val controller = ScrcpyController(stream)
        try {
            assertTrue(controller.send(ControlMessage.expandNotificationPanel()))
            gate.awaitEntered()
            repeat(128) { assertTrue(controller.send(ControlMessage.collapsePanels())) }
            gate.open()
            assertTrue(controller.flushPending(5000))
            val sizes = listOf(0) + stream.flushedSizes()
            assertEquals(129, sizes.last())
            assertTrue(sizes.zipWithNext().all { (before, after) -> after - before in 1..32 })
            assertTrue(sizes.size >= 6)
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun writeFailurePropagatesExactlyOnce() {
        val error = IOException("write failed")
        assertTransportFailure(object : OutputStream() {
            override fun write(b: Int): Unit = throw error
        }, error)
    }

    @Test
    fun flushFailureNeverAcknowledgesTouch() {
        val error = IOException("flush failed")
        assertTransportFailure(object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun flush(): Unit = throw error
        }, error)
    }

    private fun assertTransportFailure(stream: OutputStream, expected: IOException) {
        val failed = CountDownLatch(1)
        val cause = AtomicReference<Throwable>()
        val callbacks = AtomicInteger()
        val failures = AtomicInteger()
        val controller = ScrcpyController(stream, onFailure = {
            cause.set(it)
            failures.incrementAndGet()
            failed.countDown()
        }, onTouchWritten = { callbacks.incrementAndGet() })
        try {
            assertTrue(controller.send(touch(0)))
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertSame(expected, cause.get())
            assertEquals(1, failures.get())
            assertEquals(0, callbacks.get())
            assertFalse(controller.send(touch(1)))
            assertFalse(controller.flushPending(5000))
        } finally {
            controller.stop()
        }
    }

    @Test
    fun flushWaitCanBeInterruptedWithoutAbortingTransport() {
        val gate = Gate()
        val stream = RecordingStream(writeGate = gate)
        val controller = ScrcpyController(stream)
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val result = AtomicBoolean(true)
        val interrupted = AtomicBoolean()
        val waiter = Thread {
            started.countDown()
            result.set(controller.flushPending(60_000))
            interrupted.set(Thread.currentThread().isInterrupted)
            done.countDown()
        }.apply { isDaemon = true }
        try {
            assertTrue(controller.send(touch(0)))
            gate.awaitEntered()
            waiter.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            waiter.interrupt()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertFalse(result.get())
            assertTrue(interrupted.get())
            gate.open()
            assertTrue(controller.flushPending(5000))
        } finally {
            waiter.interrupt()
            gate.open()
            controller.stop()
        }
    }

    @Test(timeout = 5000)
    fun gracefulCloseIsFiniteEvenForNonInterruptibleIoAndDoesNotOwnStream() {
        val gate = Gate(interruptible = false)
        val stream = RecordingStream(writeGate = gate)
        val failures = AtomicInteger()
        val controller = ScrcpyController(stream, onFailure = { failures.incrementAndGet() })
        try {
            assertTrue(controller.send(touch(0)))
            gate.awaitEntered()
            assertFalse(controller.closeGracefully(10))
            assertFalse(controller.send(touch(1)))
            assertFalse(controller.flushPending(0))
            assertEquals(0, stream.closes.get())
            assertEquals(0, failures.get())
            controller.stop()
        } finally {
            gate.open()
            controller.stop()
        }
    }

    @Test(timeout = 5000)
    fun stopImmediatelyAbortsNonInterruptibleIoAndCancelsBarrier() {
        val gate = Gate(interruptible = false)
        val stream = RecordingStream(writeGate = gate)
        val failures = AtomicInteger()
        val controller = ScrcpyController(stream, onFailure = { failures.incrementAndGet() })
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val result = AtomicBoolean(true)
        val waiter = Thread {
            started.countDown()
            result.set(controller.flushPending(60_000))
            done.countDown()
        }.apply { isDaemon = true }
        try {
            assertTrue(controller.send(touch(0)))
            gate.awaitEntered()
            assertTrue(controller.send(touch(1)))
            waiter.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            controller.stop()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertFalse(result.get())
            assertFalse(controller.send(touch(0)))
            assertEquals(0, stream.snapshot().size)
            assertEquals(0, failures.get())
        } finally {
            waiter.interrupt()
            gate.open()
            controller.stop()
        }
    }

    @Test
    fun gracefulCloseDeliversAcceptedInputAndRejectsNewInput() {
        val stream = RecordingStream()
        val controller = ScrcpyController(stream)
        val messages = listOf(touch(0), touch(2, x = 1), touch(1, x = 1))
        try {
            messages.forEach { assertTrue(controller.send(it)) }
            assertTrue(controller.closeGracefully(5000))
            assertWire(messages, stream.snapshot())
            assertFalse(controller.send(touch(0)))
            assertEquals(0, stream.closes.get())
        } finally {
            controller.stop()
        }
    }
}
