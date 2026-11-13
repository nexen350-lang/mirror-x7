package com.scrcpy.remote.session

import java.util.ArrayDeque

/**
 * How long frames are taking to get from the network to the screen.
 *
 * Reported in milliseconds. A median says what the session normally feels like; the 95th percentile
 * and the worst case say how bad the occasional frame gets, which is what a user actually notices.
 */
data class LatencyStats(
    val samples: Int = 0,
    val medianMillis: Int = 0,
    val p95Millis: Int = 0,
    val worstMillis: Int = 0,
    /**
     * The 95th percentile of the part spent *before* the decoder accepted the frame.
     *
     * Reported separately because the two halves have different cures. Time before the decoder is
     * a backlog — the stream is arriving faster than this device can decode it, and the answer is
     * a lower bit rate, resolution or frame rate. Time after it is decode and present cost, and
     * the answer is a different encoder or fewer pixels. Without the split the two are
     * indistinguishable, and the same total sends a user looking in the wrong place.
     */
    val intakeP95Millis: Int = 0,
) {
    val hasSamples: Boolean get() = samples > 0
}

/**
 * Times frames from arrival to the moment they reach the surface.
 *
 * The app could measure how *many* frames it handled but never how *long* any of them took, so every
 * judgement about latency — including the ones that shaped this code — rested on reading it rather
 * than running it. This closes that gap for the part the client controls.
 *
 * Receipt time is carried by the pump's owned packet, not indexed here while it waits for input.
 * Submitted frames are matched by codec epoch and presentation timestamp; their entries remain
 * bounded and evicted oldest-first if a render callback never arrives.
 */
internal class FrameLatencyMeter(private val windowNanos: Long = DEFAULT_WINDOW_NANOS) {

    private data class Key(val codecEpoch: Long, val ptsUs: Long)
    private data class Pending(val receivedNanos: Long, val queuedNanos: Long)

    private val pending = LinkedHashMap<Key, Pending>()
    private val samples = ArrayDeque<Sample>()

    /**
     * Records that the decoder accepted the frame carrying [ptsUs] at [nanoTime].
     *
     * [receivedNanos] comes from that packet's ownership envelope, so a codec reset or a full
     * input queue cannot erase it. Callers without a receipt stamp retain decode-only timing.
     */
    @Synchronized
    fun arrived(ptsUs: Long, nanoTime: Long, receivedNanos: Long = nanoTime, codecEpoch: Long = 0) {
        val key = Key(codecEpoch, ptsUs)
        pending.remove(key)
        pending[key] = Pending(receivedNanos, nanoTime)
        evictOldest()
    }

    private fun evictOldest() {
        while (pending.size > MAX_PENDING) {
            val oldest = pending.keys.firstOrNull() ?: break
            pending.remove(oldest)
        }
    }

    /** Records that the frame carrying [ptsUs] reached the surface at [nanoTime]. */
    @Synchronized
    fun rendered(ptsUs: Long, nanoTime: Long, codecEpoch: Long = 0) {
        val frame = pending.remove(Key(codecEpoch, ptsUs)) ?: return
        val elapsed = nanoTime - frame.receivedNanos
        // A negative or absurd interval means the two clocks were not comparable for this frame —
        // a codec restart, a reused timestamp — and a wrong number is worse than no number.
        if (elapsed < 0 || elapsed > MAX_PLAUSIBLE_NANOS) return
        val intake = (frame.queuedNanos - frame.receivedNanos).coerceIn(0, elapsed)
        samples.addLast(Sample(nanoTime, elapsed, intake))
        prune(nanoTime)
    }

    /** Forgets submitted frames, without affecting receipts owned by packets still awaiting input. */
    @Synchronized
    fun reset() {
        pending.clear()
        samples.clear()
    }

    @Synchronized
    fun snapshot(nanoTime: Long): LatencyStats {
        prune(nanoTime)
        if (samples.isEmpty()) return LatencyStats()
        val sorted = samples.map { it.elapsedNanos }.sorted()
        val intake = samples.map { it.intakeNanos }.sorted()
        return LatencyStats(
            samples = sorted.size,
            medianMillis = millis(sorted[sorted.size / 2]),
            p95Millis = millis(sorted[percentileIndex(sorted.size, 95)]),
            worstMillis = millis(sorted.last()),
            intakeP95Millis = millis(intake[percentileIndex(intake.size, 95)]),
        )
    }

    private fun prune(nanoTime: Long) {
        while (samples.isNotEmpty() && nanoTime - samples.first.atNanos >= windowNanos) {
            samples.removeFirst()
        }
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    private data class Sample(val atNanos: Long, val elapsedNanos: Long, val intakeNanos: Long)

    companion object {
        /** Rounded rather than truncated: 1.6 ms reported as 1 would flatter the result. */
        fun millis(nanos: Long): Int = ((nanos + 500_000L) / 1_000_000L).toInt()

        /**
         * Zero-based index of the [percentile] value among [size] sorted samples, by nearest rank.
         *
         * Nearest rank is `ceil(p/100 * n)`, counting from one. Taking the floor instead — which
         * this did at first — lands one sample too high, so a window of a hundred frames reported
         * its 96th slowest as the 95th percentile, and a window of twenty reported its worst.
         * An instrument that exists to be trusted cannot round in the flattering direction.
         */
        fun percentileIndex(size: Int, percentile: Int): Int {
            if (size <= 0) return 0
            val rank = (size * percentile + 99) / 100
            return (rank - 1).coerceIn(0, size - 1)
        }

        private const val DEFAULT_WINDOW_NANOS = 5_000_000_000L
        private const val MAX_PENDING = 64
        private const val MAX_SAMPLES = 512

        /** Beyond this the pairing is not believable, so the sample is discarded rather than shown. */
        private const val MAX_PLAUSIBLE_NANOS = 10_000_000_000L
    }
}
