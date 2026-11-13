package com.scrcpy.remote.session

import java.util.ArrayDeque

internal data class PcmPresentation(
    val framePosition: Long,
    val nanoTime: Long,
    val timestampAvailable: Boolean,
    val underruns: Int,
    val outputRoute: Int? = null,
    val outputRouteId: Int? = null,
)

data class MediaTimingDiagnostics(
    val audioQueuedMs: Int = 0,
    val outputQueuedMs: Int? = null,
    val droppedFrames: Long = 0,
    val underruns: Int = 0,
    val clockReady: Boolean = false,
    val videoDelayMs: Int = 0,
    val synchronized: Boolean = false,
    val outputRoute: Int? = null,
    val videoAheadMs: Int? = null,
)

internal sealed interface VideoPresentation {
    data object Immediate : VideoPresentation
    data class At(val nanoTime: Long) : VideoPresentation
}

/**
 * Maps the PCM actually submitted to AudioTrack to its hardware presentation timestamp.
 * Wire PTS are observations, not a reason to insert or discard an assumed number of samples.
 */
class AudioPresentationClock(
    private val synchronizeVideo: Boolean = false,
    private val offsetMs: Int = 0,
) {
    private data class Span(val start: Long, val count: Int, val ptsUs: Long, val rate: Int)
    private data class Anchor(val ptsUs: Long, val nanoTime: Long, val validUntilNanos: Long)
    private val lock = Any()
    private val spans = ArrayDeque<Span>()
    private var anchor: Anchor? = null
    private var lastWritten = 0L
    private var rate = 48_000
    private var audible = true
    private var state = MediaTimingDiagnostics(synchronized = synchronizeVideo)

    internal fun record(firstFrame: Long, count: Int, ptsUs: Long?, sampleRate: Int) = synchronized(lock) {
        require(firstFrame >= 0 && count > 0 && sampleRate > 0)
        rate = sampleRate
        lastWritten = firstFrame + count
        if (ptsUs == null) {
            anchor = null
            spans.clear()
        } else {
            spans.addLast(Span(firstFrame, count, ptsUs, sampleRate))
            while (spans.size > 512) spans.removeFirst()
        }
    }

    internal fun observe(position: PcmPresentation?, stats: PcmPlaybackStats, nowNanos: Long) = synchronized(lock) {
        state = state.copy(
            audioQueuedMs = (stats.queuedFrames.toLong() * 1_000 / rate).toInt(),
            droppedFrames = stats.droppedFrames,
            underruns = position?.underruns ?: state.underruns,
            outputRoute = position?.outputRoute,
            outputQueuedMs = position?.let { ((lastWritten - it.framePosition).coerceAtLeast(0) * 1_000 / rate).toInt() },
        )
        anchor = null
        if (audible && position != null && position.timestampAvailable &&
            nowNanos - position.nanoTime in -500_000_000L..500_000_000L && position.framePosition <= lastWritten
        ) {
            val advanced = position.framePosition +
                (nowNanos - position.nanoTime) * rate / 1_000_000_000L
            // Extrapolation can run ahead during an underrun; only confirmed playback can retire spans.
            while (spans.isNotEmpty() && spans.first.start + spans.first.count <= position.framePosition) spans.removeFirst()
            val span = spans.firstOrNull { advanced >= it.start && advanced < it.start + it.count }
            if (span != null) {
                val pts = span.ptsUs + (advanced - span.start) * 1_000_000L / span.rate
                val remaining = (lastWritten - advanced) * 1_000_000_000L / span.rate
                anchor = Anchor(pts, nowNanos, nowNanos + remaining)
            }
        }
        state = state.copy(clockReady = anchor != null)
    }

    internal fun videoPresentation(ptsUs: Long, nowNanos: Long): VideoPresentation = synchronized(lock) {
        val current = anchor
        if (current == null || nowNanos > current.validUntilNanos || nowNanos - current.nanoTime !in 0..150_000_000L) {
            state = state.copy(clockReady = false, videoDelayMs = 0, videoAheadMs = null)
            return VideoPresentation.Immediate
        }
        val currentPts = current.ptsUs + (nowNanos - current.nanoTime) / 1_000
        val delayUs = ptsUs - currentPts + offsetMs * 1_000L
        // Incomparable vendor timestamps, a silent stream, or a large gap must never freeze video.
        if (delayUs !in -500_000..500_000) {
            state = state.copy(clockReady = false, videoDelayMs = 0, videoAheadMs = null)
            return VideoPresentation.Immediate
        }
        state = state.copy(videoDelayMs = if (synchronizeVideo) (delayUs.coerceAtLeast(0) / 1_000).toInt() else 0,
            videoAheadMs = ((ptsUs - currentPts) / 1_000).toInt())
        if (!synchronizeVideo) return VideoPresentation.Immediate
        when {
            delayUs <= 0 -> VideoPresentation.Immediate
            else -> VideoPresentation.At(nowNanos + delayUs * 1_000)
        }
    }

    fun diagnostics(): MediaTimingDiagnostics = synchronized(lock) { state }

    fun setAudible(value: Boolean) = synchronized(lock) {
        audible = value
        if (!value) {
            anchor = null
            state = state.copy(clockReady = false, videoDelayMs = 0, videoAheadMs = null)
        }
    }

    fun reset() = synchronized(lock) {
        anchor = null
        spans.clear()
        lastWritten = 0
        state = state.copy(clockReady = false, outputQueuedMs = null, videoDelayMs = 0, videoAheadMs = null)
    }
}
