package com.scrcpy.remote.scrcpy

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A malformed media stream is not a transient connection failure. */
class MediaProtocolException(message: String) : IOException(message)

/** Reconnecting cannot repair a decoder which will not accept its input. */
class MediaInputException(message: String) : IOException(message)

object MediaPacketLimits {
    const val AUDIO_PAYLOAD_BYTES = 256 * 1024
    const val VIDEO_PAYLOAD_BYTES = 4 * 1024 * 1024

    fun maxPayloadBytes(codecId: Int): Int = when (codecId) {
        ScrcpyCodecs.AUDIO_OPUS, ScrcpyCodecs.AUDIO_AAC,
        ScrcpyCodecs.AUDIO_FLAC, ScrcpyCodecs.AUDIO_RAW -> AUDIO_PAYLOAD_BYTES
        // Unknown/disabled codec ids are rejected by the codec setup, but still have a hard cap.
        else -> VIDEO_PAYLOAD_BYTES
    }

    fun ownedBytes(codecId: Int): Int = 2 * maxPayloadBytes(codecId)

    fun checkLength(length: Int, codecId: Int) {
        val limit = maxPayloadBytes(codecId)
        if (length !in 0..limit) {
            throw MediaProtocolException("Invalid media packet size: $length (maximum $limit)")
        }
    }
}

/**
 * One budget covers reader-owned copies, queued packets and the decoder's pending packet.
 * Closing wakes blocked reservers; live reservations remain charged until their owners release.
 */
class MediaMemoryBudget(val maxBytes: Int) : Closeable {
    init { require(maxBytes > 0) }

    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private var closed = false
    private var used = 0
    private var peak = 0

    val usedBytes: Int get() = lock.withLock { used }
    val peakBytes: Int get() = lock.withLock { peak }

    @Throws(InterruptedException::class)
    fun reserve(bytes: Int): Reservation {
        require(bytes in 0..maxBytes) { "Reservation exceeds the media memory budget" }
        lock.lockInterruptibly()
        try {
            while (!closed && bytes > maxBytes - used) available.await()
            if (closed) throw InterruptedException("Media memory budget is closed")
            val reservation = Reservation(bytes)
            used += bytes
            peak = maxOf(peak, used)
            return reservation
        } finally {
            lock.unlock()
        }
    }

    inner class Reservation internal constructor(private val bytes: Int) : Closeable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            lock.withLock {
                used -= bytes
                available.signalAll()
            }
        }
    }

    override fun close() {
        lock.withLock {
            closed = true
            available.signalAll()
        }
    }
}
