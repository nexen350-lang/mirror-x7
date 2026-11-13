package com.scrcpy.remote.session

import java.util.ArrayDeque

data class ConnectionQualitySample(
    val elapsedMillis: Long,
    val renderedFps: Int?,
    val clientP95Millis: Int?,
    val audioQueuedMillis: Int?,
    val skippedDelta: Long?,
    val underrunDelta: Int?,
    val intervalMillis: Long?,
)

data class ConnectionQualitySnapshot(
    val elapsedMillis: Long = 0,
    val collecting: Boolean = false,
    val samples: List<ConnectionQualitySample> = emptyList(),
)

/**
 * Main-thread-owned, in-memory observations, not a session log. The owner supplies monotonic local
 * elapsed time and only samples while the details panel is visible. No device identity is retained.
 */
internal class ConnectionQualityHistory {
    private data class Baseline(val elapsedMillis: Long, val skipped: Long?, val underruns: Int?)

    private val samples = ArrayDeque<ConnectionQualitySample>()
    private var collecting = false
    private var lastElapsedMillis: Long? = null
    private var baseline: Baseline? = null

    fun setWatching(watching: Boolean, elapsedMillis: Long): ConnectionQualitySnapshot {
        advance(elapsedMillis)
        if (collecting != watching) {
            collecting = watching
            baseline = null
        }
        return snapshot(elapsedMillis)
    }

    fun reset(elapsedMillis: Long): ConnectionQualitySnapshot {
        require(elapsedMillis >= 0)
        samples.clear()
        baseline = null
        lastElapsedMillis = elapsedMillis
        return snapshot(elapsedMillis)
    }

    fun sample(
        elapsedMillis: Long,
        frames: VideoFrameStats?,
        latency: LatencyStats?,
        timing: MediaTimingDiagnostics?,
        audioAvailable: Boolean,
    ): ConnectionQualitySnapshot {
        advance(elapsedMillis)
        if (!collecting || samples.peekLast()?.elapsedMillis == elapsedMillis) {
            return snapshot(elapsedMillis)
        }
        val previous = baseline
        val interval = previous?.let { elapsedMillis - it.elapsedMillis }
            ?.takeIf { it in 1..MAX_DELTA_INTERVAL_MILLIS }
        val skipped = frames?.skippedFrames?.takeIf { it >= 0 }
        // AudioTrack presentation may be unavailable even while the PCM queue is observable.
        val underruns = timing?.takeIf { audioAvailable && it.outputQueuedMs != null }
            ?.underruns?.takeIf { it >= 0 }
        samples.addLast(ConnectionQualitySample(
            elapsedMillis = elapsedMillis,
            // This is the meter's existing one-second window, never a lifetime count / 500 ms.
            renderedFps = frames?.takeIf { it.surfaceFrames > 0 }?.surfaceFps?.takeIf { it >= 0 },
            clientP95Millis = latency?.takeIf { it.hasSamples }?.p95Millis?.takeIf { it >= 0 },
            audioQueuedMillis = timing?.takeIf { audioAvailable }?.audioQueuedMs?.takeIf { it >= 0 },
            skippedDelta = if (interval != null && skipped != null && previous?.skipped != null &&
                skipped >= previous.skipped) skipped - previous.skipped else null,
            underrunDelta = if (interval != null && underruns != null && previous?.underruns != null &&
                underruns >= previous.underruns) underruns - previous.underruns else null,
            intervalMillis = interval,
        ))
        baseline = Baseline(elapsedMillis, skipped, underruns)
        prune(elapsedMillis)
        return snapshot(elapsedMillis)
    }

    fun snapshot(elapsedMillis: Long): ConnectionQualitySnapshot {
        advance(elapsedMillis)
        return ConnectionQualitySnapshot(elapsedMillis, collecting, samples.toList())
    }

    private fun advance(elapsedMillis: Long) {
        require(elapsedMillis >= 0)
        if (lastElapsedMillis?.let { elapsedMillis < it } == true) {
            samples.clear()
            baseline = null
        }
        lastElapsedMillis = elapsedMillis
        prune(elapsedMillis)
    }

    private fun prune(elapsedMillis: Long) {
        var removed = false
        while (samples.isNotEmpty() &&
            (samples.size > MAX_SAMPLES || elapsedMillis - samples.first.elapsedMillis >= WINDOW_MILLIS)) {
            samples.removeFirst()
            removed = true
        }
        if (samples.isEmpty()) {
            baseline = null
        } else if (removed) {
            // A delta is only part of this window when both of its endpoints are still retained.
            samples.addFirst(samples.removeFirst().copy(
                skippedDelta = null, underrunDelta = null, intervalMillis = null,
            ))
        }
    }

    companion object {
        const val WINDOW_MILLIS = 60_000L
        const val MAX_SAMPLES = 120
        const val SAMPLE_INTERVAL_MILLIS = 500L
        const val MAX_DELTA_INTERVAL_MILLIS = 1_000L
    }
}
