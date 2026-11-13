package com.scrcpy.remote.session

import java.io.Closeable
import java.io.InterruptedIOException

/**
 * Releases blocked startup I/O by closing physical transports first, then ordinary resources in
 * reverse registration order. A successful handoff transfers all ownership to the live session.
 */
class StartupResources : Closeable {
    private class TransportResource(resource: Closeable) : Closeable by resource

    private val resources = mutableListOf<Closeable>()
    private var cancelled = false
    private var transferred = false

    internal fun <T : Closeable> trackTransport(resource: T): T {
        track(TransportResource(resource))
        return resource
    }

    fun <T : Closeable> track(resource: T): T {
        val dispose = synchronized(this) {
            if (cancelled) true else { resources += resource; false }
        }
        if (dispose) {
            resource.close()
            throw InterruptedIOException("Connection cancelled")
        }
        return resource
    }

    @Synchronized
    fun checkActive() {
        if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("Connection cancelled")
    }

    /** Whether the attempt was cancelled, or has already released what it held. */
    val isCancelled: Boolean
        @Synchronized get() = cancelled

    @Synchronized
    fun transfer() {
        checkActive()
        transferred = true
        resources.clear()
    }

    override fun close() {
        val pending = synchronized(this) {
            cancelled = true
            if (transferred) emptyList() else resources.toList().asReversed().also { resources.clear() }
        }
        val (transports, ordinaryResources) = pending.partition { it is TransportResource }
        var failure: Exception? = null
        for (resource in transports + ordinaryResources) {
            try { resource.close() }
            catch (e: Exception) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }
}
