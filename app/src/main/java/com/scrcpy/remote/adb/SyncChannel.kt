package com.scrcpy.remote.adb

import io.github.muntashirakon.adb.AdbStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A bidirectional byte channel to an ADB service.
 *
 * [AdbSync] speaks the sync protocol over this rather than over the library's own stream type:
 * that keeps the protocol logic free of the transport, which is what makes it testable — the
 * library's stream cannot be constructed or subclassed outside its own package.
 */
interface SyncChannel : Closeable {
    val input: InputStream
    val output: OutputStream
}

/**
 * Adapts a libadb stream to [SyncChannel], bounded by [inactivityMillis].
 *
 * Opening `sync:` has its own deadline, but nothing bounded the transaction after the device
 * accepted it: a sync service that stopped answering left an app listing loading forever and a
 * transfer stuck until cancelled.
 */
class AdbStreamChannel(
    stream: AdbStream,
    inactivityMillis: Long = SYNC_INACTIVITY_MILLIS,
) : SyncChannel by RawAdbStreamChannel(stream).withInactivityDeadline(inactivityMillis) {
    companion object {
        /** Longest a single sync read, write or flush may go without completing. */
        const val SYNC_INACTIVITY_MILLIS = 25_000L
    }
}

private class RawAdbStreamChannel(private val stream: AdbStream) : SyncChannel {
    // Opened once: the library hands back a new wrapper per call.
    override val input: InputStream by lazy { stream.openInputStream() }
    override val output: OutputStream by lazy { stream.openOutputStream() }
    override fun close() = stream.close()
}

/**
 * Closes this channel when one read, write or flush makes no progress for [timeoutMillis], and
 * reports that as [SocketTimeoutException].
 *
 * The deadline restarts with every call, so a long transfer that keeps moving never trips it.
 * Closing is what releases the blocked call: an ADB stream read ignores interruption.
 */
fun SyncChannel.withInactivityDeadline(timeoutMillis: Long): SyncChannel {
    require(timeoutMillis > 0) { "Timeout must be positive" }
    return InactivityBoundChannel(this, timeoutMillis)
}

private class InactivityBoundChannel(
    private val delegate: SyncChannel,
    private val timeoutMillis: Long,
) : SyncChannel {
    @Volatile private var expired = false

    override val input: InputStream = object : InputStream() {
        override fun read(): Int = bounded { delegate.input.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = bounded { delegate.input.read(b, off, len) }
        override fun available(): Int = delegate.input.available()
        override fun close() = delegate.input.close()
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = bounded { delegate.output.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = bounded { delegate.output.write(b, off, len) }
        override fun flush() = bounded { delegate.output.flush() }
        override fun close() = delegate.output.close()
    }

    override fun close() = delegate.close()

    private fun <T> bounded(operation: () -> T): T {
        if (expired) throw stalled(null)
        val deadline = WATCHDOG.schedule({
            expired = true
            closeOffWatchdog()
        }, timeoutMillis, TimeUnit.MILLISECONDS)
        try {
            return operation()
        } catch (error: IOException) {
            throw if (expired) stalled(error) else error
        } finally {
            deadline.cancel(false)
        }
    }

    /**
     * Closing a logical stream can wait behind the very writer that is blocked, so it never runs
     * on the shared watchdog thread: that would disable every other channel's deadline.
     */
    private fun closeOffWatchdog() {
        Thread({
            try { delegate.close() } catch (_: IOException) { }
        }, "sync-inactivity-close").apply { isDaemon = true }.start()
    }

    private fun stalled(cause: Throwable?) =
        SocketTimeoutException("The device stopped answering a file request").also { timeout ->
            cause?.let(timeout::initCause)
        }

    private companion object {
        val WATCHDOG = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "sync-inactivity").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
