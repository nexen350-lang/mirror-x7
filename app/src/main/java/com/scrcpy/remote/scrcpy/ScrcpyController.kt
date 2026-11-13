package com.scrcpy.remote.scrcpy

import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.withLock

/**
 * Bounded, ordered control transport. A finger's queued move may be superseded by its newer one,
 * even with other fingers' moves queued between them, and adjacent display resizes by the latest;
 * transitions and relative scroll deltas are never discarded while the transport is healthy.
 * Superseding moves of every finger is what keeps a gesture during a network stall from filling
 * the queue: two fingers' moves alternate, so merging only adjacent ones merged nothing.
 *
 * [onTouchWritten] runs on the writer after a successful transport flush, not a remote acknowledgement.
 * [onFailure] runs once, outside the queue lock: on the sender for overflow, or on the writer for
 * serialization/I/O failure. Callbacks must not block. Callback exceptions are logged and isolated.
 *
 * The caller owns [output] and must close its transport to release non-interruptible socket I/O.
 * Neither [stop] nor [closeGracefully] calls a potentially blocking OutputStream.close().
 */
class ScrcpyController(
    output: OutputStream,
    private val onFailure: (Throwable) -> Unit = { logFailure("Control transport failed", it) },
    private val onTouchWritten: (ControlMessage.InjectTouchEvent) -> Unit = {},
) {
    private class Slot(
        var message: ControlMessage,
        var sequence: Long,
        val generation: Long,
    )

    private val out = DataOutputStream(output.buffered())
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val flushed = lock.newCondition()
    private val queue = ArrayDeque<Slot>()
    private var running = true
    private var accepting = true
    private var acceptedSequence = 0L
    private var flushedSequence = 0L
    private var generation = 0L

    private val thread = Thread({ pump() }, "scrcpy-controller").apply { isDaemon = true }

    init {
        // Even the thread reference must be assigned before pump() can report a failure.
        thread.start()
    }

    /** Never waits for network I/O or queue space. False means this session cannot accept the input. */
    fun send(message: ControlMessage): Boolean {
        var overflow: IOException? = null
        val accepted = lock.withLock {
            if (!accepting || !running) return@withLock false
            val tail = queue.lastOrNull()
            val superseded = supersededMove(message)
            if (tail != null && tail.generation == generation && canReplace(tail.message, message)) {
                tail.message = message
                tail.sequence = ++acceptedSequence
            } else if (superseded >= 0) {
                // Moved to the end rather than replaced in place, so the queue stays in the order
                // its messages were accepted, which is what a flush barrier counts on.
                queue.removeAt(superseded)
                queue.addLast(Slot(message, ++acceptedSequence, generation))
            } else {
                if (queue.size >= MAX_PENDING) {
                    overflow = ControlStalledException(
                        "Control backlog exceeded $MAX_PENDING pending messages: the device stopped taking input")
                    abortLocked()
                    return@withLock false
                }
                queue.addLast(Slot(message, ++acceptedSequence, generation))
            }
            notEmpty.signal()
            true
        }
        overflow?.let {
            thread.interrupt()
            notifyFailure(it)
        }
        return accepted
    }

    /**
     * Waits for a successful output.flush() covering all input accepted before this call, including
     * in-flight writes. A barrier also prevents later messages from replacing its queued input.
     * False indicates timeout, interruption (interrupt status is retained), failure, or abort.
     * Waiting from a writer callback is not supported and returns false unless already flushed.
     */
    fun flushPending(timeoutMs: Long): Boolean = awaitFlush(timeoutMs, close = false)

    /** Rejects new input, waits at most [timeoutMs] for accepted input, then aborts the worker. */
    fun closeGracefully(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0) { "Timeout must not be negative" }
        return try {
            awaitFlush(timeoutMs, close = true)
        } finally {
            stop()
        }
    }

    /** Immediate abort; does not flush, join the worker, or wait for a blocked stream. */
    fun stop() {
        lock.withLock { abortLocked() }
        thread.interrupt()
    }

    private fun awaitFlush(timeoutMs: Long, close: Boolean): Boolean {
        require(timeoutMs >= 0) { "Timeout must not be negative" }
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val start = System.nanoTime()
        try {
            lock.lockInterruptibly()
            try {
                if (close) accepting = false
                if (!running) return false
                val target = acceptedSequence
                generation++
                while (flushedSequence < target) {
                    if (!running || Thread.currentThread() === thread) return false
                    val remaining = timeoutNanos - (System.nanoTime() - start)
                    if (remaining <= 0) return false
                    flushed.awaitNanos(remaining)
                }
                return running
            } finally {
                lock.unlock()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }

    private fun pump() {
        // Touches take this path on their way to the device, so it carries the same urgency as
        // the media threads: anything that delays it is felt directly as lag under the finger.
        com.scrcpy.remote.session.raisePriority()
        try {
            while (true) {
                val burst = lock.withLock {
                    while (running && queue.isEmpty()) notEmpty.await()
                    if (!running) return
                    List(minOf(queue.size, MAX_BURST)) { queue.removeFirst() }
                }
                for (slot in burst) {
                    if (!lock.withLock { running }) return
                    slot.message.writeTo(out)
                }
                if (!lock.withLock { running }) return
                out.flush()
                val delivered = lock.withLock {
                    if (!running) {
                        false
                    } else {
                        flushedSequence = burst.last().sequence
                        flushed.signalAll()
                        true
                    }
                }
                if (!delivered) return
                for (slot in burst) {
                    val touch = slot.message as? ControlMessage.InjectTouchEvent ?: continue
                    try {
                        onTouchWritten(touch)
                    } catch (error: Throwable) {
                        logFailure("Control touch callback failed", error)
                    }
                }
            }
        } catch (error: InterruptedException) {
            fail(IOException("Control writer interrupted", error))
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun fail(error: Throwable) {
        val notify = lock.withLock {
            if (!running) return@withLock false
            abortLocked()
            true
        }
        if (notify) notifyFailure(error)
    }

    private fun abortLocked() {
        accepting = false
        running = false
        queue.clear()
        notEmpty.signalAll()
        flushed.signalAll()
    }

    private fun notifyFailure(error: Throwable) {
        try {
            onFailure(error)
        } catch (callbackError: Throwable) {
            logFailure("Control failure callback failed", callbackError)
        }
    }

    /**
     * Where [next], a finger's move, supersedes that finger's queued move, or -1.
     *
     * Looks back only across other fingers' moves on the same picture: a finger going down or up,
     * a key, a resize or a flush barrier in between keeps everything before it as it was.
     */
    private fun supersededMove(next: ControlMessage): Int {
        if (next !is ControlMessage.InjectTouchEvent || next.action != MOTION_ACTION_MOVE) return -1
        for (index in queue.indices.reversed()) {
            val slot = queue[index]
            val queued = slot.message
            if (slot.generation != generation || queued !is ControlMessage.InjectTouchEvent ||
                queued.action != MOTION_ACTION_MOVE ||
                queued.screenWidth != next.screenWidth || queued.screenHeight != next.screenHeight
            ) {
                return -1
            }
            if (queued.pointerId == next.pointerId) return if (canReplace(queued, next)) index else -1
        }
        return -1
    }

    private fun canReplace(previous: ControlMessage, next: ControlMessage): Boolean =
        (previous is ControlMessage.ResizeDisplay && next is ControlMessage.ResizeDisplay) ||
            (previous is ControlMessage.InjectTouchEvent &&
                next is ControlMessage.InjectTouchEvent &&
                previous.action == MOTION_ACTION_MOVE &&
                next.action == MOTION_ACTION_MOVE &&
                previous.pointerId == next.pointerId &&
                previous.screenWidth == next.screenWidth &&
                previous.screenHeight == next.screenHeight &&
                previous.actionButton == next.actionButton &&
                previous.buttons == next.buttons)

    private companion object {
        const val MAX_PENDING = 1024
        const val MAX_BURST = 32
        const val MOTION_ACTION_MOVE = 2

        fun logFailure(message: String, error: Throwable) {
            Logger.getLogger("ScrcpyController").log(Level.WARNING, message, error)
        }
    }
}
