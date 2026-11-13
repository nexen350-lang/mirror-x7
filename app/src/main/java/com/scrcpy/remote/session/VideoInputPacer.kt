package com.scrcpy.remote.session

/** Keep intentional sync delay in compressed packets, not scarce decoder/surface buffers. */
internal object VideoInputPacer {
    const val DECODE_LEAD_NANOS = 50_000_000L
    const val MAX_QUEUED_PACKETS = 64

    fun ready(presentation: VideoPresentation, nowNanos: Long): Boolean = when (presentation) {
        VideoPresentation.Immediate -> true
        is VideoPresentation.At -> presentation.nanoTime - nowNanos <= DECODE_LEAD_NANOS
    }
}
