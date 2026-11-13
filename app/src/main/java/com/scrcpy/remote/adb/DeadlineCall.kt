package com.scrcpy.remote.adb

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs a blocking call on another thread and gives up on it after a deadline.
 *
 * Some libadb operations wait on the device with no deadline of their own: opening a stream sends
 * the request and then waits to be told it was accepted. A device that holds the connection open
 * but stops answering leaves that wait outstanding forever, and the caller with it.
 *
 * A deadline or caller interruption abandons the result and interrupts the worker. An operation
 * that ignores interruption may still finish later; its result is handed to `dispose` rather than
 * leaking the stream it went on to create.
 */
internal object DeadlineCall {

    /**
     * Returns the result of [produce], or throws [IOException] if it takes longer than
     * [timeoutMillis].
     *
     * Caller interruption is propagated. Any result abandoned by a deadline or interruption is
     * passed to [dispose] exactly once instead of being returned.
     */
    fun <T> within(
        timeoutMillis: Long,
        name: String,
        message: String,
        dispose: (T) -> Unit,
        produce: () -> T,
    ): T {
        require(timeoutMillis > 0) { "Timeout must be positive" }
        val produced = AtomicReference<T?>()
        val failure = AtomicReference<Throwable?>()
        val abandoned = AtomicBoolean(false)
        val finished = CountDownLatch(1)

        val worker = Thread({
            try {
                val value = produce()
                produced.set(value)
                // Publish first, then re-read the flag. The waiter sets the flag and then reads
                // the slot, so whichever of the two happens second sees the other's write and
                // exactly one of them clears the slot and disposes of the value.
                if (abandoned.get()) produced.getAndSet(null)?.let(dispose)
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                finished.countDown()
            }
        }, name).apply { isDaemon = true; start() }

        fun abandon() {
            abandoned.set(true)
            worker.interrupt()
            produced.getAndSet(null)?.let(dispose)
        }

        val completed = try {
            finished.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            abandon()
            throw error
        }
        if (!completed) {
            abandon()
            throw SocketTimeoutException(message)
        }
        failure.get()?.let { throw it as? IOException ?: IOException(it.message ?: it.toString(), it) }
        return produced.get() ?: throw IOException(message)
    }
}
