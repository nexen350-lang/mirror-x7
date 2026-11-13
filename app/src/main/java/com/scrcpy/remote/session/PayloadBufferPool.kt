package com.scrcpy.remote.session

/**
 * Bounded pool of payload buffers, reused across packets in one stream.
 *
 * The reader owns a single scratch array that it refills for every packet, so the pump has to
 * take a copy before handing the packet to another thread. Allocating that copy per packet costs
 * roughly a megabyte a second at an 8 Mbit/s video stream, all of it short-lived — exactly the
 * shape that drives collections during a session, and collections are visible as dropped frames.
 *
 * Recycling is only safe because nothing downstream keeps the array: every consumer copies out of
 * it (into a codec input buffer, the PCM ring, or its own configuration copy) before the packet is
 * released. A buffer handed back here must therefore already be finished with.
 *
 * Capacities are rounded up so that buffers are reusable between packets of similar size rather
 * than only between identical ones; a returned buffer may be larger than the packet it carried,
 * which is why every consumer reads `length` rather than `data.size`.
 */
internal class PayloadBufferPool(private val maxPooledBytes: Int) {
    private val lock = Any()
    private val free = ArrayDeque<ByteArray>()
    private var pooledBytes = 0

    /** Buffers currently held for reuse. */
    val size: Int get() = synchronized(lock) { free.size }

    /** Bytes currently held for reuse, which is memory this pool is keeping off the heap's churn. */
    val retainedBytes: Int get() = synchronized(lock) { pooledBytes }

    /** A buffer of at least [length] bytes. Its contents are undefined. */
    fun acquire(length: Int): ByteArray {
        require(length >= 0)
        // Only a buffer of exactly the size class this packet wants. Handing out any buffer that
        // merely fits let a recycled 4 MiB array carry a few hundred bytes, and since the
        // ownership budget charges the payload rather than the array, that oversize was memory
        // nothing accounted for. Matching the class keeps allocation within one block of the
        // payload, so the budget stays an honest bound.
        val capacity = capacityFor(length)
        synchronized(lock) {
            val index = free.indexOfFirst { it.size == capacity }
            if (index >= 0) {
                val buffer = free.removeAt(index)
                pooledBytes -= buffer.size
                return buffer
            }
        }
        return ByteArray(capacity)
    }

    /** Hands [buffer] back. It must not be used again by the caller. */
    fun recycle(buffer: ByteArray) {
        synchronized(lock) {
            // Dropped rather than grown without bound: the pool exists to stop churn, not to
            // become a second copy of the ownership budget.
            if (pooledBytes + buffer.size > maxPooledBytes) return
            free.addLast(buffer)
            pooledBytes += buffer.size
        }
    }

    /** Releases everything held, for a stream that has ended. */
    fun clear() {
        synchronized(lock) {
            free.clear()
            pooledBytes = 0
        }
    }

    private companion object {
        const val MINIMUM = 4 * 1024
        const val GRANULARITY = 8 * 1024

        /**
         * Rounded up to a block, so similar sizes share a buffer instead of each needing its own.
         *
         * Deliberately not the next power of two: that wastes up to half of a large buffer, and
         * the ownership budget charges what is actually allocated, so the waste would come
         * straight out of how many packets may be in flight.
         */
        fun capacityFor(length: Int): Int {
            if (length <= MINIMUM) return MINIMUM
            val blocks = (length + GRANULARITY - 1) / GRANULARITY
            val capacity = blocks * GRANULARITY
            // A payload this large is already bounded by MediaPacketLimits; give it exactly what
            // it asked for rather than overflowing into a negative capacity.
            return if (capacity <= 0) length else capacity
        }
    }
}
