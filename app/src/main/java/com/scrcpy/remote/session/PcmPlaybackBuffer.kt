package com.scrcpy.remote.session

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class PcmChunk(val length: Int, val ptsUs: Long?, val discontinuity: Boolean)

/** Frame-aligned PCM only. Codec input must never be discarded to reduce playback latency. */
internal class PcmPlaybackBuffer(
    private val frameSize: Int,
    private val targetFrames: Int,
    private val maximumFrames: Int,
    private val sampleRate: Int = 48_000,
) {
    init {
        require(frameSize > 0 && targetFrames > 0 && maximumFrames >= targetFrames && sampleRate > 0)
    }

    private val data = ByteArray(Math.multiplyExact(frameSize, maximumFrames))
    private val timestamps = LongArray(maximumFrames) { Long.MIN_VALUE }
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private var head = 0
    private var size = 0
    private var closed = false
    private var discarded = 0L
    private var revision = 0L
    private var readRevision = 0L
    private var discardRevision = 0L

    val queuedFrames: Int get() = lock.withLock { size / frameSize }
    val droppedFrames: Long get() = lock.withLock { discarded }

    fun offer(source: ByteArray, length: Int, ptsUs: Long? = null): Boolean = lock.withLock {
        require(length in 0..source.size && length % frameSize == 0)
        if (closed) return false
        var offset = 0
        val totalFrames = size.toLong() / frameSize + length / frameSize
        if (totalFrames > maximumFrames) {
            revision++
            val dropFrames = totalFrames - targetFrames
            discarded += dropFrames
            val oldFrames = minOf(dropFrames, (size / frameSize).toLong()).toInt()
            head = (head + oldFrames * frameSize) % data.size
            size -= oldFrames * frameSize
            offset = ((dropFrames - oldFrames) * frameSize).toInt()
        }
        val count = length - offset
        val tail = (head + size) % data.size
        val first = minOf(count, data.size - tail)
        source.copyInto(data, tail, offset, offset + first)
        source.copyInto(data, 0, offset + first, length)
        repeat(count / frameSize) { frame ->
            timestamps[(tail / frameSize + frame) % maximumFrames] =
                ptsUs?.plus((offset / frameSize + frame) * 1_000_000L / sampleRate) ?: Long.MIN_VALUE
        }
        size += count
        if (count > 0) available.signalAll()
        true
    }

    fun poll(destination: ByteArray, timeoutMillis: Long): Int = pollChunk(destination, timeoutMillis).length

    fun pollChunk(destination: ByteArray, timeoutMillis: Long, minimumFrames: Int = 1): PcmChunk = lock.withLock {
        require(destination.isNotEmpty() && destination.size % frameSize == 0 && timeoutMillis >= 0)
        require(minimumFrames in 1..maximumFrames)
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        // A discard (timeline reset or route change) ends a refill wait at once: the player has
        // to apply the reset now, not after waiting to refill audio that has just been dropped.
        val discards = discardRevision
        while (size / frameSize < minimumFrames && !closed && remaining > 0 && discardRevision == discards) {
            remaining = available.awaitNanos(remaining)
        }
        if (closed || size == 0) return PcmChunk(0, null, false)
        val firstPts = timestamps[head / frameSize].takeUnless { it == Long.MIN_VALUE }
        var count = minOf(size, destination.size)
        if (firstPts != null) {
            for (frame in 1 until count / frameSize) {
                val pts = timestamps[(head / frameSize + frame) % maximumFrames]
                if (pts == Long.MIN_VALUE || kotlin.math.abs(pts - firstPts - frame * 1_000_000L / sampleRate) > 2) {
                    count = frame * frameSize
                    break
                }
            }
        }
        val first = minOf(count, data.size - head)
        data.copyInto(destination, 0, head, head + first)
        data.copyInto(destination, first, 0, count - first)
        head = (head + count) % data.size
        size -= count
        val changed = revision != readRevision
        readRevision = revision
        PcmChunk(count, firstPts, changed)
    }

    fun discardQueued() = lock.withLock {
        discarded += size / frameSize
        size = 0
        revision++
        discardRevision++
        available.signalAll()
    }

    fun close() = lock.withLock {
        closed = true
        size = 0
        available.signalAll()
    }
}
